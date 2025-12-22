(ns clojars-stats.tasks
  "Babashka task implementations.

   All tasks accept an optional --today YYYYMMDD flag to override
   the current date for testing purposes.

   Example: bb fetch --today 20130215 ./test.sqlite"
  (:require [babashka.cli :as cli]
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

(defn ^:export import-db
  "Import SQL files into a new database.
   Usage: bb import <db-path>"
  [args]
  (let [{:keys [args]} (parse-args args)
        db-path (require-db-path args "import")]
    (util/with-timing "Import"
      (import-ns/import-all! db-path))))

(defn ^:export export-db
  "Export database to daily SQL files.
   Usage: bb export <db-path>"
  [args]
  (let [{:keys [args]} (parse-args args)
        db-path (require-db-path args "export")]
    (util/with-timing "Export"
      (export/export-all! db-path))))

(defn ^:export fetch-missing
  "Fetch missing dates from Clojars.
   Usage: bb fetch <db-path>"
  [args]
  (let [{:keys [args]} (parse-args args)
        db-path (require-db-path args "fetch")]
    (db/init-db! db-path)
    (util/with-timing "Fetch"
      (fetch/fetch-and-store! db-path))))

(defn ^:export update-and-export
  "Fetch missing dates and re-export all (for CI with DB).
   Usage: bb update <db-path>"
  [args]
  (let [{:keys [args]} (parse-args args)
        db-path (require-db-path args "update")]
    (db/init-db! db-path)
    (let [before-latest (db/get-latest-date db-path)]
      (util/with-timing "Fetch"
        (fetch/fetch-and-store! db-path))
      (let [after-latest (db/get-latest-date db-path)]
        (when (and after-latest (not= before-latest after-latest))
          (util/with-timing "Export"
            (export/export-all! db-path)))))))

(defn ^:export status
  "Show database and export status.
   Usage: bb status <db-path>"
  [args]
  (let [{:keys [args]} (parse-args args)
        db-path (require-db-path args "status")
        db-stats (try (db/stats db-path) (catch Exception _ nil))
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
    (if missing
      (println (format "  %d dates missing (fetch to update)" (count missing)))
      (println "  Up to date with Clojars"))

    (println)))

;;; ============ State-Based CI Tasks (no database required) ============

(defn ^:export generate-state
  "Generate state.edn from an existing database.
   Usage: bb generate-state <db-path>"
  [args]
  (let [{:keys [args]} (parse-args args)
        db-path (require-db-path args "generate-state")]
    (util/with-timing "Generate state"
      (state/generate-state-from-db! db-path))))

(defn ^:export update-day
  "Fetch a single day and append to exports (no database required).
   Uses state.edn for ID mappings.
   Usage: bb update-day <date>"
  [args]
  (let [{:keys [args]} (parse-args args)
        date-str (first args)]
    (when-not date-str
      (println "Usage: bb update-day <date>")
      (println "Example: bb update-day 20251221")
      (System/exit 1))
    (util/with-timing "Update day"
      (state/update-daily! date-str))))

(defn ^:export update-latest
  "Fetch all missing dates from Clojars up to yesterday (no database required).
   Fills any gaps since last update. Idempotent - safe for CI cron jobs.
   Usage: bb update-latest"
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

(defn ^:export state-status
  "Show state.edn status (for CI debugging).
   Usage: bb state-status"
  [args]
  (let [_ (parse-args args)]  ; Still parse for potential future options
    (if-let [s (state/load-state)]
      (do
        (println "\n=== State Status ===")
        (println (format "  Artifacts:       %,d" (count (:artifacts s))))
        (println (format "  Versions:        %,d" (count (:versions s))))
        (println (format "  Next artifact ID: %d" (:next-artifact-id s)))
        (println (format "  Next version ID:  %d" (:next-version-id s)))
        (println (format "  Latest date:      %s" (:latest-date s)))
        (println))
      (println "No state.edn found. Run 'bb generate-state <db>' to create one."))))
