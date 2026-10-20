import type Database from 'better-sqlite3';
import { TTLS } from '../constants.js';

export class CleanerService {
  private timer: NodeJS.Timeout | null = null;

  constructor(private db: Database.Database) {}

  runPruning(): {
    expiredNotifications: number;
    expiredPendingChannels: number;
    expiredRevokedChannels: number;
    expiredPendingSubscribers: number;
    expiredSessions: number;
  } {
    const now = Date.now();
    const revokedThreshold = now - TTLS.REVOKED_CHANNEL_RETENTION_MS;

    const resNotifications = this.db.prepare('DELETE FROM notifications WHERE expires_at < ?').run(now);
    const resPendingChannels = this.db.prepare(`
      DELETE FROM channels WHERE status = 'pending' AND pending_expires_at < ?
    `).run(now);
    const resRevokedChannels = this.db.prepare(`
      DELETE FROM channels WHERE status = 'revoked' AND revoked_at < ?
    `).run(revokedThreshold);
    const resPendingSubscribers = this.db.prepare(`
      DELETE FROM subscribers WHERE status = 'pending' AND pending_expires_at < ?
    `).run(now);
    const resSessions = this.db.prepare('DELETE FROM admin_sessions WHERE expires_at < ?').run(now);

    const counts = {
      expiredNotifications: resNotifications.changes,
      expiredPendingChannels: resPendingChannels.changes,
      expiredRevokedChannels: resRevokedChannels.changes,
      expiredPendingSubscribers: resPendingSubscribers.changes,
      expiredSessions: resSessions.changes,
    };

    const totalCleaned = Object.values(counts).reduce((a, b) => a + b, 0);
    if (totalCleaned > 0) {
      console.log(`[cleaner] Pruned expired rows:`, counts);
    }

    return counts;
  }

  start(intervalMs = 10 * 60 * 1000): void {
    // Run immediately on boot
    this.runPruning();

    if (this.timer) {
      clearInterval(this.timer);
    }

    this.timer = setInterval(() => {
      try {
        this.runPruning();
      } catch (err: any) {
        console.error('[cleaner] Error during periodic pruning:', err.message);
      }
    }, intervalMs);
  }

  stop(): void {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }
}
