export const ANDROID_PACKAGE_NAME = 'com.crossnotify.app';

export const LIMITS = {
  CHANNEL_NAME_MIN: 1,
  CHANNEL_NAME_MAX: 40,
  PASSWORD_MIN: 8,
  PASSWORD_MAX: 128,
  USERNAME_MIN: 3,
  USERNAME_MAX: 32,
  USERNAME_REGEX: /^[a-zA-Z0-9._-]+$/,
  APP_NAME_MAX: 64,
  MAX_NOTIFICATION_CIPHERTEXT_BYTES: 16 * 1024, // 16 KiB decoded
  FCM_DATA_MAX_BYTES: 3500, // Threshold to switch to fetch: '1'
};

export const TTLS = {
  PENDING_CHANNEL_MS: 15 * 60 * 1000, // 15 minutes
  PENDING_SUBSCRIBER_MS: 15 * 60 * 1000, // 15 minutes
  SESSION_MS: 7 * 24 * 60 * 60 * 1000, // 7 days
  DEFAULT_NOTIFICATION_HOURS: 48,
  REVOKED_CHANNEL_RETENTION_MS: 7 * 24 * 60 * 60 * 1000, // 7 days
};

export const COOKIE = {
  ADMIN_SESSION: 'cn_admin',
};
