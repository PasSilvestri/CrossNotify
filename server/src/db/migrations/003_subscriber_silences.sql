CREATE TABLE IF NOT EXISTS subscriber_silences (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  subscriber_id  TEXT NOT NULL REFERENCES subscribers(subscriber_id) ON DELETE CASCADE,
  channel_id     TEXT NOT NULL REFERENCES channels(channel_id) ON DELETE CASCADE,
  app_name       TEXT NOT NULL DEFAULT '',
  silenced_until INTEGER NOT NULL,
  created_at     INTEGER NOT NULL,
  UNIQUE(subscriber_id, app_name)
);
CREATE INDEX IF NOT EXISTS idx_subscriber_silences_sub ON subscriber_silences(subscriber_id);
CREATE INDEX IF NOT EXISTS idx_subscriber_silences_until ON subscriber_silences(silenced_until);
