(ns clojars-stats.e2e-test
  "End-to-end tests using the test database and bb tasks.

   These tests shell out to `bb` to run actual tasks, simulating
   real CI/user workflows."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojars-stats.db :as db]
            [clojars-stats.export :as export]
            [clojars-stats.import :as import-ns]
            [clojars-stats.state :as state]
            [clojars-stats.util :as util]
            [clojure.string :as string]))

;;; ============ Test Infrastructure ============

(def test-db "test-data/test.sqlite")
(def temp-dir "test-data/temp")

(defn- bb
  "Run a bb task and return {:exit :out :err}.
   Each arg is passed separately to the shell."
  [& args]
  (let [result (apply process/shell
                      {:dir "."
                       :out :string
                       :err :string
                       :continue true}
                      "bb" args)]
    {:exit (:exit result)
     :out (:out result)
     :err (:err result)}))

(defn- setup-temp-dir! []
  (when (fs/exists? temp-dir)
    (fs/delete-tree temp-dir))
  (fs/create-dirs temp-dir))

(defn- teardown-temp-dir! []
  (when (fs/exists? temp-dir)
    (fs/delete-tree temp-dir)))

(defn- assert=
  "Simple assertion helper."
  [expected actual msg]
  (if (= expected actual)
    (println (format "  ✓ %s" msg))
    (do
      (println (format "  ✗ %s" msg))
      (println (format "    Expected: %s" (pr-str expected)))
      (println (format "    Actual:   %s" (pr-str actual)))
      (throw (ex-info "Assertion failed" {:expected expected :actual actual})))))

(defn- assert-success
  "Assert that a bb command succeeded."
  [{:keys [exit out err]} msg]
  (if (= 0 exit)
    (println (format "  ✓ %s" msg))
    (do
      (println (format "  ✗ %s" msg))
      (println (format "    Exit code: %d" exit))
      (println (format "    Stderr: %s" err))
      (throw (ex-info "Command failed" {:exit exit :err err})))))

