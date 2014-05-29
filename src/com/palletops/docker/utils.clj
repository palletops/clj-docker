(ns com.palletops.docker.utils
  (:require
   [clojure.java.io :refer [copy file]])
  (:import
   (java.io File FileInputStream FileOutputStream InputStream OutputStream)
   (java.security MessageDigest DigestOutputStream)
   (org.apache.commons.compress.archivers.zip ZipArchiveEntry ZipFile)
   (org.apache.commons.compress.archivers.tar
    TarArchiveEntry TarArchiveInputStream TarArchiveOutputStream)))

(defn- tar-entries
  "Return a lazy-seq of tarfile entries."
  [^TarArchiveInputStream s]
  (when-let [e (.getNextTarEntry s)]
    (cons e (lazy-seq (tar-entries s)))))

(defn- extract-entry
  [^TarArchiveInputStream tar-stream ^TarArchiveEntry entry target]
  (when entry
    (when-not (.isDirectory entry)
      (let [entry-name (.getName entry)
            file (file target)
            path (.getPath file)]
        (.mkdirs (.getParentFile file))
        (when (.exists file)
          (.setWritable file true))
        (with-open [out (FileOutputStream. path)]
          (copy tar-stream out))
        (let [mode (.getMode entry)
              bxor (fn [x m] (pos? (bit-xor x m)))
              band (fn [x m] (pos? (bit-and x m)))]
          (.setReadable file (band mode 400) (bxor mode 004))
          (.setWritable file (band mode 200) (bxor mode 002))
          (.setExecutable file (band mode 100) (bxor mode 001)))))))

(defn untar-stream
  "Untar from input stream to target directory."
  [target ^InputStream input-stream strip-components]
  (with-open [tar-stream (TarArchiveInputStream. input-stream)]
    (doseq [^TarArchiveEntry entry (tar-entries tar-stream)]
      (extract-entry tar-stream entry (file target (.getName entry))))))

(defn untar-stream-file
  "Untar from input stream to target directory."
  [target ^InputStream input-stream]
  (with-open [tar-stream (TarArchiveInputStream. input-stream)]
    (let [^TarArchiveEntry entry (first (tar-entries tar-stream))]
      (extract-entry tar-stream entry target))))
