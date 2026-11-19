ALTER TABLE subscribers ADD COLUMN allow_actions INTEGER NOT NULL DEFAULT 1;
ALTER TABLE notifications ADD COLUMN handled_at INTEGER;
ALTER TABLE notifications ADD COLUMN handled_reason TEXT;
ALTER TABLE notifications ADD COLUMN handled_by TEXT;
CREATE INDEX IF NOT EXISTS idx_notifications_handled ON notifications(channel_id, handled_at);
