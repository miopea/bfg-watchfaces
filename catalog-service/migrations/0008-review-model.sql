-- A null override preserves the deployment's existing model and cached policy.
ALTER TABLE moderation_policy ADD COLUMN model TEXT
  CHECK (model IS NULL OR model IN ('claude-haiku-4-5-20251001', 'claude-sonnet-4-6'));
