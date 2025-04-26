(ns user
  "fns used on repl"
  (:require [clojure.test :refer [run-tests run-test]]
            [logseq.e2e.editor-test]
            [logseq.e2e.fixtures :as fixtures]
            [logseq.e2e.outliner-test]
            [logseq.e2e.rtc-basic-test]
            [logseq.e2e.multi-tabs-test]
            [logseq.e2e.util :as util]
            [logseq.e2e.config :as config]
            [wally.main :as w]
            [wally.repl :as repl]))

;; Use port 3001 for local testing
(reset! config/*port 3001)
;; show ui
(reset! config/*headless false)

(defn run-editor-test
  []
  (future (run-tests 'logseq.e2e.editor-test)))

(defn run-outliner-test
  []
  (future (run-tests 'logseq.e2e.outliner-test)))

(defn run-rtc-basic-test
  []
  (future (run-tests 'logseq.e2e.rtc-basic-test)))

(defn run-multi-tabs-test
  []
  (future (run-tests 'logseq.e2e.multi-tabs-test)))

(comment

  (future
    (fixtures/open-page
     repl/pause
     {:headless false}))

  ;; You can put `(repl/pause)` in any test to pause the tests,
  ;; this allows us to continue experimenting with the current page.
  (repl/pause)

  ;; To resume the tests, close the page/context/browser
  (repl/resume)

  ;; Run specific test
  (future (run-test logseq.e2e.editor-test/commands-test))

  ;; after the test has been paused, you can do anything with the current page like this
  (repl/with-page
    (w/wait-for (first (util/get-edit-block-container))
                {:state :detached}))

  ;;
  )
