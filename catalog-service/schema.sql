-- The catalog service's storage. D1, which is SQLite.
--
-- Applied with:
--   wrangler d1 execute bfg-catalog --local  --file=schema.sql
--   wrangler d1 execute bfg-catalog --remote --file=schema.sql
--
-- Everything here is idempotent, so re-running it on a live database is safe.

-- A submitted face, in exactly one of the four states docs/specs/catalog-service.md
-- names, plus `withdrawn` for an author taking their own face back.
--
-- `params` is stored VERBATIM as it arrived. The catalog's on-disk format is the
-- interchange format (R6), so the bytes that come out of /export must be the
-- bytes that went in -- re-serializing here would make this service a second
-- opinion about the file format, which is the one thing it must never be.
CREATE TABLE IF NOT EXISTS faces (
  id                TEXT PRIMARY KEY,
  slug              TEXT NOT NULL UNIQUE,
  name              TEXT NOT NULL,
  author            TEXT NOT NULL DEFAULT '',
  params            TEXT NOT NULL,
  params_hash       TEXT NOT NULL,
  engine            TEXT NOT NULL,
  dial_color        TEXT NOT NULL,
  ink_color         TEXT NOT NULL,
  generator_version INTEGER NOT NULL,
  -- Who published it: sha256(salt + Google subject id). Never the subject id,
  -- never an email, never a name.
  --
  -- NULL means the author deleted their account. The face is ABANDONED, not
  -- removed: a watch face is parameters -- "knotwork, scale 26, pewter" -- and
  -- settings are not personal information. The account id was the personal data
  -- and it is what goes. Nothing should pull a face off a wrist because its
  -- author left.
  author_key        TEXT,
  state             TEXT NOT NULL DEFAULT 'pending'
                    CHECK (state IN ('pending','published','rejected','removed','withdrawn')),
  reason            TEXT,
  installs          INTEGER NOT NULL DEFAULT 0,
  created           TEXT NOT NULL,
  reviewed          TEXT
);

-- The queue, oldest first. Submissions are worked through in order and nothing
-- is turned away, so this index is the moderation tool's whole read pattern.
CREATE INDEX IF NOT EXISTS faces_by_state ON faces(state, created);

-- The gallery, ordered by popularity.
CREATE INDEX IF NOT EXISTS faces_by_installs ON faces(state, installs DESC);

-- Byte-identical submissions are rejected: an exact parameter match adds
-- nothing. Anything less exact is somebody's judgement about colour and scale,
-- and refereeing that needs a threshold nobody can defend.
--
-- Partial, excluding withdrawn: taking your own face back has to leave you able
-- to submit it again. Enforced by the engine rather than by a SELECT first,
-- because two submissions racing would both find nothing and both insert.
CREATE UNIQUE INDEX IF NOT EXISTS faces_by_hash
  ON faces(params_hash) WHERE state <> 'withdrawn';

-- A report is a MESSAGE, not an action. Nothing here hides a face: with no
-- accounts, "N people reported it" is one person and a loop, and auto-hiding on
-- a count would hand anyone a takedown button.
CREATE TABLE IF NOT EXISTS reports (
  id         TEXT PRIMARY KEY,
  face_slug  TEXT NOT NULL,
  reason     TEXT NOT NULL,
  detail     TEXT NOT NULL DEFAULT '',
  state      TEXT NOT NULL DEFAULT 'open' CHECK (state IN ('open','resolved')),
  outcome    TEXT,
  created    TEXT NOT NULL,
  resolved   TEXT
);

CREATE INDEX IF NOT EXISTS reports_by_state ON reports(state, created);
CREATE INDEX IF NOT EXISTS reports_by_face  ON reports(face_slug);

-- An author's own submissions, for "my faces" and for the per-author history a
-- moderator has never had.
CREATE INDEX IF NOT EXISTS faces_by_author ON faces(author_key);

