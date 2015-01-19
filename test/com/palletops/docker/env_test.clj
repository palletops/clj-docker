(ns com.palletops.docker.env-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer :all]
   [com.palletops.docker.env :refer :all]
   [cheshire.core :refer [generate-string]]))

(deftest connection-options-test
  (is (= {:unix "unix:/var/run/docker.sock"}
         (connection-options {})))
  (is (= {:unix "unix:/some/path"}
         (connection-options {:host "unix:/some/path" :auth "identity"})))
  (is (= {:url "http://localhost/some/path"
          :auth :none
          :tls false
          :verify false
          :keystore nil,
          :keystore-path nil,
          :keystore-pass ""}
         (connection-options
          {:host "tcp://localhost/some/path" :auth "none"})))
  (let [home (System/getProperty "user.home")
        dir (io/file (System/getProperty "user.dir") ".clj-docker")
        ddir (io/file dir ".docker")]
    (System/setProperty "user.home" (str dir))
    (.mkdirs ddir)
    (spit (io/file ddir "key.json")
          (generate-string
           {:crv "P-256",
            :d "lEtpJq8DE2szWNQ0NlrFlI8YDkVt_FAngDnU2mpOK6U",
            :kid "STLE:NGLI:BTVG:DMVF:KX3A:MFGB:BTW4:5UHD:2GAG:JQZR:KUTQ:6D6C",
            :kty "EC",
            :x "E1hPozuH_PYEaqHm9sOf0kPmDGUd5cZGTYQBgE9v5lw",
            :y "bgXG856B657yqpq7r2j-WDn-zIstxr25uX8Uw8-hb2U"}))
    (spit (io/file ddir "public-key.json")
          (generate-string
           {:crv "P-256",
            :kid "STLE:NGLI:BTVG:DMVF:KX3A:MFGB:BTW4:5UHD:2GAG:JQZR:KUTQ:6D6C",
            :kty "EC",
            :x "E1hPozuH_PYEaqHm9sOf0kPmDGUd5cZGTYQBgE9v5lw",
            :y "bgXG856B657yqpq7r2j-WDn-zIstxr25uX8Uw8-hb2U"}))
    (try
      (let [x (connection-options
               {:host "tcp://localhost/some/path" :auth "identity"})]
        (is (= {:url "https://localhost/some/path"
                :auth :identity
                :tls true
                :verify true
                :keystore-path (io/file ddir "client.ks"),
                :keystore-pass ""}
               (dissoc x :keystore)))
        (is (instance? java.security.KeyStore (:keystore x))))
      (finally
        (System/setProperty "user.home" home))))
  (is (= {:url "https://localhost/some/path"
          :auth :cert
          :tls true
          :verify true
          :keystore nil,
          :keystore-path nil,
          :keystore-pass ""}
         (connection-options
          {:host "tcp://localhost/some/path" :tls "1" :tls-verify "1"})))
  (is (= {:url "https://localhost/some/path"
          :auth :cert
          :tls true
          :verify false
          :keystore nil,
          :keystore-path nil,
          :keystore-pass ""}
         (connection-options
          {:host "tcp://localhost/some/path" :tls "1" :tls-verify "0"})))
  (is (= {:url "http://somehost:2376"
          :tls false
          :verify false
          :auth :none
          :keystore nil
          :keystore-path nil
          :keystore-pass ""}
         (connection-options
          (merge-env
           {:host "tcp://somehost:2376" :auth "none" :tls "" :tls-verify ""
            :cert-path ""}
           {})))))

(deftest merge-env-test
  (= {}
     (merge-env {} {})))
