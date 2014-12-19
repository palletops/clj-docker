(ns com.palletops.docker.identity-auth
  "Identity auth scheme as used by docker"
  (:require
   [com.palletops.docker.keystore :refer [add-cert]])
  (:import
   [java.net InetAddress Socket]
   [java.security KeyStore SecureRandom]
   [java.security.cert X509Certificate]
   [javax.net.ssl KeyManagerFactory SSLContext SSLSocket X509TrustManager]))

(defn ^X509TrustManager accept-all-trust-manager
  []
  (reify X509TrustManager
    (checkServerTrusted [_ chain autj-type])
    (getAcceptedIssuers [_])))

(defn ^X509Certificate server-cert
  "Obtain a docker server's X509 Certificate using docker's identity
  auth scheme.  This creates a connection to the docker server.  In
  order to obtain the server certificate, it does not verify the
  server certificate.

  You can add the returned certificate to a trust store to verify further
  connections to the server."
  [^String host ^Integer port ^KeyStore keystore ^chars keystore-pw]
  (let [key-mgr-factory (doto (KeyManagerFactory/getInstance
                               (KeyManagerFactory/getDefaultAlgorithm))
                          (.init keystore keystore-pw))
        context (doto (SSLContext/getInstance "TLS")
                  (.init (.getKeyManagers key-mgr-factory)
                         (into-array X509TrustManager
                                     [(accept-all-trust-manager)])
                         (SecureRandom.)))
        socket-factory (.getSocketFactory context)
        addr (InetAddress/getByName host)
        ^SSLSocket socket (.createSocket socket-factory addr port)
        server-cert (first (.getPeerCertificates (.getSession socket)))]
    (.close socket)
    server-cert))

(defn authenticate
  "Authenticate the server, adding the server's certificate into the keystore."
  ;; NOTE: this does not use known-hosts.json, and always accepts the
  ;; server cert. FIXME
  [host port ks-path keystore ^chars keystore-pw]
  (let [cert (server-cert host port keystore keystore-pw)]
    (add-cert ks-path keystore cert keystore-pw)))
