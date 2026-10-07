CREATE TABLE IF NOT EXISTS events (
  seq INTEGER PRIMARY KEY AUTOINCREMENT,
  event_uuid TEXT NOT NULL UNIQUE,
  user_id TEXT NOT NULL,
  username TEXT,
  name TEXT,
  followers_count INTEGER,
  following_count INTEGER,
  received_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS state (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

-- One row per unfollow, tracking what the group-chat automation did about it.
-- status: pending | would_kick | in_flight | kicked | failed | not_in_gc | refollowed | protected | unverified | skipped | not_tracked
-- version: a global change counter; the app asks for "everything after N".
CREATE TABLE IF NOT EXISTS kicks (
  event_uuid TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  status TEXT NOT NULL,
  detail TEXT,
  due_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  version INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS kicks_version ON kicks (version);
CREATE INDEX IF NOT EXISTS kicks_due ON kicks (status, due_at);

-- People who must never be kicked.
CREATE TABLE IF NOT EXISTS whitelist (
  user_id TEXT PRIMARY KEY
);

-- Unfollows stored before the automation existed get a row too, marked not_tracked, so the app can read them the same way.
INSERT OR IGNORE INTO kicks (event_uuid, user_id, status, detail, due_at, updated_at, version)
  SELECT event_uuid, user_id, 'not_tracked', 'before the group automation existed', received_at, received_at, seq FROM events;
INSERT OR IGNORE INTO state (key, value) SELECT 'version', CAST(IFNULL(MAX(seq), 0) AS TEXT) FROM events;
