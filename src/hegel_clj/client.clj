(ns hegel-clj.client
  "Provides the raw interface for communicating with Hegel-core."
  (:require [clj-cbor.core :as cbor]
            [clj-commons.byte-streams :as bs]
            [clojure [string :as str]
                     [walk :refer [prewalk]]]
            [clojure.tools.logging :refer [debug info warn]]
            [clojure.java.io :as io]
            [hegel-clj.generator.proto :as gen])
  (:import (clojure.lang ExceptionInfo)
           (hegel_clj HegelError
                                LimitInputStream)
           (java.io ByteArrayInputStream
                    ByteArrayOutputStream
                    DataInputStream
                    DataOutputStream
                    EOFException
                    InputStream
                    IOException
                    OutputStream)
           (java.nio ByteBuffer)
           (java.nio.charset StandardCharsets)
           (java.util.concurrent CompletableFuture
                                 ExecutionException)
           (java.util.zip CRC32)))

(def core-version
  "The version of hegel-core we ask uv for."
  "0.7.0")

(def core-version-string
  "What version string do we expect from Hegel-core? Weirdly this is *not* the
  same as the Hegel version."
  "Hegel/0.13")

(def core-log-file
  "Where we dump Hegel-core's logs"
  "hegel-core.log")

(def control-stream-id
  "The ID of the special control stream."
  0)

(def magic
  "Hegel's protocol magic string."
  (unchecked-int 0x4845474C))

(def terminator
  "Terminator."
  (unchecked-byte 0x0a))

(def ^:dynamic *final-case?*
  "This dynamic variable is bound to true when we're executing a final test
  case."
  false)

; Coercing between Clojure and Hegel names

