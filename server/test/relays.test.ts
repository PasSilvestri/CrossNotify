import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import request from 'supertest';
import {
  createTestContext,
  VALID_TEST_SERVICE_ACCOUNT,
  VALID_TEST_CLIENT_CONFIG,
} from './helpers/testApp.js';

describe('Relay Provisioning & Device Auth (Tests 5 & 6)', () => {
  let testEnv: Awaited<ReturnType<typeof createTestContext>>;
  let authCookie: string;

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

    expect(testEnv.ctx.services.setupService.getState()).toBe('READY');
  });

  afterEach(() => {
    testEnv.cleanup();
  });

  it('POST /relays returns valid relay provisioning payload and stores only hash', async () => {
    const res = await request(testEnv.ctx.app)
      .post('/admin/api/relays')
      .set('Cookie', authCookie)
      .send({ name: 'Work Pixel', color: '#4F46E5' });

    expect(res.status).toBe(201);
    expect(res.body.channelId).toBeDefined();
    expect(res.body.expiresAt).toBeGreaterThan(Date.now());

    const qr = res.body.qrPayload;
    expect(qr.t).toBe('cn-relay');
    expect(qr.v).toBe(1);
    expect(qr.server.url).toBe('http://127.0.0.1:3000');
    expect(qr.channel.id).toBe(res.body.channelId);
    expect(qr.channel.name).toBe('Work Pixel');
    expect(qr.channel.color).toBe('#4F46E5');
    expect(qr.apiKey.startsWith('cnr_')).toBe(true);
    expect(qr.firebase.projectId).toBe('test-project-123');

    // Verify DB stores only hash, never raw key
    const channelInDb = testEnv.ctx.services.channelService.getChannelById(res.body.channelId);
    expect(channelInDb?.status).toBe('pending');
    expect(channelInDb?.api_key_hash).toBeDefined();
    expect(channelInDb?.api_key_hash).not.toBe(qr.apiKey);
  });

  it('relay/init activates the channel, and a second init is idempotent', async () => {
    const relayRes = await request(testEnv.ctx.app)
      .post('/admin/api/relays')
      .set('Cookie', authCookie)
      .send({ name: 'Work Pixel' });

    const { apiKey, channelId } = {
      apiKey: relayRes.body.qrPayload.apiKey,
      channelId: relayRes.body.channelId,
    };

    // First init: pending -> active
    const init1 = await request(testEnv.ctx.app)
      .post('/api/v1/relay/init')
      .set('Authorization', `Bearer ${apiKey}`)
      .send({
        deviceName: 'Pixel 8 Pro',
        deviceModel: 'Google Pixel 8 Pro',
        osVersion: 'Android 16',
        appVersion: '1.0.0',
      });

    expect(init1.status).toBe(200);
    expect(init1.body.channelId).toBe(channelId);
    expect(init1.body.channelName).toBe('Work Pixel');

    const channel1 = testEnv.ctx.services.channelService.getChannelById(channelId);
    expect(channel1?.status).toBe('active');
    expect(channel1?.relay_device_name).toBe('Pixel 8 Pro');

    // Second init: idempotent update
    const init2 = await request(testEnv.ctx.app)
      .post('/api/v1/relay/init')
      .set('Authorization', `Bearer ${apiKey}`)
      .send({
        deviceName: 'Pixel 8 Pro Updated',
      });

    expect(init2.status).toBe(200);
    const channel2 = testEnv.ctx.services.channelService.getChannelById(channelId);
    expect(channel2?.relay_device_name).toBe('Pixel 8 Pro Updated');
  });

  it('expired pending channel init returns 410 provisioning_expired', async () => {
    const relayRes = await request(testEnv.ctx.app)
      .post('/admin/api/relays')
      .set('Cookie', authCookie)
      .send({ name: 'Old Pixel' });

    const { apiKey, channelId } = {
      apiKey: relayRes.body.qrPayload.apiKey,
      channelId: relayRes.body.channelId,
    };

    // Manually backdate pending_expires_at in DB
    testEnv.ctx.db
      .prepare('UPDATE channels SET pending_expires_at = ? WHERE channel_id = ?')
      .run(Date.now() - 1000, channelId);

    const initRes = await request(testEnv.ctx.app)
      .post('/api/v1/relay/init')
      .set('Authorization', `Bearer ${apiKey}`)
      .send({ deviceName: 'Pixel' });

    expect(initRes.status).toBe(410);
    expect(initRes.body.error).toBe('provisioning_expired');
  });

  it('revoked channel key gets 401', async () => {
    const relayRes = await request(testEnv.ctx.app)
      .post('/admin/api/relays')
      .set('Cookie', authCookie)
      .send({ name: 'Work Pixel' });

    const { apiKey, channelId } = {
      apiKey: relayRes.body.qrPayload.apiKey,
      channelId: relayRes.body.channelId,
    };

    await request(testEnv.ctx.app)
      .post('/api/v1/relay/init')
      .set('Authorization', `Bearer ${apiKey}`)
      .send({ deviceName: 'Pixel 8' });

    // Revoke channel
    await request(testEnv.ctx.app)
      .delete(`/admin/api/channels/${channelId}`)
      .set('Cookie', authCookie);

    // Call device endpoint with revoked key
    const res = await request(testEnv.ctx.app)
      .delete(`/api/v1/channels/${channelId}`)
      .set('Authorization', `Bearer ${apiKey}`);

    expect(res.status).toBe(401);
    expect(res.body.error).toBe('invalid_api_key');
  });

  it('rejects missing or invalid key with 401', async () => {
    const relayRes = await request(testEnv.ctx.app)
      .post('/admin/api/relays')
      .set('Cookie', authCookie)
      .send({ name: 'Work Pixel' });

    const channelId = relayRes.body.channelId;

    // No auth header
    const noAuth = await request(testEnv.ctx.app).delete(`/api/v1/channels/${channelId}`);
    expect(noAuth.status).toBe(401);

    // Invalid key
    const badAuth = await request(testEnv.ctx.app)
      .delete(`/api/v1/channels/${channelId}`)
      .set('Authorization', 'Bearer cnr_invalid_fake_key_123');
    expect(badAuth.status).toBe(401);
  });

  it('returns 403 channel_mismatch when key of channel A is used for channel B', async () => {
    // Create channel A and activate it
    const resA = await request(testEnv.ctx.app)
      .post('/admin/api/relays')
      .set('Cookie', authCookie)
      .send({ name: 'Channel A' });
    const keyA = resA.body.qrPayload.apiKey;
    await request(testEnv.ctx.app)
      .post('/api/v1/relay/init')
      .set('Authorization', `Bearer ${keyA}`)
      .send({ deviceName: 'Device A' });

    // Create channel B and activate it
    const resB = await request(testEnv.ctx.app)
      .post('/admin/api/relays')
      .set('Cookie', authCookie)
      .send({ name: 'Channel B' });
    const keyB = resB.body.qrPayload.apiKey;
    await request(testEnv.ctx.app)
      .post('/api/v1/relay/init')
      .set('Authorization', `Bearer ${keyB}`)
      .send({ deviceName: 'Device B' });

    // Attempt to access channel B with key A
    const mismatchRes = await request(testEnv.ctx.app)
      .delete(`/api/v1/channels/${resB.body.channelId}`)
      .set('Authorization', `Bearer ${keyA}`);

    expect(mismatchRes.status).toBe(403);
    expect(mismatchRes.body.error).toBe('channel_mismatch');
  });

  it('device routes return 409 when state is not READY', async () => {
    // Force state out of READY by deleting service account
    testEnv.ctx.services.firebaseConfigService.deleteSecret('firebase_service_account');
    await testEnv.ctx.services.firebaseAdminService.reload();
    expect(testEnv.ctx.services.setupService.getState()).toBe('NEEDS_FIREBASE');

    const res = await request(testEnv.ctx.app)
      .post('/api/v1/relay/init')
      .set('Authorization', 'Bearer cnr_test')
      .send({});

    expect(res.status).toBe(409);
    expect(res.body.error).toBe('invalid_state');
    expect(res.body.state).toBe('NEEDS_FIREBASE');
  });
});
