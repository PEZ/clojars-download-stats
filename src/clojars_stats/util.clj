(ns clojars-stats.util
  "Shared utilities for timing, date handling, and testability."
  (:import [java.time LocalDate]
           [java.time.format DateTimeFormatter]))

;;; ============ Time Abstraction ============

(defn real-today
  "Get the actual current date as YYYYMMDD string."
  []
  (.format (LocalDate/now) (DateTimeFormatter/ofPattern "yyyyMMdd")))

(defn today
  "Get today's date as YYYYMMDD string.
   If config has :today, uses that; otherwise returns actual date."
  ([] (real-today))
  ([config] (or (:today config) (real-today))))

(defn yesterday
  "Get yesterday's date as YYYYMMDD string.
   If config has :today, calculates relative to that."
  ([] (yesterday {}))
  ([config]
   (let [today-str (today config)]
     (-> (LocalDate/parse today-str (DateTimeFormatter/ofPattern "yyyyMMdd"))
         (.minusDays 1)
         (.format (DateTimeFormatter/ofPattern "yyyyMMdd"))))))

;;; ============ Timing ============

(defmacro timed
  "Execute body and return [result elapsed-ms].
   Use for timing potentially slow operations."
  [& body]
  `(let [start# (System/currentTimeMillis)
         result# (do ~@body)
         elapsed# (- (System/currentTimeMillis) start#)]
     [result# elapsed#]))

(defn format-duration
  "Format milliseconds as human-readable duration."
  [ms]
  (cond
    (< ms 1000) (format "%dms" ms)
    (< ms 60000) (format "%.1fs" (/ ms 1000.0))
    :else (format "%dm %ds" (quot ms 60000) (quot (mod ms 60000) 1000))))

(defmacro with-timing
  "Execute body, print timing info with label, return result."
  [label & body]
  `(let [[result# elapsed#] (timed ~@body)]
     (println (format "  %s: %s" ~label (format-duration elapsed#)))
     result#))

^:rct/test
(comment
  ;; today with config
  (today {:today "20130101"})
  ;=> "20130101"

  ;; yesterday calculation
  (yesterday {:today "20130101"})
  ;=> "20121231"

  (yesterday {:today "20130301"})
  ;=> "20130228"

  ;; format-duration
  (format-duration 500)
  ;=> "500ms"

  (format-duration 1500)
  ;=> "1.5s"

  (format-duration 65000)
  ;=> "1m 5s"

  :rcf)
