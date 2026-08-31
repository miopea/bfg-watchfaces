-- Sign in to publish, anonymous to complain.  2026-08-31
--
-- schema.sql is the fresh-install shape; this is what an ALREADY DEPLOYED
-- database needs to get there. Applied with:
--
--   wrangler d1 execute bfg-catalog --remote --file=migrations/0001-accounts.sql
--
-- RENAME rather than drop-and-recreate. The catalog was empty when this ran, so
-- recreating would have been safe -- but "it is empty" is a fact about one
-- moment, and a migration that destroys a table is the wrong habit to leave
-- lying around for the next person who runs it somewhere with rows in it.
ALTER TABLE faces RENAME COLUMN install_id TO author_key;

-- An author's own submissions, and the per-author history a moderator has never
-- had: whether this is somebody's first face or their eleventh.
CREATE INDEX IF NOT EXISTS faces_by_author ON faces(author_key);

-- The capability the anonymous design could not have. With nobody to block,
-- pre-moderation was the only control there was.
CREATE TABLE IF NOT EXISTS blocked_authors (
  author_key TEXT PRIMARY KEY,
  reason     TEXT NOT NULL,
  created    TEXT NOT NULL
);
