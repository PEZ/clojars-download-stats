(ns clojars-stats.fetch
  "Fetch download statistics from Clojars."
  (:require [babashka.http-client :as http]
            [clojure.edn :as edn]
            [clojars-stats.db :as db]
            [clojars-stats.util :as util])
  (:import [java.time LocalDate]
           [java.time.format DateTimeFormatter]))

(def clojars-start-date "20121101")

(def ^:private date-fmt (DateTimeFormatter/ofPattern "yyyyMMdd"))

(defn- yesterday-str []
  (util/yesterday))

(defn- next-day [date-str]
  (.format (.plusDays (LocalDate/parse date-str date-fmt) 1) date-fmt))

(defn- dates-range
  "Generate date strings from start to end (inclusive)."
  [start end]
  (loop [current start
         result []]
    (if (> (compare current end) 0)
      result
      (recur (next-day current) (conj result current)))))

(defn fetch-daily-stats
  "Fetch download stats for a date (YYYYMMDD) from Clojars.
   Returns EDN map or nil on error."
  [date-str]
  (let [url (str "https://repo.clojars.org/stats/downloads-" date-str ".edn")]
    (try
      (-> (http/get url)
          :body
          edn/read-string)
      (catch Exception e
        (println "Failed to fetch" date-str ":" (.getMessage e))
        nil))))

(defn find-missing-dates
  "Find dates not yet in database."
  [db-path]
  (let [latest (db/get-latest-date db-path)
        start (if latest (next-day latest) clojars-start-date)
        end (yesterday-str)]
    (when (<= (compare start end) 0)
      (dates-range start end))))

(defn fetch-and-store!
  "Fetch missing dates from Clojars and store in database.
   Returns count of dates fetched."
  [db-path & {:keys [progress-fn] :or {progress-fn println}}]
  (let [missing (find-missing-dates db-path)
        total (count missing)]
    (if (zero? total)
      (do (progress-fn "Database is up to date!")
          0)
      (do
        (progress-fn (format "Fetching %d dates from Clojars..." total))
        (loop [remaining missing
               fetched 0]
          (if (empty? remaining)
            (do (progress-fn (format "Done! Fetched %d dates." fetched))
                fetched)
            (let [date (first remaining)]
              (Thread/sleep 100) ; Be nice to Clojars
              (when-let [data (fetch-daily-stats date)]
                (db/store-daily-downloads! db-path date data))
              (when (zero? (mod (inc fetched) 100))
                (progress-fn (format "  Progress: %d/%d" (inc fetched) total)))
              (recur (rest remaining) (inc fetched)))))))))

^:rct/test
(comment
  ;; next-day increments date correctly
  (next-day "20241220")
  ;=> "20241221"

  ;; next-day handles month boundary
  (next-day "20241231")
  ;=> "20250101"

  ;; next-day handles February
  (next-day "20240228")
  ;=> "20240229"  ; 2024 is leap year

  ;; dates-range generates inclusive range
  (dates-range "20241220" "20241223")
  ;=> ["20241220" "20241221" "20241222" "20241223"]

  ;; dates-range with same start/end returns single date
  (dates-range "20241220" "20241220")
  ;=> ["20241220"]

  ;; dates-range empty when start > end
  (dates-range "20241225" "20241220")
  ;=> []

  :rcf)
