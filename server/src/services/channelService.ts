import crypto from 'node:crypto';
import type Database from 'better-sqlite3';
import { generateRelayKey } from '../crypto/tokens.js';
import { TTLS } from '../constants.js';
import type { SettingsService } from './settingsService.js';
import type { FirebaseAdminService } from './firebaseAdmin.js';

export interface ChannelRow {
  channel_id: string;
  name: string;
  color: string | null;
  status: 'pending' | 'active' | 'revoked';
  api_key_hash: string;
  api_key_prefix: string;
  relay_device_name: string | null;
  relay_device_model: string | null;
  relay_os_version: string | null;
  relay_app_version: string | null;
  fcm_token: string | null;
  created_at: number;
  pending_expires_at: number | null;
  activated_at: number | null;
  revoked_at: number | null;
  last_seen_at: number | null;
}

export interface RelayProvisioningPayload {
  channelId: string;
  expiresAt: number;
  qrPayload: {
    t: 'cn-relay';
    v: number;
    server: {
      url: string;
      name: string;
    };
    channel: {
      id: string;
      name: string;
      color: string | null;
    };
    apiKey: string;
    firebase: {
      projectId: string;
      appId: string;
      apiKey: string;
      senderId: string;
    };
    exp: number;
  };
}

export class ChannelService {
  constructor(
    private db: Database.Database,
    private settingsService: SettingsService,
    private firebaseAdminService: FirebaseAdminService
  ) {}

  createRelay(name: string, color?: string): RelayProvisioningPayload {
    const settings = this.settingsService.getAll();
    if (!settings.publicUrl) {
      throw { status: 409, code: 'public_url_required', message: 'public_url setting is required to provision relays' };
    }

    const firebaseParams = this.firebaseAdminService.getClientParams();
    if (!firebaseParams) {
      throw { status: 409, code: 'firebase_not_configured', message: 'Firebase client config is not available' };
    }

    const channelId = crypto.randomUUID();
    const { apiKey, hash, prefix } = generateRelayKey();
    const now = Date.now();
    const expiresAt = now + TTLS.PENDING_CHANNEL_MS;

    this.db.prepare(`
      INSERT INTO channels (
        channel_id, name, color, status, api_key_hash, api_key_prefix,
        created_at, pending_expires_at
      ) VALUES (?, ?, ?, 'pending', ?, ?, ?, ?)
    `).run(channelId, name, color || null, hash, prefix, now, expiresAt);

    return {
      channelId,
      expiresAt,
      qrPayload: {
        t: 'cn-relay',
        v: 1,
        server: {
          url: settings.publicUrl,
          name: settings.serverName,
        },
        channel: {
          id: channelId,
          name,
          color: color || null,
        },
        apiKey,
        firebase: firebaseParams,
        exp: expiresAt,
      },
    };
  }

  regenerateRelay(channelId: string): RelayProvisioningPayload {
    const channel = this.db.prepare('SELECT * FROM channels WHERE channel_id = ?').get(channelId) as ChannelRow | undefined;
    if (!channel) {
      throw { status: 404, code: 'channel_not_found', message: 'Channel not found' };
    }

    if (channel.status !== 'pending') {
      throw { status: 409, code: 'channel_not_pending', message: 'Can only regenerate pending channels' };
    }

    const settings = this.settingsService.getAll();
    if (!settings.publicUrl) {
      throw { status: 409, code: 'public_url_required', message: 'public_url setting is required' };
    }

    const firebaseParams = this.firebaseAdminService.getClientParams();
    if (!firebaseParams) {
      throw { status: 409, code: 'firebase_not_configured', message: 'Firebase client config is not available' };
    }

    const { apiKey, hash, prefix } = generateRelayKey();
    const now = Date.now();
    const expiresAt = now + TTLS.PENDING_CHANNEL_MS;

    this.db.prepare(`
      UPDATE channels SET
        api_key_hash = ?,
        api_key_prefix = ?,
        pending_expires_at = ?
      WHERE channel_id = ?
    `).run(hash, prefix, expiresAt, channelId);

    return {
      channelId,
      expiresAt,
      qrPayload: {
        t: 'cn-relay',
        v: 1,
        server: {
          url: settings.publicUrl,
          name: settings.serverName,
        },
        channel: {
          id: channelId,
          name: channel.name,
          color: channel.color,
        },
        apiKey,
        firebase: firebaseParams,
        exp: expiresAt,
      },
    };
  }

