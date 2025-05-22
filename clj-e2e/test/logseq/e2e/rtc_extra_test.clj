(ns logseq.e2e.rtc-extra-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures run-test]]
   [com.climate.claypoole :as cp]
   [logseq.e2e.assert :as assert]
   [logseq.e2e.block :as b]
   [logseq.e2e.fixtures :as fixtures :refer [*page1 *page2]]
   [logseq.e2e.graph :as graph]
   [logseq.e2e.keyboard :as k]
   [logseq.e2e.locator :as loc]
   [logseq.e2e.outliner-basic-test :as outliner-basic-test]
   [logseq.e2e.page :as page]
   [logseq.e2e.rtc :as rtc]
   [logseq.e2e.settings :as settings]
   [logseq.e2e.util :as util]
   [wally.main :as w]
   [wally.repl :as repl]))

(defn- prepare-rtc-graph-fixture
  "open 2 app instances, add a rtc graph, check this graph available on other instance"
  [f]
  (let [graph-name (str "rtc-extra-test-graph-" (.toEpochMilli (java.time.Instant/now)))]
    (cp/prun!
     2
     #(w/with-page %
        (settings/developer-mode)
        (w/refresh)
        (util/login-test-account))
     [@*page1 @*page2])
    (w/with-page @*page1
      (graph/new-graph graph-name true))
    (w/with-page @*page2
      (graph/wait-for-remote-graph graph-name)
      (graph/switch-graph graph-name true))

    (f)

    ;; cleanup
    (w/with-page @*page2
      (graph/remove-remote-graph graph-name))))

(defn- new-logseq-page
  "new logseq page and switch to this page on both page1 and page2"
  []
  (let [*page-name (atom nil)
        {:keys [_local-tx remote-tx]}
        (w/with-page @*page1
          (rtc/with-wait-tx-updated
            (reset! *page-name (fixtures/create-page))))]
    (w/with-page @*page2
      (rtc/wait-tx-update-to remote-tx)
      (page/goto-page @*page-name))))

(defn- new-logseq-page-fixture
  [f]
  (new-logseq-page)
  (f))

(use-fixtures :once
  fixtures/open-2-pages
  prepare-rtc-graph-fixture)

(use-fixtures :each
  new-logseq-page-fixture)

(defn- with-stop-restart-rtc
  [pw-page f]
  (w/with-page pw-page
    (rtc/rtc-stop))
  (f)
  (w/with-page pw-page
    (rtc/rtc-start)))

(defn- validate-2-graphs
  []
  (let [[p1-summary p2-summary]
        (map
         (fn [p]
           (w/with-page p
             (graph/validate-graph)))
         [@*page1 @*page2])]
    (assert/assert-graph-summary-equal p1-summary p2-summary)))

(defn- validate-task-blocks
  []
  (let [icon-names ["Backlog" "Todo" "InProgress50" "InReview" "Done" "Cancelled"]
        icon-name->count
        (w/with-page @*page2
          (into
           {}
           (map
            (fn [icon-name]
              [icon-name (.count (w/-query (str ".ls-icon-" icon-name)))])
            icon-names)))]
    (prn :validate-task-blocks icon-name->count)
    (w/with-page @*page1
      (doseq [[icon-name count*] icon-name->count]
        (assert/assert-have-count (str ".ls-icon-" icon-name) count*)))))

(defn- insert-task-blocks
  [title-prefix]
  (doseq [status ["Backlog" "Todo" "Doing" "In review" "Done" "Canceled"]
          priority ["No priority" "Low" "Medium" "High" "Urgent"]]
    (b/new-block (str title-prefix "-" status "-" priority))
    (util/input-command status)
    (util/input-command priority)))

(defn- update-task-blocks
  [])

