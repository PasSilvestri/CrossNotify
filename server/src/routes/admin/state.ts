import { Router } from 'express';
import type { SetupService } from '../../services/setupService.js';
import type { SettingsService } from '../../services/settingsService.js';

export function createStateRouter(setupService: SetupService, settingsService: SettingsService): Router {
  const router = Router();

  router.get('/state', (req, res) => {
    const state = setupService.getState();
    const settings = settingsService.getAll();
    res.json({
      state,
      serverName: settings.serverName,
      version: '1.0.0',
    });
  });

  return router;
}
