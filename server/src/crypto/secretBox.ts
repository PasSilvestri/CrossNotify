import crypto from 'node:crypto';

export class SecretDecryptError extends Error {
  constructor(message = 'Failed to decrypt secret: invalid key, corrupted data, or authentication tag mismatch') {
    super(message);
    this.name = 'SecretDecryptError';
  }
}

export function seal(
  masterKey: Buffer,
  name: string,
  plaintext: Buffer
): { iv: Buffer; ciphertext: Buffer } {
  if (masterKey.length !== 32) {
    throw new Error('Master key must be 32 bytes for AES-256-GCM');
  }

  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', masterKey, iv);
  cipher.setAAD(Buffer.from(`secret:${name}`, 'utf8'));

  const encrypted = cipher.update(plaintext);
  const final = cipher.final();
  const tag = cipher.getAuthTag();

  const ciphertext = Buffer.concat([encrypted, final, tag]);

  return { iv, ciphertext };
}

export function open(
  masterKey: Buffer,
  name: string,
  iv: Buffer,
  ciphertextWithTag: Buffer
): Buffer {
  if (masterKey.length !== 32) {
    throw new SecretDecryptError('Invalid master key length');
  }

  if (ciphertextWithTag.length < 16) {
    throw new SecretDecryptError('Ciphertext too short to contain auth tag');
  }

  const tag = ciphertextWithTag.subarray(ciphertextWithTag.length - 16);
  const ciphertext = ciphertextWithTag.subarray(0, ciphertextWithTag.length - 16);

  try {
    const decipher = crypto.createDecipheriv('aes-256-gcm', masterKey, iv);
    decipher.setAAD(Buffer.from(`secret:${name}`, 'utf8'));
    decipher.setAuthTag(tag);

    const decrypted = Buffer.concat([decipher.update(ciphertext), decipher.final()]);
    return decrypted;
  } catch (err) {
    throw new SecretDecryptError(err instanceof Error ? err.message : undefined);
  }
}
