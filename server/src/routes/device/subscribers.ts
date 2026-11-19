import { Router } from 'express';
import { z } from 'zod';
import { validateBody } from '../../middleware/validate.js';
import {
  parseBearerKey,
  requireRelayKey,
  requireSubscriberKey,
  requireAnyDeviceKey,
  type DeviceRequest,
} from '../../middleware/requireDeviceKey.js';
import { sha256Hex } from '../../crypto/tokens.js';
import type { SubscriberService } from '../../services/subscriberService.js';
import type { ChannelService } from '../../services/channelService.js';
import type { SettingsService } from '../../services/settingsService.js';
import type { PushService } from '../../services/pushService.js';

const MintSubscriberKeySchema = z.object({
  allowActions: z.boolean().optional().default(true),
}).optional().default({ allowActions: true });

const UpdateSubscriberPermissionsSchema = z.object({
  allowActions: z.boolean(),
});

const SubscriberInitSchema = z.object({
  fcmToken: z.string().min(1),
  deviceName: z.string().optional(),
  deviceModel: z.string().optional(),
  appVersion: z.string().trim().max(50).optional(),
  deliveryMode: z.enum(['battery_saver', 'immediate_preview']).optional(),
});

const SubscriberUpdateSelfSchema = z.object({
  fcmToken: z.string().nullable().optional(),
  deviceName: z.string().trim().min(1).max(100).nullable().optional(),
  deviceModel: z.string().trim().nullable().optional(),
  appVersion: z.string().trim().max(50).nullable().optional(),
  deliveryMode: z.enum(['battery_saver', 'immediate_preview']).nullable().optional(),
});

const SubscriberSilenceSchema = z.object({
  appName: z.string().trim().max(200).nullable().optional(),
  durationMs: z.number().int().positive().max(30 * 24 * 60 * 60 * 1000).optional(),
  silencedUntil: z.number().int().positive().optional(),
}).refine((data) => data.durationMs !== undefined || data.silencedUntil !== undefined, {
  message: 'Either durationMs or silencedUntil must be provided',
});

