import type { Request, Response, NextFunction } from 'express';

export function originCheck(req: Request, res: Response, next: NextFunction): void {
  const mutatingMethods = ['POST', 'PUT', 'PATCH', 'DELETE'];
  if (!mutatingMethods.includes(req.method.toUpperCase())) {
    return next();
  }

  // 1. Content-Type check for mutating requests
  const contentLength = req.headers['content-length'];
  const hasBody = (contentLength && parseInt(contentLength, 10) > 0) || req.headers['transfer-encoding'];
  const contentType = req.headers['content-type'];

  if (hasBody || contentType) {
    if (!req.is('application/json')) {
      res.status(415).json({ error: 'unsupported_media_type', message: 'Content-Type must be application/json' });
      return;
    }
  }

  // 2. Origin check if header is present
  const origin = req.headers.origin;
  if (origin) {
    try {
      const originUrl = new URL(origin);
      const originHost = originUrl.host.toLowerCase();
      const originHostname = originUrl.hostname.toLowerCase();
      const requestHost = (req.get('host') || '').toLowerCase();
      const requestHostname = (req.hostname || '').toLowerCase();

      if (
        originHost !== requestHost &&
        originHostname !== requestHostname &&
        originHostname !== requestHost
      ) {
        res.status(403).json({ error: 'invalid_origin', message: 'Origin header does not match Host' });
        return;
      }
    } catch {
      res.status(403).json({ error: 'invalid_origin', message: 'Malformed Origin header' });
      return;
    }
  }

  next();
}
