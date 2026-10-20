import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import request from 'supertest';
import { createTestContext } from './helpers/testApp.js';
import { COOKIE } from '../src/constants.js';

describe('Setup & Admin Auth (Tests 1 & 2)', () => {
  let testEnv: Awaited<ReturnType<typeof createTestContext>>;

  beforeEach(async () => {
    testEnv = await createTestContext({
      crossnotifySetupToken: 'test-setup-token-1234567890',
    });
  });

  afterEach(() => {
    testEnv.cleanup();
  });

  it('boots into NEEDS_ADMIN on empty DB', async () => {
    const res = await request(testEnv.ctx.app).get('/admin/api/state');
    expect(res.status).toBe(200);
    expect(res.body.state).toBe('NEEDS_ADMIN');
  });

  it('rejects setup with wrong setup token (403)', async () => {
    const res = await request(testEnv.ctx.app)
      .post('/admin/api/setup/admin')
      .send({
        setupToken: 'wrong-token',
        username: 'admin',
        password: 'password123456!',
      });

    expect(res.status).toBe(403);
    expect(res.body.error).toBe('invalid_setup_token');
  });

  it('completes setup with correct token, sets cookie and moves to NEEDS_FIREBASE', async () => {
    const res = await request(testEnv.ctx.app)
      .post('/admin/api/setup/admin')
      .set('Origin', 'http://127.0.0.1:3000')
      .send({
        setupToken: 'test-setup-token-1234567890',
        username: 'admin',
        password: 'password123456!',
      });

    expect(res.status).toBe(201);
    expect(res.body.username).toBe('admin');
    expect(res.body.state).toBe('NEEDS_FIREBASE');

    // Cookie set
    const cookies = res.headers['set-cookie'];
    expect(cookies).toBeDefined();
    expect(cookies[0]).toContain(COOKIE.ADMIN_SESSION);

    // State is now NEEDS_FIREBASE
    const stateRes = await request(testEnv.ctx.app).get('/admin/api/state');
    expect(stateRes.body.state).toBe('NEEDS_FIREBASE');

    // Second setup attempt gets 409
    const secondSetup = await request(testEnv.ctx.app)
      .post('/admin/api/setup/admin')
      .send({
        setupToken: 'test-setup-token-1234567890',
        username: 'admin2',
        password: 'password123456!',
      });
    expect(secondSetup.status).toBe(409);
    expect(secondSetup.body.error).toBe('invalid_state');
  });

  describe('Auth, Sessions & CSRF checks', () => {
    let authCookie: string;

    beforeEach(async () => {
      const res = await request(testEnv.ctx.app)
        .post('/admin/api/setup/admin')
        .send({
          setupToken: 'test-setup-token-1234567890',
          username: 'admin',
          password: 'password123456!',
        });
      authCookie = res.headers['set-cookie'][0];
    });

    it('login succeeds with correct password and fails with incorrect', async () => {
      // Failed login
      const failRes = await request(testEnv.ctx.app)
        .post('/admin/api/auth/login')
        .send({ username: 'admin', password: 'wrongpassword123' });
      expect(failRes.status).toBe(401);
      expect(failRes.body.error).toBe('invalid_credentials');

      // Successful login
      const okRes = await request(testEnv.ctx.app)
        .post('/admin/api/auth/login')
        .send({ username: 'admin', password: 'password123456!' });
      expect(okRes.status).toBe(200);
      expect(okRes.body.username).toBe('admin');
      expect(okRes.headers['set-cookie']).toBeDefined();
    });

    it('logout invalidates session and clears cookie', async () => {
      const meResBefore = await request(testEnv.ctx.app)
        .get('/admin/api/auth/me')
        .set('Cookie', authCookie);
      expect(meResBefore.status).toBe(200);
      expect(meResBefore.body.username).toBe('admin');

      const logoutRes = await request(testEnv.ctx.app)
        .post('/admin/api/auth/logout')
        .set('Cookie', authCookie);
      expect(logoutRes.status).toBe(200);

      const meResAfter = await request(testEnv.ctx.app)
        .get('/admin/api/auth/me')
        .set('Cookie', authCookie);
      expect(meResAfter.status).toBe(401);
    });

    it('changing password invalidates other sessions', async () => {
      // Login to get a second session
      const login2 = await request(testEnv.ctx.app)
        .post('/admin/api/auth/login')
        .send({ username: 'admin', password: 'password123456!' });
      const cookie2 = login2.headers['set-cookie'][0];

      // Update password using session 1
      const updateRes = await request(testEnv.ctx.app)
        .put('/admin/api/account/password')
        .set('Cookie', authCookie)
        .send({
          currentPassword: 'password123456!',
          newPassword: 'new-strong-password-123',
        });
      expect(updateRes.status).toBe(200);

      // Session 1 remains valid
      const me1 = await request(testEnv.ctx.app)
        .get('/admin/api/auth/me')
        .set('Cookie', authCookie);
      expect(me1.status).toBe(200);

      // Session 2 is invalidated
      const me2 = await request(testEnv.ctx.app)
        .get('/admin/api/auth/me')
        .set('Cookie', cookie2);
      expect(me2.status).toBe(401);
    });

    it('mutating requests without application/json get 415', async () => {
      const res = await request(testEnv.ctx.app)
        .post('/admin/api/auth/login')
        .set('Content-Type', 'text/plain')
        .send('username=admin');
      expect(res.status).toBe(415);
      expect(res.body.error).toBe('unsupported_media_type');
    });

    it('mutating requests with a foreign Origin get 403', async () => {
      const res = await request(testEnv.ctx.app)
        .post('/admin/api/auth/login')
        .set('Origin', 'https://malicious-website.com')
        .send({ username: 'admin', password: 'password123456!' });
      expect(res.status).toBe(403);
      expect(res.body.error).toBe('invalid_origin');
    });
  });
});
