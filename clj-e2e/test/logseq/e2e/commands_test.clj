(ns logseq.e2e.commands-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [logseq.e2e.block :as b]
   [logseq.e2e.fixtures :as fixtures]
   [logseq.e2e.keyboard :as k]
   [logseq.e2e.util :as util]
   [wally.main :as w]))

(use-fixtures :once fixtures/open-page)

(use-fixtures :each fixtures/new-logseq-page)

(deftest command-trigger-test
  (testing "/command trigger popup"
    (b/new-block "b2")
    (util/type " /")
    (w/wait-for ".ui__popover-content")
    (is (some? (w/find-one-by-text "span" "Node reference")))
    (k/backspace)
    (w/wait-for-not-visible ".ui__popover-content")))

(defn- input-command
  [command-match]
  (util/type "/")
  (util/type command-match)
  (w/wait-for ".ui__popover-content")
  (k/enter))

(deftest node-reference-test
  (testing "Node reference"
    (testing "Page reference"
      (b/new-blocks ["b1" ""])
      (input-command "Node eferen")
      (util/type "Another page")
      (k/enter)
      (is (= "[[Another page]]" (util/get-edit-content)))
      (util/exit-edit)
      (is (= "Another page" (util/get-text "a.page-ref"))))
    (testing "Block reference"
      (b/new-block "")
      (input-command "Node eferen")
      (util/type "b1")
      (util/wait-timeout 300)
      (k/enter)
      (is (string/includes? (util/get-edit-content) "[["))
      (util/exit-edit)
      (is (= "b1" (util/get-text ".block-ref"))))))

(deftest link-test
  (testing "/link"
    (let [add-logseq-link (fn []
                            (util/type "https://logseq.com")
                            (k/tab)
                            (util/type "Logseq")
                            (k/tab)
                            (k/enter))]
      (b/new-block "")
      (input-command "link")
      (add-logseq-link)
      (is (= "[Logseq](https://logseq.com)" (util/get-edit-content)))
      (util/type " some content ")
      (input-command "link")
      (add-logseq-link)
      (is (= (str "[Logseq](https://logseq.com)"
                  " some content "
                  "[Logseq](https://logseq.com)") (util/get-edit-content))))))

(deftest link-image-test
  (testing "/image link"
    (b/new-block "")
    (input-command "image link")
    (util/type "https://logseq.com/test.png")
    (k/tab)
    (util/type "Logseq")
    (k/tab)
    (k/enter)
    (is (= "![Logseq](https://logseq.com/test.png)" (util/get-edit-content)))))

(deftest underline-test
  (testing "/underline"
    (b/new-block "")
    (input-command "underline")
    (is (= "<ins></ins>" (util/get-edit-content)))
    (util/type "test")
    (is (= "<ins>test</ins>" (util/get-edit-content)))
    (util/move-cursor-to-end)))

(deftest code-block-test
  (testing "/code block"
    (b/new-block "")
    (input-command "code block")
    (w/wait-for ".CodeMirror")
    (util/wait-timeout 100)
    ;; create another block
    (k/shift+enter)))

(deftest math-block-test
  (testing "/math block"
    (b/new-block "")
    (input-command "math block")
    (util/type "1 + 2 = 3")
    (util/exit-edit)
    (w/wait-for ".katex")))

(deftest quote-test
  (testing "/quote"
    (b/new-block "")
    (input-command "quote")
    (w/wait-for "div[data-node-type='quote']")))

(deftest headings-test
  (testing "/heading"
    (dotimes [i 6]
      (let [heading (str "h" (inc i))
            text (str heading " test ")]
        (b/new-block text)
        (input-command heading)
        (is (= text (util/get-edit-content)))
        (util/exit-edit)
        (w/wait-for heading)))))
