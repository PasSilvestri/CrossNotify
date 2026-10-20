import { Router } from 'express';
import type Database from 'better-sqlite3';
import { requireAdmin } from '../../middleware/requireAdmin.js';
import type { AuthService } from '../../services/authService.js';

export function createStatsRouter(db: Database.Database, authService: AuthService): Router {
  const router = Router();
  const auth = requireAdmin(authService);

  router.get('/stats', auth, (req, res) => {
    const channelRows = db.prepare(`
      SELECT status, COUNT(*) as count FROM channels GROUP BY status
    `).all() as { status: string; count: number }[];

    const channelStats = { pending: 0, active: 0, revoked: 0 };
    channelRows.forEach((r) => {
      if (r.status in channelStats) {
        (channelStats as any)[r.status] = r.count;
      }
    });

    const subscriberCount = (
      db.prepare(`SELECT COUNT(*) as count FROM subscribers WHERE status = 'active'`).get() as { count: number }
    ).count;

    const notificationCount = (
      db.prepare(`SELECT COUNT(*) as count FROM notifications`).get() as { count: number }
    ).count;

    res.json({
      channels: {
        pending: channelStats.pending,
        active: channelStats.active,
      },
      subscribers: subscriberCount,
      storedNotifications: notificationCount,
    });
  });

  return router;
}
