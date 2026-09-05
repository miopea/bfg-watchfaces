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
