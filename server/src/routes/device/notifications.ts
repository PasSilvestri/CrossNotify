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
  notificationId: z.string().min(1).max(128),
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

const DismissNotificationsSchema = z.object({
  items: z.array(
    z.object({
      notificationId: z.string().min(1).max(128),
      reason: z.enum(['dismissed', 'opened', 'actioned']).optional().default('dismissed'),
    })
  ).min(1).max(100),
});

const ExecuteActionSchema = z.object({
  actionId: z.string().min(1).max(128),
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
      return buf.length <= LIMITS.MAX_ACTION_CIPHERTEXT_BYTES;
    } catch {
      return false;
    }
  }, { message: `Ciphertext must decode to <= ${LIMITS.MAX_ACTION_CIPHERTEXT_BYTES} bytes` }),
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
        handledAt: notification.handled_at ?? null,
        handledReason: notification.handled_reason ?? null,
      });
    }
  );

  // POST /api/v1/channels/:channelId/notifications/dismiss (Relay dismisses/opens notifications in batch)
  router.post(
    '/channels/:channelId/notifications/dismiss',
    requireRelayKey(channelService),
    validateBody(DismissNotificationsSchema),
    (req: DeviceRequest, res, next) => {
      try {
        const channel = req.channel!;
        const updatedEvents = notificationService.markHandledBatch(channel.channel_id, req.body.items);

        if (updatedEvents.length > 0) {
          const tokens = channelService.getActiveSubscriberTokens(channel.channel_id);
          if (tokens.length > 0) {
            pushService.sendNotificationsHandled(channel, tokens, updatedEvents).catch((err) => {
              console.error('[notifications] Error sending notifications_handled push:', err);
            });
          }
        }

        res.json({
          status: 'ok',
          updatedCount: updatedEvents.length,
        });
      } catch (err) {
        next(err);
      }
    }
  );

  // POST /api/v1/channels/:channelId/notifications/:notificationId/actions (Subscriber executes notification action)
  router.post(
    '/channels/:channelId/notifications/:notificationId/actions',
    requireSubscriberKey(subscriberService, channelService),
    validateBody(ExecuteActionSchema),
    async (req: DeviceRequest, res, next) => {
      try {
        const subscriber = req.subscriber!;
        const channel = req.channel!;
        const notificationId = String(req.params.notificationId);

        if (subscriber.allow_actions === 0) {
          res.status(403).json({
            error: 'actions_not_allowed',
            detail: 'This subscriber is not permitted to execute notification actions',
          });
          return;
        }

        if (!channel.fcm_token || channel.fcm_token.startsWith('fcm_pending_')) {
          res.status(503).json({
            error: 'relay_unreachable',
            detail: 'Relay device does not have an active push token to receive actions',
          });
          return;
        }

        let claimed: { handledAt: number; handledReason: string };
        try {
          claimed = notificationService.claimActionExecution(
            channel.channel_id,
            notificationId,
            subscriber.subscriber_id
          );
        } catch (err: any) {
          if (err && err.code === 'already_handled') {
            res.status(409).json({
              error: 'already_handled',
              detail: err.message,
              handledAt: err.handledAt,
              handledReason: err.handledReason,
            });
            return;
          }
          throw err;
        }

        const dispatched = await pushService.sendExecuteAction(channel, channel.fcm_token, {
          notificationId,
          actionId: req.body.actionId,
          iv: req.body.iv,
          ciphertext: req.body.ciphertext,
          subscriberId: subscriber.subscriber_id,
        });

        // Notify all OTHER active subscribers to dismiss the notification from their status bar
        const otherTokens = channelService.getActiveSubscriberTokens(
          channel.channel_id,
          subscriber.subscriber_id
        );
        if (otherTokens.length > 0) {
          pushService.sendNotificationsHandled(channel, otherTokens, [
            {
              notificationId,
              reason: 'actioned',
              handledAt: claimed.handledAt,
            },
          ]).catch((err) => {
            console.error('[notifications] Error broadcasting actioned dismissal to other subscribers:', err);
          });
        }

        res.json({
          status: 'ok',
          dispatched,
          handledAt: claimed.handledAt,
          handledReason: claimed.handledReason,
        });
      } catch (err) {
        next(err);
      }
    }
  );

  return router;
}
