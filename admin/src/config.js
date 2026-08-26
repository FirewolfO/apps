import path from 'node:path';

export function loadConfig(overrides = {}) {
  const config = {
    port: Number(process.env.PORT || 3000),
    host: process.env.HOST || '0.0.0.0',
    dataDir: process.env.DATA_DIR || path.resolve('data'),
    sessionHours: Number(process.env.SESSION_HOURS || 12),
    maxApkMb: Number(process.env.MAX_APK_MB || 300),
    peopleApiBaseUrl: (process.env.PEOPLE_API_BASE_URL || 'http://127.0.0.1:8082/api/open/people').replace(/\/$/, ''),
    peopleAuthorizeUrl: process.env.PEOPLE_AUTHORIZE_URL || 'http://localhost:5177/oauth/authorize',
    peopleClientId: process.env.PEOPLE_CLIENT_ID || 'app-center',
    peopleClientSecret: process.env.PEOPLE_CLIENT_SECRET || 'app-center-local-client-secret-change-me',
    peopleRedirectUri: process.env.PEOPLE_REDIRECT_URI || 'http://localhost:3000/oauth/callback',
    cookieSecure: String(process.env.COOKIE_SECURE || 'false').toLowerCase() === 'true',
  };
  return Object.assign(config, overrides);
}
