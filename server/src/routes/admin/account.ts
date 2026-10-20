import { Router } from 'express';
import { z } from 'zod';
import { LIMITS } from '../../constants.js';
import { validateBody } from '../../middleware/validate.js';
import { requireAdmin, type AdminRequest } from '../../middleware/requireAdmin.js';
import type { AuthService } from '../../services/authService.js';

const PasswordUpdateSchema = z.object({
  currentPassword: z.string().min(1),
  newPassword: z.string().min(LIMITS.PASSWORD_MIN).max(LIMITS.PASSWORD_MAX),
});

export function createAccountRouter(authService: AuthService): Router {
  const router = Router();

  router.put('/password', requireAdmin(authService), validateBody(PasswordUpdateSchema), async (req: AdminRequest, res, next) => {
    try {
      const { currentPassword, newPassword } = req.body;
      const admin = await authService.authenticate(req.admin!.username, currentPassword);

      if (!admin) {
        res.status(401).json({ error: 'invalid_credentials', message: 'Current password is incorrect' });
        return;
      }

      await authService.updatePassword(admin.id, newPassword, req.adminToken);
      res.json({ status: 'ok' });
    } catch (err) {
      next(err);
    }
  });

  return router;
}
