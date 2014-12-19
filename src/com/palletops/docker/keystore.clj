(ns com.palletops.docker.keystore
  "Create keystore based on docker machine and boot2docker cert files."
  (:require
   [clojure.data.codec.base64 :refer [decode]]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [com.palletops.docker.cert :refer [new-cert]]
   [com.palletops.docker.jwk :refer [load-jwk]])
  (:import
   java.io.File
   [java.security Key KeyFactory KeyStore]
   [java.security.cert Certificate CertificateFactory X509Certificate]
   [net.oauth.signature.pem PKCS1EncodedKeySpec]))

(defn ^Key load-key
  "Load a key from a PKCS#1 encoded pem file."
  [^File key-file]
  (let [der (with-open [s (java.io.BufferedInputStream.
                           (io/input-stream key-file))]
              (let [chars (slurp s)
                    b64chars (->> (string/split-lines chars)
                                  (remove #(re-matches #"---.*---" %))
                                  string/join)]
                (decode (.getBytes b64chars))))
        key-spec (PKCS1EncodedKeySpec. der)
        key-factory (KeyFactory/getInstance "RSA")
        key (.generatePrivate key-factory (.getKeySpec key-spec))]
    key))

(defn ^X509Certificate load-cert
  "Load a X509Certificate from a pem file."
  [^CertificateFactory cert-factory ^File path]
  (with-open [s (java.io.BufferedInputStream. (io/input-stream path))]
    (.generateCertificate cert-factory s)))

(defn key-and-certs
  "Return a key and certificate chain based on key-path and the
  certificate chain in cert-paths."
  [key-path cert-paths]
  [(load-key (io/file key-path))
   (let [cert-factory (CertificateFactory/getInstance "X.509")]
     (map #(load-cert cert-factory (io/file %)) cert-paths))])

(defn fill-keystore
  [^KeyStore ks ^chars key-pw key certs]
  (doseq [[cert cert-num] (map vector certs (range))]
    (.setEntry
     ks (str "cert" cert-num)
     (java.security.KeyStore$TrustedCertificateEntry. cert)
     nil))
  (.setKeyEntry ks "key" key key-pw (into-array X509Certificate certs))
  ks)

(defn ^KeyStore key-store-load
  "Load a keystore file if it exists.  Returns nil if the path does not exist or
  is a zero length file."
  [^KeyStore ks ^File ks-path ^chars pw]
  (if (and (.exists ks-path) (pos? (.length ks-path)))
    (doto ks
      (.load (io/input-stream ks-path) pw))))

(defn ^KeyStore key-store-create
  "Create a keystore file, with the given key and certificates."
  [^KeyStore ks ^File ks-path ^chars pw ^Key key certs]
  (.load ks nil pw)
  (fill-keystore ks pw key certs)
  (with-open [os (io/output-stream ks-path)]
    (.store ks os pw))
  ks)

(defn ^String key-store
  "Return a JKS format keystore, containing the creds specified in
  files in cert-path.  If the keystore file exists already, simply
  load it."
  ([cert-dir]
   (key-store
    (io/file cert-dir "key.pem")
    [(io/file cert-dir "cert.pem")      ; order is important here
     (io/file cert-dir "ca.pem")]))
  ([key-path cert-paths]
   (let [ks-path (io/file (.getParent (io/file key-path)) "client.ks")
         ks (KeyStore/getInstance "JKS")
         pw (.toCharArray "")]
     (or (key-store-load ks ks-path pw)
         (let [[key certs] (key-and-certs key-path cert-paths)]
           (key-store-create ks ks-path pw key certs))))))

(defn jwk-key-and-certs
  "Return the private key specified by jwk-path.  The adjacent public
  key is also read and returned.  A self-signed X509Certificate is
  created a client certificate and returned as a certificate chain in
  the second element of the return value."
  [private-key-path public-key-path]
  (let [key (load-jwk private-key-path)
        public-key (load-jwk public-key-path)
        cert (new-cert {:key key
                        :public-key public-key
                        :client-auth true
                        :server-auth false})]
    [key [cert]]))

(defn ^String key-store-jwk
  "Return a JKS format keystore, containing the orivate key specified
  by key-dir or key-path.  The adjacent public key is also read and a
  self-signed X509Certificate is create as a client certificate."
  ([key-dir]
   (key-store-jwk
    (io/file key-dir "key.json")
    (io/file key-dir "public-key.json")))
  ([key-path public-key-path]
   (let [ks-path (io/file (.getParent (io/file key-path)) "client.ks")
         ks (KeyStore/getInstance "JKS")
         pw (.toCharArray "")]
     (or (key-store-load ks ks-path pw)
         (let [[key certs] (jwk-key-and-certs key-path public-key-path)]
           (key-store-create ks ks-path pw key certs))))))

(defn add-cert
  "Add a cert to the specified keystore, storing it."
  [ks-path ^KeyStore ks ^X509Certificate cert ks-pass]
  (.setEntry
   ks "server" (java.security.KeyStore$TrustedCertificateEntry. cert) nil)
  (with-open [os (io/output-stream ks-path)]
    (.store ks os ks-pass)))
