import crypto from 'node:crypto';

export class PeopleAuthError extends Error {
  constructor(message, status = 401) {
    super(message);
    this.status = status;
  }
}

function cookieHeader(response) {
  const values = typeof response.headers.getSetCookie === 'function'
    ? response.headers.getSetCookie()
    : [response.headers.get('set-cookie') || ''];
  return values.filter(Boolean).map(value => value.split(';', 1)[0]).join('; ');
}

async function json(response) {
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    const unauthorized = response.status === 400 || response.status === 401 || response.status === 403;
    throw new PeopleAuthError(body.message || body.error_description || 'People authentication failed', unauthorized ? 401 : 502);
  }
  return body;
}

export function createPeopleAuthenticator(config) {
  const baseURL = String(config.peopleApiBaseUrl || '').replace(/\/$/, '');
  const clientID = String(config.peopleClientId || 'linkup-im');
  const clientSecret = String(config.peopleClientSecret || '');
  const redirectURI = String(config.peopleRedirectUri || 'https://im.lxvb.top/oauth/callback');
  if (!baseURL || !clientSecret) throw new Error('People OAuth configuration is required');

  return async (username, password) => {
    try {
      const csrfResponse = await fetch(`${baseURL}/auth/csrf`, { headers: { accept: 'application/json' }, signal: AbortSignal.timeout(8000) });
      const csrf = await json(csrfResponse);
      const csrfToken = csrf.data?.token;
      let cookies = cookieHeader(csrfResponse);
      if (!csrfToken || !cookies) throw new PeopleAuthError('People CSRF handshake failed', 502);
      const loginResponse = await fetch(`${baseURL}/auth/login`, {
        method: 'POST',
        headers: { 'content-type': 'application/json', cookie: cookies, 'x-xsrf-token': csrfToken },
        body: JSON.stringify({ username, password }),
        signal: AbortSignal.timeout(10000),
      });
      await json(loginResponse);
      const sessionCookie = cookieHeader(loginResponse);
      if (!sessionCookie) throw new PeopleAuthError('People session was not created', 502);
      cookies = `${cookies}; ${sessionCookie}`;
      const state = crypto.randomBytes(18).toString('base64url');
      const authorizeResponse = await fetch(`${baseURL}/oauth/authorize`, {
        method: 'POST',
        headers: { 'content-type': 'application/json', cookie: cookies, 'x-xsrf-token': csrfToken },
        body: JSON.stringify({ clientId: clientID, redirectUri: redirectURI, state }),
        signal: AbortSignal.timeout(10000),
      });
      const authorize = await json(authorizeResponse);
      const redirect = new URL(authorize.data?.redirectUrl || '');
      if (redirect.searchParams.get('state') !== state || !redirect.searchParams.get('code')) {
        throw new PeopleAuthError('People authorization response is invalid', 502);
      }
      const tokenResponse = await fetch(`${baseURL}/oauth/token`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          grant_type: 'authorization_code', client_id: clientID, client_secret: clientSecret,
          code: redirect.searchParams.get('code'), redirect_uri: redirectURI,
        }),
        signal: AbortSignal.timeout(10000),
      });
      const token = await json(tokenResponse);
      if (!token.user?.username || !token.user?.displayName) throw new PeopleAuthError('People identity is missing', 502);
      return {
        id: token.user.id,
        username: token.user.username,
        displayName: token.user.displayName,
        role: token.user.role,
      };
    } catch (error) {
      if (error instanceof PeopleAuthError) throw error;
      throw new PeopleAuthError('People service unavailable', 502);
    }
  };
}
