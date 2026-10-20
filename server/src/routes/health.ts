import { Router } from 'express';
import type { SetupService } from '../services/setupService.js';

export function createHealthRouter(setupService: SetupService): Router {
  const router = Router();

  router.get('/health', (req, res) => {
    res.json({
      status: 'ok',
      state: setupService.getState(),
    });
  });

  return router;
}