export function createDeviceSubscribersRouter(
  subscriberService: SubscriberService,
  channelService: ChannelService,
  settingsService: SettingsService,
  pushService: PushService
): Router {
  const router = Router();

  // POST /api/v1/channels/:channelId/subscribers/keys (Relay phone mints subscriber key)
  router.post(
    '/channels/:channelId/subscribers/keys',
    requireRelayKey(channelService),
    (req: DeviceRequest, res, next) => {
      try {
        const parsed = MintSubscriberKeySchema.safeParse(req.body ?? {});
        if (!parsed.success) {
          res.status(400).json({ error: 'validation_error', message: 'Invalid mint subscriber request' });
          return;
        }
        const result = subscriberService.mintSubscriberKey(
          String(req.params.channelId),
          parsed.data.allowActions
        );
        res.status(201).json(result);
      } catch (err) {
        next(err);
      }
    }
  );

  // POST /api/v1/channels/:channelId/subscribers/init (Subscriber phone activates key)
  router.post(
    '/channels/:channelId/subscribers/init',
    validateBody(SubscriberInitSchema),
    (req, res, next) => {
      try {
        const rawKey = parseBearerKey(req);
        if (!rawKey || !rawKey.startsWith('cns_')) {
          res.status(401).json({ error: 'invalid_api_key', detail: 'Missing or malformed subscriber API key' });
          return;
        }

        const keyHash = sha256Hex(rawKey);
        const result = subscriberService.initSubscriber(
          keyHash,
          req.body,
          String(req.params.channelId)
        );
        res.json(result);
      } catch (err) {
        next(err);
      }
    }
  );

  // PUT /api/v1/channels/:channelId/subscribers/self (Subscriber updates self)
  router.put(
    '/channels/:channelId/subscribers/self',
    requireSubscriberKey(subscriberService, channelService),
    validateBody(SubscriberUpdateSelfSchema),
    (req: DeviceRequest, res, next) => {
      try {
        const result = subscriberService.updateSelf(req.subscriber!.subscriber_id, req.body);
        res.json(result);
      } catch (err) {
        next(err);
      }
    }
  );

  // PUT /api/v1/channels/:channelId/subscribers/:subscriberId (Relay phone updates subscriber permissions)
  router.put(
    '/channels/:channelId/subscribers/:subscriberId',
    requireRelayKey(channelService),
    validateBody(UpdateSubscriberPermissionsSchema),
    (req: DeviceRequest, res, next) => {
      try {
        const channel = req.channel!;
        const subscriberId = String(req.params.subscriberId);
        const result = subscriberService.updateSubscriberPermissions(
          channel.channel_id,
          subscriberId,
          req.body.allowActions
        );

        if (result.status === 'active' && result.fcmToken && !result.fcmToken.startsWith('fcm_pending_')) {
          pushService.sendSubscriberPermissionsUpdated(
            channel,
            result.fcmToken,
            result.subscriberId,
            result.allowActions
          ).catch((err) => {
            console.error('[subscribers] Error sending subscriber_permissions_updated push:', err);
          });
        }

        res.json({
          status: 'ok',
          subscriberId: result.subscriberId,
          allowActions: result.allowActions,
        });
      } catch (err) {
        next(err);
      }
    }
  );

  // DELETE /api/v1/channels/:channelId/subscribers/self (Subscriber self-unsubscribes)
  router.delete(
    '/channels/:channelId/subscribers/self',
    requireSubscriberKey(subscriberService, channelService),
    (req: DeviceRequest, res, next) => {
      try {
        subscriberService.revokeSubscriber(String(req.params.channelId), req.subscriber!.subscriber_id);
        res.json({ status: 'ok' });
      } catch (err) {
        next(err);
      }
    }
  );

  // GET /api/v1/channels/:channelId/subscribers/self/silence (Subscriber gets active silences)
  router.get(
    '/channels/:channelId/subscribers/self/silence',
    requireSubscriberKey(subscriberService, channelService),
    (req: DeviceRequest, res, next) => {
      try {
        const result = subscriberService.getSilences(req.subscriber!.subscriber_id);
        res.json(result);
      } catch (err) {
        next(err);
      }
    }
  );

  // PUT /api/v1/channels/:channelId/subscribers/self/silence (Subscriber silences channel or app)
  router.put(
    '/channels/:channelId/subscribers/self/silence',
    requireSubscriberKey(subscriberService, channelService),
    validateBody(SubscriberSilenceSchema),
    (req: DeviceRequest, res, next) => {
      try {
        const result = subscriberService.setSilence(
          req.subscriber!.subscriber_id,
          req.subscriber!.channel_id,
          req.body
        );
        res.json({ status: 'ok', ...result });
      } catch (err) {
        next(err);
      }
    }
  );

  // DELETE /api/v1/channels/:channelId/subscribers/self/silence (Subscriber clears silence)
  router.delete(
    '/channels/:channelId/subscribers/self/silence',
    requireSubscriberKey(subscriberService, channelService),
    (req: DeviceRequest, res, next) => {
      try {
        const appName = typeof req.query.appName === 'string' ? req.query.appName : null;
        const all = req.query.all === 'true';
        subscriberService.clearSilence(req.subscriber!.subscriber_id, { appName, all });
        res.json({ status: 'ok' });
      } catch (err) {
        next(err);
      }
    }
  );

  // GET /api/v1/channels/:channelId/subscribers (Relay gets active subscribers list)
  router.get(
    '/channels/:channelId/subscribers',
    requireRelayKey(channelService),
    (req: DeviceRequest, res) => {
      const channel = req.channel!;
      const detail = channelService.getChannelDetail(channel.channel_id);
      res.json({
        channelId: channel.channel_id,
        subscribers: detail?.subscribers || [],
      });
    }
  );

  // GET /api/v1/channels/:channelId (Relay or Subscriber gets channel info)
  router.get(
    '/channels/:channelId',
    requireAnyDeviceKey(channelService, subscriberService),
    (req: DeviceRequest, res) => {
      const channel = req.channel!;
      const detail = channelService.getChannelDetail(channel.channel_id);
      const settings = settingsService.getAll();
      const isRelay = Boolean(req.rawApiKey?.startsWith('cnr_') || (req.channel && !req.subscriber));

      res.json({
        channelId: channel.channel_id,
        name: channel.name,
        color: channel.color,
        status: channel.status,
        relayDeviceName: channel.relay_device_name,
        subscriberCount: detail?.subscriberCount || 0,
        notificationTtlHours: settings.notificationTtlHours,
        ...(isRelay
          ? { subscribers: detail?.subscribers || [] }
          : { allowActions: req.subscriber ? req.subscriber.allow_actions !== 0 : true }),
      });
    }
  );

  return router;
}
