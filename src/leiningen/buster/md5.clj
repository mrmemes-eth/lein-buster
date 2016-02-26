(ns leiningen.buster.md5
  (:require [clojure.java.io :as io]))

(defn md5-bytes
  "Generate a md5 bytes for the given string"
  [token]
  (let [hash-bytes
        (doto (java.security.MessageDigest/getInstance "MD5")
          (.reset)
          (.update (.getBytes token)))]
    (.digest hash-bytes)))

(defn md5
  "Generate a md5 checksum for the given string"
  [string]
  (.toString
   (new java.math.BigInteger 1 (md5-bytes string))
   16))

(defn md5-file
  "Generate a md5 checksum for the given Fileable"
  [path]
  (-> path io/as-file slurp md5))
