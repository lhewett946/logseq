(ns frontend.common.graph-view
  "Main namespace for graph view fns."
  (:require [clojure.set :as set]
            [clojure.string :as string]
            [datascript.core :as d]
            [logseq.common.util :as common-util]
            [logseq.db :as ldb]
            [logseq.db.common.entity-plus :as entity-plus]
            [logseq.db.sqlite.create-graph :as sqlite-create-graph]
            [logseq.graph-parser.db :as gp-db]))

(defn- build-links
  [links]
  (keep (fn [[from to]]
          (when (and from to)
            {:source (str from)
             :target (str to)}))
        links))

(defn- node-property-links
  "Returns a sequence of [from-id to-id] for all :node property links on the given entity.
   Supports both single node maps and vectors of node maps, at any key."
  [entity]
  (let [from-id (:db/id entity)]
    (mapcat
      (fn [[_ v]]
        (cond
          ;; Single node map
          (and (map? v) (:db/id v))
          [[from-id (:db/id v)]]

          ; Handle sets or vectors of maps with db/id
          (or (and (vector? v) (every? #(contains? % :db/id) v))
              (and (set? v) (every? #(contains? % :db/id) v)))
          (map (fn [node] [from-id (:db/id node)]) (if (set? v) (vec v) v))

          :else
          []))
      entity)))

(defn- build-nodes
  [dark? db-based? db current-page page-links nodes namespaces color-property color-settings]
  (let [page-parents (set (map last namespaces))
        current-page (or current-page "")
        pages (common-util/distinct-by :db/id nodes)]
    (->>
     pages
     (remove ldb/hidden?)
     (remove nil?)
     (keep (fn [p]
             (if-let [page-title (:block/title p)]
               (let [current-page? (= page-title current-page)
                   color-type (if db-based?
                                (:block/title (d/entity db (last (map :db/id (:block/tags p)))))
                                (get (:block/properties p) color-property))
                   color (get color-settings color-type)
                   color (if current-page?
                           (if dark? "#ffffff" "#045591")
                           (if (nil? color)
                             (if dark? "#93a1a1" "#999")
                             color))
                   node-id (str (:db/id p))
                   n (get page-links node-id 1)
                   size (int (* 8 (max 1.0 (js/Math.cbrt n))))]
               (cond->
                {:id (str (:db/id p))
                 :label page-title
                 :size size
                 :color color
                 :block/created-at (:block/created-at p)}
                 (contains? page-parents (:db/id p))
                 (assoc :parent true)))
               (js/console.error (str "Page doesn't have :block/title " p)))))
     vec)))

;; slow
(defn- uuid-or-asset?
  [label]
  (or (common-util/uuid-string? label)
      (string/starts-with? label "../assets/")
      (= label "..")
      (string/starts-with? label "assets/")
      (string/ends-with? label ".gif")
      (string/ends-with? label ".jpg")
      (string/ends-with? label ".png")))

(defn- remove-uuids-and-files!
  [nodes]
  (remove
   (fn [node] (uuid-or-asset? (:label node)))
   nodes))

(defn- normalize-page-name
  [{:keys [nodes links]}]
  (let [nodes' (->> (remove-uuids-and-files! nodes)
                    (common-util/distinct-by (fn [node] (:id node)))
                    (remove nil?))]
    {:nodes nodes'
     :links links}))

(defn- build-global-graph
  [db {:keys [theme journal? orphan-pages? builtin-pages? excluded-pages? created-at-filter]} color-property color-settings]
  (let [dark? (= "dark" theme)
        relation (ldb/get-pages-relation db journal?)
        tagged-pages (ldb/get-all-tagged-pages db)
        namespaces (gp-db/get-all-namespace-relation db)
        full-pages (ldb/get-all-pages db)
        db-based? (entity-plus/db-based-graph? db)
        created-ats (map :block/created-at full-pages)

        ;; build up nodes
        full-pages'
        (cond->> full-pages
          created-at-filter
          (filter #(<= (:block/created-at %) (+ (apply min created-ats) created-at-filter)))
          (not journal?)
          (remove ldb/journal?)
          (not excluded-pages?)
          (remove (fn [p] (true?
                           (if db-based?
                             (get p :logseq.property/exclude-from-graph-view)
                             (get-in p [:block/properties :exclude-from-graph-view]))))))
        node-prop-links (mapcat node-property-links full-pages')
        links (concat relation tagged-pages namespaces node-prop-links)
        linked (set (mapcat identity links))
        build-in-pages (->> (if db-based? sqlite-create-graph/built-in-pages-names gp-db/built-in-pages-names)
                            (map string/lower-case)
                            set)
        nodes (cond->> full-pages'
                (not builtin-pages?)
                (remove #(contains? build-in-pages (:block/name %)))
                (not orphan-pages?)
                (filter #(contains? linked (:db/id %))))
        links (map (fn [[x y]] [(str x) (str y)]) links)
        page-links (reduce (fn [m [k v]] (-> (update m k inc)
                                             (update v inc))) {} links)
        links (build-links links)
        nodes (build-nodes dark? db-based? db nil page-links nodes namespaces color-property color-settings)]
    (-> {:nodes nodes
         :links links}
        normalize-page-name
        (assoc :all-pages
               {:created-at-min (apply min created-ats)
                :created-at-max (apply max created-ats)}))))

(defn get-pages-that-mentioned-page
  [db page-id include-journals?]
  (let [pages (ldb/page-alias-set db page-id)
        mentioned-pages (->>
                         (mapcat
                          (fn [id]
                            (let [page (d/entity db id)]
                              (->> (:block/_refs page)
                                   (keep (fn [ref]
                                           (if (ldb/page? ref)
                                             page
                                             (:block/page ref)))))))
                          pages)
                         (common-util/distinct-by :db/id))]
    (keep (fn [page]
            (when-not (and (not include-journals?) (ldb/journal? page))
              (:db/id page)))
          mentioned-pages)))

(defn get-page-referenced-pages
  [db page-id]
  (let [pages (ldb/page-alias-set db page-id)
        ref-pages (d/q
                   '[:find [?ref-page ...]
                     :in $ ?pages
                     :where
                     [(untuple ?pages) [?page ...]]
                     [?block :block/page ?page]
                     [?block :block/refs ?ref-page]]
                   db
                   pages)]
    ref-pages))

(defn- build-page-graph-other-page-links [db other-pages* show-journal]
  (let [other-pages (->> other-pages*
                         (remove nil?)
                         (set))]
    (mapcat
     (fn [page-id]
       (let [ref-pages (-> (get-page-referenced-pages db page-id)
                           (set)
                           (set/intersection other-pages))
             mentioned-pages (-> (get-pages-that-mentioned-page db page-id show-journal)
                                 (set)
                                 (set/intersection other-pages))]
         (concat
          (map (fn [p] [page-id p]) ref-pages)
          (map (fn [p] [p page-id]) mentioned-pages))))
     other-pages)))

(defn- build-page-graph
  [db page-uuid theme show-journal color-property color-settings]
  (let [dark? (= "dark" theme)
        page-entity (d/entity db [:block/uuid page-uuid])
        db-based? (entity-plus/db-based-graph? db)
        page-id (:db/id page-entity)
        tags (when db-based?
               (set (map :db/id (:block/tags page-entity))))
        tags (set (remove #(= page-id %) tags))
        ref-pages (get-page-referenced-pages db page-id)
        mentioned-pages (get-pages-that-mentioned-page db page-id show-journal)
        namespaces (gp-db/get-all-namespace-relation db)
        node-prop-links (node-property-links page-entity)
        links (concat
               namespaces
               (map (fn [ref-page]
                      [page-id ref-page]) ref-pages)
               (map (fn [page]
                      [page-id page]) mentioned-pages)
               (map (fn [tag]
                      [page-id tag])
                    tags)
                    node-prop-links)
        other-pages-links (build-page-graph-other-page-links db (concat ref-pages mentioned-pages) show-journal)
        links (->> (concat links other-pages-links)
                   (remove nil?)
                   (distinct)
                   (build-links))
        nodes (->> (concat
                    [page-id]
                    ref-pages
                    mentioned-pages
                    tags)
                   (remove nil?)
                   (map #(d/entity db %))
                   (common-util/distinct-by :db/id))
        nodes (build-nodes dark? db-based? db (:block/title page-entity) links nodes namespaces color-property color-settings)]
    (normalize-page-name
     {:nodes nodes
      :links links})))

(defn- build-block-graph
  "Builds a citation/reference graph for a given block uuid."
  [db block-uuid theme color-property color-settings]
  (when-let [block (and (uuid? block-uuid) (d/entity db [:block/uuid block-uuid]))]
    (let [dark? (= "dark" theme)
          db-based? (entity-plus/db-based-graph? db)
          ref-blocks (->> (concat (:block/_refs block) (:block/refs block))
                          (map (fn [b]
                                 (if (ldb/page? b) b (:block/page b))))
                          (remove (fn [node] (= (:db/id block) (:db/id node))))
                          (common-util/distinct-by :db/id))
          namespaces (gp-db/get-all-namespace-relation db)
          links (->> (concat
                      namespaces
                      (map (fn [p] [(:db/id block) (:db/id p)]) ref-blocks))
                     (remove nil?)
                     (distinct)
                     (build-links))
          nodes (->> (cons block ref-blocks)
                     distinct
                       ;; FIXME: get block tags
                     )
          nodes (build-nodes dark? db-based? db block links nodes namespaces color-property color-settings)]
      (normalize-page-name
       {:nodes nodes
        :links links}))))

(defn build-graph
  [db opts color-property color-settings]
  (case (:type opts)
    :global (build-global-graph db opts color-property color-settings)
    :block (build-block-graph db (:block/uuid opts) (:theme opts) color-property color-settings)
    :page (build-page-graph db (:block/uuid opts) (:theme opts) (:show-journal? opts) color-property color-settings)))
