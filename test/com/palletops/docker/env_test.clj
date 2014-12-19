(ns com.palletops.docker.env-test
  (:require [com.palletops.docker.env :refer :all]
            [clojure.test :refer :all]))

(deftest connection-options-test
  (is (= {:unix "unix:/var/run/docker.sock"}
         (connection-options {})))
  (is (= {:unix "unix:/some/path"}
         (connection-options {:host "unix:/some/path" :auth "identity"})))
  (is (= {:url "http://localhost/some/path"
          :auth :none
          :tls false
          :verify false}
         (connection-options
          {:host "tcp://localhost/some/path" :auth "none"})))
  (is (= {:url "https://localhost/some/path"
          :auth :identity
          :tls true
          :verify true}
         (connection-options
          {:host "tcp://localhost/some/path" :auth "identity"})))
  (is (= {:url "https://localhost/some/path"
          :auth :cert
          :tls true
          :verify true}
         (connection-options
          {:host "tcp://localhost/some/path" :tls "1" :tls-verify "1"})))
  (is (= {:url "https://localhost/some/path"
          :auth :cert
          :tls true
          :verify false}
         (connection-options
          {:host "tcp://localhost/some/path" :tls "1" :tls-verify "0"}))))
