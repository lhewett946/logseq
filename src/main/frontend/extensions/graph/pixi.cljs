(ns frontend.extensions.graph.pixi
  (:require [cljs-bean.core :as bean]
            ["d3-force"
             :refer [forceCenter forceCollide forceLink forceManyBody forceSimulation]
             :as force]
            [goog.object :as gobj]
            [frontend.colors :as colors]
            ["graphology" :as graphology]
            ["pixi-graph-fork" :as Pixi-Graph]))

(defonce *graph-instance (atom nil))
(defonce *simulation (atom nil))
(defonce *simulation-paused?
  (atom false))

(def Graph (gobj/get graphology "Graph"))

(defn default-style
  [dark?]
  {:node {:size   (fn [node]
                    (or (.-size node) 8))
          :border {:width 0}
          :color  (fn [node]
                      (.-color node))
          :label  {:content  (fn [node] (.-label node))
                   :type     (.-TEXT (.-TextType Pixi-Graph))
                   :fontSize 12
                   :color (if dark? "rgba(255, 255, 255, 0.8)" "rgba(0, 0, 0, 0.8)")
                   :padding  4}}
   :edge {:width 1
          :color (if dark? (or (colors/get-accent-color) "#094b5a") "#cccccc")}})

(defn default-hover-style
  [_dark?]
  {:node {:color (or (colors/get-accent-color) "#6366F1")
          :label {:backgroundColor "rgba(238, 238, 238, 1)"
                  :color           "#333333"}}
   :edge {:color "#A5B4FC"}})

(defn layout!
  "Node forces documentation can be read in more detail here https://d3js.org/d3-force"
  [nodes links link-dist charge-strength charge-range link-strength]
  (let [simulation (forceSimulation nodes)]
    (-> simulation
        (.force "link"
                ;; The link force pushes linked nodes together or apart according to the desired link distance.
                ;; The strength of the force is also controlled independently to allow flexibility for users
                (-> (forceLink)
                    (.id (fn [d] (.-id d)))
                    (.distance link-dist)
                    (.links links)
                    (.strength (* link-strength 0.05))))
        (.force "charge"
                ;; The many-body (or n-body) force applies mutually amongst all nodes.
                ;; It can be used to simulate gravity or electrostatic charge.
                (-> (forceManyBody)
                    ;; The minimum distance between nodes over which this force is considered.
                    ;; A minimum distance establishes an upper bound on the strength of the force between two nearby nodes, avoiding instability.
                    (.distanceMin 1)
                    ;; The maximum distance between nodes over which this force is considered.
                    ;; Specifying a finite maximum distance improves performance and produces a more localized layout.
                    (.distanceMax charge-range)
                    ;; For a cluster of nodes that is far away, the charge force can be approximated by treating the cluster as a single, larger node.
                    ;; The theta parameter determines the accuracy of the approximation
                    (.theta 0.5)
                    ;; A positive value causes nodes to attract each other, similar to gravity,
                    ;; while a negative value causes nodes to repel each other, similar to electrostatic charge.
                    (.strength charge-strength)))
        (.force "collision"
                (-> (forceCollide)
                    (.radius (+ 8 18))
                    (.iterations 2)))
        (.force "center" (forceCenter))
        ;; The decay factor is akin to atmospheric friction; after the application of any forces during a tick,
        ;; each node’s velocity is multiplied by 1 - decay. As with lowering the alpha decay rate,
        ;; less velocity decay may converge on a better solution, but risks numerical instabilities and oscillation.
        (.velocityDecay 0.3))
    (reset! *simulation simulation)
    simulation))

(defn- clear-nodes!
  [graph]
  (.forEachNode graph
                (fn [node]
                  (.dropNode graph node))))

;; (defn- clear-edges!
;;   [graph]
;;   (.forEachEdge graph
;;                 (fn [edge]
;;                   (.dropEdge graph edge))))

(defn destroy-instance!
  []
  (when-let [instance (:pixi @*graph-instance)]
    (.destroy instance)
    (reset! *graph-instance nil)
    (reset! *simulation nil))
  (reset! *simulation-paused? false))

(defn stop-simulation!
  []
  (when-let [^js simulation @*simulation]
    (.stop simulation)
    (reset! *simulation-paused? true)))

