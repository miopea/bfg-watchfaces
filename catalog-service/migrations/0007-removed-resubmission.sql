-- Keep moderation history without treating a removed face as a live duplicate.
DROP INDEX IF EXISTS faces_by_hash;
CREATE UNIQUE INDEX faces_by_hash
  ON faces(params_hash) WHERE state NOT IN ('withdrawn', 'removed');
