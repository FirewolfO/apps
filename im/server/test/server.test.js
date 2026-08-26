import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { WebSocket } from 'ws';
import { createApplication } from '../src/app.js';
import { RealtimeHub } from '../src/realtime.js';

function onceMessage(socket, expectedEvent) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error(`Timed out waiting for ${expectedEvent}`)), 3000);
    const listener = bytes => {
      const value = JSON.parse(bytes.toString());
      if (value.event !== expectedEvent) return;
      clearTimeout(timeout);
      socket.off('message', listener);
      resolve(value.data);
    };
    socket.on('message', listener);
  });
}

test('People identities can exchange messages in realtime', async t => {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'linkup-im-test-'));
  const people = new Map([
    ['alice', { password: 'AlicePass123', id: 'people-alice', username: 'alice', displayName: '飞奔的小蜗牛', role: 'employee' }],
    ['bob', { password: 'BobPass123', id: 'people-bob', username: 'bob', displayName: 'Bob', role: 'employee' }],
  ]);
  const config = {
    databasePath: ':memory:',
    uploadDir: path.join(tempDir, 'uploads'),
    publicUrl: '',
    jwtSecret: 'test-secret-that-is-long-enough-for-tests',
    maxUploadMb: 2,
    authenticatePeople: async (username, password) => {
      const identity = people.get(username);
      if (!identity || identity.password !== password) throw Object.assign(new Error('invalid credentials'), { status: 401 });
      return identity;
    }
  };
  const { app, db } = createApplication(config);
  const server = http.createServer(app);
  const realtime = new RealtimeHub(server, db, config);
  app.locals.realtime = realtime;
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  const base = `http://127.0.0.1:${address.port}`;

  t.after(async () => {
    realtime.close();
    await new Promise(resolve => server.close(resolve));
    db.close();
    fs.rmSync(tempDir, { recursive: true, force: true });
  });

  const request = async (route, { token, method = 'GET', body } = {}) => {
    const response = await fetch(`${base}/api${route}`, {
      method,
      headers: {
        ...(token ? { authorization: `Bearer ${token}` } : {}),
        ...(body ? { 'content-type': 'application/json' } : {})
      },
      body: body ? JSON.stringify(body) : undefined
    });
    return { status: response.status, body: await response.json() };
  };

  assert.equal((await fetch(`${base}/admin/`)).status, 404);
  assert.equal((await fetch(`${base}/api/apps`)).status, 404);
  const oldApk = path.join(config.uploadDir, 'linkup-v1.5.0.apk');
  fs.writeFileSync(oldApk, Buffer.from([1]));
  assert.equal((await fetch(`${base}/uploads/linkup-v1.5.0.apk`)).status, 404);

  const aliceLogin = await request('/auth/login', {
    method: 'POST', body: { username: 'alice', password: 'AlicePass123' }
  });
  const bobLogin = await request('/auth/login', {
    method: 'POST', body: { username: 'bob', password: 'BobPass123' }
  });
  assert.equal(aliceLogin.status, 200);
  assert.equal(bobLogin.status, 200);
  const aliceCreated = { body: { user: aliceLogin.body.user } };
  const bobCreated = { body: { user: bobLogin.body.user } };

  const search = await request('/users?q=bob', { token: aliceLogin.body.token });
  assert.equal(search.body.users[0].username, 'bob');
  const conversation = await request('/conversations/direct', {
    token: aliceLogin.body.token,
    method: 'POST', body: { userId: bobCreated.body.user.id }
  });
  assert.equal(conversation.status, 201);

  const bobSocket = new WebSocket(`ws://127.0.0.1:${address.port}/ws?token=${encodeURIComponent(bobLogin.body.token)}`);
  await onceMessage(bobSocket, 'ready');
  const incoming = onceMessage(bobSocket, 'message:new');
  const sent = await request(`/conversations/${conversation.body.conversation.id}/messages`, {
    token: aliceLogin.body.token,
    method: 'POST',
    body: { clientId: 'test-message-1', type: 'text', content: '你好，Bob' }
  });
  assert.equal(sent.status, 201);
  assert.equal(sent.body.message.readByPeer, false);
  assert.equal((await incoming).content, '你好，Bob');

  const history = await request(`/conversations/${conversation.body.conversation.id}/messages`, {
    token: bobLogin.body.token
  });
  assert.equal(history.body.messages.length, 1);
  assert.equal(history.body.messages[0].senderName, '飞奔的小蜗牛');
  assert.equal(history.body.messages[0].readByPeer, false);

  const hidden = await request(`/conversations/${conversation.body.conversation.id}`, {
    token: bobLogin.body.token, method: 'DELETE'
  });
  assert.equal(hidden.status, 200);
  const hiddenList = await request('/conversations', { token: bobLogin.body.token });
  assert.equal(hiddenList.body.conversations.length, 0);

  const aliceSocket = new WebSocket(`ws://127.0.0.1:${address.port}/ws?token=${encodeURIComponent(aliceLogin.body.token)}`);
  await onceMessage(aliceSocket, 'ready');
  const receipt = onceMessage(aliceSocket, 'read');
  const markedRead = await request(`/conversations/${conversation.body.conversation.id}/read`, {
    token: bobLogin.body.token,
    method: 'POST'
  });
  assert.equal(markedRead.status, 200);
  assert.equal((await receipt).userId, bobCreated.body.user.id);
  const readHistory = await request(`/conversations/${conversation.body.conversation.id}/messages`, {
    token: aliceLogin.body.token
  });
  assert.equal(readHistory.body.messages[0].readByPeer, true);

  const preparing = onceMessage(bobSocket, 'media:prepare');
  aliceSocket.send(JSON.stringify({
    event: 'media:prepare',
    data: { conversationId: conversation.body.conversation.id, clientId: 'media-pending-1', type: 'image', mimeType: 'image/png' }
  }));
  assert.equal((await preparing).clientId, 'media-pending-1');

  const websocketIncoming = onceMessage(bobSocket, 'message:new');
  const websocketAck = onceMessage(aliceSocket, 'message:ack');
  aliceSocket.send(JSON.stringify({
    event: 'message:send',
    data: { conversationId: conversation.body.conversation.id, clientId: 'ws-message-1', type: 'text', content: 'WebSocket message' }
  }));
  assert.equal((await websocketIncoming).content, 'WebSocket message');
  assert.equal((await websocketAck).clientId, 'ws-message-1');
  const restoredList = await request('/conversations', { token: bobLogin.body.token });
  assert.equal(restoredList.body.conversations.length, 1);

  const clearMediaPath = path.join(config.uploadDir, 'clear-test.png');
  fs.writeFileSync(clearMediaPath, Buffer.from([137, 80, 78, 71]));
  db.prepare(`INSERT INTO messages
    (id, conversation_id, sender_id, client_id, type, content, media_url, mime_type, duration_ms, created_at)
    VALUES (?, ?, ?, ?, 'image', '', '/uploads/clear-test.png', 'image/png', 0, ?)`).run(
      'clear-media-message', conversation.body.conversation.id, aliceCreated.body.user.id,
      'clear-media-client', Date.now());
  const uncachedMedia = await fetch(`${base}/uploads/clear-test.png`);
  assert.equal(uncachedMedia.status, 200);
  assert.equal(uncachedMedia.headers.get('cache-control'), 'private, no-store');

  const clearedForBob = onceMessage(bobSocket, 'conversation:cleared');
  const cleared = await request(`/conversations/${conversation.body.conversation.id}/messages`, {
    token: aliceLogin.body.token, method: 'DELETE'
  });
  assert.equal(cleared.status, 200);
  assert.equal(cleared.body.deletedMessages, 3);
  assert.equal((await clearedForBob).userId, aliceCreated.body.user.id);
  assert.equal(fs.existsSync(clearMediaPath), false);
  const removedMedia = await fetch(`${base}/uploads/clear-test.png`);
  assert.equal(removedMedia.status, 404);
  const emptyHistory = await request(`/conversations/${conversation.body.conversation.id}/messages`, {
    token: bobLogin.body.token
  });
  assert.equal(emptyHistory.body.messages.length, 0);

  const ringing = onceMessage(bobSocket, 'call:start');
  aliceSocket.send(JSON.stringify({
    event: 'call:start', data: { conversationId: conversation.body.conversation.id, type: 'video' }
  }));
  assert.equal((await ringing).type, 'video');
  aliceSocket.close();
  bobSocket.close();

  const rejected = await request('/auth/login', {
    method: 'POST', body: { username: 'bob', password: 'wrong-password' }
  });
  assert.equal(rejected.status, 401);
});

