import rateLimit from 'express-rate-limit';
import type { Request, Response, NextFunction } from 'express';
import { parseBearerKey, type DeviceRequest } from './requireDeviceKey.js';
import { sha256Hex } from '../crypto/tokens.js';

const isTest = process.env.NODE_ENV === 'test';

const noopLimiter = (req: Request, res: Response, next: NextFunction) => next();

export const authLimiter = isTest
  ? noopLimiter
  : rateLimit({
      windowMs: 60 * 1000,
      limit: 5,
      standardHeaders: 'draft-8',
      legacyHeaders: false,
      message: { error: 'too_many_requests', detail: 'Too many login or setup attempts, please try again later' },
    });

export const adminLimiter = isTest
  ? noopLimiter
  : rateLimit({
      windowMs: 60 * 1000,
      limit: 300,
      standardHeaders: 'draft-8',
      legacyHeaders: false,
      message: { error: 'too_many_requests', detail: 'Admin rate limit exceeded' },
    });

export const deviceIpLimiter = isTest
  ? noopLimiter
  : rateLimit({
      windowMs: 60 * 1000,
      limit: 300,
      standardHeaders: 'draft-8',
      legacyHeaders: false,
      message: { error: 'too_many_requests', detail: 'Device rate limit exceeded' },
    });

export const deviceKeyLimiter = isTest
  ? noopLimiter
  : rateLimit({
      windowMs: 60 * 1000,
      limit: 600,
      standardHeaders: 'draft-8',
      legacyHeaders: false,
      validate: { default: false },
      keyGenerator: (req) => {
        const existingHash = (req as DeviceRequest).deviceKeyHash;
        if (existingHash) return existingHash;
        const rawKey = parseBearerKey(req);
        if (rawKey && (rawKey.startsWith('cnr_') || rawKey.startsWith('cns_'))) {
          return sha256Hex(rawKey);
        }
        return req.ip || 'anonymous';
      },
      message: { error: 'too_many_requests', detail: 'Device key rate limit exceeded' },
    });
