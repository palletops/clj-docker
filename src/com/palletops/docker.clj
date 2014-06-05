(ns com.palletops.docker
  "A wrapper for the docker API."
  (:require
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.java.io :refer [copy file reader]]
   [clojure.string :as string :refer [blank? lower-case split]]
   [com.palletops.api-builder.api
    :refer [defn-api defmulti-api defmethod-api def-api]]
   [schema.core :as schema :refer [eq explain optional-key]])
  (:import
   org.apache.commons.codec.binary.Base64
   [java.net InetSocketAddress Socket URL]
   [java.io InputStream]))

;;; # Helpers
(defn api-call
  "Call the docker api via http."
  [{:keys [url] :as endpoint} path request]
  (http/request
   (merge
    {:url (str url path)
     :as :auto}
    request)))

(defn base64-json-header
  "Convert a map entry into a base64 json."
  [[k v]]
  [(name k) (Base64/encodeBase64 (.getBytes (json/generate-string v) "UTF-8"))])

;;; # Result stream parsing
(def resp-streams [:stdin :stdout :stderr])
(def ^int ^:const resp-buf-size 1024)
(def resp-buf (byte-array resp-buf-size))
(def ^java.nio.ByteBuffer hdr-bb (doto (java.nio.ByteBuffer/wrap resp-buf)
                                   (.order java.nio.ByteOrder/BIG_ENDIAN)))

(defn read-record
  [^InputStream dis]
  (println "read-record")
  (let [n (.read dis resp-buf 0 8)]
    (println "read-record n" n)
    (when (pos? n)
      (assert (= n 8))
      (.rewind hdr-bb)
      (.getInt hdr-bb)                  ; skip 4 bytes
      (let [t (int (aget resp-buf 0))
            n (.getInt hdr-bb)
            s (loop [r ""
                     n n]
                (if (pos? n)
                  (let [n2 (.read dis resp-buf 0 (min n resp-buf-size))
                        r (str r (String. resp-buf 0 n2))]
                    (if (pos? n2)
                      (recur r (- n n2))
                      r))
                  r))]
        [(resp-streams t) s]))))

(defn read-stream-records
  "Read stream record from is until it is closed, or break-fn called with the
  stream type keyword and stream content returns true.  break-fn is called with
  :entry before anything is read."
  ([is break-fn filter-fn]
     (println "Filter-fn" filter-fn)
     (with-open [is is]
       (if-not (break-fn :entry nil)
         (loop [res {}]
           (if-let [[k s] (read-record is)]
             (let [res (update-in res [k] str (filter-fn s))]
               (if (break-fn k s)
                 res
                 (recur res)))
             res)))))
  ([is break-fn]
     (read-stream-records is break-fn identity)))

;;; # API data map

(def doc-url-prefix
  "Prefix for documentation urls."
  "http://docs.docker.io/reference/api/docker_remote_api_v1.11/#")

(def ContainerId String)

