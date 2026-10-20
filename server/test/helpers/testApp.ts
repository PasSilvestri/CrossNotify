import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { createApp, type AppContext } from '../../src/app.js';
import type { PushService, PushResult } from '../../src/services/pushService.js';
import type { ChannelRow } from '../../src/services/channelService.js';
import type { NotificationRow } from '../../src/services/notificationService.js';
import type { ServiceAccount, GoogleServices } from '../../src/services/firebaseConfigService.js';

export class MockPushService implements PushService {
  public dispatched: Array<{ channel: ChannelRow; notification: NotificationRow }> = [];
  public revokedCalls: Array<{ channel: ChannelRow; tokens: string[] }> = [];

  async dispatch(channel: ChannelRow, n: NotificationRow): Promise<PushResult> {
    this.dispatched.push({ channel, notification: n });
    return { sent: 1, failed: 0, removed: 0 };
  }

  async sendChannelRevoked(channel: ChannelRow, tokens: string[], subscriberId?: string): Promise<void> {
    this.revokedCalls.push({ channel, tokens, subscriberId });
  }

  reset() {
    this.dispatched = [];
    this.revokedCalls = [];
  }
}

export const VALID_TEST_PRIVATE_KEY =
  '-----BEGIN PRIVATE KEY-----\nMIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQC95BOQpvAQv3n+\n93VTNYyGo3cd99AIvt/x2b4PR/Zzw4FLa20FUCWCQtgvPPmS0xVjHPtxp9i/EdGD\nWgbEq4/PmJ9UGEiujsZDQbtMm8KOZUdhd/CVsZbD3Q/tOMSi1b6CDzdfNdbUIq9z\nGzi/VJ4p5tDNAEo7KbQ2HVDV3JdGqEA7RfQG2KMewdCvfP2Nw2nvStLVQLzL4A7q\nnKfyCxLP7fd/jMLeiLFT/fUsTMD6t/Lu6QZg8LLERORclXkDtM8EMYPzlRJy+gcS\nTVR+7CyV+AOkjbf5Uey0j4hR+e9C8vrYuWebouV7qsvK4CmF8WFwP0gSG0Mzd3tX\nsfSWZR8jAgMBAAECggEACLT1hLNy3IG7GUrFcWxKJj5GjvQ28L+3ywId6dt13HcB\nbbDeTdkP7zobaYcVNntBRsskaGC22oP5pFkzaD8pl+46mAOJCZ/4TUstSGT39cmF\nDV4PhP2gmRiAy3LH6WN9mb8S3eW9FPsGKnP8FSHPIsK3nj6+KJpP7g9IDpZPxWiL\nUSUGtJgeNPJtmxyaC9ZSRnfuJ4VQ7CbrUrV/gYwyiKzVjZB40shaSpeVHye36JLF\nft8WRGHYIEIj7OPuvvAo6RhRdYoKeJn+bOWv8pn0ruY9yl0DmI332zYW+6HokPsV\niMgLqxJOHXDqiu0KGm/ZDBflTN2tabqNgusdOXsNIQKBgQDpMRdsIFBB0H1SEJs2\nPkDq+b8645mLa139m5u0ob05HSlLJyKoN4HJ/L20wIhdzDqdKWFOF/HMKOByxEd6\nm7gn26G6io4G/MQgK75ebFZ7go2xCalUuO8IVaf0POdX2ijonayYYJdMJTHSNMNP\n+tEeZp3Fyt7xWX0qx1fef6iiUQKBgQDQdsWAsQwwJRDoIHC3V6gkE/DrDWKpSVl5\n3ryb0Xq9QVjjARdZrB+aVSHHC69W+XFRM6IIPWZzP8n55gLi0LHz+rWQg7wpI4kL\n4gigr6+QzPfv5uZD8BaGj0xCERPwBIyE6OBNIiv0rz1B15NDZU4jhZbTkjS2xMSf\nqp9rfCz5MwKBgHljD6E+dOi9oe7NLC6hW2O13ADIp432tvekP/pYU0rezjcLyTOt\nqUc+5HWp35KMFFrpRnjDaB/xi1zSpa7CTquY3ehf5azneSSSAMfgN2iVvjJRKC8t\nEWjSRdqStY56xt7H7Sdo6Hk/x154Ds+cba0Yl7p1b3ZNMiXJnQj6hQrxAoGAeEmQ\nfUmr9rvsnYTahXCXHPpEsdzvaS9h6eiUfHTLlhj26UXf62ZedfdZnjkn28/jiy93\n97k2PJo6OXB3idlSVa38xn69PB+9HJY41D+L1snu5wcdJ2aKGOrm3dhs4Lo/OxIF\nG3z0UCQt/Kgf2WvDQsv60+oxcKTpsBgMFUF50fECgYAfPDXNL1bN4/DdoteW3Q09\nzsvsuiTUCj4GCJws7S+Dlz2fzIOKA3XNAbRJuyA6b3Sx06CtWZdNYb/dqzAaKOXE\n/BFnwqMDLys0+mG8AG1V3TBG8fgMk22Szj/dbDJ5SmSZ1aTdhmczj/WWkcqkotAW\nIg8lysM0KDeYXYPakw3nOQ==\n-----END PRIVATE KEY-----\n';

export const VALID_TEST_SERVICE_ACCOUNT: ServiceAccount = {
  type: 'service_account',
  project_id: 'test-project-123',
  private_key_id: 'pkid123',
  private_key: VALID_TEST_PRIVATE_KEY,
  client_email: 'test-admin@test-project-123.iam.gserviceaccount.com',
  client_id: '123456789',
};

export const VALID_TEST_CLIENT_CONFIG: GoogleServices = {
  project_info: {
    project_number: '1234567890',
    project_id: 'test-project-123',
  },
  client: [
    {
      client_info: {
        mobilesdk_app_id: '1:1234567890:android:abc',
        android_client_info: {
          package_name: 'com.crossnotify.app',
        },
      },
      api_key: [
        {
          current_key: 'AIzaSyTestApiKey123',
        },
      ],
    },
  ],
};

export async function createTestContext(overrides: {
  crossnotifySecret?: string;
  crossnotifySetupToken?: string;
  verifierRejects?: boolean;
  publicDir?: string;
} = {}): Promise<{
  ctx: AppContext;
  tmpDir: string;
  mockPush: MockPushService;
  cleanup: () => void;
}> {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'cn-server-test-'));
  const mockPush = new MockPushService();

  const verifier = async (sa: ServiceAccount) => {
    if (overrides.verifierRejects) {
      throw new Error('Mock verifier rejected service account');
    }
  };

  const ctx = await createApp({
    config: {
      dataDir: tmpDir,
      crossnotifySecret: overrides.crossnotifySecret,
      crossnotifySetupToken: overrides.crossnotifySetupToken,
      trustProxy: 1,
      cookieSecure: false,
    },
    pushService: mockPush,
    firebaseVerifier: verifier,
    publicDir: overrides.publicDir || path.join(tmpDir, 'public'),
    skipCleanerStart: true,
  });

  const cleanup = () => {
    try {
      ctx.db.close();
    } catch {
      // ignore
    }
    try {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    } catch {
      // ignore
    }
  };

  return { ctx, tmpDir, mockPush, cleanup };
}
