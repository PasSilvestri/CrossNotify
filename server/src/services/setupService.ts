import { timingSafeEqualHex, sha256Hex, generateSetupToken } from '../crypto/tokens.js';
import type { AuthService } from './authService.js';
import type { FirebaseAdminService } from './firebaseAdmin.js';

export type SetupState = 'NEEDS_ADMIN' | 'NEEDS_FIREBASE' | 'READY';

export class SetupService {
  private setupToken: string | null = null;

  constructor(
    private authService: AuthService,
    private firebaseAdminService: FirebaseAdminService,
    initialSetupToken?: string
  ) {
    if (!this.authService.hasAdminUser()) {
      this.setupToken = initialSetupToken || generateSetupToken();
    }
  }

  printSetupBanner(): void {
    if (this.getState() === 'NEEDS_ADMIN' && this.setupToken) {
      console.log('============================================================');
      console.log(' CrossNotify first-run setup');
      console.log(` Open the dashboard and enter this setup token:  ${this.setupToken}`);
      console.log('============================================================');
    }
  }

  getSetupToken(): string | null {
    return this.setupToken;
  }

  verifySetupToken(token: string): boolean {
    if (!this.setupToken || !token) {
      return false;
    }
    return timingSafeEqualHex(sha256Hex(token), sha256Hex(this.setupToken));
  }

  discardSetupToken(): void {
    this.setupToken = null;
  }

  getState(): SetupState {
    if (!this.authService.hasAdminUser()) {
      return 'NEEDS_ADMIN';
    }

    if (!this.firebaseAdminService.isReady()) {
      return 'NEEDS_FIREBASE';
    }

    return 'READY';
  }
}
