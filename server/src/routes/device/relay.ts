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

const RelayUpdateSchema = z.object({
  deviceName: z.string().trim().min(1).max(100).nullable().optional(),
  deviceModel: z.string().nullable().optional(),
  osVersion: z.string().nullable().optional(),
  appVersion: z.string().nullable().optional(),
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
      const existingChannel = channelService.getChannelByApiKeyHash(keyHash);
      const result = channelService.initRelay(keyHash, req.body);

      // If already active and deviceName changed, notify connected subscribers
      if (
        existingChannel &&
        existingChannel.status === 'active' &&
        req.body.deviceName &&
        req.body.deviceName !== existingChannel.relay_device_name
      ) {
        const tokens = channelService.getActiveSubscriberTokens(existingChannel.channel_id);
        if (tokens.length > 0) {
          pushService.sendRelayNameUpdated(existingChannel, tokens, req.body.deviceName).catch((err) => {
            console.error('[deviceRelay] Error sending relay_name_updated push on init:', err);
          });
        }
      }

      res.json(result);
    } catch (err) {
      next(err);
    }
  });

  // PUT /api/v1/channels/:channelId/relay (Bearer cnr_...)
  router.put('/channels/:channelId/relay', requireRelayKey(channelService), validateBody(RelayUpdateSchema), async (req: DeviceRequest, res, next) => {
    try {
      const channel = req.channel!;
      const { channel: updatedChannel, previousDeviceName } = channelService.updateRelay(
        channel.channel_id,
        req.body
      );

      if (req.body.deviceName && req.body.deviceName !== previousDeviceName) {
        const tokens = channelService.getActiveSubscriberTokens(channel.channel_id);
        if (tokens.length > 0) {
          pushService.sendRelayNameUpdated(updatedChannel, tokens, req.body.deviceName).catch((err) => {
            console.error('[deviceRelay] Error sending relay_name_updated push:', err);
          });
        }
      }

      res.json({
        status: 'ok',
        channelId: updatedChannel.channel_id,
        relayDeviceName: updatedChannel.relay_device_name,
      });
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
