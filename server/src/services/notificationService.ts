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
  handled_at?: number | null;
  handled_reason?: string | null;
  handled_by?: string | null;
}

export interface HandledNotificationEvent {
  notificationId: string;
  reason: string;
  handledAt: number;
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
      if (existing.channel_id !== channelId) {
        throw { status: 409, code: 'notification_id_conflict', message: 'Notification ID already exists' };
      }
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
      handledAt: number | null;
      handledReason: string | null;
    }>;
    hasMore: boolean;
  } {
    const fetchLimit = Math.min(Math.max(1, limit), 100);
    const now = Date.now();
    const rows = this.db.prepare(`
      SELECT * FROM notifications
      WHERE channel_id = ? AND created_at > ? AND expires_at > ?
      ORDER BY created_at ASC
      LIMIT ?
    `).all(channelId, since, now, fetchLimit + 1) as NotificationRow[];

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
        handledAt: r.handled_at ?? null,
        handledReason: r.handled_reason ?? null,
      })),
      hasMore,
    };
  }

  getNotification(channelId: string, notificationId: string): NotificationRow | null {
    const now = Date.now();
    const row = this.db.prepare(`
      SELECT * FROM notifications
      WHERE channel_id = ? AND notification_id = ? AND expires_at > ?
    `).get(channelId, notificationId, now) as NotificationRow | undefined;

    return row || null;
  }

  markHandledBatch(
    channelId: string,
    items: Array<{ notificationId: string; reason?: string }>
  ): HandledNotificationEvent[] {
    const now = Date.now();
    const updated: HandledNotificationEvent[] = [];

    const stmt = this.db.prepare(`
      UPDATE notifications
      SET handled_at = ?, handled_reason = ?, handled_by = 'relay'
      WHERE channel_id = ? AND notification_id = ? AND handled_at IS NULL
    `);

    const tx = this.db.transaction(() => {
      for (const item of items) {
        const reason = item.reason || 'dismissed';
        const res = stmt.run(now, reason, channelId, item.notificationId);
        if (res.changes > 0) {
          updated.push({
            notificationId: item.notificationId,
            reason,
            handledAt: now,
          });
        }
      }
    });

    tx();
    return updated;
  }

  claimActionExecution(
    channelId: string,
    notificationId: string,
    subscriberId: string
  ): { handledAt: number; handledReason: string } {
    const now = Date.now();
    const existing = this.db.prepare(`
      SELECT notification_id, handled_at, handled_reason, expires_at
      FROM notifications
      WHERE channel_id = ? AND notification_id = ?
    `).get(channelId, notificationId) as
      | { notification_id: string; handled_at: number | null; handled_reason: string | null; expires_at: number }
      | undefined;

    if (!existing || existing.expires_at <= now) {
      throw { status: 404, code: 'notification_not_found', message: 'Notification not found or expired' };
    }

    const res = this.db.prepare(`
      UPDATE notifications
      SET handled_at = ?, handled_reason = 'actioned', handled_by = ?
      WHERE channel_id = ? AND notification_id = ? AND handled_at IS NULL
    `).run(now, subscriberId, channelId, notificationId);

    if (res.changes === 0) {
      const latest = this.db.prepare(`
        SELECT handled_at, handled_reason FROM notifications
        WHERE channel_id = ? AND notification_id = ?
      `).get(channelId, notificationId) as { handled_at: number | null; handled_reason: string | null } | undefined;

      throw {
        status: 409,
        code: 'already_handled',
        message: 'Notification has already been handled',
        handledAt: latest?.handled_at ?? existing.handled_at ?? now,
        handledReason: latest?.handled_reason ?? existing.handled_reason ?? 'dismissed',
      };
    }

    return { handledAt: now, handledReason: 'actioned' };
  }
}
