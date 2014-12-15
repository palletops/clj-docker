(ns com.palletops.docker.cert
  (:require
   [clj-time.core :as time]
   [clj-time.coerce :refer [to-date to-long]]
   [com.palletops.docker.jwk :refer [key-id]])
  (:import
   [java.security PublicKey]
   [java.security.cert Certificate]
   [sun.security.util ObjectIdentifier]
   [sun.security.x509
    AlgorithmId CertificateAlgorithmId CertificateExtensions
    CertificateIssuerName
    CertificateSerialNumber CertificateSubjectName CertificateValidity
    CertificateX509Key CertificateVersion
    BasicConstraintsExtension ExtendedKeyUsageExtension
    X500Name X509CertImpl X509CertInfo]))

(def CRITICAL true)
(def NON_CRITICAL false)
(def server-auth-oid (ObjectIdentifier. "1.3.6.1.5.5.7.3.1"))
(def client-auth-oid (ObjectIdentifier. "1.3.6.1.5.5.7.3.2"))

(defn ^Certificate new-cert
  "Return a new X509 Certificate, signed by `key`."
  [{:keys [key ^PublicKey public-key
           client-auth server-auth
           ca max-path
           not-before not-after]
    :or {not-before (time/minus (time/now) (time/days 7))
         not-after (time/plus (time/now) (time/years 10))
         max-path 1024}}]
  (let [interval (CertificateValidity. (to-date not-before) (to-date not-after))
        algo (AlgorithmId. AlgorithmId/sha256WithECDSA_oid)
        v (filter identity
                  [(if server-auth server-auth-oid)
                   (if client-auth client-auth-oid)])
        _ (println "extensions" v)
        ext (doto (CertificateExtensions.)
              (.set ExtendedKeyUsageExtension/NAME
                    (ExtendedKeyUsageExtension.
                     NON_CRITICAL (java.util.Vector. ^java.util.List v))))
        _ (when ca
            (.set BasicConstraintsExtension/NAME
                  (BasicConstraintsExtension. CRITICAL ca max-path)))
        cert-info (doto (X509CertInfo.)
                    (.set X509CertInfo/VERSION
                          (CertificateVersion. CertificateVersion/V3))
                    (.set X509CertInfo/VALIDITY
                          interval)
                    (.set X509CertInfo/SERIAL_NUMBER
                          (CertificateSerialNumber. 0))
                    (.set (str X509CertInfo/ISSUER
                               "." CertificateSubjectName/DN_NAME)
                          (X500Name. (str "CN=" (key-id public-key))))
                    (.set (str X509CertInfo/SUBJECT
                               "." CertificateSubjectName/DN_NAME)
                          (X500Name. (str "CN=" (key-id public-key))))
                    (.set X509CertInfo/KEY (CertificateX509Key. public-key))
                    (.set X509CertInfo/EXTENSIONS ext)
                    (.set X509CertInfo/ALGORITHM_ID
                          (CertificateAlgorithmId. algo)))]
    (doto (X509CertImpl. cert-info)
      (.sign key "SHA256WithECDSA"))))
