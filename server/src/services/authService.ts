import type Database from 'better-sqlite3';
import { hash, verify } from '@node-rs/argon2';
import { sha256Hex, generateSessionToken } from '../crypto/tokens.js';
import { TTLS } from '../constants.js';

export interface AdminUser {
  id: number;
  username: string;
  created_at: number;
  last_login_at: number | null;
}

const ARGON2_OPTIONS = {
  memoryCost: 19456,
  timeCost: 2,
  outputLen: 32,
  parallelism: 1,
};

// Pre-computed dummy hash so failed user lookups take roughly the same time as successful ones
let dummyHashPromise: Promise<string> = hash('CrossNotify_Dummy_Password_123!', ARGON2_OPTIONS);

export class AuthService {
  constructor(private db: Database.Database) {}

  async hashPassword(password: string): Promise<string> {
    return hash(password, ARGON2_OPTIONS);
  }

  async verifyPassword(hashString: string, password: string): Promise<boolean> {
    try {
      return await verify(hashString, password);
    } catch {
      return false;
    }
  }

  hasAdminUser(): boolean {
    const row = this.db.prepare('SELECT id FROM admin_users LIMIT 1').get();
    return !!row;
  }

  getAdminUser(): AdminUser | null {
    const row = this.db.prepare('SELECT id, username, created_at, last_login_at FROM admin_users LIMIT 1').get() as
      | AdminUser
      | undefined;
    return row || null;
  }

  async createAdmin(username: string, password: string): Promise<AdminUser> {
    const passwordHash = await this.hashPassword(password);
    const now = Date.now();
    const result = this.db
      .prepare('INSERT INTO admin_users (username, password_hash, created_at) VALUES (?, ?, ?)')
      .run(username, passwordHash, now);

    return {
      id: Number(result.lastInsertRowid),
      username,
      created_at: now,
      last_login_at: null,
    };
  }

  async authenticate(username: string, password: string): Promise<AdminUser | null> {
    const row = this.db
      .prepare('SELECT id, username, password_hash, created_at, last_login_at FROM admin_users WHERE username = ? COLLATE NOCASE')
      .get(username) as (AdminUser & { password_hash: string }) | undefined;

    if (!row) {
      // Mitigate timing attack
      const dummyHash = await dummyHashPromise;
      await this.verifyPassword(dummyHash, password);
      return null;
    }

    const isValid = await this.verifyPassword(row.password_hash, password);
    if (!isValid) {
      return null;
    }

    // Update last_login_at
    const now = Date.now();
    this.db.prepare('UPDATE admin_users SET last_login_at = ? WHERE id = ?').run(now, row.id);
    row.last_login_at = now;

    return {
      id: row.id,
      username: row.username,
      created_at: row.created_at,
      last_login_at: now,
    };
  }

  createSession(userId: number, userAgent?: string, ip?: string): { token: string; expiresAt: number } {
    const { token, hash: tokenHash } = generateSessionToken();
    const now = Date.now();
    const expiresAt = now + TTLS.SESSION_MS;

    this.db
      .prepare('INSERT INTO admin_sessions (token_hash, user_id, created_at, expires_at, user_agent, ip) VALUES (?, ?, ?, ?, ?, ?)')
      .run(tokenHash, userId, now, expiresAt, userAgent || null, ip || null);

    return { token, expiresAt };
  }

  validateSession(token: string): AdminUser | null {
    const tokenHash = sha256Hex(token);
    const now = Date.now();

    const row = this.db
      .prepare(`
        SELECT u.id, u.username, u.created_at, u.last_login_at, s.expires_at
        FROM admin_sessions s
        JOIN admin_users u ON s.user_id = u.id
        WHERE s.token_hash = ?
      `)
      .get(tokenHash) as (AdminUser & { expires_at: number }) | undefined;

    if (!row) {
      return null;
    }

    if (row.expires_at < now) {
      this.deleteSession(token);
      return null;
    }

    return {
      id: row.id,
      username: row.username,
      created_at: row.created_at,
      last_login_at: row.last_login_at,
    };
  }

  deleteSession(token: string): void {
    const tokenHash = sha256Hex(token);
    this.db.prepare('DELETE FROM admin_sessions WHERE token_hash = ?').run(tokenHash);
  }

  async updatePassword(userId: number, newPassword: string, currentSessionToken?: string): Promise<void> {
    const passwordHash = await this.hashPassword(newPassword);
    this.db.prepare('UPDATE admin_users SET password_hash = ? WHERE id = ?').run(passwordHash, userId);

    // Invalidate all other sessions for this user
    if (currentSessionToken) {
      const currentTokenHash = sha256Hex(currentSessionToken);
      this.db.prepare('DELETE FROM admin_sessions WHERE user_id = ? AND token_hash != ?').run(userId, currentTokenHash);
    } else {
      this.db.prepare('DELETE FROM admin_sessions WHERE user_id = ?').run(userId);
    }
  }
}
