CREATE TABLE IF NOT EXISTS state (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

-- One row per run, whether it worked or not. Costs are xAI's own figure when the reply carried it.
-- status: running | ok | failed | dry_run     trigger: scheduled | spike | manual | dry_run
CREATE TABLE IF NOT EXISTS runs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  type TEXT NOT NULL,
  trigger TEXT NOT NULL,
  model TEXT NOT NULL,
  started_at INTEGER NOT NULL,
  finished_at INTEGER,
  status TEXT NOT NULL,
  error TEXT,
  focus TEXT,
  session_id INTEGER,
  target_posts INTEGER NOT NULL,
  days_back INTEGER NOT NULL,
  max_turns INTEGER NOT NULL,
  posts_fetched INTEGER NOT NULL DEFAULT 0,
  users_fetched INTEGER NOT NULL DEFAULT 0,
  search_calls INTEGER NOT NULL DEFAULT 0,
  input_tokens INTEGER NOT NULL DEFAULT 0,
  cached_tokens INTEGER NOT NULL DEFAULT 0,
  output_tokens INTEGER NOT NULL DEFAULT 0,
  reasoning_tokens INTEGER NOT NULL DEFAULT 0,
  cost_usd REAL NOT NULL DEFAULT 0,
  cost_from_xai INTEGER NOT NULL DEFAULT 0,
  sample_size INTEGER NOT NULL DEFAULT 0,
  dropped_urls INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS runs_started ON runs (started_at);
CREATE INDEX IF NOT EXISTS runs_session ON runs (session_id);

CREATE TABLE IF NOT EXISTS snapshots (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  run_id INTEGER NOT NULL,
  key TEXT NOT NULL,
  name TEXT NOT NULL,
  mentions INTEGER NOT NULL,
  share REAL NOT NULL,
  sentiment TEXT NOT NULL,
  keywords TEXT NOT NULL,
  example_urls TEXT NOT NULL,
  label TEXT NOT NULL,
  growth REAL NOT NULL,
  baseline REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS snapshots_run ON snapshots (run_id);

-- A deep scan's questions, reply targets and angles. kind: question | reply | angle
CREATE TABLE IF NOT EXISTS findings (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  run_id INTEGER NOT NULL,
  kind TEXT NOT NULL,
  text TEXT NOT NULL,
  url TEXT,
  extra TEXT
);
CREATE INDEX IF NOT EXISTS findings_run ON findings (run_id);
