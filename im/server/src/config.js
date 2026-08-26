import path from 'node:path';

const root = process.cwd();

export function loadConfig(overrides = {}) {
  const config = {
    port: Number(process.env.PORT || 3000),
    host: process.env.HOST || '0.0.0.0',
    jwtSecret: process.env.JWT_SECRET || 'development-only-change-this-secret',
    databasePath: process.env.DATABASE_PATH || path.join(root, 'data', 'im.db'),
    uploadDir: process.env.UPLOAD_DIR || path.join(root, 'data', 'uploads'),
    publicUrl: (process.env.PUBLIC_URL || '').replace(/\/$/, ''),
    corsOrigins: (process.env.CORS_ORIGIN || '*').split(',').map(value => value.trim()).filter(Boolean),
    maxUploadMb: Number(process.env.MAX_UPLOAD_MB || 50),
    peopleApiBaseUrl: (process.env.PEOPLE_API_BASE_URL || 'https://people.lxvb.top/api/open/people').replace(/\/$/, ''),
    peopleClientId: process.env.PEOPLE_CLIENT_ID || 'linkup-im',
    peopleClientSecret: process.env.PEOPLE_CLIENT_SECRET || 'linkup-local-client-secret-change-me',
    peopleRedirectUri: process.env.PEOPLE_REDIRECT_URI || 'https://im.lxvb.top/oauth/callback',
    stunUrls: (process.env.STUN_URLS || 'stun:stun.l.google.com:19302').split(',').map(value => value.trim()).filter(Boolean),
    turnUrls: (process.env.TURN_URLS || '').split(',').map(value => value.trim()).filter(Boolean),
    turnUsername: process.env.TURN_USERNAME || '',
    turnCredential: process.env.TURN_CREDENTIAL || ''
  };
  Object.assign(config, overrides);
  if (process.env.NODE_ENV === 'production') {
    if (config.jwtSecret === 'development-only-change-this-secret' || config.jwtSecret.length < 32) {
      throw new Error('Production JWT_SECRET must contain at least 32 characters');
    }
    if (config.peopleClientSecret === 'linkup-local-client-secret-change-me' || config.peopleClientSecret.length < 32) {
      throw new Error('Production PEOPLE_CLIENT_SECRET must contain at least 32 characters');
    }
  }
  return config;
}