test('People profile is synchronized when an existing user logs in', async t => {
  let displayName = 'Original Name';
  const config = {
    databasePath: ':memory:', uploadDir: fs.mkdtempSync(path.join(os.tmpdir(), 'linkup-disable-')),
    publicUrl: '', jwtSecret: 'another-test-secret', maxUploadMb: 1,
    authenticatePeople: async (username, password) => {
      if (username !== 'employee' || password !== 'StrongPass123') throw Object.assign(new Error('invalid'), { status: 401 });
      return { id: 'people-employee', username, displayName, role: 'employee' };
    }
  };
  const { app, db } = createApplication(config);
  const server = http.createServer(app);
  const realtime = new RealtimeHub(server, db, config);
  app.locals.realtime = realtime;
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  t.after(async () => {
    realtime.close();
    await new Promise(resolve => server.close(resolve));
    db.close();
    fs.rmSync(config.uploadDir, { recursive: true, force: true });
  });
  const call = async (route, token, method = 'GET', body) => {
    const response = await fetch(`${base}/api${route}`, {
      method,
      headers: { ...(token ? { authorization: `Bearer ${token}` } : {}), ...(body ? { 'content-type': 'application/json' } : {}) },
      body: body ? JSON.stringify(body) : undefined
    });
    return { status: response.status, body: await response.json() };
  };
  const first = await call('/auth/login', '', 'POST', { username: 'employee', password: 'StrongPass123' });
  assert.equal(first.body.user.displayName, 'Original Name');
  displayName = 'Updated in People';
  const second = await call('/auth/login', '', 'POST', { username: 'employee', password: 'StrongPass123' });
  assert.equal(second.body.user.id, first.body.user.id);
  assert.equal(second.body.user.displayName, 'Updated in People');
});
