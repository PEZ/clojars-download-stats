(ns clojars-stats.tasks
  "Babashka task implementations.

   All tasks accept an optional --today YYYYMMDD flag to override
   the current date for testing purposes.

   Example: bb clojars.fetch --today 20130215 ./test.sqlite"
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [clojars-stats.db :as db]
            [clojars-stats.export :as export]
            [clojars-stats.fetch :as fetch]
            [clojars-stats.import :as import-ns]
            [clojars-stats.state :as state]
            [clojars-stats.util :as util]))

(def cli-spec
  "Common CLI options for all tasks."
  {:today {:desc "Override today's date (YYYYMMDD) for testing"
           :alias :t
           :coerce :string}})

(defn- parse-args
  "Parse command line args. Returns {:opts {:today ...} :args [positional...]}."
  [args]
  (cli/parse-args args {:spec cli-spec}))

(defn- require-db-path
  "Extract db-path from positional args, exit with usage if missing."
  [args task-name]
  (let [db-path (first args)]
    (when-not db-path
      (println (format "Usage: bb %s [--today YYYYMMDD] <db-path>" task-name))
      (println (format "Example: bb %s ./clojars.sqlite" task-name))
      (println (format "         bb %s --today 20130215 ./test.sqlite" task-name))
      (System/exit 1))
    db-path))

(defn- confirm-new-db!
  "Prompt user to confirm creating a new database. Exits if declined."
  [db-path]
  (when-not (fs/exists? db-path)
    (print (format "Database '%s' does not exist. Create it? [y/N] " db-path))
    (flush)
    (let [response (read-line)]
      (when-not (contains? #{"y" "Y" "yes" "Yes"} response)
        (println "Aborted.")
        (System/exit 0)))))

(defn ^:export import-db
  "Import SQL files into a new database.
   Usage: bb files.import <db-path>"
  [args]
  (let [{:keys [args]} (parse-args args)
        db-path (require-db-path args "files.import")]
    (confirm-new-db! db-path)
    (util/with-timing "Import"
      (import-ns/import-all! db-path))))

(defn ^:export export-db
  "Export database to daily SQL files.
   Usage: bb db.export <db-path>"
  [args]
  (let [{:keys [args]} (parse-args args)
        db-path (require-db-path args "db.export")]
    (util/with-timing "Export"
      (export/export-all! db-path))))

(defn ^:export fetch-missing
  "Fetch missing dates from Clojars.
   Usage: bb clojars.fetch <db-path>"
  [args]
  (let [{:keys [args]} (parse-args args)
        db-path (require-db-path args "clojars.fetch")]
    (confirm-new-db! db-path)
    (db/init-db! db-path)
    (util/with-timing "Fetch"
      (fetch/fetch-and-store! db-path))))

(defn ^:export update-and-export
  "Fetch missing dates and re-export all (for CI with DB).
   Usage: bb db.export.update <db-path>"
  [args]
  (let [{:keys [args]} (parse-args args)
        db-path (require-db-path args "db.export.update")]
    (db/init-db! db-path)
    (let [before-latest (db/get-latest-date db-path)]
      (util/with-timing "Fetch"
        (fetch/fetch-and-store! db-path))
      (let [after-latest (db/get-latest-date db-path)]
        (when (and after-latest (not= before-latest after-latest))
          (util/with-timing "Export"
            (export/export-all! db-path)))))))

(defn ^:export status
  "Show status. Without args: state.edn status. With db-path: database status.
   Usage: bb db.export.status [<db-path>]"
  [args]
  (let [{:keys [args]} (parse-args args)
        db-path (first args)]
    (if db-path
      ;; Database status
      (let [db-stats (try (db/stats db-path) (catch Exception _ nil))
            exported (export/get-exported-dates)
            missing (try (fetch/find-missing-dates db-path) (catch Exception _ nil))]

        (println "\n=== Database Status ===")
        (if db-stats
          (do
            (println (format "  Artifacts: %,d" (:artifacts db-stats)))
            (println (format "  Versions:  %,d" (:versions db-stats)))
            (println (format "  Downloads: %,d rows" (:download-rows db-stats)))
            (println (format "  Date range: %s to %s"
                             (get-in db-stats [:date-range :earliest])
                             (get-in db-stats [:date-range :latest]))))
          (println "  Database not found or empty"))

        (println "\n=== Export Status ===")
        (if (seq exported)
          (let [sorted-dates (sort exported)]
            (println (format "  %d daily files exported (%s to %s)"
                             (count exported) (first sorted-dates) (last sorted-dates))))
          (println "  No exports found"))

        (println "\n=== Clojars Sync ===")
        (if (seq missing)
          (println (format "  %d dates pending (%s to %s) - may not be available on Clojars yet"
                           (count missing) (first missing) (last missing)))
          (println "  Up to date through yesterday"))

        (println))

      ;; State status (no db-path)
      (if-let [s (state/load-state)]
        (do
          (println "\n=== State Status ===")
          (println (format "  Artifacts:       %,d" (count (:artifacts s))))
          (println (format "  Versions:        %,d" (count (:versions s))))
          (println (format "  Next artifact ID: %d" (:next-artifact-id s)))
          (println (format "  Next version ID:  %d" (:next-version-id s)))
          (println (format "  Latest date:      %s" (:latest-date s)))
          (println))
        (println "No state.edn found. Run 'bb db.export.generate-state <db>' to create one.")))))

;;; ============ State-Based CI Tasks (no database required) ============

(defn ^:export generate-state
  "Generate state.edn from an existing database.
   Usage: bb db.export.generate-state <db-path>"
  [args]
  (let [{:keys [args]} (parse-args args)
        db-path (require-db-path args "db.export.generate-state")]
    (util/with-timing "Generate state"
      (state/generate-state-from-db! db-path))))

(defn ^:export update-day
  "Fetch a single day and append to exports (no database required).
   Uses state.edn for ID mappings.
   Usage: bb clojars.export.day <date>"
  [args]
  (let [{:keys [args]} (parse-args args)
        date-str (first args)]
    (when-not date-str
      (println "Usage: bb clojars.export.day <date>")
      (println "Example: bb clojars.export.day 20251221")
      (System/exit 1))
    (util/with-timing "Update day"
      (state/update-daily! date-str))))

(defn ^:export update-latest
  "Fetch all missing dates from Clojars up to yesterday (no database required).
   Fills any gaps since last update. Idempotent - safe for CI cron jobs.
   Usage: bb clojars.export.update"
  [args]
  (let [_ (parse-args args)
        yesterday (util/yesterday)
        latest-in-state (:latest-date (state/load-state))]
    (if (and latest-in-state (>= (compare latest-in-state yesterday) 0))
      (println (format "Already up to date (latest: %s)" latest-in-state))
      (let [start (if latest-in-state
                    (util/next-day latest-in-state)
                    yesterday)  ; If no state, just fetch yesterday
            dates-to-fetch (util/dates-range start yesterday)]
        (println (format "Fetching %d missing date(s): %s to %s"
                         (count dates-to-fetch) start yesterday))
        (doseq [date dates-to-fetch]
          (util/with-timing (format "Fetch %s" date)
            (state/update-daily! date)))))))
