;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.tools.deps.graph
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as jio]
    [clojure.pprint :refer [pprint]]
    [clojure.string :as str]
    [clojure.tools.cli :as cli]
    [clojure.tools.deps.alpha :as deps]
    [clojure.tools.deps.alpha.extensions :as ext]
    [clojure.tools.deps.alpha.reader :as reader]
    [clojure.tools.deps.alpha.script.parse :as parse]
    [clojure.tools.deps.alpha.script.make-classpath2 :as makecp]
    [clojure.tools.deps.alpha.util.session :as session]
    [clojure.tools.deps.alpha.util.io :as io :refer [printerrln]]
    [dorothy.core :as dot]
    [dorothy.jvm :as dotjvm]
    [clojure.string :as str])
  (:import
    [java.io IOException]
    [clojure.lang IExceptionInfo]))

(set! *warn-on-reflection* true)

(defn parse-syms
  "Parses a concatenated string of libs into a collection of symbols
  Ex: (parse-libs \"org.clojure/clojure,org.clojure/test.check\")
  Returns: [org.clojure/clojure org.clojure/test.check]"
  [s]
  (->> (str/split (or s "") #",")
    (remove str/blank?)
    (map symbol)))


(def ^:private opts
  [;; input
   ["-d" "--deps DEPSFILE" "deps.edn file to read, default ./deps.edn" :default "deps.edn"]
   ;; trace mode
   ["-t" "--trace" "Trace mode, output one image per trace step"]
   ["-f" "--tracefile TRACEFILE" "Read trace directly from file, output one image per trace step"]
   ;; options
   ["-h" "--help"]
   ["-o" "--output-file FILE" "Basename of output-file without extension"
    :default "deps"]
   [nil "--output-format OUTPUT_FORMAT" "Either png, pdf, svg, or viewer"
    :default :viewer
    :parse-fn keyword
    :validate [#(#{:png, :pdf, :svg, :viewer} %) "Must be either png, pdf, svg, or viewer"]]
   ["-a" "--aliases ALIASES" "Concatenated alias names to enable" :parse-fn parse/parse-kws]
   [nil "--trace-omit LIBS" "Comma delimited list of libs to omit in trace imgs"
    :default '[org.clojure/clojure]
    :parse-fn parse-syms]
   [nil "--size" "Include sizes in dep graph nodes"]])

(defn parse-opts
  "Parse the command line opts to make-classpath"
  [args]
  (cli/parse-opts args opts))

(defn node-id
  [lib]
  (if (= (ns lib) (name lib))
    (keyword (ns lib))
    (keyword lib)))

(defn make-node
  [id rows style-attrs]
  [id (merge {:shape :record
              :label (str/join "|" rows)
              :style :filled
              :color :black
              :fillcolor :lightgrey}
        style-attrs)])

(defn get-size-path
  [path]
  (let [f (jio/file path)]
    (if (.exists f)
      (if (.isFile f)
        (.length f)
        0) ;; TODO: sum dir size?
      0)))

(defn get-size
  [lib coord config]
  (let [{:deps/keys [manifest]} (ext/manifest-type lib coord config)
        paths (ext/coord-paths lib coord manifest config)]
    (->> paths (map get-size-path) (reduce +))))

(defn make-dep-node
  [lib coord config opts style-attrs]
  (let [id (node-id lib)
        summary (ext/coord-summary lib coord)
        space (str/index-of summary " ")
        rows [(subs summary 0 space)
              (subs summary (inc space))]
        rows (if (:size opts)
               (let [size (get-size lib coord config)]
                 (if (pos? size)
                   (conj rows (format "%10.1f kb" (/ size 1024.0)))
                   rows))
               rows)]
    (make-node id rows style-attrs)))

(defn make-edges
  [lib {:keys [dependents] :as coord}]
  (if (seq dependents)
    (map (fn [dlib] [(node-id dlib) (node-id lib)]) dependents)
    [[:root (node-id lib)]]))

(defn make-graph
  [lib-map config {output :output-file output-format :output-format :as opts}]
  (println output-format)
  (let [statements (into [(make-node :root ["deps.edn"] {:shape :box :fillcolor :cadetblue1}) ]
                     (mapcat
                       (fn [[lib coord]]
                         (into [(make-dep-node lib coord config opts nil)]
                           (make-edges lib coord)))
                       lib-map))]
    ;(clojure.pprint/pprint statements)
    (let [d (dot/dot (dot/digraph (concat [{:rankdir :LR, :splines :polyline}] statements)))]
      (case output-format
        :viewer (dotjvm/show! d)
        (dotjvm/save! d (str output "." (name output-format)) {:format output-format})))))

(defn output-trace
  [trace output config trace-omit {output :output-file output-format :output-format :as opts}]
  (let [omitted-libs (set trace-omit)
        trace' (remove (fn [{:keys [lib include]}]
                         (and (not include) (contains? omitted-libs lib)))
                 trace)]
    (println "Writing" (inc (count trace')) "trace images, omitted" (inc (- (count trace) (count trace'))) "frames")
    (loop [[step & steps] trace'
           stmts [[:root {:label "deps.edn"
                          :shape :box
                          :fillcolor :cadetblue1
                          :style :filled}]]
           i 100]
      (if step
        (let [{:keys [lib coord use-coord path include reason vmap]} step
              nx (symbol (namespace lib) (str (name lib) "-CONSIDER"))
              dependee-id (if-let [parent (last path)] (node-id parent) :root)
              nx-stmts [(make-dep-node nx use-coord config nil {:fillcolor (if include :green2 :brown1)})
                        [dependee-id (node-id nx) {:label reason}]]]
          (print ".") (flush)
          (-> (dot/digraph (concat [{:rankdir :LR, :splines :polyline}] (into stmts nx-stmts)))
            dot/dot
            (dotjvm/save! (str output i "." (name output-format)) {:format output-format}))
          (recur steps
            (case reason
              ;; add new node and link from parent to it
              (:new-top-dep :new-dep)
              (into stmts [(make-dep-node lib use-coord config nil nil) [dependee-id (node-id lib)]])

              ;; add new node and remove previous node, link from parent to it
              :newer-version
              ;; todo: remove edges to dependents of old version
              ;; todo: remove then orphaned deps?
              (into (remove (fn [[id b]] (and (= id (node-id lib)) (not (keyword? b)))) stmts)
                [(make-dep-node lib use-coord config nil nil)
                 [dependee-id (node-id lib)]])

              ;; just link to existing node
              (:same-version :old-version :use-top)
              (into stmts [[dependee-id (node-id lib)]])

              ;; no change
              ;; (:excluded :parent-omitted)
              stmts)
            (inc i)))
        (do
          (println)
          (-> (dot/digraph (concat [{:rankdir :LR, :splines :polyline}] stmts))
            dot/dot
            (dotjvm/save! (str output i "." (name output-format)) {:format output-format})))))))

(defn run
  [{:keys [deps trace tracefile output-file aliases trace-omit size] :as opts}]
  (try
    (if tracefile
      (do
        (when-not output-file (throw (ex-info "-o must specify output file name in trace mode" nil)))
        (let [tf (jio/file tracefile)]
          (if (.exists tf)
            (output-trace (-> tf slurp edn/read-string :log) nil output-file trace-omit opts)
            (throw (ex-info (str "Trace file does not exist: " tracefile) {})))))
      (let [project-dep-loc (jio/file (or deps "deps.edn"))]
        (when (and deps (not (.exists project-dep-loc)))
          (throw (ex-info (str "Deps file does not exist: " deps) {})))
        (let [install-deps (reader/install-deps)
              user-dep-loc (jio/file (reader/user-deps-location))
              user-deps (when (.exists user-dep-loc) (reader/slurp-deps user-dep-loc))
              project-deps (when (.exists project-dep-loc) (reader/slurp-deps project-dep-loc))
              deps-map (->> [install-deps user-deps project-deps] (remove nil?) reader/merge-deps)]
          (makecp/check-aliases deps-map aliases)
          (let [deps-map' (if-let [replace-deps (get (deps/combine-aliases deps-map aliases) :deps)]
                            (->> [install-deps user-deps project-deps {:deps replace-deps}] (remove nil?) reader/merge-deps)
                            deps-map)
                resolve-args (deps/combine-aliases deps-map' aliases)
                lib-map (session/with-session (deps/resolve-deps deps-map' resolve-args {:trace trace}))]
            (if trace
              (output-trace (-> lib-map meta :trace :log) deps-map' output-file trace-omit opts)
              (make-graph lib-map deps-map' opts))))))
    (catch IOException e
      (if (str/starts-with? (.getMessage e) "Cannot run program")
        (throw (ex-info "tools.deps.graph requires Graphviz (https://graphviz.gitlab.io/download) to be installed to generate graphs." {} e))))))


(defn main-usage [options-summary & more]
  (->> ["Create deps graphs."
        ""
        "usage: clj -A:graph <options>"
        ""
        "Options:"
        options-summary
        ""
        ""
        (when more
          ["-------------------------------------------------------------------"
           (with-out-str (pprint more))
           "-------------------------------------------------------------------"])]
       flatten (clojure.string/join \newline)))


(defn -main
  "Create deps graphs, invoke with --help for options"
  [& args]
  (try
    (let [{:keys [options errors summary]} (parse-opts args)]
      (when (seq errors)
        (run! println errors)
        (System/exit 1))
      (if (:help options)
        (println (main-usage summary {:args args :options options}))
        (run options))
      (shutdown-agents))
    (catch Throwable t
      (printerrln (.getMessage t))
      (when-not (instance? IExceptionInfo t)
        (.printStackTrace t))
      (System/exit 1))))
