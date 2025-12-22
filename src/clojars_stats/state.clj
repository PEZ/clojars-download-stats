(ns clojars-stats.state
  "State management for CI daily updates without database.

   Maintains state.edn with ID mappings for artifacts and versions,
   allowing daily SQL file generation without rebuilding the database."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as string]))

;; Default paths
(def default-state-file "data/state.edn")
(def default-data-dir "data")

;;; ============ State File Operations ============

(defn load-state
  "Load state from state file. Returns nil if file doesn't exist."
  ([] (load-state {}))
  ([config]
   (let [state-file (or (:state-file config) default-state-file)]
     (when (.exists (io/file state-file))
       (edn/read-string (slurp state-file))))))

(defn save-state!
  "Save state to state file (pretty-printed for readable diffs)."
  ([state] (save-state! state {}))
  ([state config]
   (let [state-file (or (:state-file config) default-state-file)]
     (io/make-parents state-file)
     (spit state-file (with-out-str (pprint/pprint state))))))

(defn init-state
  "Create initial empty state."
  []
  {:artifacts {}      ; {[group-id artifact-id] id}
   :versions {}       ; {version-string id}
   :next-artifact-id 1
   :next-version-id 1
   :latest-date nil})

;;; ============ ID Allocation ============

(defn get-or-create-artifact-id
  "Get existing artifact ID or allocate a new one.
   Returns [id updated-state]."
  [state group-id artifact-id]
  (let [key [group-id artifact-id]]
    (if-let [id (get-in state [:artifacts key])]
      [id state]
      (let [new-id (:next-artifact-id state)]
        [new-id (-> state
                    (assoc-in [:artifacts key] new-id)
                    (update :next-artifact-id inc))]))))

(defn get-or-create-version-id
  "Get existing version ID or allocate a new one.
   Returns [id updated-state]."
  [state version]
  (if-let [id (get-in state [:versions version])]
    [id state]
    (let [new-id (:next-version-id state)]
      [new-id (-> state
                  (assoc-in [:versions version] new-id)
                  (update :next-version-id inc))])))

^:rct/test
(comment
  ;; init-state creates empty state with starting IDs
  (init-state)
  ;=> {:artifacts {}, :versions {}, :next-artifact-id 1, :next-version-id 1, :latest-date nil}

  ;; get-or-create-artifact-id - new artifact
  (let [state (init-state)
        [id new-state] (get-or-create-artifact-id state "reagent" "reagent")]
    [id (:next-artifact-id new-state)])
  ;=> [1 2]

  ;; get-or-create-artifact-id - existing artifact returns same ID
  (let [state (-> (init-state)
                  (assoc-in [:artifacts ["reagent" "reagent"]] 42))
        [id new-state] (get-or-create-artifact-id state "reagent" "reagent")]
    [id (= state new-state)])
  ;=> [42 true]

  ;; get-or-create-version-id - new version
  (let [state (init-state)
        [id new-state] (get-or-create-version-id state "1.0.0")]
    [id (:next-version-id new-state)])
  ;=> [1 2]

  ;; get-or-create-version-id - existing version
  (let [state (-> (init-state)
                  (assoc-in [:versions "1.0.0"] 99))
        [id new-state] (get-or-create-version-id state "1.0.0")]
    [id (= state new-state)])
  ;=> [99 true]

  :rcf)

;;; ============ SQL Generation ============

(defn- escape-sql-string [s]
  (string/replace (str s) "'" "''"))

(defn generate-daily-sql
  "Generate SQL INSERT statements for a day's Clojars data.
   Returns {:sql string, :state updated-state, :new-artifacts [...], :new-versions [...]}."
  [state date-str clojars-data]
  (let [sb (StringBuilder.)
        new-artifacts (atom [])
        new-versions (atom [])]
    ;; Process all data, tracking state changes
    (loop [entries (seq clojars-data)
           current-state state]
      (if-not entries
        {:sql (str sb)
         :state (assoc current-state :latest-date date-str)
         :new-artifacts @new-artifacts
         :new-versions @new-versions}
        (let [[[group-id artifact-id] version-map] (first entries)
              [art-id state-after-art] (get-or-create-artifact-id current-state group-id artifact-id)]
          ;; Track if this is a new artifact
          (when (not= current-state state-after-art)
            (swap! new-artifacts conj {:id art-id :group-id group-id :artifact-id artifact-id}))
          ;; Process all versions for this artifact
          (recur (next entries)
                 (reduce
                  (fn [s [version dl-count]]
                    (let [[ver-id state-after-ver] (get-or-create-version-id s version)]
                      ;; Track if this is a new version
                      (when (not= s state-after-ver)
                        (swap! new-versions conj {:id ver-id :version version}))
                      ;; Generate download INSERT
                      (.append sb (format "INSERT OR REPLACE INTO downloads (date, artifact_id, version_id, downloads) VALUES ('%s', %d, %d, %d);\n"
                                          date-str art-id ver-id dl-count))
                      state-after-ver))
                  state-after-art
                  version-map)))))))

(defn generate-artifact-inserts
  "Generate INSERT statements for new artifacts."
  [new-artifacts]
  (string/join
   (for [{:keys [id group-id artifact-id]} new-artifacts]
     (format "INSERT OR IGNORE INTO artifacts (id, group_id, artifact_id) VALUES (%d, '%s', '%s');\n"
             id (escape-sql-string group-id) (escape-sql-string artifact-id)))))

(defn generate-version-inserts
  "Generate INSERT statements for new versions."
  [new-versions]
  (string/join
   (for [{:keys [id version]} new-versions]
     (format "INSERT OR IGNORE INTO versions (id, version) VALUES (%d, '%s');\n"
             id (escape-sql-string version)))))

;;; ============ File Operations ============

(defn- date->path
  "Convert date string (YYYYMMDD) to file path: data/YYYY/MM/DD.sql"
  [date-str config]
  (let [data-dir (or (:data-dir config) default-data-dir)
        year (subs date-str 0 4)
        month (subs date-str 4 6)
        day (subs date-str 6 8)]
    (str data-dir "/" year "/" month "/" day ".sql")))

(defn write-daily-sql!
  "Write SQL file for a single day. Creates file with header and all statements."
  ([date-str artifact-sql version-sql download-sql]
   (write-daily-sql! date-str artifact-sql version-sql download-sql {}))
  ([date-str artifact-sql version-sql download-sql config]
   (let [path (date->path date-str config)]
     (io/make-parents path)
     (spit path (str "-- " (subs date-str 0 4) "-" (subs date-str 4 6) "-" (subs date-str 6 8) "\n"
                     artifact-sql
                     version-sql
                     download-sql))
     path)))







;;; ============ Clojars Fetch ============

(defn- fetch-from-clojars
  "Actual HTTP fetch from Clojars."
  [date-str]
  (let [http (requiring-resolve 'babashka.http-client/get)
        url (str "https://repo.clojars.org/stats/downloads-" date-str ".edn")]
    (try
      (-> (http url)
          :body
          edn/read-string)
      (catch Exception e
        (println "Failed to fetch" date-str ":" (.getMessage e))
        nil))))

(defn fetch-daily-stats
  "Fetch download stats for a date (YYYYMMDD) from Clojars.
   If config has :fetch-fn, uses that instead of HTTP.
   Returns EDN map or nil on error."
  ([date-str] (fetch-daily-stats date-str {}))
  ([date-str config]
   (if-let [fetch-fn (:fetch-fn config)]
     (fetch-fn date-str)
     (fetch-from-clojars date-str))))

;;; ============ Main CI Entry Point ============

(defn update-daily!
  "Fetch a single day from Clojars and write to daily SQL file.
   No database required - uses state.edn for ID mappings.
   Config options: :state-file, :data-dir, :fetch-fn, :progress-fn
   Returns {:date, :downloads, :new-artifacts, :new-versions} or nil on error."
  [date-str & {:keys [progress-fn] :or {progress-fn println} :as config}]
  (progress-fn (format "Fetching %s from Clojars..." date-str))
  (if-let [clojars-data (fetch-daily-stats date-str config)]
    (let [state (or (load-state config) (init-state))
          _ (progress-fn (format "  Processing %d artifacts..." (count clojars-data)))
          {:keys [sql state new-artifacts new-versions]} (generate-daily-sql state date-str clojars-data)
          artifact-sql (generate-artifact-inserts new-artifacts)
          version-sql (generate-version-inserts new-versions)
          download-count (count (re-seq #"INSERT OR REPLACE INTO downloads" sql))
          path (date->path date-str config)]

      ;; Write daily file
      (progress-fn (format "  Writing %d downloads to %s..." download-count path))
      (write-daily-sql! date-str artifact-sql version-sql sql config)

      ;; Save updated state
      (save-state! state config)

      (progress-fn (format "  Done! %d new artifacts, %d new versions"
                           (count new-artifacts) (count new-versions)))

      {:date date-str
       :downloads download-count
       :new-artifacts (count new-artifacts)
       :new-versions (count new-versions)})

    (do (progress-fn (format "  Failed to fetch %s" date-str))
        nil)))

;;; ============ State Generation from Database ============

(defn generate-state-from-db!
  "Generate state.edn from an existing database.
   This bootstraps CI updates from a pre-populated DB.
   Config options: :state-file, :progress-fn"
  [db-path & {:keys [progress-fn] :or {progress-fn println} :as config}]
  (let [get-all-artifacts (requiring-resolve 'clojars-stats.db/get-all-artifacts)
        get-all-versions (requiring-resolve 'clojars-stats.db/get-all-versions)
        get-latest-date (requiring-resolve 'clojars-stats.db/get-latest-date)]

    (progress-fn "Loading artifacts from database...")
    (let [artifacts (get-all-artifacts db-path)
          _ (progress-fn (format "  %d artifacts" (count artifacts)))

          _ (progress-fn "Loading versions from database...")
          versions (get-all-versions db-path)
          _ (progress-fn (format "  %d versions" (count versions)))

          _ (progress-fn "Getting latest date...")
          latest-date (get-latest-date db-path)

          ;; Build state map
          state {:artifacts (into {}
                                  (map (fn [{:keys [id group_id artifact_id]}]
                                         [[group_id artifact_id] id])
                                       artifacts))
                 :versions (into {}
                                 (map (fn [{:keys [id version]}]
                                        [version id])
                                      versions))
                 :next-artifact-id (inc (reduce max 0 (map :id artifacts)))
                 :next-version-id (inc (reduce max 0 (map :id versions)))
                 :latest-date latest-date}]

      (progress-fn (format "Saving state.edn (latest: %s, next-art: %d, next-ver: %d)"
                           latest-date
                           (:next-artifact-id state)
                           (:next-version-id state)))
      (save-state! state config)
      state)))

^:rct/test
(comment
  ;; date->path converts YYYYMMDD to path structure
  (#'date->path "20241220" {})
  ;=> "data/2024/12/20.sql"

  ;; date->path respects custom data-dir
  (#'date->path "20241220" {:data-dir "custom"})
  ;=> "custom/2024/12/20.sql"

  ;; generate-daily-sql processes Clojars data format
  (let [state (init-state)
        data {["reagent" "reagent"] {"1.0.0" 100}}
        {:keys [sql state new-artifacts new-versions]} (generate-daily-sql state "20241220" data)]
    {:has-download-insert (clojure.string/includes? sql "INSERT OR REPLACE INTO downloads")
     :latest-date (:latest-date state)
     :artifact-count (count new-artifacts)
     :version-count (count new-versions)})
  ;=> {:has-download-insert true, :latest-date "20241220", :artifact-count 1, :version-count 1}

  ;; generate-daily-sql reuses existing IDs
  (let [state (-> (init-state)
                  (assoc-in [:artifacts ["reagent" "reagent"]] 42)
                  (assoc-in [:versions "1.0.0"] 99))
        data {["reagent" "reagent"] {"1.0.0" 100}}
        {:keys [new-artifacts new-versions]} (generate-daily-sql state "20241220" data)]
    {:new-artifacts (count new-artifacts)
     :new-versions (count new-versions)})
  ;=> {:new-artifacts 0, :new-versions 0}

  ;; escape-sql-string handles single quotes
  (#'escape-sql-string "O'Reilly")
  ;=> "O''Reilly"

  ;; generate-artifact-inserts creates proper SQL
  (generate-artifact-inserts [{:id 1 :group-id "reagent" :artifact-id "reagent"}])
  ;=> "INSERT OR IGNORE INTO artifacts (id, group_id, artifact_id) VALUES (1, 'reagent', 'reagent');\n"

  ;; generate-artifact-inserts handles SQL escaping
  (generate-artifact-inserts [{:id 1 :group-id "O'Reilly" :artifact-id "test"}])
  ;=> "INSERT OR IGNORE INTO artifacts (id, group_id, artifact_id) VALUES (1, 'O''Reilly', 'test');\n"

  ;; generate-version-inserts creates proper SQL
  (generate-version-inserts [{:id 1 :version "1.0.0"}])
  ;=> "INSERT OR IGNORE INTO versions (id, version) VALUES (1, '1.0.0');\n"

  ;; generate-version-inserts handles SQL escaping
  (generate-version-inserts [{:id 1 :version "1.0.0-SNAPSHOT'test"}])
  ;=> "INSERT OR IGNORE INTO versions (id, version) VALUES (1, '1.0.0-SNAPSHOT''test');\n"

  ;; Multiple inserts are joined
  (generate-artifact-inserts [{:id 1 :group-id "a" :artifact-id "b"}
                              {:id 2 :group-id "c" :artifact-id "d"}])
  ;=> "INSERT OR IGNORE INTO artifacts (id, group_id, artifact_id) VALUES (1, 'a', 'b');\nINSERT OR IGNORE INTO artifacts (id, group_id, artifact_id) VALUES (2, 'c', 'd');\n"

  :rcf)
