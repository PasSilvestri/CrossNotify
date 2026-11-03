import { Router } from 'express';
import type { SetupService } from '../../services/setupService.js';
import type { SettingsService } from '../../services/settingsService.js';
import { SERVER_VERSION } from '../../constants.js';

export function createStateRouter(setupService: SetupService, settingsService: SettingsService): Router {
  const router = Router();

  router.get('/state', (req, res) => {
    const state = setupService.getState();
    const settings = settingsService.getAll();
    res.json({
      state,
      serverName: settings.serverName,
      version: SERVER_VERSION,
    });
  });

  return router;
}
