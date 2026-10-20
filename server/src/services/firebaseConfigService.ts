import { z } from 'zod';
import type Database from 'better-sqlite3';
import { ANDROID_PACKAGE_NAME } from '../constants.js';
import { seal, open, SecretDecryptError } from '../crypto/secretBox.js';
import type { SettingsService } from './settingsService.js';

export const ServiceAccountSchema = z.object({
  type: z.literal('service_account'),
  project_id: z.string().min(1),
  private_key_id: z.string().optional(),
  private_key: z.string().refine((k) => k.includes('-----BEGIN PRIVATE KEY-----'), {
    message: 'Must contain -----BEGIN PRIVATE KEY-----',
  }),
  client_email: z.string().refine((e) => e.endsWith('.iam.gserviceaccount.com'), {
    message: 'Must end with .iam.gserviceaccount.com',
  }),
  client_id: z.string().optional(),
  auth_uri: z.string().optional(),
  token_uri: z.string().optional(),
  auth_provider_x509_cert_url: z.string().optional(),
  client_x509_cert_url: z.string().optional(),
}).passthrough();

export type ServiceAccount = z.infer<typeof ServiceAccountSchema>;

export const GoogleServicesClientSchema = z.object({
  client_info: z.object({
    mobilesdk_app_id: z.string().min(1),
    android_client_info: z.object({
      package_name: z.string(),
    }),
  }),
  api_key: z.array(
    z.object({
      current_key: z.string().min(1),
    })
  ).min(1),
}).passthrough();

export const GoogleServicesSchema = z.object({
  project_info: z.object({
    project_id: z.string().min(1),
    project_number: z.string().min(1),
  }).passthrough(),
  client: z.array(GoogleServicesClientSchema).min(1),
}).passthrough();

export type GoogleServices = z.infer<typeof GoogleServicesSchema>;

export interface ClientParams {
  projectId: string;
  appId: string;
  apiKey: string;
  senderId: string;
}

export class FirebaseConfigService {
  constructor(
    private db: Database.Database,
    private masterKey: Buffer,
    private settingsService: SettingsService
  ) {}

  setMasterKey(key: Buffer): void {
    this.masterKey = key;
  }

  getMasterKey(): Buffer {
    return this.masterKey;
  }

  getSecret(name: string): Buffer | null {
    const row = this.db.prepare('SELECT iv, ciphertext FROM secrets WHERE name = ?').get(name) as
      | { iv: Buffer; ciphertext: Buffer }
      | undefined;
    if (!row) {
      return null;
    }
    return open(this.masterKey, name, row.iv, row.ciphertext);
  }

  saveSecret(name: string, data: Buffer): void {
    const { iv, ciphertext } = seal(this.masterKey, name, data);
    const now = Date.now();
    this.db.prepare(`
      INSERT INTO secrets (name, iv, ciphertext, created_at, updated_at)
      VALUES (?, ?, ?, ?, ?)
      ON CONFLICT(name) DO UPDATE SET
        iv = excluded.iv,
        ciphertext = excluded.ciphertext,
        updated_at = excluded.updated_at
    `).run(name, iv, ciphertext, now, now);
  }

  deleteSecret(name: string): void {
    this.db.prepare('DELETE FROM secrets WHERE name = ?').run(name);
  }

  getServiceAccount(): ServiceAccount | null {
    try {
      const buf = this.getSecret('firebase_service_account');
      if (!buf) return null;
      return JSON.parse(buf.toString('utf8'));
    } catch (err) {
      if (err instanceof SecretDecryptError) {
        console.error('[firebaseConfig] Failed to decrypt service account with master key');
        return null;
      }
      throw err;
    }
  }

  getClientConfig(): GoogleServices | null {
    try {
      const buf = this.getSecret('firebase_client_config');
      if (!buf) return null;
      return JSON.parse(buf.toString('utf8'));
    } catch (err) {
      if (err instanceof SecretDecryptError) {
        console.error('[firebaseConfig] Failed to decrypt client config with master key');
        return null;
      }
      throw err;
    }
  }

  extractClientParams(clientConfig: GoogleServices): ClientParams {
    const matchingClient = clientConfig.client.find(
      (c) => c.client_info.android_client_info.package_name === ANDROID_PACKAGE_NAME
    );

    if (!matchingClient) {
      throw new Error(`Package not registered: ${ANDROID_PACKAGE_NAME}`);
    }

    return {
      projectId: clientConfig.project_info.project_id,
      appId: matchingClient.client_info.mobilesdk_app_id,
      apiKey: matchingClient.api_key[0].current_key,
      senderId: clientConfig.project_info.project_number,
    };
  }

  getClientParams(): ClientParams | null {
    const config = this.getClientConfig();
    if (!config) return null;
    try {
      return this.extractClientParams(config);
    } catch {
      return null;
    }
  }
}
