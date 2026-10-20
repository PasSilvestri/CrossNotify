import { Router } from 'express';
import { requireAdmin } from '../../middleware/requireAdmin.js';
import {
  ServiceAccountSchema,
  GoogleServicesSchema,
  type FirebaseConfigService,
} from '../../services/firebaseConfigService.js';
import type { FirebaseAdminService } from '../../services/firebaseAdmin.js';
import type { ChannelService } from '../../services/channelService.js';
import type { SettingsService } from '../../services/settingsService.js';
import type { AuthService } from '../../services/authService.js';
import { ANDROID_PACKAGE_NAME } from '../../constants.js';

export function createFirebaseRouter(
  firebaseConfigService: FirebaseConfigService,
  firebaseAdminService: FirebaseAdminService,
  channelService: ChannelService,
  settingsService: SettingsService,
  authService: AuthService
): Router {
  const router = Router();
  const auth = requireAdmin(authService);

  router.get('/status', auth, (req, res) => {
    res.json(firebaseAdminService.status());
  });

  router.post('/service-account', auth, async (req, res, next) => {
    try {
      const parsed = ServiceAccountSchema.safeParse(req.body);
      if (!parsed.success) {
        res.status(422).json({
          error: 'invalid_service_account',
          detail: parsed.error.issues[0]?.message || 'Invalid service account JSON structure',
        });
        return;
      }

      const sa = parsed.data;

      // Verify the service account credentials with Firebase Admin SDK
      try {
        await firebaseAdminService.verifyServiceAccount(sa);
      } catch (err: any) {
        res.status(422).json({
          error: 'firebase_credential_rejected',
          detail: err?.message || 'Firebase Admin SDK rejected the service account credentials',
        });
        return;
      }

      const clientConfig = firebaseConfigService.getClientConfig();
      const currentProjectId = settingsService.get('firebase_project_id');

      // Check conflict with existing client config
      if (clientConfig && clientConfig.project_info.project_id !== sa.project_id) {
        const activeChannels = channelService.countActiveAndPending();
        if (activeChannels > 0 && req.query.force !== 'true') {
          res.status(409).json({
            error: 'project_change_requires_reset',
            channels: activeChannels,
            message: `Changing project to ${sa.project_id} will revoke ${activeChannels} active channels`,
          });
          return;
        }

        if (req.query.force === 'true') {
          channelService.revokeAllChannels();
          firebaseConfigService.deleteSecret('firebase_client_config');
        } else {
          res.status(422).json({
            error: 'project_mismatch',
            detail: `Service account project ID (${sa.project_id}) does not match google-services.json project ID (${clientConfig.project_info.project_id})`,
          });
          return;
        }
      } else if (currentProjectId && currentProjectId !== sa.project_id) {
        const activeChannels = channelService.countActiveAndPending();
        if (activeChannels > 0 && req.query.force !== 'true') {
          res.status(409).json({
            error: 'project_change_requires_reset',
            channels: activeChannels,
            message: `Changing project to ${sa.project_id} will revoke ${activeChannels} active channels`,
          });
          return;
        }

        if (req.query.force === 'true') {
          channelService.revokeAllChannels();
        }
      }

      // Save secret
      firebaseConfigService.saveSecret('firebase_service_account', Buffer.from(JSON.stringify(sa), 'utf8'));
      settingsService.set('firebase_project_id', sa.project_id);

      await firebaseAdminService.reload();
      res.json(firebaseAdminService.status());
    } catch (err) {
      next(err);
    }
  });

  router.post('/client-config', auth, async (req, res, next) => {
    try {
      const parsed = GoogleServicesSchema.safeParse(req.body);
      if (!parsed.success) {
        res.status(422).json({
          error: 'invalid_client_config',
          detail: parsed.error.issues[0]?.message || 'Invalid google-services.json structure',
        });
        return;
      }

      const config = parsed.data;
      const matchingClient = config.client.find(
        (c) => c.client_info.android_client_info.package_name === ANDROID_PACKAGE_NAME
      );

      if (!matchingClient) {
        res.status(422).json({
          error: 'package_not_registered',
          detail: `Register an Android app with package name ${ANDROID_PACKAGE_NAME} in your Firebase project`,
        });
        return;
      }

      const sa = firebaseConfigService.getServiceAccount();
      const currentProjectId = settingsService.get('firebase_project_id');

      // Check conflict with existing service account
      if (sa && sa.project_id !== config.project_info.project_id) {
        const activeChannels = channelService.countActiveAndPending();
        if (activeChannels > 0 && req.query.force !== 'true') {
          res.status(409).json({
            error: 'project_change_requires_reset',
            channels: activeChannels,
            message: `Changing project to ${config.project_info.project_id} will revoke ${activeChannels} active channels`,
          });
          return;
        }

        if (req.query.force === 'true') {
          channelService.revokeAllChannels();
          firebaseConfigService.deleteSecret('firebase_service_account');
        } else {
          res.status(422).json({
            error: 'project_mismatch',
            detail: `google-services.json project ID (${config.project_info.project_id}) does not match service account project ID (${sa.project_id})`,
          });
          return;
        }
      } else if (currentProjectId && currentProjectId !== config.project_info.project_id) {
        const activeChannels = channelService.countActiveAndPending();
        if (activeChannels > 0 && req.query.force !== 'true') {
          res.status(409).json({
            error: 'project_change_requires_reset',
            channels: activeChannels,
            message: `Changing project to ${config.project_info.project_id} will revoke ${activeChannels} active channels`,
          });
          return;
        }

        if (req.query.force === 'true') {
          channelService.revokeAllChannels();
        }
      }

      firebaseConfigService.saveSecret('firebase_client_config', Buffer.from(JSON.stringify(config), 'utf8'));
      settingsService.set('firebase_project_id', config.project_info.project_id);

      await firebaseAdminService.reload();
      res.json(firebaseAdminService.status());
    } catch (err) {
      next(err);
    }
  });

  return router;
}
