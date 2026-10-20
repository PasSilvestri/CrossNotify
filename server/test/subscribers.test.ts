import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import request from 'supertest';
import {
  createTestContext,
  VALID_TEST_SERVICE_ACCOUNT,
  VALID_TEST_CLIENT_CONFIG,
} from './helpers/testApp.js';

describe('Subscriber Key Engine & Individual Revocation (Test 9)', () => {
  let testEnv: Awaited<ReturnType<typeof createTestContext>>;
  let authCookie: string;
  let relayKey: string;
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
      .send({ name: 'Work Relay' });
    channelId = relayRes.body.channelId;
    relayKey = relayRes.body.qrPayload.apiKey;

    await request(testEnv.ctx.app)
      .post('/api/v1/relay/init')
      .set('Authorization', `Bearer ${relayKey}`)
      .send({ deviceName: 'Pixel 8' });
  });

  afterEach(() => {
    testEnv.cleanup();
  });

  it('relay mints unique subscriber key and subscriber initializes successfully', async () => {
    // 1. Relay mints key
    const mintRes = await request(testEnv.ctx.app)
      .post(`/api/v1/channels/${channelId}/subscribers/keys`)
      .set('Authorization', `Bearer ${relayKey}`);

    expect(mintRes.status).toBe(201);
    expect(mintRes.body.subscriberId).toBeDefined();
    expect(mintRes.body.apiKey.startsWith('cns_')).toBe(true);
    expect(mintRes.body.expiresAt).toBeGreaterThan(Date.now());

    const subKey = mintRes.body.apiKey;
    const subId = mintRes.body.subscriberId;

    // 2. Subscriber initializes
    const initRes = await request(testEnv.ctx.app)
      .post(`/api/v1/channels/${channelId}/subscribers/init`)
      .set('Authorization', `Bearer ${subKey}`)
      .send({
        fcmToken: 'fcm_token_subscriber_1',
        deviceName: 'Galaxy Tab S9',
        deviceModel: 'SM-X810',
        deliveryMode: 'battery_saver',
      });

    expect(initRes.status).toBe(200);
    expect(initRes.body.subscriberId).toBe(subId);
    expect(initRes.body.channelId).toBe(channelId);
    expect(initRes.body.deliveryMode).toBe('battery_saver');

    // Appears on dashboard channel detail
    const detailRes = await request(testEnv.ctx.app)
      .get(`/admin/api/channels/${channelId}`)
      .set('Cookie', authCookie);

    expect(detailRes.status).toBe(200);
    expect(detailRes.body.subscriberCount).toBe(1);
    expect(detailRes.body.subscribers.length).toBe(1);
    expect(detailRes.body.subscribers[0].subscriberId).toBe(subId);
    expect(detailRes.body.subscribers[0].deviceName).toBe('Galaxy Tab S9');
    // Does NOT leak FCM token or API key in admin response
    expect(detailRes.body.subscribers[0].fcm_token).toBeUndefined();
    expect(detailRes.body.subscribers[0].api_key_hash).toBeUndefined();
  });

  it('subscriber self-update is idempotent and token update does not duplicate rows', async () => {
    const mintRes = await request(testEnv.ctx.app)
      .post(`/api/v1/channels/${channelId}/subscribers/keys`)
      .set('Authorization', `Bearer ${relayKey}`);
    const subKey = mintRes.body.apiKey;

    await request(testEnv.ctx.app)
      .post(`/api/v1/channels/${channelId}/subscribers/init`)
      .set('Authorization', `Bearer ${subKey}`)
      .send({
        fcmToken: 'token_v1',
        deviceName: 'Tab',
      });

    // Update token & delivery mode via /subscribers/self
    const updateRes = await request(testEnv.ctx.app)
      .put(`/api/v1/channels/${channelId}/subscribers/self`)
      .set('Authorization', `Bearer ${subKey}`)
      .send({
        fcmToken: 'token_v2',
        deliveryMode: 'immediate_preview',
      });

    expect(updateRes.status).toBe(200);
    expect(updateRes.body.deliveryMode).toBe('immediate_preview');

    // Confirm no duplicate row created
    const detailRes = await request(testEnv.ctx.app)
      .get(`/admin/api/channels/${channelId}`)
      .set('Cookie', authCookie);

    expect(detailRes.body.subscribers.length).toBe(1);
  });

  it('individual subscriber revocation invalidates key immediately without affecting others', async () => {
    // Mint and activate Subscriber 1
    const mint1 = await request(testEnv.ctx.app)
      .post(`/api/v1/channels/${channelId}/subscribers/keys`)
      .set('Authorization', `Bearer ${relayKey}`);
    const sub1Key = mint1.body.apiKey;
    const sub1Id = mint1.body.subscriberId;
    await request(testEnv.ctx.app)
      .post(`/api/v1/channels/${channelId}/subscribers/init`)
      .set('Authorization', `Bearer ${sub1Key}`)
      .send({ fcmToken: 'fcm_1', deviceName: 'Sub 1' });

    // Mint and activate Subscriber 2
    const mint2 = await request(testEnv.ctx.app)
      .post(`/api/v1/channels/${channelId}/subscribers/keys`)
      .set('Authorization', `Bearer ${relayKey}`);
    const sub2Key = mint2.body.apiKey;
    const sub2Id = mint2.body.subscriberId;
    await request(testEnv.ctx.app)
      .post(`/api/v1/channels/${channelId}/subscribers/init`)
      .set('Authorization', `Bearer ${sub2Key}`)
      .send({ fcmToken: 'fcm_2', deviceName: 'Sub 2' });

    // Revoke only Subscriber 1 from Dashboard
    const revokeRes = await request(testEnv.ctx.app)
      .delete(`/admin/api/channels/${channelId}/subscribers/${sub1Id}`)
      .set('Cookie', authCookie);
    expect(revokeRes.status).toBe(200);

    // Verify revoked push was sent for sub 1
    expect(testEnv.mockPush.revokedCalls.length).toBe(1);
    expect(testEnv.mockPush.revokedCalls[0].tokens).toEqual(['fcm_1']);

    // Sub 1 key fails immediately (401)
    const sub1Call = await request(testEnv.ctx.app)
      .get(`/api/v1/channels/${channelId}`)
      .set('Authorization', `Bearer ${sub1Key}`);
    expect(sub1Call.status).toBe(401);

    // Sub 2 key continues working completely unaffected (200)
    const sub2Call = await request(testEnv.ctx.app)
      .get(`/api/v1/channels/${channelId}`)
      .set('Authorization', `Bearer ${sub2Key}`);
    expect(sub2Call.status).toBe(200);
    expect(sub2Call.body.channelId).toBe(channelId);

    // Relay phone continues working completely unaffected
    const relayCall = await request(testEnv.ctx.app)
      .get(`/api/v1/channels/${channelId}`)
      .set('Authorization', `Bearer ${relayKey}`);
    expect(relayCall.status).toBe(200);
  });
});
