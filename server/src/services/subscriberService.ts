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

  mintSubscriberKey(channelId: string): { subscriberId: string; apiKey: string; expiresAt: number } {
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

    this.db.prepare(`
      INSERT INTO subscribers (
        channel_id, subscriber_id, api_key_hash, api_key_prefix,
        status, pending_expires_at, created_at
      ) VALUES (?, ?, ?, ?, 'pending', ?, ?)
    `).run(channelId, subscriberId, hash, prefix, expiresAt, now);

    return {
      subscriberId,
      apiKey,
      expiresAt,
    };
  }

  initSubscriber(
    apiKeyHash: string,
    body: SubscriberInitInput
  ): {
    subscriberId: string;
    channelId: string;
    channelName: string;
    deliveryMode: 'battery_saver' | 'immediate_preview';
  } {
    const subscriber = this.db.prepare('SELECT * FROM subscribers WHERE api_key_hash = ?').get(apiKeyHash) as
      | SubscriberRow
      | undefined;

    if (!subscriber || subscriber.status === 'revoked') {
      throw { status: 401, code: 'invalid_api_key', message: 'Invalid or revoked subscriber key' };
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
    updates: { fcmToken?: string; deviceName?: string; deliveryMode?: 'battery_saver' | 'immediate_preview' }
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
        delivery_mode = COALESCE(?, delivery_mode),
        last_seen_at = ?
      WHERE subscriber_id = ?
    `).run(
      updates.fcmToken || null,
      updates.deviceName || null,
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

  removeToken(fcmToken: string): void {
    this.db.prepare('DELETE FROM subscribers WHERE fcm_token = ?').run(fcmToken);
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
}
