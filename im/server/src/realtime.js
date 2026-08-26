import { WebSocketServer, WebSocket } from 'ws';
import { randomUUID } from 'node:crypto';
import { verifyToken } from './auth.js';

const MESSAGE_TYPES = new Set(['text', 'emoji', 'image', 'video']);

function messageView(row) {
  return {
    id: row.id,
    conversationId: row.conversation_id,
    senderId: row.sender_id,
    senderName: row.sender_name || '',
    clientId: row.client_id,
    type: row.type,
    content: row.content,
    mediaUrl: row.media_url,
    mimeType: row.mime_type,
    durationMs: row.duration_ms,
    readByPeer: Boolean(row.read_by_peer),
    createdAt: row.created_at
  };
}

export class RealtimeHub {
  constructor(server, db, config) {
    this.db = db;
    this.config = config;
    this.clients = new Map();
    this.wss = new WebSocketServer({ noServer: true });
    server.on('upgrade', (request, socket, head) => this.upgrade(request, socket, head));
    this.wss.on('connection', (ws, request, userId) => this.connected(ws, userId));
  }

  upgrade(request, socket, head) {
    try {
      const url = new URL(request.url, 'http://localhost');
      if (url.pathname !== '/ws') return socket.destroy();
      const payload = verifyToken(url.searchParams.get('token'), this.config.jwtSecret);
      const user = payload ? this.db.prepare('SELECT id, token_version FROM users WHERE id = ? AND active = 1').get(payload.sub) : null;
      if (!user || Number(payload.ver || 0) !== user.token_version) {
        socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
        return socket.destroy();
      }
      this.wss.handleUpgrade(request, socket, head, ws => {
        this.wss.emit('connection', ws, request, payload.sub);
      });
    } catch {
      socket.destroy();
    }
  }

  connected(ws, userId) {
    if (!this.clients.has(userId)) this.clients.set(userId, new Set());
    this.clients.get(userId).add(ws);
    ws.send(JSON.stringify({ event: 'ready', data: { userId } }));
    ws.on('message', bytes => this.receive(ws, userId, bytes));
    ws.on('close', () => {
      const sockets = this.clients.get(userId);
      sockets?.delete(ws);
      if (!sockets?.size) this.clients.delete(userId);
    });
  }

