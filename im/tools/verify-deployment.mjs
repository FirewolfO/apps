import WebSocket from '../server/node_modules/ws/wrapper.mjs';

const required = ['BASE_URL', 'ADMIN_USER', 'ADMIN_PASS', 'TEST_USER_A', 'TEST_PASS_A', 'TEST_USER_B', 'TEST_PASS_B'];
for (const name of required) {
  if (!process.env[name]) throw new Error(`Missing ${name}`);
}

const baseUrl = process.env.BASE_URL.replace(/\/$/, '');

async function json(path, options = {}, token = '') {
  const headers = new Headers(options.headers || {});
  if (token) headers.set('Authorization', `Bearer ${token}`);
  if (options.body && typeof options.body === 'string') headers.set('Content-Type', 'application/json');
  const response = await fetch(baseUrl + path, { ...options, headers });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(`${options.method || 'GET'} ${path}: ${response.status} ${body.error || ''}`);
  return body;
}

async function login(username, password) {
  return json('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password })
  });
}

async function ensureUser(adminToken, username, password, displayName) {
  const users = (await json(`/api/admin/users?q=${encodeURIComponent(username)}`, {}, adminToken)).users;
  let user = users.find(item => item.username.toLowerCase() === username.toLowerCase());
  if (!user) {
    user = (await json('/api/admin/users', {
      method: 'POST',
      body: JSON.stringify({ username, password, displayName, isAdmin: false })
    }, adminToken)).user;
  } else {
    await json(`/api/admin/users/${user.id}/password`, {
      method: 'POST',
      body: JSON.stringify({ password })
    }, adminToken);
    if (!user.active || user.displayName !== displayName) {
      user = (await json(`/api/admin/users/${user.id}`, {
        method: 'PATCH',
        body: JSON.stringify({ active: true, displayName })
      }, adminToken)).user;
    }
  }
  return user;
}

function openSocket(token) {
  const url = baseUrl.replace(/^http/, 'ws') + `/ws?token=${encodeURIComponent(token)}`;
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(url);
    const timer = setTimeout(() => reject(new Error('WebSocket connection timed out')), 15_000);
    socket.once('open', () => {
      clearTimeout(timer);
      resolve(socket);
    });
    socket.once('error', reject);
  });
}

function waitForMessage(socket, clientId) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Realtime message timed out')), 15_000);
    const listener = raw => {
      const envelope = JSON.parse(raw.toString());
      if (envelope.event !== 'message:new' || envelope.data?.clientId !== clientId) return;
      clearTimeout(timer);
      socket.off('message', listener);
      resolve(envelope.data);
    };
    socket.on('message', listener);
  });
}

function waitForEvent(socket, event, predicate = () => true) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`${event} timed out`)), 15_000);
    const listener = raw => {
      const envelope = JSON.parse(raw.toString());
      if (envelope.event !== event || !predicate(envelope.data || {})) return;
      clearTimeout(timer);
      socket.off('message', listener);
      resolve(envelope.data || {});
    };
    socket.on('message', listener);
  });
}

const admin = await login(process.env.ADMIN_USER, process.env.ADMIN_PASS);
const userA = await ensureUser(admin.token, process.env.TEST_USER_A, process.env.TEST_PASS_A, process.env.TEST_NAME_A || '演示用户 A');
const userB = await ensureUser(admin.token, process.env.TEST_USER_B, process.env.TEST_PASS_B, process.env.TEST_NAME_B || '演示用户 B');
for (const username of (process.env.DISABLE_USERS || '').split(',').map(value => value.trim()).filter(Boolean)) {
  const users = (await json(`/api/admin/users?q=${encodeURIComponent(username)}`, {}, admin.token)).users;
  const user = users.find(item => item.username.toLowerCase() === username.toLowerCase());
  if (user?.active && !user.isAdmin) {
    await json(`/api/admin/users/${user.id}`, {
      method: 'PATCH',
      body: JSON.stringify({ active: false })
    }, admin.token);
  }
}
const sessionA = await login(process.env.TEST_USER_A, process.env.TEST_PASS_A);
const sessionB = await login(process.env.TEST_USER_B, process.env.TEST_PASS_B);
const socketA = await openSocket(sessionA.token);
const socketB = await openSocket(sessionB.token);

