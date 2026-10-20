import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

export function loadMasterKey(dataDir: string, envSecret?: string): Buffer {
  if (envSecret) {
    const key = Buffer.from(envSecret, 'base64');
    if (key.length !== 32) {
      throw new Error(`CROSSNOTIFY_SECRET must be a base64 string decoding to exactly 32 bytes (got ${key.length} bytes)`);
    }
    console.log('[crypto] Master key loaded from CROSSNOTIFY_SECRET environment variable.');
    return key;
  }

  const keyPath = path.join(dataDir, 'secret.key');
  if (fs.existsSync(keyPath)) {
    const key = fs.readFileSync(keyPath);
    if (key.length !== 32) {
      throw new Error(`Master key at ${keyPath} must be exactly 32 bytes (got ${key.length} bytes)`);
    }
    console.log(`[crypto] Master key loaded from file: ${keyPath}`);
    return key;
  }

  // Ensure dataDir exists
  fs.mkdirSync(dataDir, { recursive: true });

  const newKey = crypto.randomBytes(32);
  fs.writeFileSync(keyPath, newKey, { mode: 0o600 });
  console.log(`[crypto] Auto-generated new 32-byte master key at: ${keyPath}`);
  return newKey;
}
