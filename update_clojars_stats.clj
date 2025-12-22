#!/usr/bin/env bb
;; Standalone Clojars download statistics updater
;;
;; Updates a clojars.sqlite database with the latest download stats from Clojars.
;; No repository clone required - just this script and your database.
;;
;; Usage:
;;   bb update_clojars_stats.clj <path-to-clojars.sqlite>
;;
;; Example:
;;   bb update_clojars_stats.clj ~/data/clojars.sqlite
;;
;; Prerequisites:
;;   - Babashka (https://babashka.org)
;;   - An existing clojars.sqlite database (from initial import)

(require '[babashka.pods :as pods]
         '[babashka.http-client :as http]
         '[clojure.edn :as edn])

;; Guard against double-loading when used via load-file in tests
(when-not (find-ns 'pod.babashka.go-sqlite3)
  (pods/load-pod 'org.babashka/go-sqlite3 "0.3.13"))
(require '[pod.babashka.go-sqlite3 :as sqlite])

;;; ============ Date Utilities ============

(defn- parse-date [date-str]
  (java.time.LocalDate/parse
   date-str
   (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd")))

(defn- format-date [local-date]
  (.format local-date (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd")))

(defn- next-day [date-str]
  (-> (parse-date date-str)
      (.plusDays 1)
      format-date))

(defn- yesterday []
  (-> (java.time.LocalDate/now)
      (.minusDays 1)
      format-date))

(defn- dates-range [start-date end-date]
  (loop [current start-date
         dates []]
    (if (pos? (compare current end-date))
      dates
      (recur (next-day current) (conj dates current)))))

;;; ============ Database Operations ============

(defn- get-or-create-artifact-id!
  [db-path group-id artifact-id]
  (or (:id (first (sqlite/query db-path
                                ["SELECT id FROM artifacts WHERE group_id = ? AND artifact_id = ?"
                                 group-id artifact-id])))
      (do (sqlite/execute! db-path
                           ["INSERT INTO artifacts (group_id, artifact_id) VALUES (?, ?)"
                            group-id artifact-id])
          (:id (first (sqlite/query db-path ["SELECT last_insert_rowid() as id"]))))))

(defn- get-or-create-version-id!
  [db-path version]
  (or (:id (first (sqlite/query db-path
                                ["SELECT id FROM versions WHERE version = ?" version])))
      (do (sqlite/execute! db-path
                           ["INSERT INTO versions (version) VALUES (?)" version])
          (:id (first (sqlite/query db-path ["SELECT last_insert_rowid() as id"]))))))

(defn- store-daily-downloads!
  [db-path date raw-data]
  (doseq [[coords version-map] raw-data
          :let [[group-id artifact-id] coords
                art-id (get-or-create-artifact-id! db-path group-id artifact-id)]
          [version dl-count] version-map
          :let [ver-id (get-or-create-version-id! db-path version)]]
    (sqlite/execute! db-path
                     ["INSERT OR REPLACE INTO downloads (date, artifact_id, version_id, downloads)
                       VALUES (?, ?, ?, ?)"
                      date art-id ver-id dl-count])))

(defn- get-latest-date [db-path]
  (:date (first (sqlite/query db-path ["SELECT MAX(date) as date FROM downloads"]))))

;;; ============ Clojars Fetch ============

(defn- make-fetch-fn
  "Returns a fetch function. If mock-data is provided, returns that for any date.
   Otherwise fetches from Clojars."
  [mock-data]
  (if mock-data
    (fn [_date-str] mock-data)
    (fn [date-str]
      (let [url (str "https://repo.clojars.org/stats/downloads-" date-str ".edn")]
        (try
          (-> (http/get url)
              :body
              edn/read-string)
          (catch Exception e
            (println "  Failed to fetch" date-str ":" (.getMessage e))
            nil))))))

;;; ============ Main ============

(defn update-database!
  [db-path & {:keys [mock-data]}]
  (let [fetch-fn (make-fetch-fn mock-data)
        latest (get-latest-date db-path)
        end (yesterday)
        start (if latest (next-day latest) end)]
    (if (pos? (compare start end))
      (println (format "Database is up to date (latest: %s)" latest))
      (let [dates (dates-range start end)
            total (count dates)]
        (println (format "Fetching %d date(s): %s to %s" total start end))
        (loop [remaining dates
               fetched 0
               failed 0]
          (if (empty? remaining)
            (do
              (println (format "Done! Fetched %d, failed %d" fetched failed))
              {:fetched fetched :failed failed})
            (let [date (first remaining)
                  _ (print (format "  %s... " date))
                  _ (flush)
                  _ (when-not mock-data (Thread/sleep 100)) ; Be nice to Clojars
                  data (fetch-fn date)]
              (if data
                (do
                  (store-daily-downloads! db-path date data)
                  (println (format "%d artifacts" (count data)))
                  (recur (rest remaining) (inc fetched) failed))
                (recur (rest remaining) fetched (inc failed))))))))))

(defn -main [& args]
  (let [db-path (first args)]
    (when-not db-path
      (println "Usage: bb update_clojars_stats.clj <path-to-clojars.sqlite>")
      (println "Example: bb update_clojars_stats.clj ~/data/clojars.sqlite")
      (System/exit 1))
    (when-not (.exists (java.io.File. db-path))
      (println (format "Error: Database '%s' not found." db-path))
      (println "Run the initial import first using the clojars-download-stats repository.")
      (System/exit 1))
    (update-database! db-path)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