try {
  const conversation = (await json('/api/conversations/direct', {
    method: 'POST',
    body: JSON.stringify({ userId: userB.id })
  }, sessionA.token)).conversation;

  const clientId = `verify-${Date.now()}`;
  let deliveredAt = 0;
  let acknowledgedAt = 0;
  const realtime = waitForMessage(socketB, clientId).then(message => {
    deliveredAt = performance.now();
    return message;
  });
  const acknowledgement = waitForEvent(socketA, 'message:ack', data => data.clientId === clientId).then(message => {
    acknowledgedAt = performance.now();
    return message;
  });
  const sendStarted = performance.now();
  socketA.send(JSON.stringify({
    event: 'message:send',
    data: { conversationId: conversation.id, type: 'text', content: '部署验收消息', clientId }
  }));
  const [receivedMessage, sentMessage] = await Promise.all([realtime, acknowledgement]);
  const realtimeLatencyMs = Math.round(deliveredAt - sendStarted);
  const acknowledgementLatencyMs = Math.round(acknowledgedAt - sendStarted);
  if (receivedMessage.content !== '部署验收消息' || sentMessage.readByPeer !== false) {
    throw new Error('WebSocket message or acknowledgement is invalid');
  }
  const readReceipt = waitForEvent(socketA, 'read', data => data.userId === userB.id);
  await json(`/api/conversations/${conversation.id}/read`, { method: 'POST' }, sessionB.token);
  await readReceipt;
  const readHistory = await json(`/api/conversations/${conversation.id}/messages?limit=100`, {}, sessionA.token);
  if (!readHistory.messages.find(message => message.clientId === clientId)?.readByPeer) {
    throw new Error('Read receipt is missing from message history');
  }

  const emojiClientId = `${clientId}-emoji`;
  const emojiAck = waitForEvent(socketA, 'message:ack', data => data.clientId === emojiClientId);
  socketA.send(JSON.stringify({
    event: 'message:send',
    data: { conversationId: conversation.id, type: 'emoji', content: '🙂', clientId: emojiClientId }
  }));
  await emojiAck;

  const mediaClientId = `${clientId}-image`;
  const mediaPreparing = waitForEvent(socketB, 'media:prepare', data => data.clientId === mediaClientId);
  socketA.send(JSON.stringify({
    event: 'media:prepare',
    data: { conversationId: conversation.id, clientId: mediaClientId, type: 'image', mimeType: 'image/png' }
  }));
  const prepared = await mediaPreparing;
  if (prepared.type !== 'image') throw new Error('Media placeholder is invalid');
  const form = new FormData();
  form.append('file', new Blob([new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10])], { type: 'image/png' }), 'verify.png');
  const upload = await json('/api/uploads', { method: 'POST', body: form }, sessionA.token);
  const mediaIncoming = waitForMessage(socketB, mediaClientId);
  const mediaAck = waitForEvent(socketA, 'message:ack', data => data.clientId === mediaClientId);
  socketA.send(JSON.stringify({
    event: 'message:send',
    data: {
      conversationId: conversation.id, type: 'image', content: '', mediaUrl: upload.url,
      mimeType: upload.mimeType, clientId: mediaClientId
    }
  }));
  await Promise.all([mediaIncoming, mediaAck]);

  const history = await json(`/api/conversations/${conversation.id}/messages?limit=100`, {}, sessionB.token);
  if (!history.messages.some(message => message.clientId === clientId)) throw new Error('Message missing from history');
  const media = await fetch(baseUrl + upload.url);
  if (!media.ok) throw new Error(`Media download failed: ${media.status}`);
  if (media.headers.get('cache-control') !== 'private, no-store') {
    throw new Error(`Media cache policy is unsafe: ${media.headers.get('cache-control')}`);
  }
  const rtc = await json('/api/rtc/config', {}, sessionA.token);
  if (!Array.isArray(rtc.iceServers) || rtc.iceServers.length === 0) throw new Error('RTC configuration is empty');

  const incomingCall = waitForEvent(socketB, 'call:start', data => data.type === 'video');
  const createdCall = waitForEvent(socketA, 'call:created', data => data.type === 'video');
  socketA.send(JSON.stringify({ event: 'call:start', data: { conversationId: conversation.id, type: 'video' } }));
  const [{ callId }, incoming] = await Promise.all([createdCall, incomingCall]);
  if (incoming.callId !== callId) throw new Error('Call IDs do not match');
  const acceptedCall = waitForEvent(socketA, 'call:accept', data => data.callId === callId);
  socketB.send(JSON.stringify({ event: 'call:accept', data: { conversationId: conversation.id, callId } }));
  await acceptedCall;
  const signal = waitForEvent(socketB, 'signal', data => data.callId === callId);
  socketA.send(JSON.stringify({
    event: 'signal',
    data: { conversationId: conversation.id, callId, signal: { type: 'candidate', candidate: 'verification' } }
  }));
  await signal;
  const endedCall = waitForEvent(socketB, 'call:end', data => data.callId === callId);
  socketA.send(JSON.stringify({ event: 'call:end', data: { conversationId: conversation.id, callId, reason: 'verified' } }));
  await endedCall;

  const clearedForPeer = waitForEvent(socketB, 'conversation:cleared', data => data.conversationId === conversation.id);
  const cleared = await json(`/api/conversations/${conversation.id}/messages`, {
    method: 'DELETE'
  }, sessionA.token);
  await clearedForPeer;
  const emptyHistory = await json(`/api/conversations/${conversation.id}/messages?limit=100`, {}, sessionB.token);
  if (emptyHistory.messages.length !== 0) throw new Error('Conversation was not cleared');
  const removedMedia = await fetch(baseUrl + upload.url);
  if (removedMedia.status !== 404) throw new Error(`Cleared media still exists: ${removedMedia.status}`);

  console.log(JSON.stringify({
    ok: true,
    users: [userA.username, userB.username],
    conversationId: conversation.id,
    messageCount: history.messages.length,
    mediaStatus: media.status,
    websocket: 'received',
    realtimeLatencyMs,
    acknowledgementLatencyMs,
    mediaPlaceholder: 'received then replaced',
    clearConversation: `${cleared.deletedMessages} messages removed for both peers`,
    clearedMediaStatus: removedMedia.status,
    readReceipt: 'received',
    callSignaling: 'video accepted, signaled, ended',
    iceServers: rtc.iceServers.length
  }));
} finally {
  socketA.close(1000, 'verification complete');
  socketB.close(1000, 'verification complete');
}