  initRelay(
    apiKeyHash: string,
    deviceInfo: {
      deviceName?: string;
      deviceModel?: string;
      osVersion?: string;
      appVersion?: string;
      fcmToken?: string;
    }
  ): {
    channelId: string;
    channelName: string;
    color: string | null;
    serverName: string;
    notificationTtlHours: number;
  } {
    const channel = this.db.prepare('SELECT * FROM channels WHERE api_key_hash = ?').get(apiKeyHash) as ChannelRow | undefined;
    if (!channel || channel.status === 'revoked') {
      throw { status: 401, code: 'invalid_api_key', message: 'Invalid or revoked API key' };
    }

    const now = Date.now();
    if (channel.status === 'pending') {
      if (channel.pending_expires_at && channel.pending_expires_at < now) {
        throw { status: 410, code: 'provisioning_expired', message: 'Relay provisioning QR has expired' };
      }

      this.db.prepare(`
        UPDATE channels SET
          status = 'active',
          activated_at = ?,
          pending_expires_at = NULL,
          last_seen_at = ?,
          relay_device_name = ?,
          relay_device_model = ?,
          relay_os_version = ?,
          relay_app_version = ?,
          fcm_token = ?
        WHERE channel_id = ?
      `).run(
        now,
        now,
        deviceInfo.deviceName || null,
        deviceInfo.deviceModel || null,
        deviceInfo.osVersion || null,
        deviceInfo.appVersion || null,
        deviceInfo.fcmToken || null,
        channel.channel_id
      );
    } else if (channel.status === 'active') {
      // Idempotent update
      this.db.prepare(`
        UPDATE channels SET
          last_seen_at = ?,
          relay_device_name = COALESCE(?, relay_device_name),
          relay_device_model = COALESCE(?, relay_device_model),
          relay_os_version = COALESCE(?, relay_os_version),
          relay_app_version = COALESCE(?, relay_app_version),
          fcm_token = COALESCE(?, fcm_token)
        WHERE channel_id = ?
      `).run(
        now,
        deviceInfo.deviceName || null,
        deviceInfo.deviceModel || null,
        deviceInfo.osVersion || null,
        deviceInfo.appVersion || null,
        deviceInfo.fcmToken || null,
        channel.channel_id
      );
    }

    const settings = this.settingsService.getAll();
    return {
      channelId: channel.channel_id,
      channelName: channel.name,
      color: channel.color,
      serverName: settings.serverName,
      notificationTtlHours: settings.notificationTtlHours,
    };
  }

  getChannelById(channelId: string): ChannelRow | null {
    const row = this.db.prepare('SELECT * FROM channels WHERE channel_id = ?').get(channelId) as ChannelRow | undefined;
    return row || null;
  }

  getActiveSubscriberTokens(channelId: string, excludeSubscriberId?: string): string[] {
    const rows = excludeSubscriberId
      ? (this.db.prepare(`
          SELECT fcm_token FROM subscribers
          WHERE channel_id = ? AND status = 'active' AND fcm_token IS NOT NULL AND subscriber_id != ?
        `).all(channelId, excludeSubscriberId) as { fcm_token: string }[])
      : (this.db.prepare(`
          SELECT fcm_token FROM subscribers
          WHERE channel_id = ? AND status = 'active' AND fcm_token IS NOT NULL
        `).all(channelId) as { fcm_token: string }[]);
    return rows.map((r) => r.fcm_token).filter((t): t is string => !!t && !t.startsWith('fcm_pending_'));
  }

  updateRelay(
    channelId: string,
    updates: {
      deviceName?: string | null;
      deviceModel?: string | null;
      osVersion?: string | null;
      appVersion?: string | null;
      fcmToken?: string | null;
    }
  ): { channel: ChannelRow; previousDeviceName: string | null } {
    const channel = this.getChannelById(channelId);
    if (!channel || channel.status === 'revoked') {
      throw { status: 404, code: 'channel_not_found', message: 'Channel not found or revoked' };
    }

    const previousDeviceName = channel.relay_device_name;
    const now = Date.now();
    this.db.prepare(`
      UPDATE channels SET
        last_seen_at = ?,
        relay_device_name = COALESCE(?, relay_device_name),
        relay_device_model = COALESCE(?, relay_device_model),
        relay_os_version = COALESCE(?, relay_os_version),
        relay_app_version = COALESCE(?, relay_app_version)
      WHERE channel_id = ?
    `).run(
      now,
      updates.deviceName || null,
      updates.deviceModel || null,
      updates.osVersion || null,
      updates.appVersion || null,
      channelId
    );

    if (updates.fcmToken !== undefined) {
      this.db.prepare('UPDATE channels SET fcm_token = ? WHERE channel_id = ?').run(updates.fcmToken, channelId);
    }

    return { channel: this.getChannelById(channelId)!, previousDeviceName };
  }

