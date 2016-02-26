(ns leiningen.buster
  (:require [cheshire.core :as chesh]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [leiningen.buster.md5 :as md5]
            [leiningen.core.main :as lmain]
            [leiningen.compile :as lcompile]
            [robert.hooke :as hooke]))

(defn- name-parts
  [file]
  (-> file .getName (string/split #"\.")))

(defn extension
  "Return only the extension part of the java.io.File instance."
  [file]
  (-> file name-parts last))

(defn basename
  "Return the name part of the java.io.File instance without extension."
  [file]
  (->> file name-parts butlast (string/join ".")))

(defn digest
  "Return an MD5 digest for the java.io.File instance."
  [file]
  ;; We're only taking the first 10 characters of the MD5 here to be in sync
  ;; with how rev-gulp manages its file revisions... it's up for debate as to
  ;; whether this is correct or not and probably doesn't matter a whole lot
  ;; whether it's long or short.
  (apply str (take 10 (md5/md5-file file))))

(defn fingerprinted-file
  "File handle for a fingerprinted version of the provided file."
  [file]
  (io/file (.getParent file)
           (str (basename file)
                "-" (digest file) "."
                (extension file))))

(defn asset-name
  "Return the just the file name for the figerprinted version of the file."
  [file]
  (-> file fingerprinted-file .getName))

(defn write-fingerprinted-file
  "Copies the provided file to its fingerprinted equivalent path"
  [file]
  (lmain/info (format "[buster]: writing %s." (asset-name file)))
  (->> file fingerprinted-file (io/copy file)))

(defn- manifest-map
  "Return existing manifest map or a new hash-map."
  [manifest]
  (if (.exists manifest)
    (chesh/parse-string (slurp manifest))
    {}))

(defn update-manifest
  "Updates or creates manifest file at location with a mapping of the file to its fingerprinted equivalent.
  The updating portion of this is to work in harmony with external tools, such as `gulp-rev', in additon
  to the fact that this function is called iteratively on each file."
  [manifest file]
  (let [mappings (assoc (manifest-map manifest)
                        (.getName file) (asset-name file))]
    (spit manifest (chesh/generate-string mappings))))

(defn bust-paths!
  "Create fingerprinted files for provided resource paths suitable for use in browser cache-busting.
  e.g. `(bust-paths! \"resources/rev-manifest.json\" [\"resources/public/foo.css\"])'
  would create a \"resources/foo-acbd18db4c.css\" file."
  [manifest files]
  (doseq [file files]
    (if (.exists file)
      (doto file write-fingerprinted-file ((partial update-manifest manifest)))
      (lmain/warn (format "[buster]: %s is not a valid resource." file)))))

(defn buster
  "Run buster on a project. This doubles as the standalone task entrypoint and the entrypoint for the compile hook."
  [{:keys [buster] :as project}]
  (if (and (seq (:files buster)) (:manifest buster))
    (let [project-path (partial io/file (:root project))]
      (bust-paths! (project-path (:manifest buster)) (map project-path (distinct (:files buster)))))
    (doseq [config-key [:files :manifest]]
      (when (empty? (get buster config-key))
        (lmain/warn (format "[buster]: Missing required configuration: %s." config-key))))))

(defn- compile-hook
  "Runs buster after compilation completes."
  [f project & args]
  (apply f project args)
  (buster project))

(defn activate
  "lein calls this to register any hooks we specify."
  []
  (hooke/add-hook #'lcompile/compile #'compile-hook))
