(ns com.palletops.docker.env
  "Read docker configuration from environment or command line"
  (:require
   [clojure.java.io :as io]
   [com.palletops.docker.keystore :refer [key-store-jwk key-store]]))

(defn default-identity-dir
  "Default dir for JWK key files for identity auth."
  []
  java.io.File
  (.getCanonicalPath
   (io/file
    (System/getProperty "user.home")
    ".docker")))

(defn env-vars
  "Read all docker related environment variables."
  []
  {:host (System/getenv "DOCKER_HOST")
   :auth (System/getenv "DOCKER_AUTH")
   :tls (System/getenv "DOCKER_TLS")
   :tls-verify (System/getenv "DOCKER_TLS_VERIFY")
   :cert-path (System/getenv "DOCKER_CERT_PATH")})

(defn merge-env
  "Merge two env specifications (eg. from env vars and from cli).
  Values from b dominate."
  [a b]
  (let [a (-> a
              (cond-> (or (:auth-ca b) (:auth-cert b) (:auth-key b))
                      (dissoc :cert-path))
              (cond-> (:auth b)
                      (dissoc :tls :tls-verify)))]
    (merge a b)))

(defn connection-options
  "Return a map of connection options, given a map with values as
  returned by `env-vars', possibly from command line options.

  For a unix endpoint, the returned map contains a :unix key.

  For a http endpoint it will contain :url for the endpoint url
  and a :tls flag to specify whether TLS should be used. The :verify
  flag will be set if the server certs should be verified.
  The :cert-path key will be set to a directory containing the
  certs. The :auth key will be set to :identity, :cert or :none."

  [{:keys [^String host auth tls tls-verify cert-path
           auth-ca auth-cert auth-key]}]
  (if (and host (.startsWith host "tcp:"))
    (let [tls (boolean (or (and (not= tls-verify "") (not= tls-verify "0"))
                           (and (not= auth "none")
                                (or auth
                                    (not= tls "no")))))
          auth (if auth (keyword auth) (if tls :cert :none))
          [ks-path ks] (cond
                        (and (= auth :cert) cert-path)
                        (key-store cert-path)

                        (and (= auth :cert) auth-ca auth-cert auth-key)
                        (key-store auth-ca auth-cert auth-key)

                        (= auth :identity)
                        (key-store-jwk (default-identity-dir)))]
      {:url (str (if tls "https" "http") (subs host 3))
       :tls tls
       :verify (and (not= tls-verify "0") (not= auth :none))
       :auth auth
       :keystore ks
       :keystore-path ks-path
       :keystore-pass ""})
    {:unix (or host "unix:/var/run/docker.sock")}))
