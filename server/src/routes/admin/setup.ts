import { Router } from 'express';
import { z } from 'zod';
import { LIMITS, COOKIE, TTLS } from '../../constants.js';
import { validateBody } from '../../middleware/validate.js';
import { authLimiter } from '../../middleware/rateLimits.js';
import type { SetupService } from '../../services/setupService.js';
import type { AuthService } from '../../services/authService.js';
import type { SettingsService } from '../../services/settingsService.js';
import type { ServerConfig } from '../../config.js';

const SetupSchema = z.object({
  setupToken: z.string().min(1),
  username: z.string().min(LIMITS.USERNAME_MIN).max(LIMITS.USERNAME_MAX).regex(LIMITS.USERNAME_REGEX, {
    message: 'Username can only contain alphanumeric characters, dots, underscores, and hyphens',
  }),
  password: z.string().min(LIMITS.PASSWORD_MIN).max(LIMITS.PASSWORD_MAX),
});

export function createSetupRouter(
  setupService: SetupService,
  authService: AuthService,
  settingsService: SettingsService,
  config: ServerConfig
): Router {
  const router = Router();

  router.post('/admin', authLimiter, validateBody(SetupSchema), async (req, res, next) => {
    try {
      if (setupService.getState() !== 'NEEDS_ADMIN') {
        res.status(409).json({ error: 'invalid_state', state: setupService.getState() });
        return;
      }

      const { setupToken, username, password } = req.body;

      if (!setupService.verifySetupToken(setupToken)) {
        res.status(403).json({ error: 'invalid_setup_token', message: 'The provided setup token is invalid' });
        return;
      }

      const admin = await authService.createAdmin(username, password);

      // Default public_url from request if not set
      if (!settingsService.get('public_url')) {
        let inferredUrl: string;
        if (req.headers.origin) {
          inferredUrl = req.headers.origin;
        } else {
          const protocol = req.secure ? 'https' : req.protocol || 'http';
          inferredUrl = `${protocol}://${req.get('host')}`;
        }
        // Strip trailing slash if present
        inferredUrl = inferredUrl.replace(/\/+$/, '');
        settingsService.set('public_url', inferredUrl);
      }

      // Discard setup token permanently
      setupService.discardSetupToken();

      // Create session & set cookie
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

      res.status(201).json({
        username: admin.username,
        state: setupService.getState(),
      });
    } catch (err) {
      next(err);
    }
  });

  return router;
}
