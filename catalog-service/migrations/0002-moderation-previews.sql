CREATE TABLE IF NOT EXISTS face_reviews (
  face_id           TEXT PRIMARY KEY REFERENCES faces(id) ON DELETE CASCADE,
  params_hash       TEXT NOT NULL,
  generator_version INTEGER NOT NULL,
  verdict           TEXT NOT NULL CHECK (verdict IN ('passed','failed')),
  problems          TEXT NOT NULL DEFAULT '[]',
  preview_base64    TEXT,
  created           TEXT NOT NULL
);
