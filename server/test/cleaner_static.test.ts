import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import request from 'supertest';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {
  createTestContext,
  VALID_TEST_SERVICE_ACCOUNT,
  VALID_TEST_CLIENT_CONFIG,
} from './helpers/testApp.js';
import { TTLS } from '../src/constants.js';

describe('Revocation, Cleaner & Static Serving (Tests 10, 11 & 12)', () => {
  let testEnv: Awaited<ReturnType<typeof createTestContext>>;
  let authCookie: string;
  let relayKey: string;
  let subKey: string;
  let channelId: string;
  let publicDir: string;

  beforeEach(async () => {
    testEnv = await createTestContext({
      crossnotifySetupToken: 'test-token',
    });

    publicDir = path.join(testEnv.tmpDir, 'public');
    fs.mkdirSync(publicDir, { recursive: true });
    fs.writeFileSync(path.join(publicDir, 'index.html'), '<!DOCTYPE html><html><body>Dashboard</body></html>');

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
        fcmToken: 'fcm_token_sub',
        deviceName: 'Subscriber Device',
      });
  });

  afterEach(() => {
    testEnv.cleanup();
  });

  it('admin channel revoke sends channel_revoked push, deletes notifications, and keys stop working', async () => {
    // Publish a notification
    await request(testEnv.ctx.app)
      .post(`/api/v1/channels/${channelId}/notifications`)
      .set('Authorization', `Bearer ${relayKey}`)
      .send({
        notificationId: crypto.randomUUID(),
        iv: crypto.randomBytes(12).toString('base64url'),
        ciphertext: Buffer.from('data').toString('base64url'),
      });

    // Revoke channel from admin API
    const revokeRes = await request(testEnv.ctx.app)
      .delete(`/admin/api/channels/${channelId}`)
      .set('Cookie', authCookie);
    expect(revokeRes.status).toBe(200);

    // Verify channel_revoked push dispatched to subscriber
    expect(testEnv.mockPush.revokedCalls.length).toBe(1);
    expect(testEnv.mockPush.revokedCalls[0].tokens).toContain('fcm_token_sub');

    // Notifications deleted
    const notifCount = (
      testEnv.ctx.db.prepare('SELECT COUNT(*) as count FROM notifications WHERE channel_id = ?').get(channelId) as { count: number }
    ).count;
    expect(notifCount).toBe(0);

    // Relay key stops working
    const relayCall = await request(testEnv.ctx.app)
      .get(`/api/v1/channels/${channelId}`)
      .set('Authorization', `Bearer ${relayKey}`);
    expect(relayCall.status).toBe(401);

    // Subscriber key stops working
    const subCall = await request(testEnv.ctx.app)
      .get(`/api/v1/channels/${channelId}`)
      .set('Authorization', `Bearer ${subKey}`);
    expect(subCall.status).toBe(401);
  });

  it('cleaner service prunes expired rows of each type', () => {
    const now = Date.now();

    // 1. Expired notification
    testEnv.ctx.db.prepare(`
      INSERT INTO notifications (notification_id, channel_id, iv, ciphertext, created_at, expires_at)
      VALUES ('exp-notif', ?, 'iv', 'ct', ?, ?)
    `).run(channelId, now - 5000, now - 1000);

    // 2. Expired pending channel
    testEnv.ctx.db.prepare(`
      INSERT INTO channels (channel_id, name, status, api_key_hash, api_key_prefix, created_at, pending_expires_at)
      VALUES ('exp-pending-ch', 'Old Pending', 'pending', 'hash1', 'cnr_test', ?, ?)
    `).run(now - 20 * 60 * 1000, now - 5 * 60 * 1000);

    // 3. Expired revoked channel (> 7 days)
    const eightDaysAgo = now - 8 * 24 * 60 * 60 * 1000;
    testEnv.ctx.db.prepare(`
      INSERT INTO channels (channel_id, name, status, api_key_hash, api_key_prefix, created_at, revoked_at)
      VALUES ('exp-revoked-ch', 'Old Revoked', 'revoked', 'hash2', 'cnr_test', ?, ?)
    `).run(eightDaysAgo - 1000, eightDaysAgo);

    // 4. Expired pending subscriber
    testEnv.ctx.db.prepare(`
      INSERT INTO subscribers (channel_id, subscriber_id, api_key_hash, api_key_prefix, status, pending_expires_at, created_at)
      VALUES (?, 'exp-pending-sub', 'hash3', 'cns_test', 'pending', ?, ?)
    `).run(channelId, now - 1000, now - 5000);

    // 5. Expired admin session
    testEnv.ctx.db.prepare(`
      INSERT INTO admin_sessions (token_hash, user_id, created_at, expires_at)
      VALUES ('exp-sess-hash', 1, ?, ?)
    `).run(now - 10000, now - 1000);

    // Run cleaner
    const cleaned = testEnv.ctx.services.cleanerService.runPruning();

    expect(cleaned.expiredNotifications).toBeGreaterThanOrEqual(1);
    expect(cleaned.expiredPendingChannels).toBeGreaterThanOrEqual(1);
    expect(cleaned.expiredRevokedChannels).toBeGreaterThanOrEqual(1);
    expect(cleaned.expiredPendingSubscribers).toBeGreaterThanOrEqual(1);
    expect(cleaned.expiredSessions).toBeGreaterThanOrEqual(1);
  });

  it('serves static files, SPA fallback, minimal health, and JSON 404 for unknown API paths', async () => {
    // GET /health
    const health = await request(testEnv.ctx.app).get('/health');
    expect(health.status).toBe(200);
    expect(health.body).toEqual({ status: 'ok', state: 'READY' });
    // No leaks of counts or internal details
    expect(health.body.channels).toBeUndefined();
    expect(health.body.notifications).toBeUndefined();

    // Unknown API endpoint returns JSON 404
    const api404 = await request(testEnv.ctx.app).get('/api/v1/unknown-endpoint');
    expect(api404.status).toBe(404);
    expect(api404.body.error).toBe('not_found');

    const adminApi404 = await request(testEnv.ctx.app).get('/admin/api/unknown-endpoint');
    expect(adminApi404.status).toBe(404);
    expect(adminApi404.body.error).toBe('not_found');

    // SPA fallback: GET / or GET /relays/new serves index.html
    const rootRes = await request(testEnv.ctx.app).get('/');
    expect(rootRes.status).toBe(200);
    expect(rootRes.text).toContain('Dashboard');
    expect(rootRes.headers['cache-control']).toBe('no-cache');

    const spaRoute = await request(testEnv.ctx.app).get('/relays/new');
    expect(spaRoute.status).toBe(200);
    expect(spaRoute.text).toContain('Dashboard');
  });
});
