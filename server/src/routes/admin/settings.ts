import { Router } from 'express';
import { z } from 'zod';
import { validateBody } from '../../middleware/validate.js';
import { requireAdmin } from '../../middleware/requireAdmin.js';
import type { SettingsService } from '../../services/settingsService.js';
import type { AuthService } from '../../services/authService.js';

const SettingsUpdateSchema = z.object({
  publicUrl: z
    .string()
    .url({ message: 'publicUrl must be a valid absolute URL' })
    .refine((url) => !url.endsWith('/'), { message: 'publicUrl must not have a trailing slash' })
    .refine((url) => url.startsWith('http://') || url.startsWith('https://'), {
      message: 'publicUrl must use http or https',
    })
    .optional(),
  serverName: z.string().min(1).max(100).optional(),
  notificationTtlHours: z.number().int().min(1).max(168).optional(),
});

export function createSettingsRouter(settingsService: SettingsService, authService: AuthService): Router {
  const router = Router();
  const auth = requireAdmin(authService);

  router.get('/', auth, (req, res) => {
    res.json(settingsService.getAll());
  });

  router.put('/', auth, validateBody(SettingsUpdateSchema), (req, res) => {
    settingsService.setAll(req.body);
    res.json(settingsService.getAll());
  });

  return router;
}
