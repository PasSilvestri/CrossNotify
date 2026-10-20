import dotenv from 'dotenv';
import path from 'node:path';

dotenv.config();

export interface ServerConfig {
  port: number;
  host: string;
  dataDir: string;
  crossnotifySecret?: string;
  crossnotifySetupToken?: string;
  trustProxy: boolean | string | number;
  cookieSecure: 'auto' | boolean;
  logLevel: 'debug' | 'info' | 'warn' | 'error';
}

export function loadConfig(overrides: Partial<ServerConfig> = {}): ServerConfig {
  const trustProxyEnv = overrides.trustProxy !== undefined ? String(overrides.trustProxy) : process.env.TRUST_PROXY;
  let trustProxy: boolean | string | number = false;
  if (trustProxyEnv === 'true' || trustProxyEnv === '1') {
    trustProxy = 1;
  } else if (trustProxyEnv && trustProxyEnv !== 'false' && trustProxyEnv !== '0') {
    trustProxy = trustProxyEnv;
  }

  const cookieSecureEnv = overrides.cookieSecure !== undefined ? String(overrides.cookieSecure) : process.env.COOKIE_SECURE;
  let cookieSecure: 'auto' | boolean = 'auto';
  if (cookieSecureEnv === 'true') {
    cookieSecure = true;
  } else if (cookieSecureEnv === 'false') {
    cookieSecure = false;
  }

  const logLevelEnv = (overrides.logLevel || process.env.LOG_LEVEL || 'info').toLowerCase();
  const validLogLevels = ['debug', 'info', 'warn', 'error'] as const;
  const logLevel = validLogLevels.includes(logLevelEnv as any)
    ? (logLevelEnv as 'debug' | 'info' | 'warn' | 'error')
    : 'info';

  const dataDir = overrides.dataDir || process.env.DATA_DIR || './data';

  return {
    port: overrides.port ?? (process.env.PORT ? parseInt(process.env.PORT, 10) : 3000),
    host: overrides.host ?? process.env.HOST ?? '0.0.0.0',
    dataDir: path.resolve(dataDir),
    crossnotifySecret: overrides.crossnotifySecret ?? process.env.CROSSNOTIFY_SECRET,
    crossnotifySetupToken: overrides.crossnotifySetupToken ?? process.env.CROSSNOTIFY_SETUP_TOKEN,
    trustProxy: overrides.trustProxy !== undefined ? overrides.trustProxy : trustProxy,
    cookieSecure: overrides.cookieSecure !== undefined ? overrides.cookieSecure : cookieSecure,
    logLevel,
  };
}

export const config = loadConfig();
