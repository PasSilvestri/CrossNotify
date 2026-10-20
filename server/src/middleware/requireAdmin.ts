import type { Request, Response, NextFunction } from 'express';
import { COOKIE } from '../constants.js';
import type { AuthService, AdminUser } from '../services/authService.js';

export interface AdminRequest extends Request {
  admin?: AdminUser;
  adminToken?: string;
}

export function requireAdmin(authService: AuthService) {
  return (req: AdminRequest, res: Response, next: NextFunction): void => {
    let token = req.cookies?.[COOKIE.ADMIN_SESSION];

    if (!token && req.headers.authorization?.startsWith('Bearer ')) {
      token = req.headers.authorization.slice(7).trim();
    }

    if (!token) {
      res.status(401).json({ error: 'unauthorized' });
      return;
    }

    const admin = authService.validateSession(token);
    if (!admin) {
      res.status(401).json({ error: 'unauthorized' });
      return;
    }

    req.admin = admin;
    req.adminToken = token;
    next();
  };
}
