(ns com.aphyr.hegel-clj.gen.proto
  "The protocol for generators. This lives in a separate namespace so that we
  can tie the circular knot between the client (which uses schemas) and the
  schemas (which need the client to generate new values).")

(defprotocol Schema
  (->map [schema]
         "Transforms this schema into a CBOR-serializable map, so that it can be sent to Hegel-Core")

  (post [schema value]
        "Post-processes a generated value. This can be used to transform one
        datatype into another, or to reject a generated value by throwing
        :hegel-clj/invalid."))
