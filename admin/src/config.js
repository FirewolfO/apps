import path from 'node:path';

export function loadConfig(overrides = {}) {
  const config = {
    port: Number(process.env.PORT || 3000),
    host: process.env.HOST || '0.0.0.0',
    dataDir: process.env.DATA_DIR || path.resolve('data'),
    adminUsername: process.env.ADMIN_USERNAME || 'admin',
    adminPassword: process.env.ADMIN_PASSWORD || 'admin123!',
    sessionHours: Number(process.env.SESSION_HOURS || 12),
    maxApkMb: Number(process.env.MAX_APK_MB || 300),
  };
  return Object.assign(config, overrides);
}
