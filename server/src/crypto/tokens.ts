import crypto from 'node:crypto';

export function randomToken(bytes = 32): string {
  return crypto.randomBytes(bytes).toString('base64url');
}

export function sha256Hex(value: string | Buffer): string {
  return crypto.createHash('sha256').update(value).digest('hex');
}

export function timingSafeEqualHex(a: string, b: string): boolean {
  const bufA = Buffer.from(a, 'utf8');
  const bufB = Buffer.from(b, 'utf8');
  if (bufA.length !== bufB.length) {
    return false;
  }
  return crypto.timingSafeEqual(bufA, bufB);
}

export function generateRelayKey(): { apiKey: string; hash: string; prefix: string } {
  const apiKey = `cnr_${randomToken(32)}`;
  return {
    apiKey,
    hash: sha256Hex(apiKey),
    prefix: apiKey.slice(0, 8),
  };
}

export function generateSubscriberKey(): { apiKey: string; hash: string; prefix: string } {
  const apiKey = `cns_${randomToken(32)}`;
  return {
    apiKey,
    hash: sha256Hex(apiKey),
    prefix: apiKey.slice(0, 8),
  };
}

export function generateSessionToken(): { token: string; hash: string } {
  const token = randomToken(32);
  return {
    token,
    hash: sha256Hex(token),
  };
}

export function generateSetupToken(): string {
  return randomToken(18); // 24 chars in base64url
}