  receive(ws, userId, bytes) {
    let envelope;
    try { envelope = JSON.parse(bytes.toString()); } catch { return; }
    const { event, data = {} } = envelope;
    if (!['typing', 'read', 'media:prepare', 'media:cancel', 'message:send',
      'signal', 'call:start', 'call:ringing', 'call:accept', 'call:end'].includes(event)) return;
    if (!data.conversationId || !this.isMember(data.conversationId, userId)) return;

    if (event === 'media:prepare' || event === 'media:cancel') {
      const clientId = String(data.clientId || '');
      const type = data.type === 'video' ? 'video' : 'image';
      if (!clientId || clientId.length > 100) return;
      const sender = this.db.prepare('SELECT display_name FROM users WHERE id = ?').get(userId);
      this.broadcastConversation(data.conversationId, userId, event, {
        conversationId: data.conversationId,
        senderId: userId,
        senderName: sender?.display_name || '',
        clientId,
        type,
        mimeType: String(data.mimeType || ''),
        createdAt: Date.now()
      });
      return;
    }
    if (event === 'message:send') {
      this.receiveMessage(ws, userId, data);
      return;
    }

    if (event === 'typing') {
      this.broadcastConversation(data.conversationId, userId, event, {
        conversationId: data.conversationId,
        userId,
        active: Boolean(data.active)
      });
      return;
    }
    if (event === 'read') {
      const readAt = Date.now();
      this.db.prepare('UPDATE conversation_members SET last_read_at = ? WHERE conversation_id = ? AND user_id = ?')
        .run(readAt, data.conversationId, userId);
      this.broadcastConversation(data.conversationId, userId, event, {
        conversationId: data.conversationId,
        userId,
        readAt
      });
      return;
    }
    if (event === 'signal') {
      this.sendToConversation(data.conversationId, userId, event, {
        conversationId: data.conversationId,
        fromUserId: userId,
        callId: String(data.callId || ''),
        signal: data.signal
      });
      return;
    }

    let callId = String(data.callId || '');
    if (event === 'call:start') {
      if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(callId)) {
        callId = randomUUID();
      }
      const type = data.type === 'video' ? 'video' : 'audio';
      const caller = this.db.prepare('SELECT username, display_name FROM users WHERE id = ?').get(userId);
      this.db.prepare(`INSERT INTO calls
        (id, conversation_id, initiator_id, type, status, started_at)
        VALUES (?, ?, ?, ?, 'ringing', ?)`)
        .run(callId, data.conversationId, userId, type, Date.now());
      this.sendToConversation(data.conversationId, userId, event, {
        conversationId: data.conversationId, fromUserId: userId,
        fromUsername: caller?.username || '', fromUserName: caller?.display_name || '',
        callId, type
      });
      ws.send(JSON.stringify({ event: 'call:created', data: { callId, conversationId: data.conversationId, type } }));
      return;
    }
    const call = this.db.prepare('SELECT * FROM calls WHERE id = ? AND conversation_id = ?').get(callId, data.conversationId);
    if (!call) return;
    const status = event === 'call:accept' ? 'active' : event === 'call:end' ? 'ended' : 'ringing';
    this.db.prepare('UPDATE calls SET status = ?, ended_at = ? WHERE id = ?')
      .run(status, event === 'call:end' ? Date.now() : null, callId);
    this.broadcastConversation(data.conversationId, null, event, {
      conversationId: data.conversationId,
      fromUserId: userId,
      callId,
      type: call.type,
      reason: String(data.reason || '')
    });
  }

  receiveMessage(ws, userId, data) {
    const conversationId = String(data.conversationId || '');
    const clientId = String(data.clientId || '');
    const type = String(data.type || '');
    const content = String(data.content || '').trim();
    const mediaUrl = String(data.mediaUrl || '');
    const mimeType = String(data.mimeType || '');
    const durationMs = Math.max(0, Number(data.durationMs || 0));
    const fail = error => ws.send(JSON.stringify({
      event: 'message:error', data: { conversationId, clientId, error }
    }));
    if (!clientId || clientId.length > 100) return fail('消息标识无效');
    if (!MESSAGE_TYPES.has(type)) return fail('不支持的消息类型');
    if ((type === 'text' || type === 'emoji') && (!content || content.length > 4000)) {
      return fail('消息内容不能为空且不能超过 4000 字');
    }
    if ((type === 'image' || type === 'video') && !mediaUrl.startsWith('/uploads/')) {
      return fail('请先上传媒体文件');
    }

    const selectMessage = `SELECT m.*, u.display_name AS sender_name,
        EXISTS(
          SELECT 1 FROM conversation_members reader
          WHERE reader.conversation_id = m.conversation_id
            AND reader.user_id <> m.sender_id
            AND reader.last_read_at >= m.created_at
        ) AS read_by_peer
      FROM messages m JOIN users u ON u.id = m.sender_id
      WHERE m.sender_id = ? AND m.client_id = ?`;
    try {
      let row = this.db.prepare(selectMessage).get(userId, clientId);
      let created = false;
      if (!row) {
        const id = randomUUID();
        const createdAt = Date.now();
        this.db.prepare('UPDATE conversation_members SET hidden_at = 0 WHERE conversation_id = ? AND user_id <> ?')
          .run(conversationId, userId);
        this.db.prepare(`INSERT INTO messages
          (id, conversation_id, sender_id, client_id, type, content, media_url, mime_type, duration_ms, created_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`)
          .run(id, conversationId, userId, clientId, type, content, mediaUrl, mimeType, durationMs, createdAt);
        row = this.db.prepare(selectMessage).get(userId, clientId);
        created = true;
      }
      const message = messageView(row);
      if (created) this.broadcastConversation(conversationId, userId, 'message:new', message);
      ws.send(JSON.stringify({ event: 'message:ack', data: message }));
    } catch (error) {
      console.error(error);
      fail('消息发送失败');
    }
  }

  isMember(conversationId, userId) {
    return Boolean(this.db.prepare('SELECT 1 FROM conversation_members WHERE conversation_id = ? AND user_id = ?')
      .get(conversationId, userId));
  }

  memberIds(conversationId) {
    return this.db.prepare('SELECT user_id FROM conversation_members WHERE conversation_id = ?')
      .all(conversationId).map(row => row.user_id);
  }

  send(userId, event, data) {
    const payload = JSON.stringify({ event, data });
    for (const socket of this.clients.get(userId) || []) {
      if (socket.readyState === WebSocket.OPEN) socket.send(payload);
    }
  }

  disconnectUser(userId) {
    for (const socket of this.clients.get(userId) || []) socket.close(4001, 'session revoked');
    this.clients.delete(userId);
  }

  broadcastConversation(conversationId, exceptUserId, event, data) {
    for (const memberId of this.memberIds(conversationId)) {
      if (memberId !== exceptUserId) this.send(memberId, event, data);
    }
  }

  sendToConversation(conversationId, fromUserId, event, data) {
    this.broadcastConversation(conversationId, fromUserId, event, data);
  }

  close() {
    for (const socket of this.wss.clients) socket.close();
    this.wss.close();
  }
}
