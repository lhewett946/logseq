(ns export-graph
  "A script that exports a graph to a sqlite.build EDN file"
  (:require ["fs" :as fs]
            ["os" :as os]
            ["path" :as node-path]
            [babashka.cli :as cli]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as string]
            [logseq.db.common.sqlite-cli :as sqlite-cli]
            [logseq.db.sqlite.export :as sqlite-export]
            [nbb.core :as nbb]))

(defn- resolve-path
  "If relative path, resolve with $ORIGINAL_PWD"
  [path]
  (if (node-path/isAbsolute path)
    path
    (node-path/join (or js/process.env.ORIGINAL_PWD ".") path)))

(defn- get-dir-and-db-name
  "Gets dir and db name for use with open-db! Works for relative and absolute paths and
   defaults to ~/logseq/graphs/ when no '/' present in name"
  [graph-dir]
  (if (string/includes? graph-dir "/")
    (let [resolve-path' #(if (node-path/isAbsolute %) %
                             ;; $ORIGINAL_PWD used by bb tasks to correct current dir
                             (node-path/join (or js/process.env.ORIGINAL_PWD ".") %))]
      ((juxt node-path/dirname node-path/basename) (resolve-path' graph-dir)))
    [(node-path/join (os/homedir) "logseq" "graphs") graph-dir]))

(def spec
  "Options spec"
  {:help {:alias :h
          :desc "Print help"}
   :include-timestamps? {:alias :t
                         :desc "Include timestamps in export"}
   :file {:alias :f
          :desc "Saves edn to file"}
   :catch-validation-errors? {:alias :c
                              :desc "Catch validation errors for dev"}
   :exclude-namespaces {:alias :e
                        :coerce #{}
                        :desc "Namespaces to exclude from properties and classes"}
   :exclude-built-in-pages? {:alias :b
                             :desc "Exclude built-in pages"}
   :exclude-files? {:alias :F
                    :desc "Exclude :file/path files"}})

(defn -main [args]
  (let [{options :opts args' :args} (cli/parse-args args {:spec spec})
        graph-dir (first args')
        _ (when (or (nil? graph-dir) (:help options))
            (println (str "Usage: $0 GRAPH-NAME [& ARGS] [OPTIONS]\nOptions:\n"
                          (cli/format-opts {:spec spec})))
            (js/process.exit 1))
        [dir db-name] (get-dir-and-db-name graph-dir)
        conn (sqlite-cli/open-db! dir db-name)
        export-options (dissoc options :file)
        export-map (sqlite-export/build-export @conn {:export-type :graph :graph-options export-options})]
    (if (:file options)
      (do
        (println "Exported" (count (:properties export-map)) "properties,"
                 (count (:properties export-map)) "classes and"
                 (count (:pages-and-blocks export-map)) "pages")
        (fs/writeFileSync (resolve-path (:file options))
                          (with-out-str (pprint/pprint export-map))))
      (pprint/pprint export-map))))

(when (= nbb/*file* (nbb/invoked-file))
  (-main *command-line-args*))
