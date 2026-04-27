(ns com.aphyr.hegel-clj.client
  "Provides the raw interface for communicating with Hegel-core."
  (:require [clj-cbor.core :as cbor]
            [clj-commons.byte-streams :as bs]
            [clojure [string :as str]
                     [walk :refer [prewalk]]]
            [clojure.tools.logging :refer [info warn]]
            [clojure.java.io :as io])
  (:import (com.aphyr.hegel_clj LimitInputStream)
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
           (java.util.concurrent CompletableFuture)
           (java.util.zip CRC32)))

(def core-version
  "The version of hegel-core we ask uv for."
  "0.4.7")

(def core-version-string
  "What version string do we expect from Hegel-core? Weirdly this is *not* the
  same as the Hegel version."
  "0.10")

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

; Coercing between Clojure and Hegel names

(defn snake-case-str
  "Converts dashes in any named thing to underscored strings."
  [s]
  (str/replace (name s) #"-" "_"))

(defn kebab-case-kw
  "Converts underscored strings to kebab-case keywords."
  [s]
  (str/replace (name s) #"_" "-"))

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
           (fn []
             (try ~@body
                  (catch Exception e#
                    (warn e# "Uncaught exception in" ~name))))))

;; The hegel-core state machine

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
                 ; An atom mapping stream ids to functions which handle new
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
                                     "--stdio"
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
              ; actually the end; they signal that with IOException.
              ;(info "read -1")
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

(def cbor-codec
  "Our custom CBOR codec"
  (cbor/cbor-codec
    :write-handlers cbor/default-write-handlers
    :read-handlers  cbor/default-read-handlers))

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

        (info "Receive" :stream-id stream-id :message-id message-id
              (pr-str payload))

        (if (neg? message-id)
          ; Deliver replies to futures
          (let [rid (request-message-id message-id )]
            (if-let [^CompletableFuture p (-> @replies (get stream-id) (get rid))]
              ; Deliver promise
              (do (if (and (map? payload)
                           (payload "error"))
                    ; This is an error message
                    (.completeExceptionally p (ex-info (str "Hegel-core error: "
                                                            (payload "type"))
                                                       payload))
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

          ; Otherwise, invoke a stream handler.
          (if-let [handler (get @stream-handlers stream-id)]
            (vthread "hegel-clj handler"
              (handler (CborPacket. stream-id message-id payload)))
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
        os  (.out core)
        buf (raw-packet->buf raw-packet)]
    (info "Send" (pr-str packet)
          #_"\n" #_(with-out-str (bs/print-bytes buf)))
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
  (send! core (RawPacket. stream-id
                          Integer/MAX_VALUE
                          (.. (ByteBuffer/allocate 1)
                              (put (unchecked-byte 0xfe))
                              (position 0))))
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
    (assert (= (str "Hegel/" core-version-string) res))
    res))

(defn handle-test-case!
  "Called when we get a test-case command from the core."
  [core case-fn ^CompletableFuture results req]
  (let [payload (:payload req)
        event   (payload "event")]
    (if-let [error (payload "error")]
      (do (info "Hegel returned error" payload)
          (.completeExceptionally results
                                  (ex-info "Hegel error"
                                           payload)))
      (case event
        "test_case"
        (do (info "Starting test case")
            (reply! core req {"result" nil})
            (let [stream-id (payload "stream_id")]
              (assert (integer? stream-id))
              (open-stream! core stream-id)
              (try
                ; Actually run case
                (let [res (case-fn stream-id)]
                  ; Validate case result
                  (assert (map? res))
                  (assert (:status res))
                  ; And mark case as completed
                  @(rpc! core
                         (CborPacket. stream-id (gen-message-id! core stream-id)
                                      (cond-> {"command" "mark_complete"
                                               "status"  (case (:status res)
                                                           :valid "VALID"
                                                           :invalid "INVALID"
                                                           :interesting "INTERESTING")}
                                        (identical? :interesting (:status res))
                                        (assoc "origin" (:origin res))))))
                (finally
                  (close-stream! core stream-id)))))

        ; Fun fact: this does not mean the test is done. It's going to keep
        ; sending us test cases!
        "test_done"
        (do (info "Test done.")
            (let [r (payload "results")]
              (.complete results
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
                          :error                  (r "error")}))
            (reply! core req {"result" true}))))))

(defn run-test!
  "Sends a run-test command. Options are:

  :test-cases     The number of test cases to run
  :seed           A random seed
  :derandomize    If true, and seed is not set, derives a determinstic seed from
                  database-key
  :database-key   A stable database key for this test
  :database       A path to the DB directory
  :suppress-health-check  A vector of health check keywords to suppress: any of
                          :test-cases-too-large, :filter-too-much, :too-slow,
                          :large-initial-test-case

  `(case-fn stream-id) will be invoked whenever Hegel requests a test
  case. This function's job is to make various calls to (e.g.) generate!, and
  return a test case result, which should be one of:

    {:status :valid}
    {:status :invalid}
    {:status :interesting
     :origin \"...\"}"
  [core opts case-fn]
  (info "run-test!")
  (let [stream-id (open-stream! core)
        ; Register a handler for this stream
        results (CompletableFuture.)
        _ (register-stream-handler! core stream-id
                                    (partial handle-test-case!
                                             core case-fn
                                             results))
        _ (rpc! core (CborPacket. control-stream-id
                                     (gen-message-id! core control-stream-id)
                                     (-> opts
                                         (update- :suppress-health-check
                                                  (partial mapv snake-case-str))
                                         (update-keys snake-case-str)
                                         (assoc "command" "run_test"
                                                "stream_id" stream-id))))]
    @results))

(defn generate!
  "Asks a core to generate a value. See the schema definition at
  https://hegel.dev/reference/protocol#schemas"
  [core stream-id schema]
  (-> core
      (rpc! (CborPacket. stream-id (gen-message-id! core stream-id)
                         {"command" "generate"
                          "schema"  schema}))
      deref
      (get "result")))
