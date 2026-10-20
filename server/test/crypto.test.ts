import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { loadMasterKey } from '../src/crypto/masterKey.js';
import { seal, open, SecretDecryptError } from '../src/crypto/secretBox.js';
import {
  randomToken,
  sha256Hex,
  generateRelayKey,
  generateSubscriberKey,
  generateSessionToken,
  generateSetupToken,
  timingSafeEqualHex,
} from '../src/crypto/tokens.js';

describe('crypto utilities', () => {
  let tmpDir: string;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'cn-crypto-test-'));
  });

  afterEach(() => {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  describe('masterKey', () => {
    it('generates a 32-byte secret.key file if unset', () => {
      const key = loadMasterKey(tmpDir);
      expect(key).toBeInstanceOf(Buffer);
      expect(key.length).toBe(32);

      const keyPath = path.join(tmpDir, 'secret.key');
      expect(fs.existsSync(keyPath)).toBe(true);
      const readKey = fs.readFileSync(keyPath);
      expect(readKey).toEqual(key);

      // Subsequent call reuses file
      const key2 = loadMasterKey(tmpDir);
      expect(key2).toEqual(key);
    });

    it('loads master key from CROSSNOTIFY_SECRET env string', () => {
      const random32 = crypto.randomBytes(32);
      const b64 = random32.toString('base64');
      const loaded = loadMasterKey(tmpDir, b64);
      expect(loaded).toEqual(random32);
    });

    it('throws if CROSSNOTIFY_SECRET is not 32 bytes', () => {
      const b64 = crypto.randomBytes(16).toString('base64');
      expect(() => loadMasterKey(tmpDir, b64)).toThrow(/must be a base64 string decoding to exactly 32 bytes/);
    });
  });

  describe('secretBox', () => {
    it('encrypts and decrypts with authenticated AAD', () => {
      const masterKey = crypto.randomBytes(32);
      const plaintext = Buffer.from('hello sensitive firebase credentials', 'utf8');

      const { iv, ciphertext } = seal(masterKey, 'firebase_service_account', plaintext);
      expect(iv.length).toBe(12);
      expect(ciphertext.length).toBeGreaterThan(plaintext.length);

      const decrypted = open(masterKey, 'firebase_service_account', iv, ciphertext);
      expect(decrypted.toString('utf8')).toBe('hello sensitive firebase credentials');
    });

    it('fails to decrypt with wrong secret name (AAD mismatch)', () => {
      const masterKey = crypto.randomBytes(32);
      const plaintext = Buffer.from('hello', 'utf8');
      const { iv, ciphertext } = seal(masterKey, 'name1', plaintext);

      expect(() => open(masterKey, 'name2', iv, ciphertext)).toThrow(SecretDecryptError);
    });

    it('fails to decrypt with wrong master key', () => {
      const key1 = crypto.randomBytes(32);
      const key2 = crypto.randomBytes(32);
      const { iv, ciphertext } = seal(key1, 'test', Buffer.from('data'));

      expect(() => open(key2, 'test', iv, ciphertext)).toThrow(SecretDecryptError);
    });
  });

  describe('tokens', () => {
    it('generates relay keys with cnr_ prefix and sha256 hash', () => {
      const { apiKey, hash, prefix } = generateRelayKey();
      expect(apiKey.startsWith('cnr_')).toBe(true);
      expect(hash).toBe(sha256Hex(apiKey));
      expect(prefix).toBe(apiKey.slice(0, 8));
    });

    it('generates subscriber keys with cns_ prefix and sha256 hash', () => {
      const { apiKey, hash, prefix } = generateSubscriberKey();
      expect(apiKey.startsWith('cns_')).toBe(true);
      expect(hash).toBe(sha256Hex(apiKey));
      expect(prefix).toBe(apiKey.slice(0, 8));
    });

    it('generates setup token with 24 chars', () => {
      const token = generateSetupToken();
      expect(token.length).toBe(24);
    });

    it('timingSafeEqualHex correctly compares equal and non-equal strings', () => {
      expect(timingSafeEqualHex('abcd', 'abcd')).toBe(true);
      expect(timingSafeEqualHex('abcd', 'abce')).toBe(false);
      expect(timingSafeEqualHex('abcd', 'abc')).toBe(false);
    });
  });
});