(defn resume-simulation!
  []
  (when-let [^js simulation @*simulation]
    (.restart simulation))
  (reset! *simulation-paused? false))

(defn- update-position!
  [node obj]
  (when node
    (try
      (.updatePosition node #js {:x (.-x obj)
                                 :y (.-y obj)})
      (catch :default e
        (js/console.error e)))))

(defn- tick!
  [pixi _graph nodes-js links-js]
  (fn []
    (try
      (let [nodes-objects (.getNodesObjects pixi)
            edges-objects (.getEdgesObjects pixi)]
        (doseq [node nodes-js]
          (when-let [node-object (.get nodes-objects (.-id node))]
            (update-position! node-object node)))
        (doseq [edge links-js]
          (when-let [edge-object (.get edges-objects (str (.-index edge)))]
            (.updatePosition edge-object
                             #js {:x (.-x (.-source edge))
                                  :y (.-y (.-source edge))}
                             #js {:x (.-x (.-target edge))
                                  :y (.-y (.-target edge))}))))
      (catch :default e
        (js/console.error e)
        nil))))

(defn- set-up-listeners!
  [pixi-graph]
  (when pixi-graph
    ;; drag start
    (let [*dragging? (atom false)
          nodes (.getNodesObjects pixi-graph)
          on-drag-end (fn [_node event]
                        (.stopPropagation event)
                        (when-let [s @*simulation]
                          (when-not (.-active event)
                            (.alphaTarget s 0)))
                        (reset! *dragging? false))]
      (.on pixi-graph "nodeMousedown"
           (fn [event node-key]
             #_:clj-kondo/ignore
             (when-let [node (.get nodes node-key)]
               (when-let [s @*simulation]
                 (when-not (or (.-active event)
                               @*simulation-paused?)
                   (-> (.alphaTarget s 0.3)
                       (.restart))
                   (js/setTimeout #(.alphaTarget s 0) 2000))
                 (reset! *dragging? true)))))

      (.on pixi-graph "nodeMouseup"
           (fn [event node-key]
             (when-let [node (.get nodes node-key)]
               (on-drag-end node event))))

      (.on pixi-graph "nodeMousemove"
           (fn [event node-key]
             (when-let [node (.get nodes node-key)]
               (when @*dragging?
                 (update-position! node event))))))))

(defn render!
  [state]
  (try
    (when @*graph-instance
      (clear-nodes! (:graph @*graph-instance))
      (destroy-instance!))
    (let [{:keys [nodes links style hover-style height register-handlers-fn dark? link-dist charge-strength charge-range link-strength]} (first (:rum/args state))
          style       (or style (default-style dark?))
          hover-style (or hover-style (default-hover-style dark?))
          graph       (Graph.)
          nodes-set   (set (map :id nodes))
          links       (->>
                        (filter
                         (fn [link]
                           (and (nodes-set (:source link)) (nodes-set (:target link))))
                          links)
                         (distinct)) ;; #3331 (@zhaohui0923) seems caused by duplicated links. Why distinct doesn't work?
          nodes       (remove nil? nodes)
          links       (remove (fn [{:keys [source target]}] (or (nil? source) (nil? target))) links)
          nodes-js    (bean/->js nodes)
          links-js    (bean/->js links)
          simulation  (layout! nodes-js links-js link-dist charge-strength charge-range link-strength)]
      (doseq [node nodes-js]
        (try (.addNode graph (.-id node) node)
             (catch :default e
               (js/console.error e))))
      (doseq [link links-js]
        (let [source (.-id (.-source link))
              target (.-id (.-target link))]
          (try (.addEdge graph source target link)
               (catch :default e
                 (js/console.error e)))))
      (when-let [container-ref (:ref state)]
        (let [pixi-graph (new (.-PixiGraph Pixi-Graph)
                              (bean/->js
                               {:container  @container-ref
                                :graph      graph
                                :style      style
                                :hoverStyle hover-style
                                :height     height}))]
          (reset! *graph-instance
                  {:graph graph
                   :pixi  pixi-graph})
          (when register-handlers-fn
            (register-handlers-fn pixi-graph))
          (set-up-listeners! pixi-graph)
          (.on simulation "tick" (tick! pixi-graph graph nodes-js links-js)))))
    (catch :default e
      (js/console.error e)))
  state)
