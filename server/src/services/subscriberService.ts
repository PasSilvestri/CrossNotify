import crypto from 'node:crypto';
import type Database from 'better-sqlite3';
import { generateSubscriberKey } from '../crypto/tokens.js';
import { TTLS } from '../constants.js';

export interface SubscriberRow {
  id: number;
  channel_id: string;
  subscriber_id: string;
  api_key_hash: string;
  api_key_prefix: string;
  status: 'pending' | 'active' | 'revoked';
  pending_expires_at: number | null;
  fcm_token: string | null;
  device_name: string | null;
  device_model: string | null;
  delivery_mode: 'battery_saver' | 'immediate_preview';
  allow_actions: number;
  created_at: number;
  activated_at: number | null;
  revoked_at: number | null;
  last_seen_at: number | null;
}

export interface SubscriberInitInput {
  fcmToken: string;
  deviceName?: string;
  deviceModel?: string;
  deliveryMode?: 'battery_saver' | 'immediate_preview';
}

export class SubscriberService {
  constructor(private db: Database.Database) {}

  mintSubscriberKey(
    channelId: string,
    allowActions = true
  ): { subscriberId: string; apiKey: string; expiresAt: number; allowActions: boolean } {
    const channel = this.db.prepare('SELECT status FROM channels WHERE channel_id = ?').get(channelId) as
      | { status: string }
      | undefined;

    if (!channel) {
      throw { status: 404, code: 'channel_not_found', message: 'Channel not found' };
    }
    if (channel.status !== 'active') {
      throw { status: 409, code: 'channel_not_active', message: 'Channel must be active to mint subscriber keys' };
    }

    const subscriberId = crypto.randomUUID();
    const { apiKey, hash, prefix } = generateSubscriberKey();
    const now = Date.now();
    const expiresAt = now + TTLS.PENDING_SUBSCRIBER_MS;
    const allowActionsInt = allowActions ? 1 : 0;

    this.db.prepare(`
      INSERT INTO subscribers (
        channel_id, subscriber_id, api_key_hash, api_key_prefix,
        status, pending_expires_at, allow_actions, created_at
      ) VALUES (?, ?, ?, ?, 'pending', ?, ?, ?)
    `).run(channelId, subscriberId, hash, prefix, expiresAt, allowActionsInt, now);

    return {
      subscriberId,
      apiKey,
      expiresAt,
      allowActions,
    };
  }

  updateSubscriberPermissions(
    channelId: string,
    subscriberId: string,
    allowActions: boolean
  ): { subscriberId: string; allowActions: boolean; status: string; fcmToken: string | null } {
    const sub = this.db.prepare(`
      SELECT subscriber_id, status, fcm_token FROM subscribers
      WHERE channel_id = ? AND subscriber_id = ?
    `).get(channelId, subscriberId) as
      | { subscriber_id: string; status: string; fcm_token: string | null }
      | undefined;

    if (!sub || sub.status === 'revoked') {
      throw { status: 404, code: 'subscriber_not_found', message: 'Subscriber not found or revoked' };
    }

    this.db.prepare(`
      UPDATE subscribers SET allow_actions = ?
      WHERE channel_id = ? AND subscriber_id = ?
    `).run(allowActions ? 1 : 0, channelId, subscriberId);

    return {
      subscriberId: sub.subscriber_id,
      allowActions,
      status: sub.status,
      fcmToken: sub.fcm_token,
    };
  }

