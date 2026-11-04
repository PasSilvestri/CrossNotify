ALTER TABLE channels ADD COLUMN fcm_token TEXT;
CREATE INDEX IF NOT EXISTS idx_channels_fcm_token ON channels(fcm_token);
