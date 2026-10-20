import express, { type Express } from 'express';
import helmet from 'helmet';
import cors from 'cors';
import cookieParser from 'cookie-parser';
import path from 'node:path';
import type Database from 'better-sqlite3';

import { loadConfig, type ServerConfig } from './config.js';
import { openDatabase } from './db/index.js';
import { loadMasterKey } from './crypto/masterKey.js';

import { SettingsService } from './services/settingsService.js';
import { AuthService } from './services/authService.js';
import { FirebaseConfigService } from './services/firebaseConfigService.js';
import { FirebaseAdminService, type FirebaseVerifier } from './services/firebaseAdmin.js';
import { SetupService } from './services/setupService.js';
import { ChannelService } from './services/channelService.js';
import { SubscriberService } from './services/subscriberService.js';
import { NotificationService } from './services/notificationService.js';
import { FcmPushService, type PushService } from './services/pushService.js';
import { CleanerService } from './services/cleanerService.js';

import { requireState } from './middleware/requireState.js';
import { originCheck } from './middleware/originCheck.js';
import { adminLimiter, deviceIpLimiter, deviceKeyLimiter } from './middleware/rateLimits.js';
import { errorHandler } from './middleware/errorHandler.js';
import { setupStaticServing } from './static.js';

import { createHealthRouter } from './routes/health.js';
import { createStateRouter } from './routes/admin/state.js';
import { createSetupRouter } from './routes/admin/setup.js';
import { createAuthRouter } from './routes/admin/auth.js';
import { createAccountRouter } from './routes/admin/account.js';
import { createFirebaseRouter } from './routes/admin/firebase.js';
import { createSettingsRouter } from './routes/admin/settings.js';
import { createAdminChannelsRouter } from './routes/admin/channels.js';
import { createStatsRouter } from './routes/admin/stats.js';

import { createDeviceRelayRouter } from './routes/device/relay.js';
import { createDeviceNotificationsRouter } from './routes/device/notifications.js';
import { createDeviceSubscribersRouter } from './routes/device/subscribers.js';

export interface AppDependencies {
  config?: Partial<ServerConfig>;
  db?: Database.Database;
  pushService?: PushService;
  firebaseVerifier?: FirebaseVerifier;
  publicDir?: string;
  skipCleanerStart?: boolean;
}

export interface AppContext {
  app: Express;
  db: Database.Database;
  config: ServerConfig;
  masterKey: Buffer;
  services: {
    settingsService: SettingsService;
    authService: AuthService;
    firebaseConfigService: FirebaseConfigService;
    firebaseAdminService: FirebaseAdminService;
    setupService: SetupService;
    channelService: ChannelService;
    subscriberService: SubscriberService;
    notificationService: NotificationService;
    pushService: PushService;
    cleanerService: CleanerService;
  };
}

export async function createApp(deps: AppDependencies = {}): Promise<AppContext> {
  const config = loadConfig(deps.config);
  const db = deps.db || openDatabase(path.join(config.dataDir, 'crossnotify.sqlite'));
  const masterKey = loadMasterKey(config.dataDir, config.crossnotifySecret);

  const settingsService = new SettingsService(db);
  const authService = new AuthService(db);
  const firebaseConfigService = new FirebaseConfigService(db, masterKey, settingsService);
  const firebaseAdminService = new FirebaseAdminService(firebaseConfigService, deps.firebaseVerifier);

  // Attempt to reload Firebase credentials at boot (if present and valid)
  await firebaseAdminService.reload();

  const setupService = new SetupService(authService, firebaseAdminService, config.crossnotifySetupToken);
  const channelService = new ChannelService(db, settingsService, firebaseAdminService);
  const subscriberService = new SubscriberService(db);
  const notificationService = new NotificationService(db, settingsService);
  const pushService = deps.pushService || new FcmPushService(firebaseAdminService, subscriberService);
  const cleanerService = new CleanerService(db);

  if (!deps.skipCleanerStart) {
    cleanerService.start();
  }

  const app = express();

  // Trust proxy
  if (config.trustProxy) {
    app.set('trust proxy', config.trustProxy);
  }

  // Security headers with CSP allowing data: images for client-side QR codes
  app.use(
    helmet({
      contentSecurityPolicy: {
        directives: {
          ...helmet.contentSecurityPolicy.getDefaultDirectives(),
          'img-src': ["'self'", 'data:'],
        },
      },
    })
  );

  app.use(cors());
  app.use(cookieParser());
  app.use(express.json({ limit: '64kb' }));
  app.use(express.urlencoded({ extended: false }));

  // Public Health endpoint
  app.use(createHealthRouter(setupService));

  // Admin API routes: /admin/api/*
  const adminApi = express.Router();
  adminApi.use(originCheck);
  adminApi.use(adminLimiter);

  // State route: accessible in any state
  adminApi.use(createStateRouter(setupService, settingsService));

  // Setup route: only in NEEDS_ADMIN
  adminApi.use('/setup', requireState(setupService, 'NEEDS_ADMIN'), createSetupRouter(setupService, authService, settingsService, config));

  // Auth, account, firebase, settings: allowed in NEEDS_FIREBASE and READY
  const authedAdminApi = express.Router();
  authedAdminApi.use(requireState(setupService, 'NEEDS_FIREBASE', 'READY'));
  authedAdminApi.use('/auth', createAuthRouter(authService, setupService, config));
  authedAdminApi.use('/account', createAccountRouter(authService));
  authedAdminApi.use('/firebase', createFirebaseRouter(firebaseConfigService, firebaseAdminService, channelService, settingsService, authService));
  authedAdminApi.use('/settings', createSettingsRouter(settingsService, authService));
  adminApi.use(authedAdminApi);

  // Channels, Relays, Stats: only in READY
  const readyAdminApi = express.Router();
  readyAdminApi.use(requireState(setupService, 'READY'));
  readyAdminApi.use(createAdminChannelsRouter(channelService, subscriberService, pushService, authService));
  readyAdminApi.use(createStatsRouter(db, authService));
  adminApi.use(readyAdminApi);

  app.use('/admin/api', adminApi);

  // Device API routes: /api/v1/* (only in READY)
  const deviceApi = express.Router();
  deviceApi.use(requireState(setupService, 'READY'));
  deviceApi.use(deviceIpLimiter);
  deviceApi.use(deviceKeyLimiter);

  deviceApi.use(createDeviceRelayRouter(channelService, pushService));
  deviceApi.use(createDeviceSubscribersRouter(subscriberService, channelService, settingsService));
  deviceApi.use(createDeviceNotificationsRouter(notificationService, channelService, subscriberService, pushService));

  app.use('/api/v1', deviceApi);

  // Static serving for dashboard build & SPA fallback
  const publicDir = deps.publicDir || path.resolve(process.cwd(), 'public');
  setupStaticServing(app, publicDir);

  // Central error handler
  app.use(errorHandler);

  return {
    app,
    db,
    config,
    masterKey,
    services: {
      settingsService,
      authService,
      firebaseConfigService,
      firebaseAdminService,
      setupService,
      channelService,
      subscriberService,
      notificationService,
      pushService,
      cleanerService,
    },
  };
}