-- The trusted moderation artifact. It is produced by the repository's JVM
-- renderer from the exact stored params, never accepted from a submitter.
-- Keeping the params hash and generator version beside it makes a stale image
-- unusable even if the face record is ever repaired or migrated in place.
CREATE TABLE IF NOT EXISTS face_reviews (
  face_id           TEXT PRIMARY KEY REFERENCES faces(id) ON DELETE CASCADE,
  params_hash       TEXT NOT NULL,
  generator_version INTEGER NOT NULL,
  verdict           TEXT NOT NULL CHECK (verdict IN ('passed','failed')),
  problems          TEXT NOT NULL DEFAULT '[]',
  preview_base64    TEXT,
  created           TEXT NOT NULL
);

-- A revision-bound recommendation, kept separate from the technical verdict.
-- This row cannot replace the technical review or the operator's approval policy.
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

-- AI advice settings. Automatic publication requires the separate operator
-- policy below as well as the revision, confidence and library safeguards.
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

-- Blocked authors. This is the capability the anonymous design could not have:
-- with nobody to block, pre-moderation was the only control there was.
--
-- Blocking is by author_key, which survives a reinstall and a new phone. It
-- does NOT survive a new Google account, so it raises the cost of abuse a great
-- deal and is not a wall -- pre-moderation is still doing real work.
CREATE TABLE IF NOT EXISTS blocked_authors (
  author_key TEXT PRIMARY KEY,
  reason     TEXT NOT NULL,
  created    TEXT NOT NULL
);

-- Per-IP rate limiting, understood as a speed bump rather than a control: with
-- no accounts, pre-moderation is the substantive defence and this only slows a
-- flood that would still land.
--
-- `bucket` is a SALTED HASH of the address, never the address. The About screen
-- promises nothing about the person is stored, and an IP is something about a
-- person. Rows expire and are swept on write.
CREATE TABLE IF NOT EXISTS rate (
  bucket  TEXT PRIMARY KEY,
  hits    INTEGER NOT NULL,
  expires INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS rate_by_expiry ON rate(expires);
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
CREATE TABLE IF NOT EXISTS catalog_revision (id INTEGER PRIMARY KEY CHECK (id = 1), revision INTEGER NOT NULL DEFAULT 0);
INSERT OR IGNORE INTO catalog_revision VALUES (1, 0);
CREATE TRIGGER IF NOT EXISTS catalog_published_insert AFTER INSERT ON faces WHEN NEW.state = 'published'
BEGIN UPDATE catalog_revision SET revision = revision + 1 WHERE id = 1; END;
CREATE TRIGGER IF NOT EXISTS catalog_published_update AFTER UPDATE OF state,params_hash,generator_version ON faces WHEN OLD.state = 'published' OR NEW.state = 'published'
BEGIN UPDATE catalog_revision SET revision = revision + 1 WHERE id = 1; END;
CREATE TRIGGER IF NOT EXISTS catalog_published_delete AFTER DELETE ON faces WHEN OLD.state = 'published'
BEGIN UPDATE catalog_revision SET revision = revision + 1 WHERE id = 1; END;
CREATE TRIGGER IF NOT EXISTS catalog_preview_update AFTER UPDATE ON face_reviews WHEN EXISTS(SELECT 1 FROM faces WHERE id=NEW.face_id AND state='published')
BEGIN UPDATE catalog_revision SET revision = revision + 1 WHERE id = 1; END;
CREATE TRIGGER IF NOT EXISTS catalog_preview_insert AFTER INSERT ON face_reviews WHEN EXISTS(SELECT 1 FROM faces WHERE id=NEW.face_id AND state='published')
BEGIN UPDATE catalog_revision SET revision = revision + 1 WHERE id = 1; END;
CREATE TRIGGER IF NOT EXISTS catalog_preview_delete AFTER DELETE ON face_reviews WHEN EXISTS(SELECT 1 FROM faces WHERE id=OLD.face_id AND state='published')
BEGIN UPDATE catalog_revision SET revision = revision + 1 WHERE id = 1; END;