(def api
  {:containers {:query-params {:all schema/Bool
                               :limit schema/Int
                               :since ContainerId
                               :before ContainerId
                               :size schema/Bool}
                :path {:fmt "/containers/json" :arg-order [] :args {}}
                :method :get
                :return {"Id" ContainerId
                         "Image" String
                         "Command" String
                         "Created" schema/Int
                         "Status" String
                         "Ports" [schema/Int]
                         (optional-key "SizeRw") schema/Int
                         (optional-key "SizeRootFs") schema/Int}
                :doc-url "list-containers"
                :doc "List containers."}
   :container-create {:path {:fmt "/containers/create"}
                      :method :post
                      :query-params {:name String}
                      :json-body {:Hostname String
                                  :User String
                                  :Memory schema/Int
                                  :MemorySwap schema/Int
                                  :AttachStdin schema/Bool
                                  :AttachStdout schema/Bool
                                  :AttachStderr schema/Bool
                                  :PortSpecs {String schema/Any}
                                  :Tty schema/Bool
                                  :OpenStdin schema/Bool
                                  :StdinOnce schema/Bool
                                  :Env {String String}
                                  :Cmd (schema/either String [String])
                                  :Dns String
                                  :Image String
                                  :Volumes {String schema/Any}
                                  :VolumesFrom String
                                  :WorkingDir String
                                  :DisableNetwork schema/Bool
                                  :ExposedPorts {String schema/Any}}
                      :doc-url "create-a-container"
                      :doc "Create a container"}
   :container {:path {:fmt "/containers/%s/json"
                      :args {:id ContainerId}
                      :arg-order [:id]}
               :method :get
               :doc-url "inspect-a-container"
               :doc "Return low-level information on the container id"}
   :container-processes {:path {:fmt "/containers/%s/top"
                                :args {:id ContainerId}
                                :arg-order [:id]}
                         :method :get
                         :query-params {:ps_args String}
                         :doc-url "list-processes-running-inside-a-container"
                         :doc "List processes running inside the container id"}
   :container-logs {:path {:fmt "/containers/%s/logs"
                           :args {:id ContainerId}
                           :arg-order [:id]}
                    :method :get
                    :headers {:accept "application/vnd.docker.raw-stream"}
                    :query-params {:follow schema/Bool
                                   :stdout schema/Bool
                                   :stderr schema/Bool
                                   :timestamps schema/Bool}
                    :doc-url "get-container-logs"
                    :doc "Get stdout and stderr logs from the container id"}
   :container-changes {:path {:fmt "/containers/%s/changes"
                              :args {:id ContainerId}
                              :arg-order [:id]}
                       :method :get
                       :return [{:Kind schema/Int
                                 :Path String}]
                       :doc-url "inspect-changes-on-a-containers-filesystem"
                       :doc "Inspect changes on container id's filesystem."}
   :container-export {:path {:fmt "/containers/%s/export"
                             :args {:id ContainerId}
                             :arg-order [:id]}
                      :method :get
                      :doc-url "export-a-container"
                      :doc "Export the contents of container id"}
   :container-start {:path {:fmt "/containers/%s/start"
                            :args {:id ContainerId}
                            :arg-order [:id]}
                     :method :post
                     :json-body {:Binds [String]
                                 :LxcConf {String schema/Any}
                                 :PortBindings {String schema/Any}
                                 :PublishAllPorts schema/Bool
                                 :Privileged schema/Bool}
                     :doc-url "start-a-container"
                     :doc "Start the container id."}
   :container-stop {:path {:fmt "/containers/%s/stop"
                           :args {:id ContainerId}
                           :arg-order [:id]}
                    :method :post
                    :query-params {:t schema/Int}
                    :doc-url "stop-a-container"
                    :doc "Stop the container id."}
   :container-restart {:path {:fmt "/containers/%s/restart"
                              :args {:id ContainerId}
                              :arg-order [:id]}
                       :method :post
                       :query-params {:t schema/Int}
                       :doc-url "restart-a-container"
                       :doc "Restart the container id"}
   :container-kill {:path {:fmt "/containers/%s/kill" :args {:id ContainerId}
                           :arg-order [:id]}
                    :method :post
                    :query-params {:signal (schema/either String schema/Int)}
                    :doc-url "kill-a-container"
                    :doc "Kill the container id"}
   :container-wait
   {:path {:fmt "/containers/%s/wait" :args {:id ContainerId}
           :arg-order [:id]}
    :method :post
    :doc-url "wait-a-container"
    :doc "Block until container id stops, then returns the exit code."}
   :container-delete {:path {:fmt "/containers/%s" :args {:id ContainerId}
                             :arg-order [:id]}
                      :method :delete
                      :query-params {:force schema/Bool
                                     :v schema/Bool}
                      :doc-url "remove-a-container"
                      :doc "Remove the container id from the filesystem."}
   :container-copy {:path {:fmt "/containers/%s/copy" :args {:id ContainerId}
                           :arg-order [:id]}
                    :method :post
                    :headers {:accept "application/x-tar"}
                    :json-body {:Resource String}
                    :doc-url "copy-files-or-folders-from-a-container"
                    :doc "Copy files or folders of container id."}

   :images {:path {:fmt "/images/json"}
            :method :get
            :doc-url "list-images"
            :doc "List images."}
   :image-create
   {:path {:fmt "/images/%s/create" :args {:name String}
           :arg-order [:name]}
    :method :post
    :base64-json-headers {:X-Registry-Auth schema/Any}
    :query-params {:repo String
                   :tag String
                   :registry String
                   :fromSource String}
    :doc-url "create-an-image"
    :doc
    "Create an image, either by pull it from the registry or by importing it."}
   :image-insert {:path {:fmt "/images/%s/insert" :args {:name String}
                         :arg-order [:name]}
                  :method :post
                  :query-params {:path String
                                 :url String}
                  :doc-url "insert-a-file-in-an-image"
                  :doc "Insert a file from url in the image name at path."}
   :image {:path {:fmt "/images/%s/json" :args {:name String}
                  :arg-order [:name]}
           :method :get
           :doc-url "inspect-an-image"
           :doc "Return low-level information on the image name."}
   :image-history {:path {:fmt "/images/%s/history" :args {:name String}
                          :arg-order [:name]}
                   :method :get
                   :doc-url "get-the-history-of-an-image"
                   :doc "Return the history of the image name"}
   :image-push {:path {:fmt "/images/%s/push" :args {:name String}
                       :arg-order [:name]}
                :method :post
                :base64-json-headers {:X-Registry-Auth schema/Any}
                :query-params {:registry String}
                :doc-url "push-an-image-on-the-registry"
                :doc "Push the image name on the registry."}
   :image-tag {:path {:fmt "/images/%s/tag" :args {:name String}
                      :arg-order [:name]}
               :method :post
               :query-params {:force schema/Bool
                              :repo String}
               :doc-url "tag-an-image-into-a-repository"
               :doc "Tag the image name into a repository."}
   :image-delete {:path {:fmt "/images/%s" :args {:name String}
                         :arg-order [:name]}
                  :method :delete
                  :query-params {:force schema/Bool
                                 :noprune schema/Bool}
                  :doc-url "remove-an-image"
                  :doc "Remove the image name from the filesystem."}

   :build {:path {:fmt "/build"}
           :method :post
           :body java.io.InputStream
           :headers {:content-type "application/tar"}
           :base64-json-headers {:X-Registry-Config schema/Any}
           :query-params {:t String
                          :q schema/Bool
                          :nocache schema/Bool}
           :return {}
           :doc-url "build-an-image-from-dockerfile-via-stdin"
           :doc "Build an image from Dockerfile via stdin"}
   :auth {:path {:fmt "/auth"}
          :method :post
          :json-body {:username String
                      :password String
                      :email String
                      :server String}
          :return {}
          :doc-url "check-auth-configuration"
          :doc "Set the default username and email."}
   :info {:path {:fmt "/info"}
          :method :get
          :return {}
          :doc-url "display-system-wide-information"
          :doc "Display system-wide information"}
   :version {:path {:fmt "/version"}
             :method :get
             :return {}
             :doc-url "show-the-docker-version-information"
             :doc "Show the docker version information."}
   :ping {:path {:fmt "/_ping"}
          :method :get
          :return {}
          :doc-url "ping-the-docker-server"
          :doc "Ping the docker server."}
   :commit
   {:path {:fmt "/commit"}
    :query-params {:container ContainerId
                   :repo String
                   :tag String
                   :m String
                   :author String}
    :json-body {:Hostname String
                :User String
                :Memory schema/Int
                :MemorySwap schema/Int
                :AttachStdin schema/Bool
                :AttachStdout schema/Bool
                :AttachStderr schema/Bool
                :PortSpecs schema/Any
                :Tty schema/Bool
                :OpenStdin schema/Bool
                :StdinOnce schema/Bool
                :Env {String String}
                :Cmd (schema/either String [String])
                :Volumes {String schema/Any}
                :WorkingDir String
                :DisableNetwork schema/Bool
                :ExposedPorts {String schema/Any}}
    :doc-url "create-a-new-image-from-a-containers-changes"
    :doc "Create a new image from a container's changes."}
   :events
   {:path {:fmt "/events"}
    :query-params {:since ContainerId
                   :until ContainerId}
    :doc-url "monitor-dockers-events"
    :doc "Get events from docker, either in real time via streaming, or via polling (using since)"}
   :repo-images
   {:path {:fmt "/images/%s/get"
           :args {:name String}
           :arg-order [:name]}
    :doc-url "get-a-tarball-containing-all-images-and-tags-in-a-repository"
    :doc "Get a tarball containing all images and metadata for the repository specified by name."}
   :load-images
   {:path {:fmt "/images/load"}
    :body (schema/either String java.io.InputStream)
    :doc-url "load-a-tarball-with-a-set-of-images-and-tags-into-docker"
    :doc "Load a set of images and tags into the docker repository."}})