  getChannelByApiKeyHash(hash: string): ChannelRow | null {
    const row = this.db.prepare('SELECT * FROM channels WHERE api_key_hash = ?').get(hash) as ChannelRow | undefined;
    return row || null;
  }

  listChannels(): any[] {
    const channels = this.db.prepare(`
      SELECT
        c.channel_id as channelId,
        c.name,
        c.color,
        c.status,
        c.relay_device_name as relayDeviceName,
        c.relay_device_model as relayDeviceModel,
        c.created_at as createdAt,
        c.activated_at as activatedAt,
        c.last_seen_at as lastSeenAt,
        c.pending_expires_at as pendingExpiresAt,
        COUNT(DISTINCT CASE WHEN s.status = 'active' THEN s.id END) as subscriberCount,
        COUNT(DISTINCT n.notification_id) as storedNotifications
      FROM channels c
      LEFT JOIN subscribers s ON s.channel_id = c.channel_id
      LEFT JOIN notifications n ON n.channel_id = c.channel_id
      GROUP BY c.channel_id
      ORDER BY c.created_at DESC
    `).all();

    return channels;
  }

  getChannelDetail(channelId: string): any {
    const channel = this.db.prepare(`
      SELECT
        c.channel_id as channelId,
        c.name,
        c.color,
        c.status,
        c.relay_device_name as relayDeviceName,
        c.relay_device_model as relayDeviceModel,
        c.relay_os_version as relayOsVersion,
        c.relay_app_version as relayAppVersion,
        c.created_at as createdAt,
        c.activated_at as activatedAt,
        c.last_seen_at as lastSeenAt,
        c.pending_expires_at as pendingExpiresAt,
        COUNT(DISTINCT CASE WHEN s.status = 'active' THEN s.id END) as subscriberCount,
        COUNT(DISTINCT n.notification_id) as storedNotifications
      FROM channels c
      LEFT JOIN subscribers s ON s.channel_id = c.channel_id
      LEFT JOIN notifications n ON n.channel_id = c.channel_id
      WHERE c.channel_id = ?
      GROUP BY c.channel_id
    `).get(channelId) as any;

    if (!channel) return null;

    const subscribers = (this.db.prepare(`
      SELECT
        subscriber_id as subscriberId,
        device_name as deviceName,
        device_model as deviceModel,
        app_version as appVersion,
        delivery_mode as deliveryMode,
        allow_actions as allowActions,
        status,
        created_at as createdAt,
        activated_at as activatedAt,
        last_seen_at as lastSeenAt
      FROM subscribers
      WHERE channel_id = ?
      ORDER BY created_at DESC
    `).all(channelId) as Array<any>).map((s) => ({
      ...s,
      allowActions: s.allowActions !== 0,
    }));

    return {
      ...channel,
      subscribers,
    };
  }

  updateChannel(channelId: string, updates: { name?: string; color?: string }): ChannelRow {
    const channel = this.getChannelById(channelId);
    if (!channel) {
      throw { status: 404, code: 'channel_not_found', message: 'Channel not found' };
    }

    if (updates.name !== undefined) {
      this.db.prepare('UPDATE channels SET name = ? WHERE channel_id = ?').run(updates.name, channelId);
    }
    if (updates.color !== undefined) {
      this.db.prepare('UPDATE channels SET color = ? WHERE channel_id = ?').run(updates.color, channelId);
    }

    return this.getChannelById(channelId)!;
  }

