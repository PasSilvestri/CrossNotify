import { Router } from 'express';
import { z } from 'zod';
import { validateBody } from '../../middleware/validate.js';
import { parseBearerKey, requireRelayKey, type DeviceRequest } from '../../middleware/requireDeviceKey.js';
import { sha256Hex } from '../../crypto/tokens.js';
import type { ChannelService } from '../../services/channelService.js';
import type { PushService } from '../../services/pushService.js';

const RelayInitSchema = z.object({
  deviceName: z.string().optional(),
  deviceModel: z.string().optional(),
  osVersion: z.string().optional(),
  appVersion: z.string().optional(),
});

export function createDeviceRelayRouter(
  channelService: ChannelService,
  pushService: PushService
): Router {
  const router = Router();

  // POST /api/v1/relay/init (Bearer cnr_...)
  router.post('/relay/init', validateBody(RelayInitSchema), (req, res, next) => {
    try {
      const rawKey = parseBearerKey(req);
      if (!rawKey || !rawKey.startsWith('cnr_')) {
        res.status(401).json({ error: 'invalid_api_key', detail: 'Missing or malformed relay API key' });
        return;
      }

      const keyHash = sha256Hex(rawKey);
      const result = channelService.initRelay(keyHash, req.body);
      res.json(result);
    } catch (err) {
      next(err);
    }
  });

  // DELETE /api/v1/channels/:channelId (Bearer cnr_...)
  router.delete('/channels/:channelId', requireRelayKey(channelService), async (req: DeviceRequest, res, next) => {
    try {
      const channel = req.channel!;
      const { activeSubscriberTokens } = channelService.revokeChannel(channel.channel_id);

      if (activeSubscriberTokens.length > 0) {
        pushService.sendChannelRevoked(channel, activeSubscriberTokens).catch((err) => {
          console.error('[deviceRelay] Error sending channel_revoked push:', err);
        });
      }

      res.json({ status: 'ok' });
    } catch (err) {
      next(err);
    }
  });

  return router;
}
