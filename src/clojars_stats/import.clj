(ns clojars-stats.import
  "Import daily SQL files into database (fresh or incremental).
   Uses temp file with transaction wrapping for efficiency."
  (:require [clojure.java.io :as io]
            [babashka.process :as process]
            [clojars-stats.db :as db]
            [clojars-stats.export :as export]))

(defn- collect-daily-files
  "Collect all daily SQL files from data/YYYY/MM/DD.sql structure, sorted by date.
   Only includes files matching the year/month/day directory pattern.
   Config options: :data-dir"
  [config]
  (let [data-dir (or (:data-dir config) export/default-data-dir)
        dir (io/file data-dir)]
    (when (.exists dir)
      (->> (file-seq dir)
           (filter (fn [f]
                     (and (.isFile f)
                          (.endsWith (.getName f) ".sql")
                          ;; Verify parent structure is month/year
                          (let [parent (.getParentFile f)
                                grandparent (when parent (.getParentFile parent))]
                            (and parent grandparent
                                 (re-matches #"\d{2}" (.getName parent))
                                 (re-matches #"\d{4}" (.getName grandparent)))))))
           (sort-by #(.getPath %))))))

(defn- file->date
  "Extract date string (YYYYMMDD) from a daily SQL file path.
   Path format: data/YYYY/MM/DD.sql"
  [file]
  (let [day (.getName file)
        month (.getName (.getParentFile file))
        year (.getName (.getParentFile (.getParentFile file)))]
    (str year month (subs day 0 2))))

(defn- files-to-import
  "Determine which SQL files need importing.
   For fresh DB: all files. For existing DB: from latest date onwards."
  [db-path sql-files]
  (let [db-file (io/file db-path)]
    (if-not (.exists db-file)
      {:mode :fresh :files sql-files}
      (let [latest-date (db/get-latest-date db-path)]
        (if-not latest-date
          {:mode :fresh :files sql-files}
          (let [files-needed (filter #(>= (compare (file->date %) latest-date) 0)
                                     sql-files)]
            {:mode :incremental
             :latest-date latest-date
             :files files-needed}))))))

(defn import-all!
  "Import daily SQL files into database at db-path.
   Concatenates all files into a temp file with transaction wrapping,
   then feeds to sqlite3 CLI for efficient bulk import.
   If DB exists: incremental import (only dates from latest onwards).
   If DB doesn't exist: fresh import of all files.
   Config options: :data-dir, :progress-fn"
  [db-path & {:keys [progress-fn data-dir] :or {progress-fn println} :as config}]
  (let [sql-files (collect-daily-files config)
        {:keys [mode files latest-date]} (files-to-import db-path sql-files)
        data-dir (or data-dir export/default-data-dir)]
    (when (empty? sql-files)
      (throw (ex-info "No SQL files found" {:dir data-dir})))

    (case mode
      :fresh
      (do
        (progress-fn (format "Fresh import: %d daily files into %s..." (count files) db-path))
        (let [db-file (io/file db-path)]
          (when (.exists db-file)
            (.delete db-file)))
        (db/init-db! db-path))

      :incremental
      (do
        (progress-fn (format "Incremental import: %d files from %s onwards (DB has data through %s)"
                             (count files) latest-date latest-date))
        (db/delete-downloads-from-date! db-path latest-date)
        (progress-fn (format "  Cleared data from %s onwards" latest-date))))

    ;; Create temp file with transaction wrapping
    (let [temp-file (java.io.File/createTempFile "clojars-import" ".sql")
          total-files (count files)]
      (try
        (progress-fn (format "  Writing %d files to temp file..." total-files))
        (with-open [w (io/writer temp-file)]
          (.write w "BEGIN TRANSACTION;\n")
          (doseq [[i file] (map-indexed vector files)]
            (when (zero? (mod (inc i) 500))
              (progress-fn (format "    Progress: %d/%d files" (inc i) total-files)))
            (io/copy file w))
          (.write w "COMMIT;\n"))

        (let [temp-bytes (.length temp-file)]
          (progress-fn (format "  Importing %.1f MB..." (/ temp-bytes 1024.0 1024.0)))
          (let [result (process/shell {:in (io/input-stream temp-file)
                                       :err :string
                                       :continue true}
                                      "sqlite3" db-path)]
            (when (not= 0 (:exit result))
              (throw (ex-info "Import failed" {:error (:err result)}))))
          (progress-fn "Done!")
          {:mode mode
           :files total-files
           :bytes temp-bytes
           :stats (db/stats db-path)})
        (finally
          (.delete temp-file))))))

^:rct/test
(comment
  ;; file->date extracts YYYYMMDD from nested path structure
  (file->date (java.io.File. "data/2024/12/20.sql"))
  ;=> "20241220"

  ;; file->date works with different years/months
  (file->date (java.io.File. "data/2012/11/01.sql"))
  ;=> "20121101"

  :rcf)
