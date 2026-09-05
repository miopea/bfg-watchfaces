CREATE TABLE IF NOT EXISTS automatic_approval_policy (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  mode TEXT NOT NULL CHECK (mode IN ('recommendations','automatic')),
  max_per_hour INTEGER NOT NULL CHECK (max_per_hour BETWEEN 1 AND 100),
  max_per_author_day INTEGER NOT NULL CHECK (max_per_author_day BETWEEN 1 AND 20)
);
INSERT OR IGNORE INTO automatic_approval_policy VALUES (1, 'recommendations', 5, 1);
CREATE TABLE IF NOT EXISTS automatic_publications (
  id TEXT PRIMARY KEY,
  face_id TEXT NOT NULL REFERENCES faces(id),
  params_hash TEXT NOT NULL,
  generator_version INTEGER NOT NULL,
  model TEXT NOT NULL,
  policy TEXT NOT NULL,
  created TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS automatic_publications_created ON automatic_publications(created);
