(ns logseq.e2e.rtc
  (:require [clojure.edn :as edn]
            [logseq.e2e.util :as util]
            [wally.main :as w]))

(defn get-rtc-tx
  []
  (let [loc (w/get-by-test-id "rtc-tx")]
    (edn/read-string (w/text-content loc))))

(defmacro with-wait-tx-updated
  "exec body, then wait for the rtc-tx update.
  Return the updated rtc-tx(local-tx and remote-tx)"
  [& body]
  `(let [m# (get-rtc-tx)
         local-tx# (or (:local-tx m#) 0)
         remote-tx# (or (:remote-tx m#) 0)
         _# (prn :current-rtc-tx m# local-tx# remote-tx#)
         tx# (max local-tx# remote-tx#)]
     ~@body
     (loop [i# 5]
       (when (zero? i#) (throw (ex-info "wait-tx-updated failed" {:data m#})))
       (util/wait-timeout 1000)
       (let [new-m# (get-rtc-tx)
             new-local-tx# (or (:local-tx new-m#) 0)
             new-remote-tx# (or (:remote-tx new-m#) 0)]
         (if (and (= new-local-tx# new-remote-tx#)
                  (> new-local-tx# tx#))
           (do (prn :new-rtc-tx new-m#)
               {:local-tx new-local-tx# :remote-tx new-remote-tx#})
           (do (prn :current-rtc-tx new-m#)
               (recur (dec i#))))))))

(defn wait-tx-update-to
  [new-tx]
  (loop [i 5]
    (when (zero? i) (throw (ex-info "wait-tx-update-to" {:update-to new-tx})))
    (util/wait-timeout 1000)
    (let [m (get-rtc-tx)
          local-tx (or (:local-tx m) 0)
          ;; remote-tx (or (:remote-tx m) 0)
          ]
      (if (>= local-tx new-tx)
        local-tx
        (recur (dec i))))))