  initSubscriber(
    apiKeyHash: string,
    body: SubscriberInitInput,
    expectedChannelId?: string
  ): {
    subscriberId: string;
    channelId: string;
    channelName: string;
    deliveryMode: 'battery_saver' | 'immediate_preview';
    allowActions: boolean;
  } {
    const subscriber = this.db.prepare('SELECT * FROM subscribers WHERE api_key_hash = ?').get(apiKeyHash) as
      | SubscriberRow
      | undefined;

    if (!subscriber || subscriber.status === 'revoked') {
      throw { status: 401, code: 'invalid_api_key', message: 'Invalid or revoked subscriber key' };
    }

    if (expectedChannelId && subscriber.channel_id !== expectedChannelId) {
      throw { status: 403, code: 'channel_mismatch', message: 'Subscriber key does not belong to this channel' };
    }

    const channel = this.db.prepare('SELECT name, status FROM channels WHERE channel_id = ?').get(subscriber.channel_id) as
      | { name: string; status: string }
      | undefined;

    if (!channel || channel.status !== 'active') {
      throw { status: 403, code: 'channel_inactive', message: 'Parent channel is not active' };
    }

    const now = Date.now();
    const deliveryMode = body.deliveryMode || 'battery_saver';

    if (subscriber.status === 'pending') {
      if (subscriber.pending_expires_at && subscriber.pending_expires_at < now) {
        throw { status: 410, code: 'provisioning_expired', message: 'Subscriber provisioning key has expired' };
      }

      // If this device/token already has an active subscription for this channel,
      // revoke the prior one so the channel never accumulates duplicate active subscriptions for the same device
      if (body.fcmToken) {
        this.db.prepare(`
          UPDATE subscribers SET status = 'revoked', revoked_at = ?
          WHERE channel_id = ? AND fcm_token = ? AND status = 'active' AND id != ?
        `).run(now, subscriber.channel_id, body.fcmToken, subscriber.id);
      }

      this.db.prepare(`
        UPDATE subscribers SET
          status = 'active',
          fcm_token = ?,
          device_name = ?,
          device_model = ?,
          delivery_mode = ?,
          activated_at = ?,
          pending_expires_at = NULL,
          last_seen_at = ?
        WHERE id = ?
      `).run(
        body.fcmToken,
        body.deviceName || null,
        body.deviceModel || null,
        deliveryMode,
        now,
        now,
        subscriber.id
      );
    } else if (subscriber.status === 'active') {
      // Idempotent update
      this.db.prepare(`
        UPDATE subscribers SET
          fcm_token = COALESCE(?, fcm_token),
          device_name = COALESCE(?, device_name),
          device_model = COALESCE(?, device_model),
          delivery_mode = COALESCE(?, delivery_mode),
          last_seen_at = ?
        WHERE id = ?
      `).run(
        body.fcmToken,
        body.deviceName || null,
        body.deviceModel || null,
        body.deliveryMode || null,
        now,
        subscriber.id
      );
    }

    return {
      subscriberId: subscriber.subscriber_id,
      channelId: subscriber.channel_id,
      channelName: channel.name,
      deliveryMode,
      allowActions: subscriber.allow_actions !== 0,
    };
  }

  getSubscriberByApiKeyHash(hash: string): SubscriberRow | null {
    const row = this.db.prepare('SELECT * FROM subscribers WHERE api_key_hash = ?').get(hash) as SubscriberRow | undefined;
    return row || null;
  }

  getSubscriberById(subscriberId: string): SubscriberRow | null {
    const row = this.db.prepare('SELECT * FROM subscribers WHERE subscriber_id = ?').get(subscriberId) as SubscriberRow | undefined;
    return row || null;
  }

  updateSelf(
    subscriberId: string,
    updates: {
      fcmToken?: string | null;
      deviceName?: string | null;
      deviceModel?: string | null;
      deliveryMode?: 'battery_saver' | 'immediate_preview' | null;
    }
  ): { subscriberId: string; deliveryMode: string } {
    const sub = this.getSubscriberById(subscriberId);
    if (!sub || sub.status !== 'active') {
      throw { status: 404, code: 'subscriber_not_found', message: 'Subscriber not found' };
    }

    const now = Date.now();
    this.db.prepare(`
      UPDATE subscribers SET
        fcm_token = COALESCE(?, fcm_token),
        device_name = COALESCE(?, device_name),
        device_model = COALESCE(?, device_model),
        delivery_mode = COALESCE(?, delivery_mode),
        last_seen_at = ?
      WHERE subscriber_id = ?
    `).run(
      updates.fcmToken || null,
      updates.deviceName || null,
      updates.deviceModel || null,
      updates.deliveryMode || null,
      now,
      subscriberId
    );

    const updated = this.getSubscriberById(subscriberId)!;
    return {
      subscriberId: updated.subscriber_id,
      deliveryMode: updated.delivery_mode,
    };
  }

  revokeSubscriber(channelId: string, subscriberId: string): { fcmToken: string | null } {
    const sub = this.db.prepare(`
      SELECT fcm_token, status FROM subscribers
      WHERE channel_id = ? AND subscriber_id = ?
    `).get(channelId, subscriberId) as { fcm_token: string | null; status: string } | undefined;

    if (!sub) {
      throw { status: 404, code: 'subscriber_not_found', message: 'Subscriber not found' };
    }

    const now = Date.now();
    this.db.prepare(`
      UPDATE subscribers SET status = 'revoked', revoked_at = ?
      WHERE channel_id = ? AND subscriber_id = ?
    `).run(now, channelId, subscriberId);

    return { fcmToken: sub.fcm_token };
  }

  removeSubscriber(channelId: string, subscriberId: string): { fcmToken: string | null } {
    return this.revokeSubscriber(channelId, subscriberId);
  }

