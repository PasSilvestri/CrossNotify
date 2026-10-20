import { initializeApp, deleteApp, cert, getApp, type App } from 'firebase-admin/app';
import { getMessaging, type Messaging } from 'firebase-admin/messaging';
import type { FirebaseConfigService, ServiceAccount, ClientParams } from './firebaseConfigService.js';

export type FirebaseVerifier = (serviceAccount: ServiceAccount) => Promise<void>;

export const defaultFirebaseVerifier: FirebaseVerifier = async (sa: ServiceAccount) => {
  const tempName = `temp-verify-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
  let tempApp: App | null = null;
  try {
    tempApp = initializeApp(
      {
        credential: cert(sa as any),
      },
      tempName
    );
    const cred = tempApp.options.credential;
    if (cred && 'getAccessToken' in cred) {
      await (cred as any).getAccessToken();
    }
  } finally {
    if (tempApp) {
      try {
        await deleteApp(tempApp);
      } catch {
        // ignore
      }
    }
  }
};

export interface FirebaseStatus {
  serviceAccount: {
    uploaded: boolean;
    projectId?: string;
    clientEmail?: string;
    verified?: boolean;
    error?: string;
  };
  clientConfig: {
    uploaded: boolean;
    projectId?: string;
    appId?: string;
  };
  projectsMatch: boolean;
}

export class FirebaseAdminService {
  private app: App | null = null;
  private messaging: Messaging | null = null;
  private cachedClientParams: ClientParams | null = null;
  private lastVerificationError: string | null = null;
  private verifier: FirebaseVerifier;

  constructor(
    private firebaseConfigService: FirebaseConfigService,
    verifier?: FirebaseVerifier
  ) {
    this.verifier = verifier || defaultFirebaseVerifier;
  }

  setVerifier(verifier: FirebaseVerifier): void {
    this.verifier = verifier;
  }

  getVerifier(): FirebaseVerifier {
    return this.verifier;
  }

  async verifyServiceAccount(sa: ServiceAccount): Promise<void> {
    await this.verifier(sa);
  }

  async reload(): Promise<boolean> {
    // Teardown existing app if present
    if (this.app) {
      try {
        await deleteApp(this.app);
      } catch (err) {
        console.warn('[firebaseAdmin] Error deleting previous firebase app:', err);
      }
      this.app = null;
      this.messaging = null;
    }
    this.cachedClientParams = null;
    this.lastVerificationError = null;

    const sa = this.firebaseConfigService.getServiceAccount();
    const clientConfig = this.firebaseConfigService.getClientConfig();

    if (!sa || !clientConfig) {
      return false;
    }

    try {
      this.cachedClientParams = this.firebaseConfigService.extractClientParams(clientConfig);
    } catch (err: any) {
      console.warn('[firebaseAdmin] Client config invalid:', err.message);
      return false;
    }

    if (sa.project_id !== clientConfig.project_info.project_id) {
      console.warn('[firebaseAdmin] Project mismatch between service account and google-services.json');
      return false;
    }

    try {
      const APP_NAME = 'crossnotify';
      try {
        const existing = getApp(APP_NAME);
        if (existing) {
          await deleteApp(existing);
        }
      } catch {
        // App not initialized yet, normal
      }

      this.app = initializeApp(
        {
          credential: cert(sa as any),
        },
        APP_NAME
      );
      this.messaging = getMessaging(this.app);
      console.log(`[firebaseAdmin] Initialized Firebase Admin SDK for project ${sa.project_id}`);
      return true;
    } catch (err: any) {
      console.error('[firebaseAdmin] Failed to initialize Firebase app:', err.message);
      this.lastVerificationError = err.message;
      return false;
    }
  }

  isReady(): boolean {
    return !!(this.messaging && this.cachedClientParams);
  }

  getMessaging(): Messaging | null {
    return this.messaging;
  }

  getClientParams(): ClientParams | null {
    return this.cachedClientParams;
  }

  status(): FirebaseStatus {
    const sa = this.firebaseConfigService.getServiceAccount();
    const clientConfig = this.firebaseConfigService.getClientConfig();

    const serviceAccountStatus = {
      uploaded: !!sa,
      projectId: sa?.project_id,
      clientEmail: sa?.client_email,
      verified: this.isReady(),
      error: this.lastVerificationError || undefined,
    };

    let clientAppId: string | undefined;
    if (clientConfig) {
      try {
        const params = this.firebaseConfigService.extractClientParams(clientConfig);
        clientAppId = params.appId;
      } catch {
        // ignore
      }
    }

    const clientConfigStatus = {
      uploaded: !!clientConfig,
      projectId: clientConfig?.project_info.project_id,
      appId: clientAppId,
    };

    const projectsMatch =
      !!sa &&
      !!clientConfig &&
      sa.project_id === clientConfig.project_info.project_id;

    return {
      serviceAccount: serviceAccountStatus,
      clientConfig: clientConfigStatus,
      projectsMatch,
    };
  }
}
