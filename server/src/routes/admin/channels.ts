import { Router } from 'express';
import { z } from 'zod';
import { LIMITS } from '../../constants.js';
import { validateBody } from '../../middleware/validate.js';
import { requireAdmin } from '../../middleware/requireAdmin.js';
import type { ChannelService } from '../../services/channelService.js';
import type { SubscriberService } from '../../services/subscriberService.js';
import type { PushService } from '../../services/pushService.js';
import type { AuthService } from '../../services/authService.js';

const CreateRelaySchema = z.object({
  name: z.string().min(LIMITS.CHANNEL_NAME_MIN).max(LIMITS.CHANNEL_NAME_MAX),
  color: z.string().regex(/^#[0-9a-fA-F]{6}$/, { message: 'Color must be in format #RRGGBB' }).optional(),
});

const UpdateChannelSchema = z.object({
  name: z.string().min(LIMITS.CHANNEL_NAME_MIN).max(LIMITS.CHANNEL_NAME_MAX).optional(),
  color: z.string().regex(/^#[0-9a-fA-F]{6}$/, { message: 'Color must be in format #RRGGBB' }).optional(),
});

export function createAdminChannelsRouter(
  channelService: ChannelService,
  subscriberService: SubscriberService,
  pushService: PushService,
  authService: AuthService
): Router {
  const router = Router();
  const auth = requireAdmin(authService);

  // Relays
  router.post('/relays', auth, validateBody(CreateRelaySchema), (req, res, next) => {
    try {
      const payload = channelService.createRelay(req.body.name, req.body.color);
      res.status(201).json(payload);
    } catch (err) {
      next(err);
    }
  });

  router.post('/relays/:channelId/regenerate', auth, (req, res, next) => {
    try {
      const channelId = String(req.params.channelId);
      const payload = channelService.regenerateRelay(channelId);
      res.json(payload);
    } catch (err) {
      next(err);
    }
  });

  // Channels
  router.get('/channels', auth, (req, res) => {
    const channels = channelService.listChannels();
    res.json(channels);
  });

  router.get('/channels/:channelId', auth, (req, res) => {
    const channelId = String(req.params.channelId);
    const detail = channelService.getChannelDetail(channelId);
    if (!detail) {
      res.status(404).json({ error: 'channel_not_found', message: 'Channel not found' });
      return;
    }
    res.json(detail);
  });

  router.patch('/channels/:channelId', auth, validateBody(UpdateChannelSchema), (req, res, next) => {
    try {
      const channelId = String(req.params.channelId);
      const updated = channelService.updateChannel(channelId, req.body);
      res.json(updated);
    } catch (err) {
      next(err);
    }
  });

  // Purge all revoked channels
  router.delete('/channels/revoked/purge', auth, (req, res, next) => {
    try {
      const deletedCount = channelService.deleteRevokedChannels();
      res.json({ status: 'ok', deletedCount });
    } catch (err) {
      next(err);
    }
  });

  router.delete('/channels/:channelId', auth, async (req, res, next) => {
    try {
      const channelId = String(req.params.channelId);
      const channel = channelService.getChannelById(channelId);
      if (!channel) {
        res.status(404).json({ error: 'channel_not_found', message: 'Channel not found' });
        return;
      }

      // If permanent delete is requested or channel was already revoked, purge it completely
      if (req.query.permanent === 'true' || channel.status === 'revoked') {
        channelService.deleteChannel(channelId);
        res.json({ status: 'ok', deleted: true });
        return;
      }

      const { activeSubscriberTokens } = channelService.revokeChannel(channelId);
      if (activeSubscriberTokens.length > 0) {
        pushService.sendChannelRevoked(channel, activeSubscriberTokens).catch((err) => {
          console.error('[channels] Error sending channel_revoked push:', err);
        });
      }

      res.json({ status: 'ok', revoked: true });
    } catch (err) {
      next(err);
    }
  });

  // Subscriber individual revocation
  router.delete('/channels/:channelId/subscribers/:subscriberId', auth, async (req, res, next) => {
    try {
      const channelId = String(req.params.channelId);
      const subscriberId = String(req.params.subscriberId);
      const channel = channelService.getChannelById(channelId);
      if (!channel) {
        res.status(404).json({ error: 'channel_not_found', message: 'Channel not found' });
        return;
      }

      const { fcmToken } = subscriberService.revokeSubscriber(channelId, subscriberId);
      if (fcmToken) {
        pushService.sendChannelRevoked(channel, [fcmToken], subscriberId).catch((err) => {
          console.error('[channels] Error sending channel_revoked push to subscriber:', err);
        });
      }

      res.json({ status: 'ok' });
    } catch (err) {
      next(err);
    }
  });

  return router;
}
