(ns com.palletops.docker-test
  (:require
   [clojure.test :refer :all]
   [com.palletops.docker :refer :all]))

(deftest container-logs-test
  (is (= {:path "/containers/abc123456/logs"
          :method :get
          :headers {}
          :query-params {}
          :as :stream}
         (api-req :container-logs {:id "abc123456"}))))

(deftest container-processes-test
  (is (= {:path "/containers/abc123456/top"
          :method :get
          :headers {}
          :query-params {}}
         (api-req :container-processes {:id "abc123456"}))))