  deleteSubscriber(channelId: string, subscriberId: string): void {
    this.db.prepare('DELETE FROM subscribers WHERE channel_id = ? AND subscriber_id = ?').run(channelId, subscriberId);
  }

  removeToken(fcmToken: string, channelId?: string): void {
    if (channelId) {
      this.db.prepare('DELETE FROM subscribers WHERE fcm_token = ? AND channel_id = ?').run(fcmToken, channelId);
    } else {
      this.db.prepare('DELETE FROM subscribers WHERE fcm_token = ?').run(fcmToken);
    }
  }

  updateLastSeen(subscriberId: string): void {
    this.db.prepare('UPDATE subscribers SET last_seen_at = ? WHERE subscriber_id = ?').run(Date.now(), subscriberId);
  }

  getActiveSubscribersForChannel(channelId: string): SubscriberRow[] {
    return this.db.prepare(`
      SELECT * FROM subscribers
      WHERE channel_id = ? AND status = 'active'
    `).all(channelId) as SubscriberRow[];
  }

  setSilence(
    subscriberId: string,
    channelId: string,
    input: { appName?: string | null; durationMs?: number; silencedUntil?: number }
  ): { appName: string | null; silencedUntil: number } {
    const now = Date.now();
    const normalizedApp = input.appName?.trim() || '';
    const silencedUntil = input.durationMs
      ? now + input.durationMs
      : input.silencedUntil ?? 0;

    if (silencedUntil <= now) {
      throw { status: 400, code: 'invalid_silence_duration', message: 'Silence expiration must be in the future' };
    }

    this.db.prepare(`
      INSERT INTO subscriber_silences (
        subscriber_id, channel_id, app_name, silenced_until, created_at
      ) VALUES (?, ?, ?, ?, ?)
      ON CONFLICT(subscriber_id, app_name) DO UPDATE SET
        silenced_until = excluded.silenced_until,
        created_at = excluded.created_at
    `).run(subscriberId, channelId, normalizedApp, silencedUntil, now);

    return {
      appName: normalizedApp || null,
      silencedUntil,
    };
  }

  clearSilence(
    subscriberId: string,
    options?: { appName?: string | null; all?: boolean }
  ): void {
    if (options?.all) {
      this.db.prepare('DELETE FROM subscriber_silences WHERE subscriber_id = ?').run(subscriberId);
      return;
    }
    const normalizedApp = options?.appName?.trim() || '';
    if (normalizedApp) {
      this.db.prepare(
        'DELETE FROM subscriber_silences WHERE subscriber_id = ? AND LOWER(app_name) = LOWER(?)'
      ).run(subscriberId, normalizedApp);
    } else {
      this.db.prepare(
        "DELETE FROM subscriber_silences WHERE subscriber_id = ? AND app_name = ''"
      ).run(subscriberId);
    }
  }

  getSilences(subscriberId: string): {
    channelSilencedUntil: number | null;
    silencedApps: Array<{ appName: string; silencedUntil: number }>;
  } {
    const now = Date.now();
    this.db.prepare('DELETE FROM subscriber_silences WHERE subscriber_id = ? AND silenced_until <= ?').run(
      subscriberId,
      now
    );

    const rows = this.db.prepare(`
      SELECT app_name, silenced_until
      FROM subscriber_silences
      WHERE subscriber_id = ? AND silenced_until > ?
      ORDER BY silenced_until ASC
    `).all(subscriberId, now) as Array<{ app_name: string; silenced_until: number }>;

    let channelSilencedUntil: number | null = null;
    const silencedApps: Array<{ appName: string; silencedUntil: number }> = [];

    for (const row of rows) {
      if (row.app_name === '') {
        channelSilencedUntil = row.silenced_until;
      } else {
        silencedApps.push({ appName: row.app_name, silencedUntil: row.silenced_until });
      }
    }

    return { channelSilencedUntil, silencedApps };
  }

  isSubscriberSilenced(subscriberId: string, appName?: string | null): boolean {
    const now = Date.now();
    const rows = this.db.prepare(`
      SELECT app_name FROM subscriber_silences
      WHERE subscriber_id = ? AND silenced_until > ?
    `).all(subscriberId, now) as Array<{ app_name: string }>;

    if (rows.length === 0) return false;

    const normalizedApp = appName?.trim().toLowerCase() || '';
    return rows.some((row) => {
      if (row.app_name === '') return true;
      return normalizedApp.length > 0 && row.app_name.toLowerCase() === normalizedApp;
    });
  }
}
