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

const SubscriberInitSchema = z.object({
  fcmToken: z.string().min(1),
  deviceName: z.string().optional(),
  deviceModel: z.string().optional(),
  deliveryMode: z.enum(['battery_saver', 'immediate_preview']).optional(),
});

const SubscriberUpdateSelfSchema = z.object({
  fcmToken: z.string().optional(),
  deviceName: z.string().optional(),
  deliveryMode: z.enum(['battery_saver', 'immediate_preview']).optional(),
});

export function createDeviceSubscribersRouter(
  subscriberService: SubscriberService,
  channelService: ChannelService,
  settingsService: SettingsService
): Router {
  const router = Router();

  // POST /api/v1/channels/:channelId/subscribers/keys (Relay phone mints subscriber key)
  router.post(
    '/channels/:channelId/subscribers/keys',
    requireRelayKey(channelService),
    (req: DeviceRequest, res, next) => {
      try {
        const result = subscriberService.mintSubscriberKey(String(req.params.channelId));
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
        const result = subscriberService.initSubscriber(keyHash, req.body);
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

  // GET /api/v1/channels/:channelId (Relay or Subscriber gets channel info)
  router.get(
    '/channels/:channelId',
    requireAnyDeviceKey(channelService, subscriberService),
    (req: DeviceRequest, res) => {
      const channel = req.channel!;
      const detail = channelService.getChannelDetail(channel.channel_id);
      const settings = settingsService.getAll();

      res.json({
        channelId: channel.channel_id,
        name: channel.name,
        color: channel.color,
        status: channel.status,
        relayDeviceName: channel.relay_device_name,
        subscriberCount: detail?.subscriberCount || 0,
        notificationTtlHours: settings.notificationTtlHours,
      });
    }
  );

  return router;
}
