import { Router } from 'express';
import { z } from 'zod';
import { LIMITS } from '../../constants.js';
import { validateBody, validateQuery } from '../../middleware/validate.js';
import {
  requireRelayKey,
  requireSubscriberKey,
  type DeviceRequest,
} from '../../middleware/requireDeviceKey.js';
import type { NotificationService } from '../../services/notificationService.js';
import type { PushService } from '../../services/pushService.js';
import type { ChannelService } from '../../services/channelService.js';
import type { SubscriberService } from '../../services/subscriberService.js';

const PublishNotificationSchema = z.object({
  notificationId: z.string().min(1),
  iv: z.string().refine((val) => {
    try {
      const buf = Buffer.from(val, 'base64url');
      return buf.length === 12;
    } catch {
      return false;
    }
  }, { message: 'IV must be a 12-byte base64url string' }),
  ciphertext: z.string().refine((val) => {
    try {
      const buf = Buffer.from(val, 'base64url');
      return buf.length <= LIMITS.MAX_NOTIFICATION_CIPHERTEXT_BYTES;
    } catch {
      return false;
    }
  }, { message: `Ciphertext must decode to <= ${LIMITS.MAX_NOTIFICATION_CIPHERTEXT_BYTES} bytes` }),
  appName: z.string().max(LIMITS.APP_NAME_MAX).optional(),
  timestamp: z.number().int().optional(),
});

const CatchUpQuerySchema = z.object({
  since: z.coerce.number().int().nonnegative().optional().default(0),
  limit: z.coerce.number().int().min(1).max(100).optional().default(50),
});

export function createDeviceNotificationsRouter(
  notificationService: NotificationService,
  channelService: ChannelService,
  subscriberService: SubscriberService,
  pushService: PushService
): Router {
  const router = Router();

  // POST /api/v1/channels/:channelId/notifications (Relay publishes notification)
  router.post(
    '/channels/:channelId/notifications',
    requireRelayKey(channelService),
    validateBody(PublishNotificationSchema),
    async (req: DeviceRequest, res, next) => {
      try {
        const { created, notification } = notificationService.publish(req.channel!.channel_id, req.body);

        if (created) {
          // Trigger push dispatch asynchronously
          pushService.dispatch(req.channel!, notification).catch((err) => {
            console.error('[notifications] Error in pushService.dispatch:', err);
          });
          res.status(201).json({
            notificationId: notification.notification_id,
            receivedAt: notification.created_at,
          });
        } else {
          // Duplicate insertion: 200 and no push
          res.status(200).json({
            notificationId: notification.notification_id,
            receivedAt: notification.created_at,
          });
        }
      } catch (err) {
        next(err);
      }
    }
  );

  // GET /api/v1/channels/:channelId/notifications (Subscriber catch-up sync)
  router.get(
    '/channels/:channelId/notifications',
    requireSubscriberKey(subscriberService, channelService),
    validateQuery(CatchUpQuerySchema),
    (req: DeviceRequest, res) => {
      const { since, limit } = req.query as unknown as { since: number; limit: number };
      const result = notificationService.catchUp(req.subscriber!.channel_id, since, limit);
      res.json(result);
    }
  );

  // GET /api/v1/channels/:channelId/notifications/:notificationId (Subscriber single fetch)
  router.get(
    '/channels/:channelId/notifications/:notificationId',
    requireSubscriberKey(subscriberService, channelService),
    (req: DeviceRequest, res) => {
      const notification = notificationService.getNotification(
        req.subscriber!.channel_id,
        String(req.params.notificationId)
      );

      if (!notification) {
        res.status(404).json({ error: 'notification_not_found', message: 'Notification not found' });
        return;
      }

      res.json({
        notificationId: notification.notification_id,
        iv: notification.iv,
        ciphertext: notification.ciphertext,
        appName: notification.app_name,
        timestamp: notification.relay_timestamp,
        receivedAt: notification.created_at,
      });
    }
  );

  return router;
}
