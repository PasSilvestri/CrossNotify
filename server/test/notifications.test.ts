import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import request from 'supertest';
import crypto from 'node:crypto';
import {
  createTestContext,
  VALID_TEST_SERVICE_ACCOUNT,
  VALID_TEST_CLIENT_CONFIG,
} from './helpers/testApp.js';
import { FcmPushService } from '../src/services/pushService.js';
import { LIMITS } from '../src/constants.js';

describe('Publish, Push Dispatch & Catch-up Sync (Tests 7 & 8)', () => {
  let testEnv: Awaited<ReturnType<typeof createTestContext>>;
  let authCookie: string;
  let relayKey: string;
  let subKey: string;
  let channelId: string;

  beforeEach(async () => {
    testEnv = await createTestContext({
      crossnotifySetupToken: 'test-token',
    });

    // 1. Setup admin
    const setupRes = await request(testEnv.ctx.app)
      .post('/admin/api/setup/admin')
      .send({
        setupToken: 'test-token',
        username: 'admin',
        password: 'password123456!',
      });
    authCookie = setupRes.headers['set-cookie'][0];

    // 2. Set public url
    testEnv.ctx.services.settingsService.set('public_url', 'http://127.0.0.1:3000');

    // 3. Upload Firebase config
    await request(testEnv.ctx.app)
      .post('/admin/api/firebase/service-account')
      .set('Cookie', authCookie)
      .send(VALID_TEST_SERVICE_ACCOUNT);
    await request(testEnv.ctx.app)
      .post('/admin/api/firebase/client-config')
      .set('Cookie', authCookie)
      .send(VALID_TEST_CLIENT_CONFIG);

    // 4. Create and activate relay
    const relayRes = await request(testEnv.ctx.app)
      .post('/admin/api/relays')
      .set('Cookie', authCookie)
      .send({ name: 'Work Phone' });
    channelId = relayRes.body.channelId;
    relayKey = relayRes.body.qrPayload.apiKey;

    await request(testEnv.ctx.app)
      .post('/api/v1/relay/init')
      .set('Authorization', `Bearer ${relayKey}`)
      .send({ deviceName: 'Pixel 8' });

    // 5. Mint and activate subscriber
    const mintRes = await request(testEnv.ctx.app)
      .post(`/api/v1/channels/${channelId}/subscribers/keys`)
      .set('Authorization', `Bearer ${relayKey}`);
    subKey = mintRes.body.apiKey;

    await request(testEnv.ctx.app)
      .post(`/api/v1/channels/${channelId}/subscribers/init`)
      .set('Authorization', `Bearer ${subKey}`)
      .send({
        fcmToken: 'fcm_token_sub_1',
        deviceName: 'Subscriber Device',
        deliveryMode: 'battery_saver',
      });
  });

  afterEach(() => {
    testEnv.cleanup();
  });

  it('publishes a new notification, returns 201 and dispatches push once', async () => {
    const notifId = crypto.randomUUID();
    const iv = crypto.randomBytes(12).toString('base64url');
    const ciphertext = Buffer.from('encrypted_payload_bytes').toString('base64url');

    const res = await request(testEnv.ctx.app)
      .post(`/api/v1/channels/${channelId}/notifications`)
      .set('Authorization', `Bearer ${relayKey}`)
      .send({
        notificationId: notifId,
        iv,
        ciphertext,
        appName: 'WhatsApp',
        timestamp: Date.now(),
      });

    expect(res.status).toBe(201);
    expect(res.body.notificationId).toBe(notifId);
    expect(res.body.receivedAt).toBeDefined();

    // Verify mock push dispatch
    expect(testEnv.mockPush.dispatched.length).toBe(1);
    expect(testEnv.mockPush.dispatched[0].channel.channel_id).toBe(channelId);
    expect(testEnv.mockPush.dispatched[0].notification.notification_id).toBe(notifId);
  });

  it('duplicate notification ID returns 200 and does NOT trigger push again', async () => {
    const notifId = crypto.randomUUID();
    const iv = crypto.randomBytes(12).toString('base64url');
    const ciphertext = Buffer.from('payload').toString('base64url');

    // First publish
    const res1 = await request(testEnv.ctx.app)
      .post(`/api/v1/channels/${channelId}/notifications`)
      .set('Authorization', `Bearer ${relayKey}`)
      .send({ notificationId: notifId, iv, ciphertext });
    expect(res1.status).toBe(201);
    expect(testEnv.mockPush.dispatched.length).toBe(1);

    // Duplicate publish
    const res2 = await request(testEnv.ctx.app)
      .post(`/api/v1/channels/${channelId}/notifications`)
      .set('Authorization', `Bearer ${relayKey}`)
      .send({ notificationId: notifId, iv, ciphertext });
    expect(res2.status).toBe(200);
    expect(res2.body.notificationId).toBe(notifId);

    // Push count remains 1
    expect(testEnv.mockPush.dispatched.length).toBe(1);
  });

  it('supports catch-up sync with pagination (since & limit) and single notification fetch', async () => {
    // Publish 3 notifications
    const ids: string[] = [];
    for (let i = 0; i < 3; i++) {
      const id = crypto.randomUUID();
      ids.push(id);
      await request(testEnv.ctx.app)
        .post(`/api/v1/channels/${channelId}/notifications`)
        .set('Authorization', `Bearer ${relayKey}`)
        .send({
          notificationId: id,
          iv: crypto.randomBytes(12).toString('base64url'),
          ciphertext: Buffer.from(`message ${i}`).toString('base64url'),
          appName: `App ${i}`,
        });
    }

    // Page 1: limit 2
    const page1 = await request(testEnv.ctx.app)
      .get(`/api/v1/channels/${channelId}/notifications?limit=2`)
      .set('Authorization', `Bearer ${subKey}`);

    expect(page1.status).toBe(200);
    expect(page1.body.notifications.length).toBe(2);
    expect(page1.body.hasMore).toBe(true);

    const lastReceived = page1.body.notifications[1].receivedAt;

    // Page 2: since lastReceived
    const page2 = await request(testEnv.ctx.app)
      .get(`/api/v1/channels/${channelId}/notifications?since=${lastReceived}&limit=2`)
      .set('Authorization', `Bearer ${subKey}`);

    expect(page2.status).toBe(200);
    expect(page2.body.notifications.length).toBe(1);
    expect(page2.body.hasMore).toBe(false);
    expect(page2.body.notifications[0].notificationId).toBe(ids[2]);

    // Single notification fetch
    const single = await request(testEnv.ctx.app)
      .get(`/api/v1/channels/${channelId}/notifications/${ids[0]}`)
      .set('Authorization', `Bearer ${subKey}`);

    expect(single.status).toBe(200);
    expect(single.body.notificationId).toBe(ids[0]);
    expect(single.body.appName).toBe('App 0');
  });

  describe('FCM Push Dispatch unit tests (message formats, oversize handling, token cleanup)', () => {
    it('formats battery_saver vs immediate_preview and switches oversize to fetch: 1', async () => {
      let sentMessages: any[] = [];
      const fakeMessaging = {
        sendEach: async (msgs: any[]) => {
          sentMessages = msgs;
          return {
            successCount: msgs.length,
            failureCount: 0,
            responses: msgs.map(() => ({ success: true })),
          };
        },
      };

      const fakeAdmin = {
        getMessaging: () => fakeMessaging,
      };

      const pushService = new FcmPushService(fakeAdmin as any, testEnv.ctx.services.subscriberService);

      // Create subscriber in immediate_preview mode
      const mint2 = await request(testEnv.ctx.app)
        .post(`/api/v1/channels/${channelId}/subscribers/keys`)
        .set('Authorization', `Bearer ${relayKey}`);
      await request(testEnv.ctx.app)
        .post(`/api/v1/channels/${channelId}/subscribers/init`)
        .set('Authorization', `Bearer ${mint2.body.apiKey}`)
        .send({
          fcmToken: 'fcm_token_immediate',
          deliveryMode: 'immediate_preview',
        });

      const channel = testEnv.ctx.services.channelService.getChannelById(channelId)!;

      // Small payload
      const smallNotif = {
        notification_id: 'small-1',
        channel_id: channelId,
        iv: '123456789012',
        ciphertext: 'small_data',
        app_name: 'Signal',
        created_at: 1000,
        relay_timestamp: 1000,
        expires_at: Date.now() + 100000,
      };

      await pushService.dispatch(channel, smallNotif);
      expect(sentMessages.length).toBe(2); // 1 battery saver, 1 immediate preview

      const batteryMsg = sentMessages.find((m) => m.token === 'fcm_token_sub_1');
      expect(batteryMsg.notification.title).toContain('Work Phone • Signal');
      expect(batteryMsg.data.type).toBe('notification');

      const immediateMsg = sentMessages.find((m) => m.token === 'fcm_token_immediate');
      expect(immediateMsg.notification).toBeUndefined();
      expect(immediateMsg.data.iv).toBe('123456789012');
      expect(immediateMsg.data.ct).toBe('small_data');
      expect(immediateMsg.data.fetch).toBeUndefined();

      // Large payload (> 3500 bytes)
      const largeCt = 'A'.repeat(4000);
      const largeNotif = {
        ...smallNotif,
        notification_id: 'large-1',
        ciphertext: largeCt,
      };

      await pushService.dispatch(channel, largeNotif);
      const largeImmediateMsg = sentMessages.find((m) => m.token === 'fcm_token_immediate');
      expect(largeImmediateMsg.data.fetch).toBe('1');
      expect(largeImmediateMsg.data.iv).toBeUndefined();
      expect(largeImmediateMsg.data.ct).toBeUndefined();
    });

    it('removes unregistered/invalid registration tokens', async () => {
      const fakeMessaging = {
        sendEach: async (msgs: any[]) => {
          return {
            successCount: 0,
            failureCount: msgs.length,
            responses: [
              {
                success: false,
                error: { code: 'messaging/registration-token-not-registered', message: 'Token expired' },
              },
            ],
          };
        },
      };

      const fakeAdmin = {
        getMessaging: () => fakeMessaging,
      };

      const pushService = new FcmPushService(fakeAdmin as any, testEnv.ctx.services.subscriberService);
      const channel = testEnv.ctx.services.channelService.getChannelById(channelId)!;

      const notif = {
        notification_id: 'test-1',
        channel_id: channelId,
        iv: '123',
        ciphertext: 'ct',
        app_name: 'App',
        created_at: 1000,
        relay_timestamp: 1000,
        expires_at: Date.now() + 100000,
      };

      const result = await pushService.dispatch(channel, notif);
      expect(result.removed).toBe(1);

      // Verify subscriber was removed
      const remaining = testEnv.ctx.services.subscriberService.getActiveSubscribersForChannel(channelId);
      expect(remaining.length).toBe(0);
    });
  });

  describe('Dismissal Sync, Action Execution & Permissions (v1.2.0)', () => {
    it('batches relay dismissals and broadcasts notifications_handled to subscribers', async () => {
      const id1 = crypto.randomUUID();
      const id2 = crypto.randomUUID();
      for (const id of [id1, id2]) {
        await request(testEnv.ctx.app)
          .post(`/api/v1/channels/${channelId}/notifications`)
          .set('Authorization', `Bearer ${relayKey}`)
          .send({
            notificationId: id,
            iv: crypto.randomBytes(12).toString('base64url'),
            ciphertext: Buffer.from('msg').toString('base64url'),
          });
      }

      const dismissRes = await request(testEnv.ctx.app)
        .post(`/api/v1/channels/${channelId}/notifications/dismiss`)
        .set('Authorization', `Bearer ${relayKey}`)
        .send({
          items: [
            { notificationId: id1, reason: 'opened' },
            { notificationId: id2, reason: 'dismissed' },
          ],
        });

      expect(dismissRes.status).toBe(200);
      expect(dismissRes.body.updatedCount).toBe(2);
      expect(testEnv.mockPush.handledCalls.length).toBe(1);
      expect(testEnv.mockPush.handledCalls[0].tokens).toEqual(['fcm_token_sub_1']);
      expect(testEnv.mockPush.handledCalls[0].items.length).toBe(2);

      // Catch-up includes handledAt and handledReason
      const catchUp = await request(testEnv.ctx.app)
        .get(`/api/v1/channels/${channelId}/notifications`)
        .set('Authorization', `Bearer ${subKey}`);
      expect(catchUp.status).toBe(200);
      const n1 = catchUp.body.notifications.find((n: any) => n.notificationId === id1);
      expect(n1.handledAt).toBeTruthy();
      expect(n1.handledReason).toBe('opened');
    });

    it('executes action atomically, blocks concurrent second subscriber with 409, and notifies other subscribers', async () => {
      // Register relay FCM token
      await request(testEnv.ctx.app)
        .put(`/api/v1/channels/${channelId}/relay`)
        .set('Authorization', `Bearer ${relayKey}`)
        .send({ fcmToken: 'fcm_token_relay_1' });

      // Add a second subscriber
      const mint2 = await request(testEnv.ctx.app)
        .post(`/api/v1/channels/${channelId}/subscribers/keys`)
        .set('Authorization', `Bearer ${relayKey}`)
        .send({ allowActions: true });
      const subKey2 = mint2.body.apiKey;
      await request(testEnv.ctx.app)
        .post(`/api/v1/channels/${channelId}/subscribers/init`)
        .set('Authorization', `Bearer ${subKey2}`)
        .send({ fcmToken: 'fcm_token_sub_2', deliveryMode: 'immediate_preview' });

      // Publish notification
      const notifId = crypto.randomUUID();
      await request(testEnv.ctx.app)
        .post(`/api/v1/channels/${channelId}/notifications`)
        .set('Authorization', `Bearer ${relayKey}`)
        .send({
          notificationId: notifId,
          iv: crypto.randomBytes(12).toString('base64url'),
          ciphertext: Buffer.from('hello').toString('base64url'),
        });

      // Subscriber 1 executes action -> succeeds
      const act1 = await request(testEnv.ctx.app)
        .post(`/api/v1/channels/${channelId}/notifications/${notifId}/actions`)
        .set('Authorization', `Bearer ${subKey}`)
        .send({
          actionId: crypto.randomUUID(),
          iv: crypto.randomBytes(12).toString('base64url'),
          ciphertext: Buffer.from('reply_payload').toString('base64url'),
        });

      expect(act1.status).toBe(200);
      expect(act1.body.dispatched).toBe(true);
      expect(testEnv.mockPush.executeActionCalls.length).toBe(1);
      expect(testEnv.mockPush.executeActionCalls[0].relayFcmToken).toBe('fcm_token_relay_1');

      // Other subscriber (sub 2) gets notifications_handled push
      expect(testEnv.mockPush.handledCalls.length).toBe(1);
      expect(testEnv.mockPush.handledCalls[0].tokens).toEqual(['fcm_token_sub_2']);

      // Subscriber 2 attempts action -> 409 already_handled, relay is NOT woken again
      const act2 = await request(testEnv.ctx.app)
        .post(`/api/v1/channels/${channelId}/notifications/${notifId}/actions`)
        .set('Authorization', `Bearer ${subKey2}`)
        .send({
          actionId: crypto.randomUUID(),
          iv: crypto.randomBytes(12).toString('base64url'),
          ciphertext: Buffer.from('reply_payload_2').toString('base64url'),
        });

      expect(act2.status).toBe(409);
      expect(act2.body.error).toBe('already_handled');
      expect(testEnv.mockPush.executeActionCalls.length).toBe(1);
    });

    it('enforces allowActions permission per subscriber via mint and update endpoint', async () => {
      await request(testEnv.ctx.app)
        .put(`/api/v1/channels/${channelId}/relay`)
        .set('Authorization', `Bearer ${relayKey}`)
        .send({ fcmToken: 'fcm_token_relay_1' });

      // Mint subscriber with default allowActions=true, then toggle to false before pairing
      const mint = await request(testEnv.ctx.app)
        .post(`/api/v1/channels/${channelId}/subscribers/keys`)
        .set('Authorization', `Bearer ${relayKey}`);
      expect(mint.body.allowActions).toBe(true);

      const toggleRes = await request(testEnv.ctx.app)
        .put(`/api/v1/channels/${channelId}/subscribers/${mint.body.subscriberId}`)
        .set('Authorization', `Bearer ${relayKey}`)
        .send({ allowActions: false });
      expect(toggleRes.status).toBe(200);
      expect(toggleRes.body.allowActions).toBe(false);

      const initRes = await request(testEnv.ctx.app)
        .post(`/api/v1/channels/${channelId}/subscribers/init`)
        .set('Authorization', `Bearer ${mint.body.apiKey}`)
        .send({ fcmToken: 'fcm_token_readonly', deliveryMode: 'immediate_preview' });
      expect(initRes.status).toBe(200);
      expect(initRes.body.allowActions).toBe(false);

      const notifId = crypto.randomUUID();
      await request(testEnv.ctx.app)
        .post(`/api/v1/channels/${channelId}/notifications`)
        .set('Authorization', `Bearer ${relayKey}`)
        .send({
          notificationId: notifId,
          iv: crypto.randomBytes(12).toString('base64url'),
          ciphertext: Buffer.from('hello').toString('base64url'),
        });

      const actRes = await request(testEnv.ctx.app)
        .post(`/api/v1/channels/${channelId}/notifications/${notifId}/actions`)
        .set('Authorization', `Bearer ${mint.body.apiKey}`)
        .send({
          actionId: crypto.randomUUID(),
          iv: crypto.randomBytes(12).toString('base64url'),
          ciphertext: Buffer.from('reply').toString('base64url'),
        });

      expect(actRes.status).toBe(403);
      expect(actRes.body.error).toBe('actions_not_allowed');
    });
  });
});
