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
  -- The random per-install id, sent only on submit. It exists so an author can
  -- withdraw their own face and for nothing else -- deliberately NOT used for
  -- moderation, because blocking by it would make it a real identity with
  -- consequences while still being defeated by a reinstall.
  install_id        TEXT,
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
