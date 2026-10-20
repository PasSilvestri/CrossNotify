import { Router } from 'express';
import { z } from 'zod';
import { COOKIE, TTLS } from '../../constants.js';
import { validateBody } from '../../middleware/validate.js';
import { authLimiter } from '../../middleware/rateLimits.js';
import { requireAdmin, type AdminRequest } from '../../middleware/requireAdmin.js';
import type { AuthService } from '../../services/authService.js';
import type { SetupService } from '../../services/setupService.js';
import type { ServerConfig } from '../../config.js';

const LoginSchema = z.object({
  username: z.string().min(1),
  password: z.string().min(1),
});

export function createAuthRouter(
  authService: AuthService,
  setupService: SetupService,
  config: ServerConfig
): Router {
  const router = Router();

  router.post('/login', authLimiter, validateBody(LoginSchema), async (req, res, next) => {
    try {
      const { username, password } = req.body;
      const admin = await authService.authenticate(username, password);

      if (!admin) {
        res.status(401).json({ error: 'invalid_credentials', message: 'Invalid username or password' });
        return;
      }

      const userAgent = req.headers['user-agent'];
      const ip = req.ip;
      const { token } = authService.createSession(admin.id, userAgent, ip);

      const isSecure = config.cookieSecure === 'auto' ? req.secure : !!config.cookieSecure;

      res.cookie(COOKIE.ADMIN_SESSION, token, {
        httpOnly: true,
        sameSite: 'strict',
        path: '/',
        secure: isSecure,
        maxAge: TTLS.SESSION_MS,
      });

      res.json({
        username: admin.username,
        state: setupService.getState(),
      });
    } catch (err) {
      next(err);
    }
  });

  router.post('/logout', requireAdmin(authService), (req: AdminRequest, res) => {
    if (req.adminToken) {
      authService.deleteSession(req.adminToken);
    }
    res.clearCookie(COOKIE.ADMIN_SESSION, { path: '/' });
    res.json({ status: 'ok' });
  });

  router.get('/me', requireAdmin(authService), (req: AdminRequest, res) => {
    res.json({ username: req.admin!.username });
  });

  return router;
}
