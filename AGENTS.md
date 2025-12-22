# Clojars Download Stats - AI Agent Instructions

A Babashka-powered pipeline that mirrors Clojars download statistics (Nov 2012 - present) as daily SQL files and SQLite databases.

## Architecture Overview

**Two operational modes:**
1. **Database mode** - Full SQLite DB for querying/analysis (`bb files.import`, `bb clojars.fetch`)
2. **Stateless CI mode** - Uses `state.edn` to generate daily SQL files without rebuilding DB (`bb clojars.export.update`)

```
data/state.edn          → ID mappings for artifacts/versions (persisted between CI runs)
data/YYYY/MM/DD.sql     → Daily SQL INSERT files (one per day since Nov 2012)
clojars-downloads.sqlite → Full normalized database (user-generated via import)
```

## Namespace Structure

| Namespace | Purpose | When to check |
|-----------|---------|---------------|
| `clojars-stats.tasks` | CLI entry points | Adding/modifying bb tasks |
| `clojars-stats.state` | ID allocation, SQL generation | CI workflow, state.edn format |
| `clojars-stats.fetch` | HTTP fetch from Clojars | Clojars API, gap detection |
| `clojars-stats.export` | Database → SQL files | Bulk export, sqlite3 CLI usage |
| `clojars-stats.import` | SQL files → SQLite | Incremental import logic |
| `clojars-stats.db` | SQLite via go-sqlite3 pod | Schema, queries, db operations |
| `clojars-stats.util` | Date handling, timing | Date arithmetic, testability |

All tasks defined in `bb.edn`. The `deps.edn` is for JVM Clojure (used by tech-stacks project).

### Key Files

- **`state.clj`** - Core logic: ID allocation, SQL generation. Most pure functions live here.
- **`util.clj`** - Date arithmetic (`next-day`, `dates-range`, `yesterday`). Config injection for testability.
- **`db.clj`** - SQLite operations. Loads go-sqlite3 pod. Schema definition in `init-db!`.
- **`tasks.clj`** - CLI entry points. Thin wrappers that parse args and call other namespaces.
- **`e2e_test.clj`** - Integration tests with mocked HTTP. Good examples of config injection.

## Data Formats

**Clojars EDN** (what we fetch):
```clojure
{["group-id" "artifact-id"] {"version" download-count}}
```

**Date strings**: Always `YYYYMMDD` format (no dashes). See `util.clj` for date arithmetic.

**Daily SQL files** (`data/YYYY/MM/DD.sql`):
```sql
-- 2024-12-20
INSERT OR IGNORE INTO artifacts ...  -- new artifacts only
INSERT OR IGNORE INTO versions ...   -- new versions only
INSERT OR REPLACE INTO downloads ... -- all downloads for day
```

**state.edn structure**:
```clojure
{:artifacts {["group" "artifact"] id}  ; coords → integer ID
 :versions {"1.0.0" id}                 ; version string → integer ID
 :next-artifact-id 34567
 :next-version-id 31234
 :latest-date "20251221"}
```

## Key Patterns

### Functional Core / Imperative Shell
Pure functions in `state.clj` handle ID allocation and SQL generation. Side effects (file I/O, HTTP) are isolated to wrapper functions with `!` suffix.

**ID allocation returns tuples**: `(get-or-create-artifact-id state group artifact)` returns `[id updated-state]` for functional state threading.

### Testability via Config Injection
Functions accept optional config maps for dependency injection:
```clojure
;; Time injection for deterministic tests
(util/yesterday {:today "20130101"})  ;=> "20121231"

;; Mock HTTP for CI workflow tests
(state/update-daily! "20251221"
  :fetch-fn (fn [_date] mock-data)
  :progress-fn (fn [_]))  ; suppress output
```

### Rich Comment Tests (RCT)
Tests live inside `^:rct/test (comment ...)` blocks with `;=>` assertions:
```clojure
^:rct/test
(comment
  (next-day "20241231")
  ;=> "20250101"
  :rcf)
```

**Run tests from REPL** (preferred):
```clojure
(require '[com.mjdowney.rich-comment-tests :as rct])
(require '[clojars-stats.state :as state])
(rct/run-file-tests! "src/clojars_stats/state.clj" (the-ns 'clojars-stats.state))
```

CLI: `bb test` (all files) or `bb e2e-test` (integration tests).

## Development Workflow

**REPL-first development** - Connect to the Babashka REPL, require namespaces, and test interactively:
```clojure
(in-ns 'clojars-stats.state)
(require '[clojars-stats.util :as util])

;; Test date handling
(util/yesterday {:today "20130101"})

;; Test ID allocation (returns [id updated-state])
(let [s init-state)
      [id s'] get-or-create-artifact-id s "reagent" "reagent")]
  {:id id :next (:next-artifact-id s')})
```

**CLI commands** (for CI or full runs):
```bash
bb test              # RCT unit tests
bb e2e-test          # Integration tests (uses test-data/test.sqlite)
bb db.export.status  # Check state.edn status
```

## Database Schema

Three normalized tables with composite primary keys:
- `artifacts(id, group_id, artifact_id)` - ~34K unique libraries
- `versions(id, version)` - ~31K version strings
- `downloads(date, artifact_id, version_id, downloads)` - ~36M rows

The `state.edn` mirrors this schema as EDN maps to enable database-free CI updates.

## CI Considerations

The `clojars.export.update` task is idempotent and fills any gaps since `state.edn`'s `latest-date`. Safe for daily cron jobs. Commits generated SQL files and updated `state.edn`.

## Prerequisites

- **Babashka** - All tasks run via `bb`
- **sqlite3 CLI** - Required by `export.clj` for fast bulk queries (shells out to `sqlite3`)

## Known Clojars Data Gaps

Missing dates on Clojars (server outages): 2015-12-27, 2021-01-05 through 2021-01-22, scattered single days in 2021-2023. Fetch gracefully handles 404s.
