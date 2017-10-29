(ns leiningen.buster
  (:require [cheshire.core :as chesh]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [leiningen.buster.md5 :as md5]
            [leiningen.core.main :as lmain]
            [leiningen.compile :as lcompile]
            [robert.hooke :as hooke]))

(defn- with-replacements
  "Provide compatibility of :files given just as vector
  e.g. :files [\"resources/public/js/compiled/app.jsy\"]
  means file is references in html as \"resources/public/js/compiled/app.js\"
  and will be replaces to \"resources/public/js/compiled/app-DIGEST.js\""
  [v]
  (if (sequential? v)
    (->> v distinct (map (fn [i] [i i])))
    (vec v)))

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
  (let [asset (asset-name file)
        mappings (assoc (manifest-map manifest)
                        (.getName file) asset)]
    (spit manifest (chesh/generate-string mappings))
    asset))

(defn bust-paths!
  "Create fingerprinted files for provided resource paths suitable for use in browser cache-busting.
  e.g. `(bust-paths! \"resources/rev-manifest.json\" [\"resources/public/foo.css\" \"/css/foo.css\"])'
  would create a \"resources/foo-acbd18db4c.css\" file.
  Returns [\"/css/foo.css\" \"foo.css\" \"foo-DIGEST.css\"]"
  [manifest files]
  (map (fn [[file match]]
         (if (.exists file)
           [match (.getName file)  (update-manifest manifest (doto file write-fingerprinted-file))]
           (lmain/warn (format "[buster]: %s is not a valid resource." file))))
       files))

(defn replace-paths!
  "Replaces some static path ot js/css with a digested version
  e.g. (replace-paths! [\"resources/public/index.html\"] {\"app.js\" \"app-7383593c86.js\"})"
  [files replace-paths]
  (doseq [replace-path replace-paths]
    (loop [[head & tail] files content (slurp replace-path)]
      (if-let [[match file-name asset] head]
        (let [replacement (string/replace match file-name asset)
              old-version-file-match (string/replace file-name "." "[-\\w]*\\.")
              match-incl-older (re-pattern (string/replace match file-name old-version-file-match))]
          (lmain/info "[buster]: patching" replace-path ":" match-incl-older "=>" replacement)
          (recur tail (string/replace content match-incl-older replacement)))
        (spit replace-path content)))))

(defn buster
  "Run buster on a project. This doubles as the standalone task entrypoint and the entrypoint for the compile hook."
  [{:keys [buster] :as project}]
  (if (and (seq (:files buster)) (:manifest buster))
    (let [project-path (partial io/file (:root project))
          {:keys [files manifest replace-paths]} buster
          files (->> files
                     with-replacements
                     (map (fn [[k v]] [(project-path k) v])))]
      (replace-paths! (bust-paths! (project-path manifest) files)
                      (seq replace-paths)))
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
