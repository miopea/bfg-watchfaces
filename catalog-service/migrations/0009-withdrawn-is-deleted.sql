-- Withdrawing a face now DELETES it (DECISIONS.md 2026-11-05). Faces withdrawn
-- before that were only marked `withdrawn` and kept the account code, the typed
-- name, the settings, the preview and the AI review. This removes them, the
-- same way postWithdraw now does, so the privacy policy is true of the past as
-- well as the future. Reports are left alone; they name a face only by slug.
--
-- Also blanks the typed name on any face whose account was deleted before
-- deleteMe started doing so. Counted on 2026-11-05: none, so that statement is
-- here for a database restored from an older export.
--
-- Idempotent: a second run finds nothing to delete and nothing to blank.
DELETE FROM face_reviews WHERE face_id IN (SELECT id FROM faces WHERE state = 'withdrawn');
DELETE FROM face_ai_reviews WHERE face_id IN (SELECT id FROM faces WHERE state = 'withdrawn');
DELETE FROM moderation_jobs WHERE face_id IN (SELECT id FROM faces WHERE state = 'withdrawn');
DELETE FROM automatic_publications WHERE face_id IN (SELECT id FROM faces WHERE state = 'withdrawn');
DELETE FROM faces WHERE state = 'withdrawn';
UPDATE faces SET author = '' WHERE author_key IS NULL AND author <> '';
