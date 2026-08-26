import crypto from 'node:crypto';

const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');

export function createToken(userId, secret, ttlSeconds = 60 * 60 * 24 * 30, tokenVersion = 0) {
  const header = encode({ alg: 'HS256', typ: 'JWT' });
  const payload = encode({ sub: userId, ver: tokenVersion, exp: Math.floor(Date.now() / 1000) + ttlSeconds });
  const data = `${header}.${payload}`;
  const signature = crypto.createHmac('sha256', secret).update(data).digest('base64url');
  return `${data}.${signature}`;
}

export function verifyToken(token, secret) {
  try {
    const parts = String(token || '').split('.');
    if (parts.length !== 3) return null;
    const data = `${parts[0]}.${parts[1]}`;
    const expected = crypto.createHmac('sha256', secret).update(data).digest();
    const actual = Buffer.from(parts[2], 'base64url');
    if (expected.length !== actual.length || !crypto.timingSafeEqual(expected, actual)) return null;
    const payload = JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf8'));
    if (!payload.sub || payload.exp <= Math.floor(Date.now() / 1000)) return null;
    return payload;
  } catch {
    return null;
  }
}

export function hashPassword(password, salt = crypto.randomBytes(16).toString('hex')) {
  const hash = crypto.scryptSync(password, salt, 64).toString('hex');
  return { salt, hash };
}

export function verifyPassword(password, salt, passwordHash) {
  const expected = Buffer.from(String(passwordHash || ''), 'hex');
  const actual = Buffer.from(hashPassword(password, salt).hash, 'hex');
  return expected.length === actual.length && crypto.timingSafeEqual(expected, actual);
}
