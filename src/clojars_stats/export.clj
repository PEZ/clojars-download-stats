(ns clojars-stats.export
  "Export database to daily SQL files in year/month/day.sql structure.
   Uses sqlite3 CLI for fast bulk export, then processes in memory."
  (:require [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as string]))

;; Default data directory
(def default-data-dir "data")

;;; ============ SQLite CLI Helpers ============

(defn- sqlite3-query
  "Run a SQL query via sqlite3 CLI, return lines of output."
  [db-path sql]
  (-> (p/shell {:out :string} "sqlite3" db-path sql)
      :out
      string/split-lines))

(defn- parse-artifact
  "Parse artifact line: id|group_id|artifact_id"
  [line]
  (let [[id group-id artifact-id] (string/split line #"\|")]
    [(parse-long id) {:group-id group-id :artifact-id artifact-id}]))

(defn- parse-version
  "Parse version line: id|version"
  [line]
  (let [[id version] (string/split line #"\|")]
    [(parse-long id) version]))

(defn- parse-download
  "Parse download line: date|artifact_id|version_id|downloads"
  [line]
  (let [[date artifact-id version-id downloads] (string/split line #"\|")]
    {:date date
     :artifact-id (parse-long artifact-id)
     :version-id (parse-long version-id)
     :downloads (parse-long downloads)}))

;;; ============ SQL Generation ============

(defn- escape-sql
  "Escape single quotes for SQL strings."
  [s]
  (string/replace (str s) "'" "''"))

(defn- write-day-file!
  "Write a single day's SQL file given pre-loaded lookups."
  [date day-downloads artifacts versions data-dir]
  (let [year (subs date 0 4)
        month (subs date 4 6)
        day (subs date 6 8)
        filename (str data-dir "/" year "/" month "/" day ".sql")
        artifact-ids (set (map :artifact-id day-downloads))
        version-ids (set (map :version-id day-downloads))]
    (io/make-parents filename)
    (with-open [w (io/writer filename)]
      ;; Header
      (.write w (format "-- %s-%s-%s\n" year month day))
      ;; Artifacts for this day
      (doseq [aid (sort artifact-ids)]
        (let [{:keys [group-id artifact-id]} (get artifacts aid)]
          (.write w (format "INSERT OR IGNORE INTO artifacts (id, group_id, artifact_id) VALUES (%d, '%s', '%s');\n"
                            aid (escape-sql group-id) (escape-sql artifact-id)))))
      ;; Versions for this day
      (doseq [vid (sort version-ids)]
        (let [version (get versions vid)]
          (.write w (format "INSERT OR IGNORE INTO versions (id, version) VALUES (%d, '%s');\n"
                            vid (escape-sql version)))))
      ;; Downloads
      (doseq [{:keys [artifact-id version-id downloads]} day-downloads]
        (.write w (format "INSERT OR REPLACE INTO downloads (date, artifact_id, version_id, downloads) VALUES ('%s', %d, %d, %d);\n"
                          date artifact-id version-id downloads))))
    {:date date :rows (count day-downloads)}))

;;; ============ Public API ============

(defn export-all!
  "Export all dates to daily SQL files using fast sqlite3 CLI dump.
   Loads all artifacts/versions once, then streams downloads grouped by date.
   Config options: :data-dir, :progress-fn
   Returns {:days count :total-downloads count}."
  [db-path & {:keys [progress-fn data-dir] :or {progress-fn println}}]
  (let [data-dir (or data-dir default-data-dir)]
    (progress-fn "Loading artifacts and versions...")
    (let [artifacts (into {} (map parse-artifact (sqlite3-query db-path "SELECT * FROM artifacts")))
          versions (into {} (map parse-version (sqlite3-query db-path "SELECT * FROM versions")))
          _ (progress-fn (format "  %d artifacts, %d versions" (count artifacts) (count versions)))
          _ (progress-fn "Loading downloads...")
          downloads (map parse-download (sqlite3-query db-path "SELECT * FROM downloads ORDER BY date"))
          by-date (group-by :date downloads)
          total-days (count by-date)]
      (progress-fn (format "Exporting %d days..." total-days))
      (doseq [[i [date day-downloads]] (map-indexed vector (sort-by first by-date))]
        (write-day-file! date day-downloads artifacts versions data-dir)
        (when (zero? (mod (inc i) 500))
          (progress-fn (format "  Progress: %d/%d days" (inc i) total-days))))
      (progress-fn "Export complete!")
      {:days total-days :total-downloads (count downloads)})))

(defn get-exported-dates
  "Get set of dates (YYYYMMDD strings) already exported to daily SQL files.
   Config options: :data-dir"
  ([] (get-exported-dates {}))
  ([config]
   (let [data-dir (or (:data-dir config) default-data-dir)
         dir (io/file data-dir)]
     (when (.exists dir)
       (->> (file-seq dir)
            (filter #(and (.isFile %)
                          (.endsWith (.getName %) ".sql")))
            (map (fn [f]
                   (let [day (.getName f)
                         month (.getName (.getParentFile f))
                         year (.getName (.getParentFile (.getParentFile f)))]
                     (str year month (subs day 0 2)))))
            set)))))

^:rct/test
(comment
  ;; parse-artifact parses pipe-delimited line
  (parse-artifact "42|reagent|reagent")
  ;=> [42 {:group-id "reagent", :artifact-id "reagent"}]

  ;; parse-version parses pipe-delimited line
  (parse-version "99|1.2.3")
  ;=> [99 "1.2.3"]

  ;; parse-download parses all fields
  (parse-download "20241220|1|2|500")
  ;=> {:date "20241220", :artifact-id 1, :version-id 2, :downloads 500}

  ;; escape-sql escapes single quotes
  (escape-sql "O'Reilly")
  ;=> "O''Reilly"

  ;; escape-sql handles nil gracefully
  (escape-sql nil)
  ;=> ""

  :rcf)
