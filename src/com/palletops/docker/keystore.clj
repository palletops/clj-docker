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

(defn cert-path-key-and-certs
  "Return a key and certificate chain based on the files in cert-path."
  [cert-path]
  (let [ca-f (io/file cert-path "ca.pem")]
    (if (.exists ca-f)
      (let [cert-factory (CertificateFactory/getInstance "X.509")
            ca-cert (load-cert cert-factory ca-f)
            server-cert (load-cert cert-factory (io/file cert-path "cert.pem"))
            key (load-key (io/file cert-path "key.pem"))]
        [key [server-cert ca-cert]])
      (throw (ex-info (str "No such file: " (str ca-f)) {:file (str ca-f)})))))

(defn ^String key-store
  "Return a JKS format keystore, containing the creds specified in
  files in cert-path."
  [cert-path]
  (let [ks-path (io/file cert-path "client.ks")
        ks (KeyStore/getInstance "JKS")
        pw (.toCharArray "")]
    (if (and (.exists ks-path) (pos? (.length ks-path)))
      [(str ks-path) (doto ks
                       (.load (io/input-stream ks-path) pw))]
      (let [[key [server-cert ca-cert]] (cert-path-key-and-certs cert-path)]
        (doto ks
          (.load nil pw)
          (.setEntry
           "ca"
           (java.security.KeyStore$TrustedCertificateEntry. ca-cert)
           nil)
          (.setEntry
           "server"
           (java.security.KeyStore$TrustedCertificateEntry. server-cert)
           nil)
          (.setKeyEntry
           "client" key pw
           (into-array X509Certificate [server-cert ca-cert])))
        (with-open [os (io/output-stream ks-path)]
          (.store ks os pw))
        [(str ks-path) ks]))))

(defn jwk-key-and-cert
  "Return the private key specified by jwk-path.  The adjacent public
  key is also read and returned.  A self-signed X509Certificate is
  create as a client certificate and returned."
  [jwk-path]
  (let [f (io/file jwk-path)]
    (if (.exists f)
      (let [key (load-jwk f)
            public-key (load-jwk
                        (io/file (.getParent f) "public-key.json"))
            cert (new-cert {:key key
                            :public-key public-key
                            :client-auth true
                            :server-auth false})]

        [key public-key cert])
      (throw (ex-info (str "No such file: " (str f)) {:file (str f)})))))


(defn ^String key-store-jwk
  "Return a JKS format keystore, containing the orivate key specified
  by jwk-path.  The adjacent public key is also read and a self-signed
  X509Certificate is create as a client certificate."
  [jwk-path]
  (let [ks-path (io/file (.getParentFile (io/file jwk-path)) "client.ks")
        ks (KeyStore/getInstance "JKS")
        pw (.toCharArray "")]
    (if (and (.exists ks-path) (pos? (.length ks-path)))
      [(str ks-path) (doto ks
                       (.load (io/input-stream ks-path) pw))]
      (let [[key public-key cert] (jwk-key-and-cert jwk-path)]
        (doto ks
          (.load nil pw)
          (.setEntry
           "client"
           (java.security.KeyStore$TrustedCertificateEntry. cert)
           nil)
          (.setKeyEntry "clientkey" key pw
                        (into-array X509Certificate [cert])))
        (with-open [os (io/output-stream ks-path)]
          (.store ks os pw))
        [(str ks-path) ks]))))

(defn add-cert
  "Add a cert to the specified keystore"
  [ks-path ^KeyStore ks ^X509Certificate cert]
  (.setEntry
   ks "server" (java.security.KeyStore$TrustedCertificateEntry. cert) nil)
  (with-open [os (io/output-stream ks-path)]
    (.store ks os (.toCharArray ""))))
