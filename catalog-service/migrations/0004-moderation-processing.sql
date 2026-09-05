CREATE TABLE IF NOT EXISTS moderation_jobs (
  face_id TEXT PRIMARY KEY REFERENCES faces(id) ON DELETE CASCADE,
  params_hash TEXT NOT NULL,
  generator_version INTEGER NOT NULL,
  status TEXT NOT NULL CHECK(status IN ('waiting','running','retry','failed','attention','complete')),
  stage TEXT NOT NULL DEFAULT 'preview' CHECK(stage IN ('preview','ai')),
  attempts INTEGER NOT NULL DEFAULT 0,
  lease TEXT,
  lease_until INTEGER NOT NULL DEFAULT 0,
  next_attempt INTEGER NOT NULL DEFAULT 0,
  last_error TEXT,
  updated_at INTEGER NOT NULL,
  completed_at INTEGER
);
CREATE TABLE IF NOT EXISTS moderation_runner (
  id INTEGER PRIMARY KEY CHECK(id = 1),
  last_seen INTEGER NOT NULL DEFAULT 0,
  last_success INTEGER NOT NULL DEFAULT 0,
  last_error TEXT
);
INSERT OR IGNORE INTO moderation_runner(id) VALUES (1);
