(ns com.palletops.docker.env
  "Read docker configuration from environment or command line")

(defn env-vars
  "Read all docker related environment variables."
  []
  {:host (System/getenv "DOCKER_HOST")
   :auth (System/getenv "DOCKER_AUTH")
   :tls (System/getenv "DOCKER_TLS")
   :tls-verify (System/getenv "DOCKER_TLS_VERIFY")
   :cert-path (System/getenv "DOCKER_CERT_PATH")})

(defn connection-options
  "Return a map of connection options, given a map with values as
  returned by `env-vars', possibly from command line options.

  For a unix endpoint, the returned map contains a :unix key.

  For a http endpoint it will contain :url for the endpoint url
  and a :tls flag to specify whether TLS should be used. The :verify
  flag will be set if the server certs should be verified.
  The :cert-path key will be set to a directory containing the
  certs. The :auth key will be set to :identity, :cert or :none."

  [{:keys [^String host auth tls tls-verify cert-path]}]
  (if (and host (.startsWith host "tcp:"))
    (let [tls (boolean (or tls-verify
                           (and (not= auth "none")
                                (or auth
                                    (not= tls "no")))))
          auth (if auth (keyword auth) (if tls :cert :none))]
      {:url (str (if tls "https" "http") (subs host 3))
       :tls tls
       :verify (and (not= tls-verify "0") (not= auth :none))
       :auth auth})
    {:unix (or host "unix:/var/run/docker.sock")}))
