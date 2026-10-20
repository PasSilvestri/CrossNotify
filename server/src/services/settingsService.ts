import type Database from 'better-sqlite3';
import { TTLS } from '../constants.js';

export interface ServerSettings {
  publicUrl: string | null;
  serverName: string;
  notificationTtlHours: number;
}

export class SettingsService {
  constructor(private db: Database.Database) {}

  get(key: string): string | null {
    const row = this.db.prepare('SELECT value FROM settings WHERE key = ?').get(key) as { value: string } | undefined;
    return row ? row.value : null;
  }

  set(key: string, value: string): void {
    this.db.prepare(`
      INSERT INTO settings (key, value) VALUES (?, ?)
      ON CONFLICT(key) DO UPDATE SET value = excluded.value
    `).run(key, value);
  }

  delete(key: string): void {
    this.db.prepare('DELETE FROM settings WHERE key = ?').run(key);
  }

  getAll(): ServerSettings {
    const publicUrl = this.get('public_url');
    const serverName = this.get('server_name') || 'CrossNotify Server';
    const ttlStr = this.get('notification_ttl_hours');
    const notificationTtlHours = ttlStr ? parseInt(ttlStr, 10) : TTLS.DEFAULT_NOTIFICATION_HOURS;

    return {
      publicUrl,
      serverName,
      notificationTtlHours: isNaN(notificationTtlHours) ? TTLS.DEFAULT_NOTIFICATION_HOURS : notificationTtlHours,
    };
  }

  setAll(settings: { publicUrl?: string; serverName?: string; notificationTtlHours?: number }): void {
    if (settings.publicUrl !== undefined) {
      this.set('public_url', settings.publicUrl);
    }
    if (settings.serverName !== undefined) {
      this.set('server_name', settings.serverName);
    }
    if (settings.notificationTtlHours !== undefined) {
      this.set('notification_ttl_hours', String(settings.notificationTtlHours));
    }
  }
}
