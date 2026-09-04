CREATE TABLE IF NOT EXISTS face_ai_reviews (
  face_id           TEXT PRIMARY KEY REFERENCES faces(id) ON DELETE CASCADE,
  params_hash       TEXT NOT NULL,
  generator_version INTEGER NOT NULL,
  model             TEXT NOT NULL,
  recommendation    TEXT NOT NULL CHECK (recommendation IN ('approve','review','reject')),
  confidence        TEXT NOT NULL CHECK (confidence IN ('low','medium','high')),
  rationale         TEXT NOT NULL,
  signals           TEXT NOT NULL DEFAULT '{}',
  created           TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS moderation_policy (
  id               INTEGER PRIMARY KEY CHECK (id = 1),
  enabled          INTEGER NOT NULL CHECK (enabled IN (0,1)),
  sensitivity      TEXT NOT NULL CHECK (sensitivity IN ('permissive','balanced','cautious')),
  comparison_limit INTEGER NOT NULL CHECK (comparison_limit BETWEEN 1 AND 12),
  pending_warn     INTEGER NOT NULL CHECK (pending_warn BETWEEN 1 AND 50)
);

INSERT OR IGNORE INTO moderation_policy
  (id, enabled, sensitivity, comparison_limit, pending_warn)
VALUES (1, 1, 'balanced', 6, 3);
