import type { Message } from 'firebase-admin/messaging';
import { LIMITS } from '../constants.js';
import type { ChannelRow } from './channelService.js';
import type { HandledNotificationEvent, NotificationRow } from './notificationService.js';
import type { FirebaseAdminService } from './firebaseAdmin.js';
import type { SubscriberService } from './subscriberService.js';

export interface PushResult {
  sent: number;
  failed: number;
  removed: number;
}

export interface ExecuteActionPushPayload {
  notificationId: string;
  actionId: string;
  iv: string;
  ciphertext: string;
  subscriberId: string;
}

export interface PushService {
  dispatch(channel: ChannelRow, n: NotificationRow): Promise<PushResult>;
  sendChannelRevoked(channel: ChannelRow, tokens: string[], subscriberId?: string): Promise<void>;
  sendRelayNameUpdated(channel: ChannelRow, tokens: string[], newRelayDeviceName: string): Promise<void>;
  sendNotificationsHandled(channel: ChannelRow, tokens: string[], items: HandledNotificationEvent[]): Promise<void>;
  sendExecuteAction(channel: ChannelRow, relayFcmToken: string, payload: ExecuteActionPushPayload): Promise<boolean>;
  sendSubscriberPermissionsUpdated(channel: ChannelRow, token: string, subscriberId: string, allowActions: boolean): Promise<void>;
}

export class FcmPushService implements PushService {
  constructor(
    private firebaseAdminService: FirebaseAdminService,
    private subscriberService: SubscriberService
  ) {}

  async dispatch(channel: ChannelRow, n: NotificationRow): Promise<PushResult> {
    const messaging = this.firebaseAdminService.getMessaging();
    if (!messaging) {
      console.warn('[pushService] Firebase messaging not initialized, skipping push');
      return { sent: 0, failed: 0, removed: 0 };
    }

    const subscribers = this.subscriberService.getActiveSubscribersForChannel(channel.channel_id);
    const validSubscribers = subscribers.filter(
      (s) => s.fcm_token && s.fcm_token.trim().length > 0 && !s.fcm_token.startsWith('fcm_pending_')
    );

    if (validSubscribers.length === 0) {
      return { sent: 0, failed: 0, removed: 0 };
    }

    const now = Date.now();
    const ttlMs = Math.max(0, n.expires_at - now);

    const messages: Message[] = [];
    const tokens: string[] = [];

    for (const sub of validSubscribers) {
      if (this.subscriberService.isSubscriberSilenced(sub.subscriber_id, n.app_name)) {
        continue;
      }

      const token = sub.fcm_token!;
      tokens.push(token);

      if (sub.delivery_mode === 'battery_saver') {
        const title = `${channel.name} • ${n.app_name ?? 'New notification'}`;
        messages.push({
          token,
          notification: {
            title,
            body: 'Tap to decrypt and view',
          },
          data: {
            cn: '1',
            type: 'notification',
            channelId: channel.channel_id,
            notificationId: n.notification_id,
            ts: String(n.created_at),
          },
          android: {
            priority: 'high',
            ttl: ttlMs,
            notification: {
              channelId: 'crossnotify_relay',
              tag: n.notification_id,
              clickAction: 'com.crossnotify.app.OPEN_NOTIFICATION',
            },
          },
        });
      } else {
        // immediate_preview
        let dataPayload: Record<string, string> = {
          cn: '1',
          type: 'notification',
          channelId: channel.channel_id,
          notificationId: n.notification_id,
          ts: String(n.created_at),
          iv: n.iv,
          ct: n.ciphertext,
          app: n.app_name ?? '',
        };

        const serialized = JSON.stringify(dataPayload);
        if (Buffer.byteLength(serialized, 'utf8') > LIMITS.FCM_DATA_MAX_BYTES) {
          dataPayload = {
            cn: '1',
            type: 'notification',
            channelId: channel.channel_id,
            notificationId: n.notification_id,
            ts: String(n.created_at),
            app: n.app_name ?? '',
            fetch: '1',
          };
        }

        messages.push({
          token,
          data: dataPayload,
          android: {
            priority: 'high',
            ttl: ttlMs,
          },
        });
      }
    }

    let sent = 0;
    let failed = 0;
    let removed = 0;

    // Send in batches of 500
    const BATCH_SIZE = 500;
    for (let i = 0; i < messages.length; i += BATCH_SIZE) {
      const batch = messages.slice(i, i + BATCH_SIZE);
      const batchTokens = tokens.slice(i, i + BATCH_SIZE);

      try {
        const response = await messaging.sendEach(batch);
        sent += response.successCount;
        failed += response.failureCount;

        response.responses.forEach((res, idx) => {
          if (!res.success && res.error) {
            const errCode = res.error.code;
            const token = batchTokens[idx];
            if (
              errCode === 'messaging/registration-token-not-registered' ||
              errCode === 'messaging/invalid-registration-token'
            ) {
              console.log(`[pushService] Removing unregistered FCM token: ${token.slice(0, 10)}...`);
              this.subscriberService.removeToken(token, channel.channel_id);
              removed++;
            } else {
              console.warn(`[pushService] FCM send error for token ${token.slice(0, 10)}...:`, res.error.message);
            }
          }
        });
      } catch (err: any) {
        console.error('[pushService] Error sending FCM batch:', err.message);
        failed += batch.length;
      }
    }

    return { sent, failed, removed };
  }

