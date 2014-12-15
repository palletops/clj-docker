(ns com.palletops.docker.jwk
  "JSON Web Key Functions"
  (:require
   [clojure.data.codec.base64 :refer [decode]]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [cheshire.core :refer [parse-stream]])
  (:import
   java.io.File
   [java.security AlgorithmParameters Key KeyFactory MessageDigest]
   [java.security.spec
    ECGenParameterSpec ECParameterSpec ECPoint ECPrivateKeySpec ECPublicKeySpec]
   [org.apache.commons.codec.binary Base32]))

(defmulti curve-name
  "Return a JSSE curve name for the given JWK curve name."
  (fn [id] id))

(defmethod curve-name :default [id]
  (throw (ex-info (str "Unimplemented elliptic curve " id) {:id id})))

(defmethod curve-name "P-256" [_] "secp256r1")

;; http://mail.openjdk.java.net/pipermail/security-dev/2013-October/009107.html
(defn named-curve-spec
  [curve-name]
  (let [parameters (AlgorithmParameters/getInstance "EC", "SunEC")
        gen-spec (ECGenParameterSpec. curve-name)]
    (.init parameters gen-spec)
    (.getParameterSpec parameters ECParameterSpec)))

(defn base64url->base64
  [^String s]
  (let [s (-> s
              (string/replace \- \+)
              (string/replace \_ \/))]
    (case (int (mod (count s) 4))
      0 (str s "==")
      2 (str s "==")
      3 (str s "=")
      (throw
       (ex-info "Invalid base64 string" {:string s})))))

(def base64url->BigInteger
  (comp
   #(BigInteger. ^bytes %)
   decode
   #(.getBytes ^String %)
   base64url->base64))

(defn ^Key jwk->key
  "Convert a JWK map specifying an EC key to a Key"
  [{:keys [crv d x y] :as jwk}]
  (let [spec (named-curve-spec (curve-name crv))
        kf (KeyFactory/getInstance "EC")]
    (if d
      (.generatePrivate kf (ECPrivateKeySpec. d spec))
      (.generatePublic kf (ECPublicKeySpec. (ECPoint. x y) spec)))))

(defn ^Key load-jwk [^File key-file]
  (let [{:keys [crv d kid kty x y] :as json} (with-open [r (io/reader key-file)]
                                               (parse-stream r keyword))
        json (-> json
                 (cond-> (:d json) (update-in [:d] base64url->BigInteger))
                 (cond-> (:m json) (update-in [:m] base64url->BigInteger))
                 (update-in [:x] base64url->BigInteger)
                 (update-in [:y] base64url->BigInteger))]
    (when-not (= kty "EC")
      (throw (ex-info "Unsupported key type" json)))
    (jwk->key json)))

(defn key-id [^Key key]
  (->> (.digest (MessageDigest/getInstance "SHA-256") (.getEncoded key))
       (.encodeAsString (Base32.))
       (partition 4)
       (take 12)
       (map #(apply str %))
       (string/join ":")))
