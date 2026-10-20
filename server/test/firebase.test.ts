import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import request from 'supertest';
import crypto from 'node:crypto';
import path from 'node:path';
import {
  createTestContext,
  VALID_TEST_SERVICE_ACCOUNT,
  VALID_TEST_CLIENT_CONFIG,
} from './helpers/testApp.js';
import { createApp } from '../src/app.js';
import { openDatabase } from '../src/db/index.js';

describe('Firebase Configuration & Master Key (Tests 3 & 4)', () => {
  let testEnv: Awaited<ReturnType<typeof createTestContext>>;
  let authCookie: string;

  beforeEach(async () => {
    testEnv = await createTestContext({
      crossnotifySetupToken: 'test-token',
    });

    const setupRes = await request(testEnv.ctx.app)
      .post('/admin/api/setup/admin')
      .send({
        setupToken: 'test-token',
        username: 'admin',
        password: 'password123456!',
      });
    authCookie = setupRes.headers['set-cookie'][0];
  });

  afterEach(() => {
    testEnv.cleanup();
  });

  it('rejects malformed service account with 422', async () => {
    const res = await request(testEnv.ctx.app)
      .post('/admin/api/firebase/service-account')
      .set('Cookie', authCookie)
      .send({ type: 'service_account', project_id: '' });

    expect(res.status).toBe(422);
    expect(res.body.error).toBe('invalid_service_account');
  });

  it('rejects client config without com.crossnotify.app with 422 package_not_registered', async () => {
    const invalidClientConfig = {
      project_info: { project_number: '123', project_id: 'test-project-123' },
      client: [
        {
          client_info: {
            mobilesdk_app_id: '123',
            android_client_info: { package_name: 'com.other.app' },
          },
          api_key: [{ current_key: 'abc' }],
        },
      ],
    };

    const res = await request(testEnv.ctx.app)
      .post('/admin/api/firebase/client-config')
      .set('Cookie', authCookie)
      .send(invalidClientConfig);

    expect(res.status).toBe(422);
    expect(res.body.error).toBe('package_not_registered');
  });

  it('rejects project mismatch between service account and client config with 422', async () => {
    // Upload service account with project test-project-123
    await request(testEnv.ctx.app)
      .post('/admin/api/firebase/service-account')
      .set('Cookie', authCookie)
      .send(VALID_TEST_SERVICE_ACCOUNT);

    // Upload client config with mismatched project
    const mismatchedConfig = {
      ...VALID_TEST_CLIENT_CONFIG,
      project_info: {
        ...VALID_TEST_CLIENT_CONFIG.project_info,
        project_id: 'different-project-456',
      },
    };

    const res = await request(testEnv.ctx.app)
      .post('/admin/api/firebase/client-config')
      .set('Cookie', authCookie)
      .send(mismatchedConfig);

    expect(res.status).toBe(422);
    expect(res.body.error).toBe('project_mismatch');
  });

  it('moves state to READY when both files are valid and verified', async () => {
    const saRes = await request(testEnv.ctx.app)
      .post('/admin/api/firebase/service-account')
      .set('Cookie', authCookie)
      .send(VALID_TEST_SERVICE_ACCOUNT);
    expect(saRes.status).toBe(200);

    const clientRes = await request(testEnv.ctx.app)
      .post('/admin/api/firebase/client-config')
      .set('Cookie', authCookie)
      .send(VALID_TEST_CLIENT_CONFIG);
    expect(clientRes.status).toBe(200);

    const stateRes = await request(testEnv.ctx.app).get('/admin/api/state');
    expect(stateRes.body.state).toBe('READY');

    // Check secrets are encrypted in the database
    const secretRow = testEnv.ctx.db
      .prepare('SELECT iv, ciphertext FROM secrets WHERE name = ?')
      .get('firebase_service_account') as { iv: Buffer; ciphertext: Buffer };
    expect(secretRow).toBeDefined();
    // Raw ciphertext bytes must NOT contain private_key string
    expect(secretRow.ciphertext.toString('utf8')).not.toContain('private_key');
    expect(secretRow.ciphertext.toString('utf8')).not.toContain('BEGIN PRIVATE KEY');

    // No response contains private_key
    const statusRes = await request(testEnv.ctx.app)
      .get('/admin/api/firebase/status')
      .set('Cookie', authCookie);
    expect(statusRes.status).toBe(200);
    const serialized = JSON.stringify(statusRes.body);
    expect(serialized).not.toContain('private_key');
    expect(serialized).not.toContain('BEGIN PRIVATE KEY');
  });

  it('switching project with active channels returns 409, and force=true revokes them', async () => {
    // Set public url
    testEnv.ctx.services.settingsService.set('public_url', 'http://127.0.0.1:3000');

    // Upload files for project 1
    await request(testEnv.ctx.app)
      .post('/admin/api/firebase/service-account')
      .set('Cookie', authCookie)
      .send(VALID_TEST_SERVICE_ACCOUNT);
    await request(testEnv.ctx.app)
      .post('/admin/api/firebase/client-config')
      .set('Cookie', authCookie)
      .send(VALID_TEST_CLIENT_CONFIG);

    // Create a relay channel
    const relayRes = await request(testEnv.ctx.app)
      .post('/admin/api/relays')
      .set('Cookie', authCookie)
      .send({ name: 'Work Phone' });
    expect(relayRes.status).toBe(201);

    // Upload new service account with different project
    const newSa = {
      ...VALID_TEST_SERVICE_ACCOUNT,
      project_id: 'brand-new-project-789',
      client_email: 'admin@brand-new-project-789.iam.gserviceaccount.com',
    };

    const conflictRes = await request(testEnv.ctx.app)
      .post('/admin/api/firebase/service-account')
      .set('Cookie', authCookie)
      .send(newSa);

    expect(conflictRes.status).toBe(409);
    expect(conflictRes.body.error).toBe('project_change_requires_reset');
    expect(conflictRes.body.channels).toBe(1);

    // Re-upload with force=true
    const forceRes = await request(testEnv.ctx.app)
      .post('/admin/api/firebase/service-account?force=true')
      .set('Cookie', authCookie)
      .send(newSa);

    expect(forceRes.status).toBe(200);

    // Channel should now be revoked
    const channel = testEnv.ctx.services.channelService.getChannelById(relayRes.body.channelId);
    expect(channel?.status).toBe('revoked');

    // State should go back to NEEDS_FIREBASE because client-config was cleared
    const stateRes = await request(testEnv.ctx.app).get('/admin/api/state');
    expect(stateRes.body.state).toBe('NEEDS_FIREBASE');
  });

  it('restarting with a different master key moves state back to NEEDS_FIREBASE without crashing', async () => {
    // Upload initial credentials
    await request(testEnv.ctx.app)
      .post('/admin/api/firebase/service-account')
      .set('Cookie', authCookie)
      .send(VALID_TEST_SERVICE_ACCOUNT);
    await request(testEnv.ctx.app)
      .post('/admin/api/firebase/client-config')
      .set('Cookie', authCookie)
      .send(VALID_TEST_CLIENT_CONFIG);

    expect(testEnv.ctx.services.setupService.getState()).toBe('READY');

    // Close current db to test reopening
    const dbPath = path.join(testEnv.tmpDir, 'crossnotify.sqlite');
    testEnv.ctx.db.close();

    // Reopen with a DIFFERENT master key in CROSSNOTIFY_SECRET
    const newSecret = crypto.randomBytes(32).toString('base64');
    const restartedCtx = await createApp({
      config: {
        dataDir: testEnv.tmpDir,
        crossnotifySecret: newSecret,
        trustProxy: 1,
        cookieSecure: false,
      },
      firebaseVerifier: async () => {},
      skipCleanerStart: true,
    });

    // Should NOT crash, and state should gracefully revert to NEEDS_FIREBASE
    expect(restartedCtx.services.setupService.getState()).toBe('NEEDS_FIREBASE');

    restartedCtx.db.close();
  });
});