;;; ## Data based interface

;;; Functions are named after the api :command, with a -map suffix.

;;; Define functions to return request maps or some interim data
;;; definition of the command?


;;; Does a command require special handling of results? Do commands
;;; require some sequence of api requests?


(defn api-map-args
  "Define a schema for a map argument."
  [command]
  (let [{:keys [query-params path method json-body headers return]}
        (command api)]
    (merge
     (zipmap
      (map #(optional-key %) (keys query-params))
      (vals query-params))
     (:args path) json-body)))

(defn api-map-return
  "Define a schema for a map return arguments."
  [command]
  (let [{:keys [query-params path method json-body headers return]}
        (command api)]
    (merge
     (zipmap
      (map #(optional-key %) (keys query-params))
      (vals query-params))
     (:args path)
     json-body
     {:command (eq command)})))


(defn api-map
  "Given an api call definition map, generate a function that will
  return the map."
  [command {:keys [query-params path method json-body headers return]
            :as api-def}]
  (let [args (merge query-params (:args path) json-body)]
    `(defn-api ~(symbol (str (name command) "-map"))
       {:sig [[(api-map-args ~command)
               :- (api-map-return ~command)]]}
       [{:keys [~@(map #(symbol (name %)) (keys args))] :as args#}]
       (assoc args# :command ~command))))

(defmacro api-maps []
  `(do
     ~@(for [[command api-def] api]
         (api-map command api-def))))

(api-maps)

;; (containers-map {:all true})

;;; # Requests

;;; Functions to build http request maps for sending to the api.

(defmulti-api api-req
  "Given an api command keyword and a map of arguments, return an http
  request map.  The request has a :path element specifying the http
  path."
  {:sig [[schema/Keyword {schema/Any schema/Any} :- {schema/Any schema/Any}]]}
  (fn [command args] command))

(defn api-req-method
  "Given an api call definition map, generate a function that will
  return an http request map."
  [command {:keys [query-params path method body json-body headers return
                   base64-json-headers doc doc-url]
            :or {method :get}
            :as api-def}]
  (let [args (merge query-params (:args path) json-body headers
                    (select-keys api-def [:body]))
        argmap (gensym "argmap")]
    `(defmethod-api api-req ~command
       [_# {:keys [~@(map #(symbol (name %)) (keys args))] :as ~argmap}]
       {:method ~method
        :path (format ~(:fmt path) ~@(map (comp symbol name) (:arg-order path)))
        :query-params (select-keys ~argmap ~(vec (keys query-params)))
        :headers (into {}
                       (concat
                        (select-keys ~argmap ~(vec (keys headers)))
                        (map
                         base64-json-header
                         (select-keys ~argmap
                                      ~(vec (keys base64-json-headers))))))
        ~@(when json-body
            [:content-type :json
             :body `(json/generate-string
                     (select-keys ~argmap ~(vec (keys json-body))))])
        ~@(if body
            `[:body ~'body])
        ~@(if (let [accept (:accept headers)]
                (and (string? accept)
                     (or (.contains accept "application/vnd.docker.raw-stream")
                         (.contains accept "application/x-tar"))))
            [:as :stream])
        ~@[]})))

(defmacro api-reqs []
  `(do
     ~@(for [[command api-def] api]
         (api-req-method command api-def))))

(api-reqs)

;;; # Direct api calls

;;; Functions are named after the api :command.

(defn api-call-fn
  "Given an api call definition map, generate a function that will
  call the api function."
  [command {:keys [query-params path method json-body headers return
                   base64-json-headers doc doc-url]
            :or {method :get}
            :as api-def}]
  (let [args (merge query-params (:args path) json-body headers)]
    `(defn-api ~(symbol (name command))
       {:sig [[String
               (api-map-args ~command)
               :- (api-map-return ~command)]]
        :doc ~(str doc \newline \newline "See " doc-url-prefix doc-url)}
       [endpoint# {:keys [~@(map (comp symbol name) (keys args))] :as args#}]
       (let [req# (api-req ~command args#)]
         (api-call endpoint# (:path req#) (dissoc req# :path))))))

(defmacro api-calls []
  `(do
     ~@(for [[command api-def] api]
         (api-call-fn command api-def))))

(api-calls)


;;; # Map based calls to Docker API

(defmulti-api docker
  "Call the docker API based on a map specifying the command to be called,
  and and arguments."
  {:sig [[{:url String} {schema/Any schema/Any} :- {schema/Any schema/Any}]]}
  (fn [{:keys [url] :as endpoint}
       {:keys [command] :as request}]
    {:pre [(keyword? command)]}
    command))


;;; The default implementation is suitable for most endpoints, but
;;; some commands require extra processing.
(defmethod-api docker :default
  [endpoint {:keys [command all limit since before size] :as request}]
  (let [req (api-req command (dissoc request :command))]
    (api-call endpoint (:path req) (dissoc req :path))))

;;; ## Containers

(defmethod-api docker :container-logs
  [endpoint {:keys [command id follow stdout stderr timestamps] :as request}]
  {:pre [id (string? id)]}
  (let [req (api-req :container-logs (dissoc request :command))
        resp (api-call endpoint (:path req) (dissoc req :path))]
    (if follow                          ; if follow set, just return the stream
      ;; maybe this should return a lazy seq instead?
      resp
      (let [res (read-stream-records (:body resp) (constantly false))]
        (assoc resp :body res)))))

(defn- parse-hdr
  "Parse a header into a keyword value pair."
  [s]
  (let [[h v] (split s #": " 2)]
    [(keyword (lower-case h)) v]))

(defn- attach
  "Call the attach endpoint.  We have to use a plain socket to be able
  to handle the connection hijacking that docker does."
  [url-str]
  (let [url (URL. url-str)
        ^InetSocketAddress address (InetSocketAddress.
                                    (.getHost url) (.getPort url))
        socket (doto (Socket.)
                 (.connect address (int 10000)))
        is (.getInputStream socket)
        os (.getOutputStream socket)
        br (java.io.BufferedReader. (java.io.InputStreamReader. is))
        req (format
             "POST %s?%s HTTP/1.1\r\nHost: %s\r\n\r\n"
             (.getPath url)
             (.getQuery url)
             (.getHost url))]
    (.write os (.getBytes req "UTF-8"))
    (.flush os)

    (let [status (.readLine br)
          hdrs (loop [hdrs []]
                 (let [h (.readLine br)]
                   (if (blank? h)
                     hdrs
                     (recur (conj hdrs h)))))
          hdrs (->> hdrs
                    (map parse-hdr)
                    (into {}))
          n (:content-length hdrs)]
      (when-let [n (and n (Integer/parseInt n))]
        (try
          (.read is resp-buf 0 n)
          (catch Exception e
            (clojure.stacktrace/print-cause-trace e))))
      {:status status
       :headers hdrs
       :input os
       :body is})))

;; Attach to a container's stdin, stdout and stderr.

;; When passed :stream for `result-as`, returns a stream in the
;; body, otherwise it decodes the output stream into a map with
;; :stdout and :stderr keys.

;; TODO add a :lazy-seq option for return-as?

;; The general problem here is knowing when to detach.  The api will
;; close the output stream only when (and as soon as) the input stream
;; is closed.
(defmethod-api docker :container-attach
  [endpoint
   {:keys [command id result-as body body-stream logs stream
           stdin stdout stderr break-fn filter-fn]
    :or {break-fn (constantly false)
         stream (not logs)
         result-as :map}
    :as request}]
  {:pre [id (string? id)]}
  (let [resp (attach (format "%s%s?%s"
                             (:url endpoint)
                             (format "/containers/%s/attach" id)
                             (http/generate-query-string
                              (dissoc request
                                      :command :id :result-as :filter-fn))))]
    (when (string? body)
      (.write (:input resp) (.getBytes body "UTF-8"))
      (.flush (:input resp))
      (println "wrote" (pr-str body)))
    (when body-stream
      (println "copying body-stream")
      (copy body-stream (:input resp))
      (.flush (:input resp)))
    (println "result-as" result-as)
    (case result-as
      :stream resp
      :map (let [res (read-stream-records (:body resp) break-fn filter-fn)]
             (assoc resp :body res)))))

(defmethod-api docker :container-shell
  ;; "Attach to a shell running in a docker container, and execute
  ;; commands in a subshell."
  [endpoint
   {:keys [id break-fn commands filter-fn]
    :or {filter-fn identity}
    :as request}]
  (println "container-shell filter-fn" filter-fn)
  (let [eoc (gensym "EXIT")
        eof (gensym "EOF")
        break-fn (or break-fn
                     (fn [k v] (and (string? v) (.contains v (name eoc)))))
        body (format "
cat << '%s' > cmd$$
%s
%s
/bin/bash cmd$$
echo %s $?
"
                     eof commands eof eoc)]
    (println "body" body)
    (let [resp (docker endpoint
                       {:command :container-attach
                        :id id
                        :body body
                        :break-fn break-fn
                        :filter-fn filter-fn
                        :stream true
                        :stdout true
                        :stderr true
                        :stdin true})
          resp (assoc (update-in resp [:body] dissoc :stdout :stderr)
                 :out (:stdout (:body resp))
                 :err (:stderr (:body resp)))]
      (println "resp" (pr-str resp))
      (if-let [[st e] (if-let [out (:out resp)]
                        (re-find (re-pattern (str eoc " " "([0-9]+)")) out))]
        (-> resp
            (assoc :exit (Integer/parseInt e))
            (update-in [:out] string/replace st ""))
        resp))))

;; send a file to the container
(defmethod-api docker :container-file
  [endpoint
   {:keys [id break-fn path local-file content]
    :or {break-fn (constantly true)}
    :as request}]
  (let [body (format "cat > %s\n" path)
        body-stream (if local-file
                      (reader (file local-file))
                      content)]
    (println "body" body)
    (docker endpoint
            {:command :container-attach
             :id id
             :body body
             :body-stream body-stream
             :break-fn break-fn
             :stream true
             :stdout true
             :stderr true
             :stdin true})
    (docker endpoint
            {:command :container-kill
             :id id})
    (docker endpoint
            {:command :container-start
             :id id})))

;; receive a file from the container
(defmethod-api docker :container-file
  [endpoint
   {:keys [id break-fn path local-file content]
    :or {break-fn (constantly true)}
    :as request}]
  (let [body (format "cat > %s\n" path)
        body-stream (if local-file
                      (reader (file local-file))
                      content)]
    (println "body" body)
    (docker endpoint
            {:command :container-attach
             :id id
             :body body
             :body-stream body-stream
             :break-fn break-fn
             :stream true
             :stdout true
             :stderr true
             :stdin true})
    (docker endpoint
            {:command :container-kill
             :id id})
    (docker endpoint
            {:command :container-start
             :id id})))