(deftest rtc-task-blocks-test
  (let [insert-task-blocks-in-page2
        (fn [*latest-remote-tx]
          (w/with-page @*page2
            (let [{:keys [_local-tx remote-tx]}
                  (rtc/with-wait-tx-updated
                    (insert-task-blocks "t1"))]
              (reset! *latest-remote-tx remote-tx))
            ;; TODO: more operations
            (util/exit-edit)))]
    (testing "rtc-stop app1, add some task blocks, then rtc-start on app1"
      (let [*latest-remote-tx (atom nil)]
        (with-stop-restart-rtc @*page1 #(insert-task-blocks-in-page2 *latest-remote-tx))
        (w/with-page @*page1
          (rtc/wait-tx-update-to @*latest-remote-tx))
        (validate-task-blocks)
        (validate-2-graphs)))

    (new-logseq-page)

    (testing "perform same operations on page2 while keeping rtc connected on page1"
      (let [*latest-remote-tx (atom nil)]
        (insert-task-blocks-in-page2 *latest-remote-tx)
        (w/with-page @*page1
          (rtc/wait-tx-update-to @*latest-remote-tx))
        (validate-task-blocks)
        (validate-2-graphs)))))

(defn- add-new-properties
  [title-prefix]
  (b/new-blocks (map #(str title-prefix "-" %) ["Text" "Number" "Date" "DateTime" "Checkbox" "Url" "Node"]))
  (doseq [property-type ["Text" "Number" "Date" "DateTime" "Checkbox" "Url" "Node"]]
    (let [property-name (str "p-" title-prefix "-" property-type)]
      (w/click (util/get-by-text (str title-prefix "-" property-type) true))
      (k/press "Control+e")
      (util/input-command "Add new property")
      (util/input property-name)
      (w/click (w/get-by-text "New option:"))
      (assert/assert-is-visible (w/get-by-text "Select a property type"))
      (w/click (loc/and "span" (util/get-by-text property-type true)))
      (case property-type
        "Text" (util/input "Text")
        "Number" (do (assert/assert-is-visible (format "input[placeholder='%s']" (str "Set " property-name)))
                     (util/input "111")
                     (w/click (w/get-by-text "New option:")))
        ("DateTime" "Date") (do
                              (assert/assert-is-visible ".ls-property-dialog")
                              (k/enter)
                              (k/esc))
        "Checkbox" nil
        "Url" nil
        "Node" (do
                 (w/click (w/get-by-text "Skip choosing tag"))
                 (util/input (str title-prefix "-Node-value"))
                 (w/click (w/get-by-text "New option:")))))))

(deftest rtc-property-test
  (let [insert-new-property-blocks-in-page2
        (fn [*latest-remote-tx title-prefix]
          (w/with-page @*page2
            (let [{:keys [_local-tx remote-tx]}
                  (rtc/with-wait-tx-updated
                    (add-new-properties title-prefix))]
              (reset! *latest-remote-tx remote-tx))))]
    (testing "page1: rtc-stop
page2: create some user properties with different type
page1: rtc-start"
      (let [*latest-remote-tx (atom nil)]
        (with-stop-restart-rtc @*page1 #(insert-new-property-blocks-in-page2 *latest-remote-tx "rtc-property-test-1"))
        (w/with-page @*page1
          (rtc/wait-tx-update-to @*latest-remote-tx))
        (validate-2-graphs)))

    (new-logseq-page)

    (testing "perform same operations on page2 while keeping rtc connected on page1"
      (let [*latest-remote-tx (atom nil)]
        (insert-new-property-blocks-in-page2 *latest-remote-tx "rtc-property-test-2")
        (w/with-page @*page1
          (rtc/wait-tx-update-to @*latest-remote-tx))
        (validate-2-graphs)))))

(deftest rtc-outliner-test
  (doseq [test-fn [outliner-basic-test/create-test-page-and-insert-blocks
                   outliner-basic-test/indent-and-outdent
                   outliner-basic-test/move-up-down
                   outliner-basic-test/delete
                   outliner-basic-test/delete-test-with-children]]
    (let [test-fn-in-page2 (fn [*latest-remote-tx]
                             (w/with-page @*page2
                               (let [{:keys [_local-tx remote-tx]}
                                     (rtc/with-wait-tx-updated
                                       (test-fn))]
                                 (reset! *latest-remote-tx remote-tx))))]

      ;; testing while rtc connected
      (let [*latest-remote-tx (atom nil)]
        (new-logseq-page)
        (test-fn-in-page2 *latest-remote-tx)
        (w/with-page @*page1
          (rtc/wait-tx-update-to @*latest-remote-tx))
        (validate-2-graphs))

      ;; testing while rtc off then on
      (let [*latest-remote-tx (atom nil)]
        (new-logseq-page)
        (with-stop-restart-rtc @*page1 #(test-fn-in-page2 *latest-remote-tx))
        (w/with-page @*page1
          (rtc/wait-tx-update-to @*latest-remote-tx))
        (validate-2-graphs)))))

(comment
  (let [title-prefix "xxxx"
        property-type "Text"]
    (w/with-page @*page1
      (b/new-block (str title-prefix "-" property-type)))))
