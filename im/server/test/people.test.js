import assert from 'node:assert/strict';
import http from 'node:http';
import test from 'node:test';
import { createPeopleAuthenticator } from '../src/people.js';

test('People OAuth credentials are exchanged for an IM identity', async t => {
  const server = http.createServer(async (request, response) => {
    if (request.url === '/api/open/people/auth/csrf') {
      response.setHeader('Content-Type', 'application/json');
      response.setHeader('Set-Cookie', 'PEOPLE_XSRF=csrf-token; Path=/; Secure');
      response.end(JSON.stringify({ data: { token: 'csrf-token' } }));
      return;
    }
    let body = '';
    for await (const chunk of request) body += chunk;
    const parsed = JSON.parse(body || '{}');
    if (request.url === '/api/open/people/auth/login') {
      assert.equal(request.headers.cookie, 'PEOPLE_XSRF=csrf-token');
      assert.equal(request.headers['x-xsrf-token'], 'csrf-token');
      assert.equal(parsed.username, 'alice');
      assert.equal(parsed.password, 'password');
      response.setHeader('Content-Type', 'application/json');
      response.setHeader('Set-Cookie', 'PEOPLE_SESSION=session-token; Path=/; Secure; HttpOnly');
      response.end(JSON.stringify({ data: { username: 'alice' } }));
      return;
    }
    if (request.url === '/api/open/people/oauth/authorize') {
      assert.equal(request.headers.cookie, 'PEOPLE_XSRF=csrf-token; PEOPLE_SESSION=session-token');
      assert.equal(request.headers['x-xsrf-token'], 'csrf-token');
      assert.equal(parsed.username, undefined);
      response.setHeader('Content-Type', 'application/json');
      response.end(JSON.stringify({ data: { redirectUrl: `https://im.lxvb.top/oauth/callback?code=code-1&state=${parsed.state}` } }));
      return;
    }
    if (request.url === '/api/open/people/oauth/token') {
      assert.equal(parsed.client_secret, 'people-client-secret-which-is-long-enough');
      response.setHeader('Content-Type', 'application/json');
      response.end(JSON.stringify({ user: { id: 'people-alice', username: 'alice', displayName: 'Alice', role: 'employee' } }));
      return;
    }
    response.writeHead(404).end();
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const authenticate = createPeopleAuthenticator({
    peopleApiBaseUrl: `http://127.0.0.1:${server.address().port}/api/open/people`,
    peopleClientId: 'linkup-im',
    peopleClientSecret: 'people-client-secret-which-is-long-enough',
    peopleRedirectUri: 'https://im.lxvb.top/oauth/callback',
  });
  assert.deepEqual(await authenticate('alice', 'password'), {
    id: 'people-alice', username: 'alice', displayName: 'Alice', role: 'employee',
  });
});
