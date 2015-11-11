(ns de.otto.tesla.xray.xray-checker
  (:require [com.stuartsierra.component :as c]
            [overtone.at-at :as at]
            [clojure.tools.logging :as log]
            [compojure.core :as comp]
            [hiccup.page :as hc]
            [clj-time.coerce :as time]
            [compojure.route :as croute]
            [de.otto.tesla.stateful.handler :as hndl]
            [de.otto.tesla.xray.check :as chk]))

(def steps-to-keep 5)

(defprotocol XRayCheckerProtocol
  (register-check [self check checkname]))

(defn- store-check-result [result results]
  (let [limited-results (take (- steps-to-keep 1) results)]
    (conj limited-results result)))

(defn- store-result [check-results check-name current-env result]
  (swap! check-results update-in [check-name current-env] (partial store-check-result result)))

(defn- current-time []
  (System/currentTimeMillis))

(defn start-single-xraycheck [check-results check current-env check-name]
  (try
    (let [start-time (current-time)
          xray-chk-result (chk/start-check check current-env)
          stop-time (current-time)]
      (store-result check-results check-name current-env (chk/with-timings xray-chk-result (- stop-time start-time) stop-time)))
    (catch Exception e
      (log/error e "an error occured when executing check " check-name (.getMessage e))
      (store-result check-results check-name current-env (chk/->XRayCheckResult :error (.getMessage e))))))

(defn- start-the-xraychecks [{:keys [check-results checks environments]}]
  (log/info "Starting checks")
  (doseq [[check-name check] @checks]
    (doseq [current-env environments]
      (start-single-xraycheck check-results check current-env check-name))))

(defn- single-check-result-as-html [{:keys [status message time-taken stop-time]}]
  (let [text (str (time/from-long stop-time) " tt:" time-taken " " message)]
    [:div {:class (name status)} text]))

(defn- render-results-for-env [total-cols [env results]]
  (let [width (int (/ 97 total-cols))
        padding (int (/ 3 total-cols))]
    [:div {:class "env-results" :style (str "width: " width "%; padding-left: "padding"%;")}
     [:div {:class "env-header"} env]
     (map single-check-result-as-html (take steps-to-keep results))]))

(defn- check-results-as-html [[checkname results-for-env]]
  [:div {:class "check-results"}
   [:div {:class "check-header"} checkname]
   (map (partial render-results-for-env (count results-for-env)) results-for-env)])

(defn- html-response [{:keys [check-results]}]
  (hc/html5
    [:head
     [:meta {:charset "utf-8"}]
     [:title "XRayCheck Results"]
     (hc/include-css "/stylesheets/base.css")]
    [:body
     [:header
      [:h1 "XRayCheck Results"]]
     [:div {:class "check-result-container"}
      (map check-results-as-html @check-results)]]))

(defn- xray-routes [self endpoint]
  (comp/routes
    (croute/resources "/")
    (comp/GET endpoint []
      {:status  200
       :headers {"Content-Type" "text/html"}
       :body    (html-response self)})))

(defn- parse-rt-check-environments [config which-checker]
  (println (keyword (str which-checker "-environments")))
  (let [env-str (get-in config [:config (keyword (str which-checker "-check-environments"))] "default")]
    (clojure.string/split env-str #";")))

(defn- parse-refresh-frequency [config which-checker]
  (Integer/parseInt (get-in config [:config (keyword (str which-checker "-check-frequency"))] "60000")))

(defn- parse-rt-endpoint [config which-checker]
  (get-in config [:config (keyword (str which-checker "-check-endpoint"))] "/rt-checker"))

(defrecord XrayChecker [which-checker handler config registered-checks]
  c/Lifecycle
  (start [self]
    (log/info "-> starting XrayChecker")
    (let [executor (at/mk-pool)
          refresh-frequency (parse-refresh-frequency config which-checker)
          environments (parse-rt-check-environments config which-checker)
          endpoint (parse-rt-endpoint config which-checker)
          new-self (assoc self
                     :environments environments
                     :executor executor
                     :checks (atom {})
                     :check-results (atom {}))]
      (hndl/register-handler handler (xray-routes new-self endpoint))
      (log/info "running checks every " refresh-frequency "ms")
      (assoc new-self
        :schedule (at/every refresh-frequency
                            #(start-the-xraychecks new-self)
                            executor))))

  (stop [self]
    (log/info "<- stopping XrayChecker")
    (when-let [job (:schedule self)]
      (at/kill job))
    (at/stop-and-reset-pool! (:executor self))
    self)

  XRayCheckerProtocol
  (register-check [self check checkname]
    (log/info "registering check with name: " checkname)
    (swap! (:checks self) assoc checkname check)))

(defn new-xraychecker [which-checker]
  (map->XrayChecker {:which-checker which-checker}))
