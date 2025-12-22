(ns clojars-stats.db
  "SQLite database operations for Clojars download statistics.

   Uses babashka go-sqlite3 pod for SQLite access.
   The pod uses db-path strings directly, not datasources.

   Normalized schema:
   - artifacts: (id, group_id, artifact_id)
   - versions: (id, version)
   - downloads: (date, artifact_id, version_id, downloads)"
  (:require [babashka.pods :as pods]))

(pods/load-pod 'org.babashka/go-sqlite3 "0.3.13")
(require '[pod.babashka.go-sqlite3 :as sqlite])

(defn init-db!
  "Initialize the normalized database schema."
  [db-path]
  (sqlite/execute! db-path
                   ["CREATE TABLE IF NOT EXISTS artifacts (
                       id INTEGER PRIMARY KEY,
                       group_id TEXT NOT NULL,
                       artifact_id TEXT NOT NULL,
                       UNIQUE(group_id, artifact_id))"])
  (sqlite/execute! db-path
                   ["CREATE TABLE IF NOT EXISTS versions (
                       id INTEGER PRIMARY KEY,
                       version TEXT NOT NULL UNIQUE)"])
  (sqlite/execute! db-path
                   ["CREATE TABLE IF NOT EXISTS downloads (
                       date TEXT NOT NULL,
                       artifact_id INTEGER NOT NULL,
                       version_id INTEGER NOT NULL,
                       downloads INTEGER NOT NULL,
                       PRIMARY KEY (date, artifact_id, version_id),
                       FOREIGN KEY (artifact_id) REFERENCES artifacts(id),
                       FOREIGN KEY (version_id) REFERENCES versions(id))"])
  (sqlite/execute! db-path
                   ["CREATE INDEX IF NOT EXISTS idx_downloads_date
                     ON downloads(date)"])
  (sqlite/execute! db-path
                   ["CREATE INDEX IF NOT EXISTS idx_downloads_artifact
                     ON downloads(artifact_id)"]))

;;; ============ Lookup Helpers ============

(defn- get-or-create-artifact-id!
  "Get existing artifact ID or insert and return new ID."
  [db-path group-id artifact-id]
  (or (:id (first (sqlite/query db-path
                                ["SELECT id FROM artifacts WHERE group_id = ? AND artifact_id = ?"
                                 group-id artifact-id])))
      (do (sqlite/execute! db-path
                           ["INSERT INTO artifacts (group_id, artifact_id) VALUES (?, ?)"
                            group-id artifact-id])
          (:id (first (sqlite/query db-path ["SELECT last_insert_rowid() as id"]))))))

(defn- get-or-create-version-id!
  "Get existing version ID or insert and return new ID."
  [db-path version]
  (or (:id (first (sqlite/query db-path
                                ["SELECT id FROM versions WHERE version = ?" version])))
      (do (sqlite/execute! db-path
                           ["INSERT INTO versions (version) VALUES (?)" version])
          (:id (first (sqlite/query db-path ["SELECT last_insert_rowid() as id"]))))))

;;; ============ Writing Data ============

(defn store-daily-downloads!
  "Store a day's Clojars data in normalized form.
   raw-data: {[group artifact] {version downloads}}

   Note: The go-sqlite3 pod doesn't support transactions, so this
   performs individual inserts. For bulk imports, use SQL files."
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

;;; ============ Reading Data ============

(defn get-latest-date
  "Get the most recent date in the downloads table."
  [db-path]
  (:date (first (sqlite/query db-path ["SELECT MAX(date) as date FROM downloads"]))))

(defn get-earliest-date
  "Get the earliest date in the downloads table."
  [db-path]
  (:date (first (sqlite/query db-path ["SELECT MIN(date) as date FROM downloads"]))))

(defn get-dates-in-month
  "Get all dates in the database for a given month (YYYYMM)."
  [db-path month]
  (->> (sqlite/query db-path
                     ["SELECT DISTINCT date FROM downloads WHERE date LIKE ? ORDER BY date"
                      (str month "%")])
       (map :date)))

(defn get-all-months
  "Get all months that have data in the database."
  [db-path]
  (->> (sqlite/query db-path
                     ["SELECT DISTINCT substr(date, 1, 6) as month FROM downloads ORDER BY month"])
       (map :month)))

(defn stats
  "Get database statistics."
  [db-path]
  {:artifacts (:cnt (first (sqlite/query db-path ["SELECT COUNT(*) as cnt FROM artifacts"])))
   :versions (:cnt (first (sqlite/query db-path ["SELECT COUNT(*) as cnt FROM versions"])))
   :download-rows (:cnt (first (sqlite/query db-path ["SELECT COUNT(*) as cnt FROM downloads"])))
   :date-range {:earliest (get-earliest-date db-path)
                :latest (get-latest-date db-path)}})

;;; ============ Export Helpers ============

(defn get-all-artifacts
  "Get all artifacts from the database."
  [db-path]
  (sqlite/query db-path ["SELECT id, group_id, artifact_id FROM artifacts ORDER BY id"]))

(defn get-all-versions
  "Get all versions from the database."
  [db-path]
  (sqlite/query db-path ["SELECT id, version FROM versions ORDER BY id"]))

(defn get-downloads-for-month
  "Get all download records for a specific month (YYYYMM)."
  [db-path month]
  (sqlite/query db-path
                ["SELECT date, artifact_id, version_id, downloads
                  FROM downloads
                  WHERE date LIKE ?
                  ORDER BY date, artifact_id, version_id"
                 (str month "%")]))

(defn execute-sql!
  "Execute raw SQL statement."
  [db-path sql]
  (sqlite/execute! db-path [sql]))

;;; ============ Maintenance ============

(defn delete-downloads-from-month!
  "Delete all download records from a month onwards.
   Used for incremental import to avoid duplicates."
  [db-path month]
  (sqlite/execute! db-path
                   ["DELETE FROM downloads WHERE date >= ?" (str month "01")]))

(defn delete-downloads-from-date!
  "Delete all download records from a date onwards.
   Used for incremental import to avoid duplicates."
  [db-path date]
  (sqlite/execute! db-path
                   ["DELETE FROM downloads WHERE date >= ?" date]))