(defn snake-case-str
  "Converts dashes in any named thing to underscored strings."
  [s]
  (str/replace (name s) #"-" "_"))

(defn kebab-case-kw
  "Converts underscored strings to kebab-case keywords."
  [s]
  (keyword (str/replace (name s) #"_" "-")))

(defn clj->hegel
  "Converts an idiomatic Clojure data structure into a Hegel one. Turns
  keywords to strings, and kebab-case to snake_case. Only does this for map
  keys."
  [x]
  (prewalk (fn rewrite [x]
             (if (map? x)
               (update-keys x snake-case-str)
               x))
           x))

(defn hegel->clj
  "Converts a Hegel data structure into a Clojure one. Turns strings to
  keywords, and snake-_case to kebab-case. Only for map keys."
  [x]
  (prewalk (fn rewrite [x]
             (if (map? x)
               (update-keys x kebab-case-kw)
               x))
           x))

(defn default
  "Provides a default for a map."
  [m k v]
  (if (contains? m k)
    m
    (assoc m k v)))

(defn update-
  "Like Clojure update, but only if the key exists."
  ([m k f]
   (if (contains? m k)
     (update m k f)
     m))
  ([m k f a]
   (if (contains? m k)
     (update m k f a)
     m))
  ([m k f a b]
   (if (contains? m k)
     (update m k f a b)
     m))
  ([m k f a b & cs]
   (if (contains? m k)
     (apply update m k f a b cs)
     m)))

;; Threading

(defmacro with-thread-name
  "Sets the thread name for duration of block."
  [thread-name & body]
  `(let [old-name# (.. Thread currentThread getName)]
     (try
       (.. Thread currentThread (setName (name ~thread-name)))
       ~@body
       (finally (.. Thread currentThread (setName old-name#))))))

(defmacro vthread
  "Runs body in a virtual thread under the given name."
  [name & body]
  `(.start (.name (Thread/ofVirtual) ~name)
           (fn ~'run []
             (try ~@body
                  (catch Exception e#
                    (warn e# "Uncaught exception in" ~name))))))

(def command-timeout
  "How long do we wait for Hegel to respond to a command, in millis?"
  10000)

(defn deref-rethrow
  "Deref with unwrapping throw. It's sort of confusing and not really relevant
  that we construct HegelErrors in the reader thread, then hand them off
  through Futures; it complicates catch blocks and the cause stacktrace isn't
  actually that useful.

  We extract HegelErrors from a thrown ExecutionException and throw a *new*
  HegelError from the current callsite."
  ([derefable]
   (try (deref derefable)
        (catch ExecutionException e
          (let [cause (ex-cause e)]
            (if (instance? HegelError cause)
              (throw (HegelError. (.getMessage cause)
                                  (.getData cause)
                                  cause))
              (throw e))))))
  ([derefable timeout-ms]
   (try (let [v (deref derefable timeout-ms ::timeout)]
          (when (identical? v ::timeout)
            (throw (ex-info "Hegel-clj timeout (perhaps hegel-core has crashed?)"
                            {:type :timeout})))
          v)
        (catch ExecutionException e
          (let [cause (ex-cause e)]
            (if (instance? HegelError cause)
              (throw (HegelError. (.getMessage cause)
                                  (.getData cause)
                                  cause))
              (throw e)))))))

;; The hegel-core state machine

; In order to understand this design, you have to know a little about how
; Hegel's protocol works. There's a complex back-and-forth dance between client
; and server: sometimes the client drives, sometimes the server drives, and
; they shift between several streams in doing so. We also want to support
; concurrent execution of tests.
;
; There are three levels of streams: the control, the test run, and the test
; case stream. These work like so:
;
; client <- -> server
;
; | Control            | Test run            | Test case 1
; |--------------------|---------------------|---------------------------
; |         run_test-> |                     |
; | <-run_test_reply   |                     |
; |                    | <-test_case         |
; |                    |   test_case_reply-> |
; |                    |                     |               command->
; |                    |                     | <-command_reply
; |                    |                     |       ...
; |                    |                     |         mark_complete->
; |                    |                     | <-mark_complete_reply
; |                    |                     |          stream_close->
;                             ...
; |                    | <-test_done         |
; |                    |   test_done_reply-> |
; |                    | <-test_case         | Test case n
; |                    |   test_case_reply-> |--------------------------
; |                    |                     |               command->
; |                    |                     | <-command_reply
; |                    |                     |       ...
; |                    |                     |         mark_complete->
; |                    |                     | <-mark_complete_reply
; |                    |                     |          stream_close->
;
; Note that the control stream is driven by the client, the test run stream is
; driven by the server, and the test case stream is driven by the client again.
; At least, this is what I infer from Hegel's protocol docs!
;
; Because our test cases might (?) be concurrent, we run a single *reader
; thread* which reads messages off of the stream, parses their headers, and
; dispatches them to the right place. The reader dispatches messages in two
; ways:
;
; 1. For RPC calls, the caller registers a CompletableFuture in a map, and
;    the reder delivers its response payload to that future.
;
; 2. For the test run stream, each test_case message spawns a virtual thread,
;    which evaluates that test case. I'm doing this assuming that
;    Hegel might parallelize test case evaluation, and if not, it probably
;    won't hurt much? Alternatively, we can spawn a single thread to handle all
;    test cases, and hand off messages to it using a queue.
;
; The control stream is always 0; the test run stream is always odd, and the
; test case stream is always even. This follows from the even/odd convention
; for client/server generated streams.
;
; Note that test_done does NOT mean that the test is done! It means that we're
; transitioning to a final phase in which Hegel repeats the minimal failing
; cases. The idea is that now is the time to turn on extra logging so you can
; debug those shorter versions. We need to track the state from test_done and
; wait for the right number of final test cases to arrive before returning to
; the caller.

(declare send!)

(defrecord Core [^Process process
                 ^InputStream in
                 ^OutputStream out
                 ; An atom which is set to false iff we're shutting down.
                 running?
                 ; The read-loop future
                 reader
                 ; An atom of the next client-generated stream ID
                 next-stream-id
                 ; An atom to a map of client stream IDs to the next message ID
                 ; for each
                 next-message-ids
                 ; An atom mapping stream ids to threads which handle new
                 ; requests from the server
                 stream-handlers
                 ; An atom mapping stream ids to maps of message IDs we sent to
                 ; CompletableFutures which should be delivered with the
                 ; payloads of replies.
                 replies
                 ])

(defn next-stream-id
  "Picks the next stream ID. Client-created streams always have odd stream IDs."
  [stream-id]
  (assert (< stream-id 4294967295) "Out of stream IDs.")
  (+ stream-id 2))

(defn next-message-id
  "Picks the next message ID. Non-reply messages are always positive. Yes this
  is weird; I don't know why the spec uses the low bits for stream IDs and the
  high bits for message ID flags."
  [message-id]
  ; Note that Integer/MAX_VALUE is reserved for stream closing!
  (assert (< message-id (dec Integer/MAX_VALUE)))
  (inc message-id))

(defn gen-message-id!
  "Generates a fresh message ID for the given stream."
  [core stream-id]
  (let [ids (first (swap-vals! (.next-message-ids core)
                               update stream-id next-message-id))]
    (get ids stream-id)))

(defn request-message-id
  "Computes the request message ID for a given reply message ID."
  (^long [^long reply-id]
         (bit-and Integer/MAX_VALUE reply-id)))

(defn response-message-id
  "Computes the response message ID for a given request message ID."
  (^long [^long request-id]
         (bit-or Integer/MIN_VALUE request-id)))

(defn friendly-message-id
  "It's a pain to try and do twos-complement bit arithmatic in one's head to
  map between request and reply IDs. This prints a friendly string for a
  message ID, either '123' for a request, or 'r123' for the corresponding
  reply."
  [^long id]
  (if (neg? id)
     (str "r" (request-message-id id))
     (str id)))

(declare handshake! crash-core! reader!)

(defn start-core!
  "Starts the Hegel daemon. Returns a Core record.

  TODO: prefer HEGEL_SERVER_COMMAND"
  []
  (try
    (let [process (.. (ProcessBuilder.
                        (into-array ["uv" "tool" "run" "--from"
                                     (str "hegel-core==" core-version)
                                     "hegel"
                                     "--verbosity"
                                     "normal"]))
                      (redirectError (io/file core-log-file))
                      (start))
          core (map->Core
                 {:process          process
                  :in               (DataInputStream. (.getInputStream process))
                  :out              (DataOutputStream. (.getOutputStream process))
                  :running?         (atom true)
                  :next-stream-id   (atom 1)
                  :next-message-ids (atom {0 0})
                  :stream-handlers  (atom {})
                  :replies          (atom {})})]
      ; If we crash here, we need to tear down everything
      (try
        ; Start reader thread
        (let [core (assoc core :reader (reader! core))]
          (try
            ; Initial handshake
            (handshake! core)
            core
            (catch Exception e
              (crash-core! core e)
              (throw e))))
        (catch Exception e
          (crash-core! core e)
          (throw e))))
    (catch IOException e
      (if (re-find #"Cannot run program \"uv\"" (.getMessage e))
        ; Hegel says this library should automatically download and install UV
        ; binaries. I think having a library run random shell scripts from HTTP
        ; is... impolite; we'll let the user do it.
        (throw (RuntimeException. "UV not installed; please see https://docs.astral.sh/uv/getting-started/installation"))
        (throw e)))))

(defn stop-core!
  "Stops the Hegel Core process."
  [^Core core]
  (reset! (:running? core) false)
  (when-let [r (.reader core)]
    (future-cancel r))
  (.close (.in core))
  (.close (.out core))
  (.. ^Process (.process core)
      destroyForcibly
      waitFor))

(defn crash-core!
  "Used when an error occurs. Stops core, then fills in every pending promise
  with the given exception."
  [core exception]
  (stop-core! core)
  (dorun
    (for [[stream-id m] @(:replies core)
          [msg-id p] m]
      (.completeExceptionally p exception))))

(defmacro with-core
  "Evaluates body with a Hegel core bound."
  [core-sym & body]
  `(let [~core-sym (start-core!)]
     (try
       (let [res# ~@body]
         (stop-core! ~core-sym)
         res#)
       (catch Exception e#
         (crash-core! ~core-sym e#)
         (throw e#)))))

;; Serialization

(defprotocol IPacket
  (->raw-packet [p] "Converts a Packet to a RawPacket."))

; A RawPacket represents a logical Hegel packet with a byte-buffer payload.
(defrecord RawPacket [^int stream-id, ^int message-id, ^ByteBuffer payload]
  IPacket
  (->raw-packet [this]
    this))

; An ASCII Packet stores an ASCII payload as a String.
(defrecord AsciiPacket [^int stream-id, ^int message-id, ^String payload]
  IPacket
  (->raw-packet [_]
    (let [bs (.getBytes payload StandardCharsets/US_ASCII)
          buf (ByteBuffer/wrap bs)]
      (RawPacket. stream-id message-id buf))))

; A CBOR Packet stores arbitrary data.
(defrecord CborPacket [^int stream-id, ^int message-id, payload]
  IPacket
  (->raw-packet [_]
    (let [bs (cbor/encode payload)]
      (RawPacket. stream-id message-id (ByteBuffer/wrap bs)))))

(defn checksum
  "Computes the CRC32 checksum of a ByteBuffer Packet. The protocol docs say
  this is 'crS32(packet)', but I can't figure out what that is. I assume it's a
  typo?"
  (^long [^ByteBuffer packet]
         (let [crc      (CRC32.)
               position (.position packet)
               limit    (.limit packet)]
           ; Magic
           (.update crc (.. packet (position 0) (limit 4)))
           ; Zero checksum
           (.update crc 0)
           (.update crc 0)
           (.update crc 0)
           (.update crc 0)
           ; Remaining data, except terminator
           (.update crc (.. packet (limit (dec (.capacity packet))) (position 8)))
           ; Reset buffer
           (.. packet (limit limit) (position position))
           (.getValue crc))))

(defn ^ByteBuffer raw-packet->buf
  "Converts a RawPacket to a ByteBuffer."
  [^RawPacket packet]
  (let [payload (.payload packet)
        size    (+ 21 (.remaining payload))
        payload-position (.position payload)
        buf     (.. (ByteBuffer/allocate size)
                    (putInt magic)
                    (putInt 8 (.stream-id packet))
                    (putInt 12 (.message-id packet))
                    (putInt 16 (.remaining payload))
                    (position 20)
                    (put payload)
                    (put terminator)
                    (position 0))
        ; Reset payload position
        _ (.position payload payload-position)
        ; Now we can compute the checksum
        checksum (checksum buf)
        buf (.. buf
                (putInt 4 checksum)
                (position 0)
                (limit size))]
    buf))

(defn ^RawPacket buf->raw-packet
  "Converts a ByteBuffer into a RawPacket, verifying the checksum."
  [^ByteBuffer buf]
  (let [checksum- (.getInt buf 4)
        length    (.getInt buf 16)]
    (when-not (= checksum- (unchecked-int (checksum buf)))
      (throw (ex-info "Hegel packet checksum error"
                      {:type ::checksum-error
                       :message  checksum-
                       :computed (checksum buf)})))
    (RawPacket. (.getInt buf 8)
                (.getInt buf 12)
                (.. buf (position 20) (limit (+ 20 length)) (slice)))))

(defn read-int!
  "Reads an integer from an InputStream, using the given ByteBuffer as a
  scratchpad."
  [^InputStream in, ^ByteBuffer buf]
  (.position buf 0)
  (loop [i 0]
    (when (< i 4)
      (let [b (.read in)]
        (if (= -1 b)
          (do ; Love the JVM InputStream API. For STDIO streams, -1 is not
              ; actually the end; they signal that with IOException. Unless the
              ; process crashes, in which case -1 IS the end? Argh.
              (Thread/sleep 10)
              (recur i))
           (do ;(info "Read" b)
               (.put buf (unchecked-byte b))
               (recur (inc i)))))))
  (.position buf 0)
  (.getInt buf 0))

(defn read-terminator!
  "Reads a single terminator byte, validating it."
  [^InputStream in]
  (let [b (.read in)]
    (if (= -1 b)
      (recur in) ; Waiting for bytes
      (when (not= terminator b)
        (throw (ex-info "Incorrect packet terminator"
                        {:type     ::terminator-mismatch
                         :expected terminator
                         :actual   b}))))))

(defn cbor-read-string
  "Reads a string, whose payload is UTF-8, but with surrogate code points
  allowed."
  [^bytes b]
  (.toString (.decode StandardCharsets/UTF_8 (ByteBuffer/wrap b))))

(def cbor-codec
  "Our custom CBOR codec"
  (cbor/cbor-codec
    :write-handlers cbor/default-write-handlers
    :read-handlers  (merge cbor/default-read-handlers
                           {91 cbor-read-string})))

(defn error-type
  "Errors are maps with an 'error' key, which is a meaningless, unstable
  integer (!?), and a 'type' key (e.g. 'StopTest'), which is useful. Returns
  the type of an error as a qualified keyword ala ::hegel-clj/stop-test,  else
  nil."
  [payload]
  (when (and (map? payload) (contains? payload "error"))
    (let [type (payload "type")]
      ; Not sure how far down this road I want to go; for now let's enumerate.
      (case type
        ; What ARE these? Why is there no documentation for them???
        "FlakyReplay" :hegel-clj/flaky-replay
        "StopTest"    :hegel-clj/stop-test
        "TypeError"   :hegel-clj/type-error
        "ValueError"  :hegel-clj/value-error
        (keyword "hegel-clj" type)))))

(defn read-loop!
  "Reads packets from the inputstream, dispatching them. Takes a Core."
  [^Core core]
  ; You might ask why this is a big mass of imperative code---it works out that
  ; the CBOR decoder wants an InputStream, so there's not much sense in copying
  ; things over to ByteBuffers and then going *back* to streams from there.
  ; Ditto, it's easier to roll the CRC inline than to build it all
  ; functionally.
  (let [in        (.in core)
        replies   (.replies core)
        stream-handlers  (.stream-handlers core)
        ; A little scratchpad for integer decoding
        buf (ByteBuffer/allocate 4)
        ; And a rolling checksum
        crc (CRC32.)]
    (loop []
      (.reset crc)
      ; Read magic
      (let [pkt-magic (read-int! in buf)
            _ (when (not= magic pkt-magic)
                (throw (ex-info "Magic mismatch"
                       {:type     ::magic-mismatch
                        :expected magic
                        :actual   pkt-magic})))
            _ (.update crc (.position buf 0))

            ; Read headers
            pkt-checksum (read-int! in buf)
            _ (.putInt buf 0 0)
            _ (.update crc (.position buf 0))
            stream-id    (read-int! in buf)
            _ (.update crc (.position buf 0))
            message-id   (read-int! in buf)
            _ (.update crc (.position buf 0))
            payload-length (read-int! in buf)
            _ (.update crc (.position buf 0))
            ;_ (info "magic" pkt-magic "checksum" pkt-checksum "stream-id" stream-id "message-id" message-id "payload-len" payload-length)

            ; Read payload.
            payload-stream (LimitInputStream. payload-length in crc)
            ; Hegel's protocol is a bit awkward--messages have
            ; different payload encodings, but there's no type field to tell
            ; you how to interpret one. Instead we rely on knowing that
            ; a specific message IDs will be used for the handshake, and...
            ; hopefully that's the only special case.
            payload (cond
                      ; Handshake
                      (and (= 0 stream-id)
                           ; We send message 0 as the handshake, and so the
                           ; reply will be 0b100...000
                           (= Integer/MIN_VALUE message-id))
                      (bs/convert payload-stream String
                                  {:encoding "US-ASCII"})

                      ; Everything else is CBOR
                      true
                      (cbor/decode cbor-codec payload-stream ::eof))
            _ (when (identical? ::eof payload)
                (throw (EOFException. "End of Hegel stream")))

            _ (when (not= payload-length (.consumed payload-stream))
                (throw
                  (ex-info "Decoder didn't consume entire payload"
                           {:stream-id      stream-id
                            :message-id     message-id
                            :payload-length payload-length
                            :consumed       (.consumed payload-stream)
                            :payload        payload
                            :remaining      (bs/convert payload-stream String)})))

            ; Swallow terminator
            _ (read-terminator! in)

            ; Verify checksum
            checksum (unchecked-int (.getValue crc))
            _ (when (not= pkt-checksum checksum)
                (throw (ex-info "Checksum error"
                                {:type     ::checksum-error
                                 :expected pkt-checksum
                                 :actual   checksum})))]

        ;(info "Receive" stream-id (friendly-message-id message-id)
        ;      (pr-str payload))

        (if (neg? message-id)
          ; Deliver replies to futures
          (let [rid (request-message-id message-id )]
            (if-let [^CompletableFuture p (-> @replies (get stream-id) (get rid))]
              ; Deliver promise
              (do (if-let [type (error-type payload)]
                    ; This is an error message
                    (.completeExceptionally
                      p (HegelError. (str "Hegel-core error: " (payload "type"))
                                     {:stream-id          stream-id
                                      :request-message-id rid
                                      :type               type}))
                    (.complete p payload))
                  ; We don't need to track this any more
                  (swap! replies update stream-id dissoc rid))
              ; No promise!
              (throw (ex-info "Reply to message we didn't send!"
                              {:type       ::unexpected-reply
                               :stream-id  stream-id
                               :message-id message-id
                               :payload    payload
                               :replies    @replies
                               }))))

          ; Otherwise, this must be a server-sent command; we spawn a fresh
          ; thread to handle it.
          (if-let [handler (get @stream-handlers stream-id)]
            (vthread "hegel-clj handler"
                     (try
                       (handler (CborPacket. stream-id message-id payload))
                       (catch Throwable e
                         (warn e "Uncaught exception in hegel-clj stream handler")
                         (stop-core! core))))

            (throw (ex-info "No handler for stream"
                            {:type       ::no-stream-handler
                             :stream-id  stream-id
                             :message-id message-id
                             :payload    payload}))))

        ; Again again again!
        (recur)))))

(defn reader!
  "Spawns a future for a core to read incoming messages."
  [core]
  (future
    (with-thread-name "hegel-clj reader"
      (try
        (read-loop! core)
        (catch Exception e
          (when @(:running? core)
            (warn e "Hegel-clj reader crashed!")
            (crash-core! core e)
            (throw e)))))))

(defn send!
  "Sends an IPacket to Hegel core."
  [^Core core packet]
  (let [raw-packet (->raw-packet packet)
        os  ^OutputStream (.out core)
        buf (raw-packet->buf raw-packet)]
    ;(info "Send" (:stream-id packet) (friendly-message-id (:message-id packet))
    ;      (pr-str (:payload packet))
    ;      #_"\n" #_(with-out-str (bs/print-bytes buf)))
    (locking os
      (.write os (.array buf))
      (.flush os)))
  core)

(defn reply!
  "Replies to a Packet with a CBOR body."
  [core req-packet res-body]
  (send! core (CborPacket. (:stream-id req-packet)
                           (response-message-id (:message-id req-packet))
                           res-body)))

(defn open-stream!
  "Generates a fresh stream on the given core, returning its ID. If given an
  ID, prepares that stream for use locally."
  ([^Core core]
   (let [id (first (swap-vals! (.next-stream-id core) next-stream-id))]
     (open-stream! core id)))
  ([^Core core ^long stream-id]
   (swap! (.next-message-ids core) assoc stream-id 0)
   stream-id))

(defn close-stream!
  "Closes a stream on the given core. Returns core."
  [^Core core stream-id]
  ;(info "Closing stream" stream-id)
  (try
    (send! core (RawPacket. stream-id
                            Integer/MAX_VALUE
                            (.. (ByteBuffer/allocate 1)
                                (put (unchecked-byte 0xfe))
                                (position 0))))
    (catch IOException e
      (when (= "Stream Closed" (.getMessage e))
        ; Fine; we're probably racing to shut things down
        )))
  (swap! (.next-message-ids core) dissoc stream-id)
  (swap! (.stream-handlers core) dissoc stream-id)
  (swap! (.replies core) dissoc stream-id)
  core)

(defn register-stream-handler!
  "Registers a stream handler function for incoming Packets."
  [^Core core stream-id handler]
  (swap! (.stream-handlers core) assoc stream-id handler))

(defn rpc!
  "Sends an RPC request, registering a Future which will receive the payload
  of the reply to this message."
  [^Core core packet]
  (let [p (CompletableFuture.)]
    (swap! (.replies core)
           update (:stream-id packet)
           assoc (:message-id packet)
           p)
    (send! core packet)
    p))

(defn handshake!
  "Sends the handshake message and awaits the reply. Returns reply payload."
  [^Core core]
  (let [res @(rpc! core (AsciiPacket. control-stream-id
                                      (gen-message-id! core control-stream-id)
                                      "hegel_handshake_start"))]
    (when (not= (str core-version-string) res)
      (throw (ex-info (str "Unexpected Hegel-core version " res)
              {:type :hegel-clj/unexpected-core-version
               :expected core-version-string
               :actual res})))
    res))

(defn maybe-finish-test!
  "Called when we might be done with a test. If the final countdown is zero,
  delivers the results of final-case-results and tmp-results to results, and
  closes the test stream."
  [core test-stream-id final-countdown final-case-results tmp-results ^CompletableFuture results]
  (when (= 0 @final-countdown)
    (.complete results
               (assoc @tmp-results
                      :final @final-case-results))
    (close-stream! core test-stream-id)))

(defn run-test-case!
  "Part of run-test!. Same arguments; this part handles a test_case command
  from the server. Acknowledges the test-case command, invokes case-fn to
  perform the test case, and sends results back to the server."
  [core test-stream-id case-fn final-countdown final-case-results tmp-results
   ^CompletableFuture results ^CborPacket req]
  (reply! core req {"result" nil})
  (let [payload   (.payload req)
        stream-id (payload "stream_id")
        is-final? (payload "is_final")]
    (assert (integer? stream-id)
            (str "No stream_id for message " (pr-str req)))
    (open-stream! core stream-id)
    (try
      ; Actually run case
      (let [res (try (if is-final?
                       (binding [*final-case?* true]
                         (let [r (case-fn stream-id)]
                           (swap! final-case-results conj r)
                           r))
                       (case-fn stream-id))
                     ; Hegel-core sometimes returns a StopTest error in the
                     ; middle of a test case when we (e.g.) ask it to generate
                     ; an integer. I am pretty sure this is a bug? I don't know
                     ; how to respond to this either, because, like... we can't
                     ; mark it as VALID or INTERESTING, since the test case
                     ; didn't actually *run* to completion. Returning INVALID
                     ; seems to hang indefinitely. I guess we just close the
                     ; stream?
                     (catch HegelError e
                       (if (identical? :hegel-clj/stop-test
                                       (:type (ex-data e)))
                           ::weird-stop-test
                           (do (info e "Caught exception in test case")
                               {:status :interesting
                                :origin (str (.getClass e) " " (ex-message e)
                                             "\n" (pr-str (ex-data e)))})))
                     ; Any other throwable that occurs during the test case we
                     ; consider interesting. Throwable might not be ideal
                     ; here, but there's a very good chance users will use
                     ; `assert`, and that throws an Error, not an Exception!
                     ;
                     ; TODO: How are we going to surface this error to the
                     ; user?
                     (catch Throwable t
                       (do (info t "Caught exception in test case")
                           {:status :interesting
                            :origin (str (.getClass t) " " (.getMessage t))})))]
        (if (identical? ::weird-stop-test res)
          ; I think we're just supposed to close the stream and give up?
          ; Sending a mark_complete will hang forever o____o
          nil

          (let [; Validate case result
                _ (when (not (and (map? res)
                                  (keyword (:status res))))
                    (throw (ex-info
                             "Test cases should return a map with a :status value"
                             {:type :malformed-test-case-return-value
                              :return-value res})))
                res (if (and (identical? :interesting (:status res))
                             (not (string? (:origin res))))
                      ; Provide a default
                      (assoc res :origin "")
                      res)

                ; And mark case as completed
                payload (cond-> {"command" "mark_complete"
                                 "status"  (case (:status res)
                                             :valid "VALID"
                                             :invalid "INVALID"
                                             :interesting "INTERESTING")}
                          (identical? :interesting (:status res))
                          (assoc "origin" (:origin res)))
                req (CborPacket. stream-id
                                 (gen-message-id! core stream-id)
                                 payload)
                t1  (System/nanoTime)
                res (rpc! core req)]
            ; If we're in the final countdown, we should decrement.
            (when is-final?
              (swap! final-countdown
                     (fn countdown [c]
                       (if c
                         (dec c)
                         c)))
              ; And we may be ready to return to the top-level test caller
              (maybe-finish-test!
                core test-stream-id final-countdown final-case-results
                tmp-results results))
            ; Check the reply for our mark_complete message
            (try (deref-rethrow res command-timeout)
                 (catch HegelError e
                   (case (:type (ex-data e))
                     ; AFAICT the protocol documentation is wrong: you'll
                     ; *never* get a mark_complete_reply in response to
                     ; mark_complete. Instead the server sends a StopTest
                     ; error, which isn't really an error; it just means we're
                     ; done. Nor does it mean the test is done--it actually
                     ; means the test *case* is done.
                     :hegel-clj/stop-test nil

                     ; For other errors here, we'll relay them back up to the
                     ; caller. I've wound up in infinite loops ignoring flaky
                     ; errors, so I think maybe we have to kill the whole
                     ; thing?
                     (do
                       (.completeExceptionally results e)
                       (stop-core! core))))
                 (catch Exception e
                   (info "Waited for" (* 1e-9 (- (System/nanoTime) t1))
                         "s for " req)
                   (do (.completeExceptionally results e)
                       (stop-core! core)))))))
      (catch Exception e
        (.completeExceptionally results e)
        (stop-core! core))
      (finally
        (close-stream! core stream-id)))))

(defn on-test-done!
  "Part of run-test!. Same arguments; this part handles a test_done command by
  transitioning to the final phase of the test."
  [core test-stream-id case-fn final-countdown final-case-results tmp-results
   ^CompletableFuture results req]
  ; Fun fact: this does not mean the test is done. It means the start of
  ; a final phase, where the test replays `interesting_test_cases` cases.
  ; We can't return until this is done, so we need to squirrel away the
  ; test results, and count down as those tests are replayed.
  ; Save the results that *will* be delivered later
  (let [r ((:payload req) "results")]
    (deliver tmp-results
             {:passed?                (r "passed")
              :test-cases             (r "test_cases")
              :valid-test-cases       (r "valid_test_cases")
              :invalid-test-cases     (r "invalid_test_cases")
              :interesting-test-cases (r "interesting_test_cases")
              ; Not sure what these byte arrays are
              ;:failure-blobs          (r "failure_blobs")
              :seed                   (r "seed")
              :flaky?                 (r "flaky")
              :health-check-failure?  (r "health_check_failure")
              :error                  (r "error")})
    ; Set up the countdown
    (let [c (r "interesting_test_cases")]
      (assert (and (integer? c) (not (neg? c)))
              (str "Expected interesting_test_cases to be non-negative" (pr-str r)))

      (reset! final-countdown c))
    ; And ack
    (reply! core req {"result" true})
    ; If we found nothing interesting, we may return final results now
    (maybe-finish-test! core test-stream-id final-countdown final-case-results
                        tmp-results results)))

(defn run-test-handler!
  "Handles messages received from the server during a test.

  `core`      The Hegel client core.
  `test-stream-id`  A stream ID for this test run
  `case-fn`         A function (case-fn stream-id) which runs a single test
                    case.
  `final-countdown` An atom with the number of final test cases (those which
                    occur after the test_done message) we have yet to process.
  `tmp-results`     A promise where we store the result map during
                    the final test cases.
  `results`         A CompletableFuture which receives the result map once the
                    final test cases are complete. This is what the caller
                    blocks on.
  `req` The request CborPacket we're processing."
  [core test-stream-id case-fn final-countdown final-case-results tmp-results
   ^CompletableFuture results req]
  (let [payload    (:payload req)
        event      (payload "event")
        error-type (error-type payload)]
    ; TODO: I added this when fighting bizarre errors but I'm not sure it's
    ; actually necessary. Do we *really* get errors sent as commands here?
    (case error-type
      ; Not an error
      nil
      (case event
        "test_case"
        (run-test-case! core test-stream-id case-fn final-countdown
                        final-case-results tmp-results results req)

        "test_done"
        (on-test-done! core test-stream-id case-fn final-countdown
                       final-case-results tmp-results results req)

        ; Huh, something we didn't handle
        (.completeExceptionally results
                                (ex-info (str "Unknown command: " event)
                                         payload)))

      ; Any other error we'll bubble up as an exception on the caller.
      (do (.completeExceptionally results
                                  (ex-info (str "Hegel error: " error-type)
                                           {:type ::hegel-error
                                            :hegel-type error-type}))
          (close-stream! core test-stream-id)))))

(defn run-test!
  "Sends a run-test command. Options are:

      :test-cases     The number of test cases to run (default 100)
      :seed           A random seed
      :derandomize    If true, and seed is not set, derives a determinstic
                      seed from database-key
      :database-key   A stable database key for this test
      :database       A path to the DB directory
      :suppress-health-check  A vector of health check keywords to suppress:
                              any of :test-cases-too-large, :filter-too-much,
                              :too-slow, :large-initial-test-case

  Takes a function `(case-fn)` which will be invoked approximately `test-cases`
  times, with zero arguments. This function can generate values and should
  return a map which explains whether the case was valid or not:

      {:status :valid}
      {:status :invalid}
      {:status :interesting
       :origin \"...\"}

  These maps can have extra information, which will be returned to you via
  `:final` in the event Hegel finds interesting test cases. The `:origin` key
  tells Hegel where the failure occurred; I suggest a file name and line
  number. Origin is optional: if omitted, it will be the empty string.

  Returns a map describing the results of the test, of the form:

      :passed?                Did all tests pass?)
      :test-cases             How many test cases were executed?
      :valid-test-cases       How many of them were valid
      :invalid-test-cases     How many of them were invalid
      :interesting-test-cases How many of them were interesting (e.g. a bug)
      :seed                   The random seed used
      :flaky?                 Hegel thought this test was non-deterministic
      :health-check-failure?  Did a health check fail during the test?
      :final                  A vector of result maps (e.g. {:status
                              :interesting, :origin \"...\", ...}) returned
                              from each test case run during the final phase.

  May also throw an exception map like {:type :hegel-error, :message \"...\"}."
  [core opts case-fn]
  (let [stream-id          (open-stream! core)
        final-countdown    (atom nil)
        ; We use this to build up the maps returned by each final case.
        final-case-results (atom [])
        ; We use this to keep track of Hegel's results map.
        tmp-results        (promise)
        ; And here's the future we use to return to the caller.
        results            (CompletableFuture.)
        _ (register-stream-handler!
            core stream-id
            (partial run-test-handler! core stream-id case-fn final-countdown
                     final-case-results tmp-results results))
        ; Kick off test
        opts (-> opts
                 (default :test-cases 100)
                 (update- :suppress-health-check
                          (partial mapv snake-case-str))
                 (update-keys snake-case-str)
                 (assoc "command" "run_test"
                        "stream_id" stream-id))
        _ (deref-rethrow (rpc! core (CborPacket.
                                      control-stream-id
                                      (gen-message-id! core control-stream-id)
                                      opts))
                         command-timeout)
        r (deref results)]
    (when-let [err (:error r)]
      (throw (ex-info (str "Hegel error: " err)
                      {:type :hegel-error
                       :message err})))
    r))

(defn generate!
  "Asks a core to generate a value. Takes a Core, stream id, and a Schema from
  hegel-clj.gen."
  [core stream-id schema]
  (let [x (-> core
              (rpc! (CborPacket. stream-id (gen-message-id! core stream-id)
                                 {"command" "generate"
                                  "schema"  (gen/->map schema)}))
              (deref-rethrow command-timeout)
              (get "result"))]
    (gen/post schema x)))
