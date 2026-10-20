import type Database from 'better-sqlite3';
import { TTLS } from '../constants.js';
import type { SettingsService } from './settingsService.js';

export interface NotificationRow {
  notification_id: string;
  channel_id: string;
  iv: string;
  ciphertext: string;
  app_name: string | null;
  created_at: number;
  relay_timestamp: number | null;
  expires_at: number;
}

export interface PublishInput {
  notificationId: string;
  iv: string;
  ciphertext: string;
  appName?: string;
  timestamp?: number;
}

export class NotificationService {
  constructor(
    private db: Database.Database,
    private settingsService: SettingsService
  ) {}

  publish(
    channelId: string,
    input: PublishInput
  ): { created: boolean; notification: NotificationRow } {
    const existing = this.db.prepare(`
      SELECT * FROM notifications WHERE notification_id = ?
    `).get(input.notificationId) as NotificationRow | undefined;

    if (existing) {
      return { created: false, notification: existing };
    }

    const settings = this.settingsService.getAll();
    const ttlHours = settings.notificationTtlHours || TTLS.DEFAULT_NOTIFICATION_HOURS;
    const now = Date.now();
    const expiresAt = now + ttlHours * 60 * 60 * 1000;

    this.db.prepare(`
      INSERT INTO notifications (
        notification_id, channel_id, iv, ciphertext, app_name,
        created_at, relay_timestamp, expires_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    `).run(
      input.notificationId,
      channelId,
      input.iv,
      input.ciphertext,
      input.appName || null,
      now,
      input.timestamp || null,
      expiresAt
    );

    const inserted = this.db.prepare(`
      SELECT * FROM notifications WHERE notification_id = ?
    `).get(input.notificationId) as NotificationRow;

    return { created: true, notification: inserted };
  }

  catchUp(
    channelId: string,
    since = 0,
    limit = 50
  ): {
    notifications: Array<{
      notificationId: string;
      iv: string;
      ciphertext: string;
      appName: string | null;
      timestamp: number | null;
      receivedAt: number;
    }>;
    hasMore: boolean;
  } {
    const fetchLimit = Math.min(Math.max(1, limit), 100);
    const rows = this.db.prepare(`
      SELECT * FROM notifications
      WHERE channel_id = ? AND created_at > ?
      ORDER BY created_at ASC
      LIMIT ?
    `).all(channelId, since, fetchLimit + 1) as NotificationRow[];

    const hasMore = rows.length > fetchLimit;
    const items = rows.slice(0, fetchLimit);

    return {
      notifications: items.map((r) => ({
        notificationId: r.notification_id,
        iv: r.iv,
        ciphertext: r.ciphertext,
        appName: r.app_name,
        timestamp: r.relay_timestamp,
        receivedAt: r.created_at,
      })),
      hasMore,
    };
  }

  getNotification(channelId: string, notificationId: string): NotificationRow | null {
    const row = this.db.prepare(`
      SELECT * FROM notifications
      WHERE channel_id = ? AND notification_id = ?
    `).get(channelId, notificationId) as NotificationRow | undefined;

    return row || null;
  }
}