(defmacro timed
  "Execute body and print elapsed time with label."
  [label & body]
  `(let [start# (System/currentTimeMillis)
         result# (do ~@body)
         elapsed# (- (System/currentTimeMillis) start#)]
     (println (format "  [%s: %dms]" ~label elapsed#))
     result#))

;;; ============ Test Cases ============

(defn test-export-import-roundtrip
  "Test that export → import produces identical database."
  []
  (println "\n=== Test: Export/Import Round-trip ===")
  (setup-temp-dir!)
  (try
    ;; Get original stats
    (let [original-stats (db/stats test-db)
          export-data-dir (str temp-dir "/data")]
      (println (format "  Original DB: %d artifacts, %d versions, %d downloads"
                       (:artifacts original-stats)
                       (:versions original-stats)
                       (:download-rows original-stats)))
      ;; Export
      (let [sql-files (timed "Export"
                        (export/export-all! test-db :data-dir export-data-dir :progress-fn (fn [_]))
                        (->> (fs/glob export-data-dir "**/*.sql")
                             (filter #(re-matches #"\d{4}/\d{2}/\d{2}\.sql"
                                                  (str (fs/relativize export-data-dir %))))
                             count))]
        (println (format "  Exported %d daily files" sql-files)))
      ;; Import into new database from temp export dir
      (let [reimport-db (str temp-dir "/reimported.sqlite")
            _ (timed "Import" (import-ns/import-all! reimport-db :data-dir export-data-dir :progress-fn (fn [_])))
            reimport-stats (db/stats reimport-db)]
        ;; Verify stats match
        (assert= (:artifacts original-stats) (:artifacts reimport-stats)
                 "Artifact count matches")
        (assert= (:versions original-stats) (:versions reimport-stats)
                 "Version count matches")
        (assert= (:download-rows original-stats) (:download-rows reimport-stats)
                 "Download count matches")
        (assert= (get-in original-stats [:date-range :earliest])
                 (get-in reimport-stats [:date-range :earliest])
                 "Earliest date matches")
        (assert= (get-in original-stats [:date-range :latest])
                 (get-in reimport-stats [:date-range :latest])
                 "Latest date matches")))
    (finally
      (teardown-temp-dir!))))

(defn test-status-command
  "Test the status command shows correct info."
  []
  (println "\n=== Test: Status Command ===")

  (let [result (bb "db.export.status" test-db)]
    (assert-success result "bb db.export.status exits successfully")
    (assert= true (string/includes? (:out result) "4,000")
             "Status shows correct artifact count")
    (assert= true (string/includes? (:out result) "142,339")
             "Status shows correct download count")))

(defn test-time-injection
  "Test that date injection works for deterministic tests via config maps."
  []
  (println "\n=== Test: Time Injection ===")

  ;; Test with config map
  (let [config {:today "20130101"}]
    (assert= "20130101" (util/today config) "Today returns fixed date from config")
    (assert= "20121231" (util/yesterday config) "Yesterday returns day before fixed date"))

  ;; Without config uses real date
  (assert= true (not= "20130101" (util/today)) "Without config, today is real date"))

(defn test-ci-workflow
  "Test the CI daily update workflow without hitting Clojars.
   Simulates: generate-state → update-daily with mock fetch."
  []
  (println "\n=== Test: CI Workflow (mocked) ===")
  (setup-temp-dir!)
  (try
    (let [temp-data-dir (str temp-dir "/ci-data")
          temp-state-file (str temp-data-dir "/state.edn")
          ;; Mock Clojars data: one existing artifact, one new
          mock-clojars-data {["reagent" "reagent"] {"1.2.0" 100 "1.1.0" 50}
                             ["new-lib" "new-lib"] {"0.1.0" 25}}
          config {:state-file temp-state-file
                  :data-dir temp-data-dir
                  :fetch-fn (fn [_date] mock-clojars-data)
                  :progress-fn (fn [_])}]

      ;; Generate initial state from test database
      (println "  Generating state from test DB...")
      (timed "Generate state"
        (state/generate-state-from-db! test-db :state-file temp-state-file :progress-fn (fn [_])))

      ;; Verify state was created
      (let [initial-state (state/load-state config)]
        (assert= true (> (count (:artifacts initial-state)) 0)
                 "State has artifacts from DB")
        (assert= true (> (count (:versions initial-state)) 0)
                 "State has versions from DB")

        ;; Now run update-daily with mocked fetch
        (println "  Running update-daily with mock data...")
        (timed "Update daily"
          (state/update-daily! "20251221"
                               :state-file temp-state-file
                               :data-dir temp-data-dir
                               :fetch-fn (fn [_date] mock-clojars-data)
                               :progress-fn (fn [_])))

        ;; Verify SQL file was created
        (let [sql-file (str temp-data-dir "/2025/12/21.sql")]
          (assert= true (fs/exists? sql-file)
                   "Daily SQL file created")

          ;; Verify content has expected inserts
          (let [sql-content (slurp sql-file)]
            (assert= true (string/includes? sql-content "INSERT")
                     "SQL file contains INSERT statements")
            (assert= true (string/includes? sql-content "downloads")
                     "SQL file contains downloads table inserts")))

        ;; Verify state was updated
        (let [updated-state (state/load-state config)]
          (assert= "20251221" (:latest-date updated-state)
                   "State latest-date updated")
          ;; new-lib should have been added
          (assert= true (contains? (:artifacts updated-state) ["new-lib" "new-lib"])
                   "New artifact added to state"))))
    (finally
      (teardown-temp-dir!))))

(defn test-standalone-update-script
  "Test the standalone update script for ejected users.
   Loads the script and calls update-database! directly with mock-data."
  []
  (println "\n=== Test: Standalone Update Script ===")
  (setup-temp-dir!)
  (try
    (let [temp-db (str temp-dir "/standalone-test.sqlite")
          old-date "20251201"
          mock-data {["test-lib" "test-lib"] {"1.0.0" 42 "2.0.0" 100}
                     ["another" "lib"] {"0.1.0" 5}}]

      ;; Create a minimal database with old date
      (db/init-db! temp-db)
      (db/execute-sql! temp-db "INSERT INTO artifacts (id, group_id, artifact_id) VALUES (1, 'existing', 'lib')")
      (db/execute-sql! temp-db "INSERT INTO versions (id, version) VALUES (1, '1.0.0')")
      (db/execute-sql! temp-db (format "INSERT INTO downloads (date, artifact_id, version_id, downloads) VALUES ('%s', 1, 1, 50)" old-date))

      ;; Verify initial state
      (let [initial-stats (db/stats temp-db)]
        (assert= 1 (:artifacts initial-stats) "Initial: 1 artifact")
        (assert= 1 (:download-rows initial-stats) "Initial: 1 download row"))

      ;; Load and call the standalone script's update function with mock data
      (load-file "update_clojars_stats.clj")
      (let [update-fn (resolve 'update-database!)]
        (update-fn temp-db :mock-data mock-data)

        ;; Verify data was actually inserted
        (let [final-stats (db/stats temp-db)]
          (assert= true (> (:artifacts final-stats) 1)
                   "New artifacts were inserted")
          (assert= true (> (:download-rows final-stats) 1)
                   "New download rows were inserted"))

        ;; Running again should show up-to-date (idempotent)
        (let [output (with-out-str (update-fn temp-db :mock-data mock-data))]
          (assert= true (string/includes? output "up to date")
                   "Second run reports up to date")))

      ;; Test CLI: Script shows usage with no args
      (let [result (bb "update_clojars_stats.clj")]
        (assert= 1 (:exit result) "Exits with code 1 when no args")
        (assert= true (string/includes? (:out result) "Usage:")
                 "Shows usage message"))

      ;; Test CLI: Script errors on missing database
      (let [result (bb "update_clojars_stats.clj" "/nonexistent/path.sqlite")]
        (assert= 1 (:exit result) "Exits with code 1 for missing DB")
        (assert= true (string/includes? (:out result) "not found")
                 "Shows not found error")))
    (finally
      (teardown-temp-dir!))))

;;; ============ Test Runner ============

(defn run-all-tests
  "Run all E2E tests."
  []
  (println "\n╔══════════════════════════════════════╗")
  (println "║     Clojars Stats E2E Test Suite     ║")
  (println "╚══════════════════════════════════════╝")

  (let [tests [#'test-time-injection
               #'test-status-command
               #'test-export-import-roundtrip
               #'test-ci-workflow
               #'test-standalone-update-script]
        results (for [test-fn tests]
                  (try
                    (test-fn)
                    {:test (str test-fn) :status :pass}
                    (catch Exception e
                      {:test (str test-fn) :status :fail :error e})))]

    (println "\n=== Summary ===")
    (let [passed (count (filter #(= :pass (:status %)) results))
          failed (count (filter #(= :fail (:status %)) results))]
      (println (format "  Passed: %d" passed))
      (println (format "  Failed: %d" failed))
      (when (pos? failed)
        (println "\nFailed tests:")
        (doseq [{:keys [test error]} (filter #(= :fail (:status %)) results)]
          (println (format "  - %s: %s" test (.getMessage error)))))

      {:passed passed :failed failed})))

(comment
  ;; Run all tests
  (run-all-tests)

  ;; Run individual tests
  (test-time-injection)
  (test-status-command)
  (test-export-import-roundtrip)
  (test-ci-workflow)
  (test-standalone-update-script)

  :rcf)