  revokeChannel(channelId: string): { activeSubscriberTokens: string[]; relayFcmToken: string | null } {
    const channel = this.getChannelById(channelId);
    if (!channel) {
      throw { status: 404, code: 'channel_not_found', message: 'Channel not found' };
    }

    const now = Date.now();
    // Fetch active subscriber tokens for push notification
    const activeSubscribers = this.db.prepare(`
      SELECT fcm_token FROM subscribers
      WHERE channel_id = ? AND status = 'active' AND fcm_token IS NOT NULL
    `).all(channelId) as { fcm_token: string }[];

    const activeSubscriberTokens = activeSubscribers
      .map((s) => s.fcm_token)
      .filter((t): t is string => !!t && !t.startsWith('fcm_pending_'));

    const relayFcmToken = channel.fcm_token && !channel.fcm_token.startsWith('fcm_pending_')
      ? channel.fcm_token
      : null;

    this.db.transaction(() => {
      this.db.prepare(`
        UPDATE channels SET status = 'revoked', revoked_at = ? WHERE channel_id = ?
      `).run(now, channelId);

      this.db.prepare(`
        UPDATE subscribers SET status = 'revoked', revoked_at = ? WHERE channel_id = ?
      `).run(now, channelId);

      this.db.prepare('DELETE FROM notifications WHERE channel_id = ?').run(channelId);
    })();

    return { activeSubscriberTokens, relayFcmToken };
  }

  revokeAllChannels(): { channelsToNotify: Array<{ channel: ChannelRow; tokens: string[] }> } {
    const now = Date.now();
    const channels = this.db.prepare("SELECT * FROM channels WHERE status IN ('pending', 'active')").all() as ChannelRow[];
    const channelsToNotify: Array<{ channel: ChannelRow; tokens: string[] }> = [];

    for (const ch of channels) {
      const activeSubs = this.db.prepare(`
        SELECT fcm_token FROM subscribers
        WHERE channel_id = ? AND status = 'active' AND fcm_token IS NOT NULL
      `).all(ch.channel_id) as { fcm_token: string }[];
      const tokens = activeSubs
        .map((s) => s.fcm_token)
        .filter((t): t is string => !!t && !t.startsWith('fcm_pending_'));

      if (ch.fcm_token && !ch.fcm_token.startsWith('fcm_pending_') && !tokens.includes(ch.fcm_token)) {
        tokens.push(ch.fcm_token);
      }
      if (tokens.length > 0) {
        channelsToNotify.push({ channel: ch, tokens });
      }
    }

    this.db.transaction(() => {
      this.db.prepare(`UPDATE channels SET status = 'revoked', revoked_at = ? WHERE status IN ('pending', 'active')`).run(now);
      this.db.prepare(`UPDATE subscribers SET status = 'revoked', revoked_at = ? WHERE status IN ('pending', 'active')`).run(now);
      this.db.prepare('DELETE FROM notifications').run();
    })();

    return { channelsToNotify };
  }

  updateLastSeen(channelId: string): void {
    this.db.prepare('UPDATE channels SET last_seen_at = ? WHERE channel_id = ?').run(Date.now(), channelId);
  }

  countActiveAndPending(): number {
    const row = this.db.prepare(`
      SELECT COUNT(*) as count FROM channels WHERE status IN ('pending', 'active')
    `).get() as { count: number };
    return row.count;
  }

  deleteChannel(channelId: string): void {
    const channel = this.getChannelById(channelId);
    if (!channel) {
      throw { status: 404, code: 'channel_not_found', message: 'Channel not found' };
    }

    this.db.transaction(() => {
      this.db.prepare('DELETE FROM notifications WHERE channel_id = ?').run(channelId);
      this.db.prepare('DELETE FROM subscribers WHERE channel_id = ?').run(channelId);
      this.db.prepare('DELETE FROM channels WHERE channel_id = ?').run(channelId);
    })();
  }

  deleteRevokedChannels(): number {
    let deleted = 0;
    this.db.transaction(() => {
      const revoked = this.db.prepare("SELECT channel_id FROM channels WHERE status = 'revoked'").all() as { channel_id: string }[];
      for (const row of revoked) {
        this.db.prepare('DELETE FROM notifications WHERE channel_id = ?').run(row.channel_id);
        this.db.prepare('DELETE FROM subscribers WHERE channel_id = ?').run(row.channel_id);
        this.db.prepare('DELETE FROM channels WHERE channel_id = ?').run(row.channel_id);
        deleted++;
      }
    })();
    return deleted;
  }
}
