import type { Request, Response, NextFunction } from 'express';
import { sha256Hex } from '../crypto/tokens.js';
import type { ChannelService, ChannelRow } from '../services/channelService.js';
import type { SubscriberService, SubscriberRow } from '../services/subscriberService.js';

export interface DeviceRequest extends Request {
  channel?: ChannelRow;
  subscriber?: SubscriberRow;
  deviceKeyHash?: string;
  rawApiKey?: string;
}

export function parseBearerKey(req: Request): string | null {
  const auth = req.headers.authorization;
  if (!auth || !auth.startsWith('Bearer ')) {
    return null;
  }
  return auth.slice(7).trim();
}

export function requireRelayKey(channelService: ChannelService) {
  return (req: DeviceRequest, res: Response, next: NextFunction): void => {
    const rawKey = parseBearerKey(req);
    if (!rawKey) {
      res.status(401).json({ error: 'invalid_api_key', detail: 'Missing or malformed Authorization header' });
      return;
    }

    const hash = sha256Hex(rawKey);
    const channel = channelService.getChannelByApiKeyHash(hash);

    if (!channel || channel.status !== 'active') {
      res.status(401).json({ error: 'invalid_api_key', detail: 'Invalid or revoked relay key' });
      return;
    }

    const targetChannelId = req.params.channelId;
    if (targetChannelId && targetChannelId !== channel.channel_id) {
      res.status(403).json({ error: 'channel_mismatch', detail: 'Key does not match requested channel' });
      return;
    }

    // Throttle last_seen_at
    const now = Date.now();
    if (!channel.last_seen_at || now - channel.last_seen_at > 60_000) {
      channelService.updateLastSeen(channel.channel_id);
    }

    req.channel = channel;
    req.deviceKeyHash = hash;
    req.rawApiKey = rawKey;
    next();
  };
}

export function requireSubscriberKey(subscriberService: SubscriberService, channelService: ChannelService) {
  return (req: DeviceRequest, res: Response, next: NextFunction): void => {
    const rawKey = parseBearerKey(req);
    if (!rawKey) {
      res.status(401).json({ error: 'invalid_api_key', detail: 'Missing or malformed Authorization header' });
      return;
    }

    const hash = sha256Hex(rawKey);
    const subscriber = subscriberService.getSubscriberByApiKeyHash(hash);

    if (!subscriber || subscriber.status !== 'active') {
      res.status(401).json({ error: 'invalid_api_key', detail: 'Invalid or revoked subscriber key' });
      return;
    }

    const channel = channelService.getChannelById(subscriber.channel_id);
    if (!channel || channel.status !== 'active') {
      res.status(403).json({ error: 'channel_inactive', detail: 'Parent channel is not active' });
      return;
    }

    const targetChannelId = req.params.channelId;
    if (targetChannelId && targetChannelId !== subscriber.channel_id) {
      res.status(403).json({ error: 'channel_mismatch', detail: 'Key does not match requested channel' });
      return;
    }

    // Throttle last_seen_at
    const now = Date.now();
    if (!subscriber.last_seen_at || now - subscriber.last_seen_at > 60_000) {
      subscriberService.updateLastSeen(subscriber.subscriber_id);
    }

    req.subscriber = subscriber;
    req.channel = channel;
    req.deviceKeyHash = hash;
    req.rawApiKey = rawKey;
    next();
  };
}

export function requireAnyDeviceKey(channelService: ChannelService, subscriberService: SubscriberService) {
  return (req: DeviceRequest, res: Response, next: NextFunction): void => {
    const rawKey = parseBearerKey(req);
    if (!rawKey) {
      res.status(401).json({ error: 'invalid_api_key', detail: 'Missing or malformed Authorization header' });
      return;
    }

    const hash = sha256Hex(rawKey);

    if (rawKey.startsWith('cnr_')) {
      const channel = channelService.getChannelByApiKeyHash(hash);
      if (!channel || channel.status !== 'active') {
        res.status(401).json({ error: 'invalid_api_key' });
        return;
      }
      const targetChannelId = req.params.channelId;
      if (targetChannelId && targetChannelId !== channel.channel_id) {
        res.status(403).json({ error: 'channel_mismatch' });
        return;
      }
      req.channel = channel;
      return next();
    }

    if (rawKey.startsWith('cns_')) {
      const subscriber = subscriberService.getSubscriberByApiKeyHash(hash);
      if (!subscriber || subscriber.status !== 'active') {
        res.status(401).json({ error: 'invalid_api_key' });
        return;
      }
      const channel = channelService.getChannelById(subscriber.channel_id);
      if (!channel || channel.status !== 'active') {
        res.status(403).json({ error: 'channel_inactive' });
        return;
      }
      const targetChannelId = req.params.channelId;
      if (targetChannelId && targetChannelId !== subscriber.channel_id) {
        res.status(403).json({ error: 'channel_mismatch' });
        return;
      }
      req.subscriber = subscriber;
      req.channel = channel;
      return next();
    }

    res.status(401).json({ error: 'invalid_api_key' });
  };
}