  async sendChannelRevoked(channel: ChannelRow, tokens: string[], subscriberId?: string): Promise<void> {
    const messaging = this.firebaseAdminService.getMessaging();
    if (!messaging || tokens.length === 0) {
      return;
    }

    const messages: Message[] = tokens.map((token) => ({
      token,
      data: {
        cn: '1',
        type: 'channel_revoked',
        channelId: channel.channel_id,
        ...(subscriberId ? { subscriberId } : {}),
      },
      android: {
        priority: 'high',
      },
    }));

    const BATCH_SIZE = 500;
    for (let i = 0; i < messages.length; i += BATCH_SIZE) {
      const batch = messages.slice(i, i + BATCH_SIZE);
      try {
        await messaging.sendEach(batch);
      } catch (err: any) {
        console.error('[pushService] Error sending channel_revoked push:', err.message);
      }
    }
  }

  async sendRelayNameUpdated(channel: ChannelRow, tokens: string[], newRelayDeviceName: string): Promise<void> {
    const messaging = this.firebaseAdminService.getMessaging();
    if (!messaging || tokens.length === 0) {
      return;
    }

    const messages: Message[] = tokens.map((token) => ({
      token,
      data: {
        cn: '1',
        type: 'relay_name_updated',
        channelId: channel.channel_id,
        relayDeviceName: newRelayDeviceName,
      },
      android: {
        priority: 'high',
      },
    }));

    const BATCH_SIZE = 500;
    for (let i = 0; i < messages.length; i += BATCH_SIZE) {
      const batch = messages.slice(i, i + BATCH_SIZE);
      try {
        await messaging.sendEach(batch);
      } catch (err: any) {
        console.error('[pushService] Error sending relay_name_updated push:', err.message);
      }
    }
  }

  async sendNotificationsHandled(
    channel: ChannelRow,
    tokens: string[],
    items: HandledNotificationEvent[]
  ): Promise<void> {
    const messaging = this.firebaseAdminService.getMessaging();
    if (!messaging || tokens.length === 0 || items.length === 0) {
      return;
    }

    // Chunk items so each FCM data message stays well under 3.5 KB
    const CHUNK_SIZE = 25;
    for (let c = 0; c < items.length; c += CHUNK_SIZE) {
      const chunk = items.slice(c, c + CHUNK_SIZE);
      const compactItems = JSON.stringify(
        chunk.map((item) => ({
          id: item.notificationId,
          r: item.reason,
          at: item.handledAt,
        }))
      );

      const messages: Message[] = tokens.map((token) => ({
        token,
        data: {
          cn: '1',
          type: 'notifications_handled',
          channelId: channel.channel_id,
          items: compactItems,
        },
        android: {
          // Normal priority avoids waking subscriber radios unnecessarily in deep Doze
          // and prevents FCM high-priority quota throttling for non-display data messages.
          priority: 'normal',
          ttl: 24 * 60 * 60 * 1000,
        },
      }));

      const BATCH_SIZE = 500;
      for (let i = 0; i < messages.length; i += BATCH_SIZE) {
        const batch = messages.slice(i, i + BATCH_SIZE);
        try {
          await messaging.sendEach(batch);
        } catch (err: any) {
          console.error('[pushService] Error sending notifications_handled push:', err.message);
        }
      }
    }
  }

  async sendExecuteAction(
    channel: ChannelRow,
    relayFcmToken: string,
    payload: ExecuteActionPushPayload
  ): Promise<boolean> {
    const messaging = this.firebaseAdminService.getMessaging();
    if (!messaging || !relayFcmToken || relayFcmToken.startsWith('fcm_pending_')) {
      return false;
    }

    try {
      await messaging.send({
        token: relayFcmToken,
        data: {
          cn: '1',
          type: 'execute_action',
          channelId: channel.channel_id,
          notificationId: payload.notificationId,
          actionId: payload.actionId,
          iv: payload.iv,
          ct: payload.ciphertext,
          subscriberId: payload.subscriberId,
        },
        android: {
          priority: 'high',
          ttl: 60 * 1000,
        },
      });
      return true;
    } catch (err: any) {
      console.error('[pushService] Error sending execute_action push to relay:', err.message);
      return false;
    }
  }

  async sendSubscriberPermissionsUpdated(
    channel: ChannelRow,
    token: string,
    subscriberId: string,
    allowActions: boolean
  ): Promise<void> {
    const messaging = this.firebaseAdminService.getMessaging();
    if (!messaging || !token || token.startsWith('fcm_pending_')) {
      return;
    }

    try {
      await messaging.send({
        token,
        data: {
          cn: '1',
          type: 'subscriber_permissions_updated',
          channelId: channel.channel_id,
          subscriberId,
          allowActions: allowActions ? '1' : '0',
        },
        android: {
          priority: 'normal',
        },
      });
    } catch (err: any) {
      console.error('[pushService] Error sending subscriber_permissions_updated push:', err.message);
    }
  }
}
