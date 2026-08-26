import fs from 'node:fs';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import express from 'express';
import cors from 'cors';
import multer from 'multer';
import { openDatabase } from './database.js';
import { createToken, verifyToken } from './auth.js';
import { createPeopleAuthenticator } from './people.js';

const MESSAGE_TYPES = new Set(['text', 'emoji', 'image', 'video']);

function publicUser(row) {
  if (!row) return null;
  return {
    id: row.id,
    username: row.username,
    displayName: row.display_name,
    avatarUrl: row.avatar_url,
    isAdmin: Boolean(row.is_admin),
    active: Boolean(row.active),
    createdAt: row.created_at
  };
}

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

function requestOrigin(req, config) {
  return config.publicUrl || `${req.protocol}://${req.get('host')}`;
}

export function createApplication(config) {
  const db = openDatabase(config.databasePath);
  const loginAttempts = new Map();
  const authenticatePeople = config.authenticatePeople || createPeopleAuthenticator(config);
  fs.mkdirSync(config.uploadDir, { recursive: true });

  const app = express();
  app.disable('x-powered-by');
  app.use(cors({
    origin(origin, callback) {
      if (!origin || config.corsOrigins?.includes('*') || config.corsOrigins?.includes(origin)) return callback(null, true);
      callback(new Error('Origin is not allowed'));
    }
  }));
  app.use(express.json({ limit: '1mb' }));
  app.use('/uploads', (req, res, next) => {
    if (path.extname(req.path).toLowerCase() === '.apk') return res.status(404).json({ error: '文件不存在' });
    next();
  });
  app.use('/uploads', express.static(config.uploadDir, {
    cacheControl: false,
    fallthrough: false,
    setHeaders(res, filename) {
      res.setHeader('Cache-Control', 'private, no-store');
    }
  }));

  const storage = multer.diskStorage({
    destination: (_req, _file, callback) => callback(null, config.uploadDir),
    filename: (_req, file, callback) => {
      const extensions = {
        'image/jpeg': '.jpg', 'image/png': '.png', 'image/webp': '.webp', 'image/gif': '.gif',
        'video/mp4': '.mp4', 'video/webm': '.webm', 'video/quicktime': '.mov'
      };
      callback(null, `${randomUUID()}${extensions[file.mimetype] || ''}`);
    }
  });
  const upload = multer({
    storage,
    limits: { fileSize: config.maxUploadMb * 1024 * 1024, files: 1 },
    fileFilter: (_req, file, callback) => {
      callback(null, file.mimetype.startsWith('image/') || file.mimetype.startsWith('video/'));
    }
  });

  const authenticate = (req, res, next) => {
    const raw = req.get('authorization') || '';
    const token = raw.startsWith('Bearer ') ? raw.slice(7) : '';
    const payload = verifyToken(token, config.jwtSecret);
    const user = payload ? db.prepare('SELECT * FROM users WHERE id = ? AND active = 1').get(payload.sub) : null;
    if (!user || Number(payload.ver || 0) !== user.token_version) {
      return res.status(401).json({ error: '登录已失效，请重新登录' });
    }
    req.user = user;
    next();
  };

  const requireMember = (req, res, next) => {
    const member = db.prepare('SELECT 1 FROM conversation_members WHERE conversation_id = ? AND user_id = ?')
      .get(req.params.id, req.user.id);
    if (!member) return res.status(404).json({ error: '会话不存在' });
    next();
  };

  app.get('/api/health', (_req, res) => res.json({ ok: true, service: 'yuque-im', time: Date.now() }));

  app.post('/api/auth/login', async (req, res) => {
    const username = String(req.body?.username || '').trim();
    const password = String(req.body?.password || '');
    const attemptKey = `${req.ip}:${username.toLowerCase()}`;
    const now = Date.now();
    let attempt = loginAttempts.get(attemptKey);
    if (attempt && attempt.resetAt <= now) {
      loginAttempts.delete(attemptKey);
      attempt = null;
    }
    if (attempt?.count >= 8) return res.status(429).json({ error: '尝试次数过多，请稍后再试' });
    let identity;
    try {
      identity = await authenticatePeople(username, password);
    } catch (error) {
      loginAttempts.set(attemptKey, { count: (attempt?.count || 0) + 1, resetAt: now + 10 * 60 * 1000 });
      return res.status(error.status || 401).json({ error: error.status === 502 ? 'People 登录服务暂不可用' : 'People 账号或密码错误' });
    }
    loginAttempts.delete(attemptKey);
    const existing = db.prepare('SELECT * FROM users WHERE username = ? COLLATE NOCASE').get(identity.username);
    const isAdmin = identity.role === 'admin' ? 1 : 0;
    if (existing) {
      db.prepare('UPDATE users SET display_name = ?, is_admin = ?, active = 1 WHERE id = ?')
        .run(identity.displayName, isAdmin, existing.id);
    } else {
      db.prepare(`INSERT INTO users
        (id, username, password_hash, password_salt, display_name, is_admin, active, created_at)
        VALUES (?, ?, '', '', ?, ?, 1, ?)`).run(randomUUID(), identity.username, identity.displayName, isAdmin, now);
    }
    const user = db.prepare('SELECT * FROM users WHERE username = ? COLLATE NOCASE').get(identity.username);
    const token = createToken(user.id, config.jwtSecret, 60 * 60 * 24 * 30, user.token_version);
    res.json({ token, user: publicUser(user) });
  });

  app.get('/api/auth/me', authenticate, (req, res) => res.json({ user: publicUser(req.user) }));

  app.get('/api/rtc/config', authenticate, (_req, res) => {
    const iceServers = [{ urls: config.stunUrls || ['stun:stun.l.google.com:19302'] }];
    if (config.turnUrls?.length) {
      iceServers.push({
        urls: config.turnUrls,
        username: config.turnUsername,
        credential: config.turnCredential
      });
    }
    res.json({ iceServers });
  });

  app.get('/api/users', authenticate, (req, res) => {
    const queryText = String(req.query.q || '').trim();
    if (!queryText) return res.json({ users: [] });
    const query = `%${queryText}%`;
    const rows = db.prepare(`SELECT * FROM users
      WHERE active = 1 AND id <> ?
        AND (username LIKE ? COLLATE NOCASE OR display_name LIKE ? COLLATE NOCASE)
      ORDER BY display_name LIMIT 30`).all(req.user.id, query, query);
    res.json({ users: rows.map(publicUser) });
  });

  app.get('/api/conversations', authenticate, (req, res) => {
    const rows = db.prepare(`
      SELECT c.id, c.kind, c.created_at,
        u.id AS other_id, u.username AS other_username, u.display_name AS other_name,
        u.avatar_url AS other_avatar,
        m.id AS message_id, m.type AS message_type, m.content AS message_content,
        m.media_url AS message_media_url, m.created_at AS message_created_at,
        (SELECT COUNT(*) FROM messages unread
          WHERE unread.conversation_id = c.id AND unread.sender_id <> ?
            AND unread.created_at > mine.last_read_at) AS unread_count
      FROM conversations c
      JOIN conversation_members mine ON mine.conversation_id = c.id AND mine.user_id = ?
      LEFT JOIN conversation_members other_member ON other_member.conversation_id = c.id AND other_member.user_id <> ?
      LEFT JOIN users u ON u.id = other_member.user_id
      LEFT JOIN messages m ON m.id = (
        SELECT id FROM messages WHERE conversation_id = c.id ORDER BY created_at DESC LIMIT 1
      )
      WHERE mine.hidden_at = 0
      ORDER BY COALESCE(m.created_at, c.created_at) DESC
    `).all(req.user.id, req.user.id, req.user.id);
    res.json({ conversations: rows.map(row => ({
      id: row.id,
      kind: row.kind,
      createdAt: row.created_at,
      unreadCount: row.unread_count,
      peer: row.other_id ? {
        id: row.other_id, username: row.other_username,
        displayName: row.other_name, avatarUrl: row.other_avatar
      } : null,
      lastMessage: row.message_id ? {
        id: row.message_id, type: row.message_type, content: row.message_content,
        mediaUrl: row.message_media_url, createdAt: row.message_created_at
      } : null
    })) });
  });

  app.post('/api/conversations/direct', authenticate, (req, res) => {
    const peerId = String(req.body?.userId || '');
    if (!peerId || peerId === req.user.id) return res.status(400).json({ error: '请选择其他账号' });
    const peer = db.prepare('SELECT * FROM users WHERE id = ? AND active = 1').get(peerId);
    if (!peer) return res.status(404).json({ error: '联系人不存在或已停用' });
    const existing = db.prepare(`SELECT a.conversation_id AS id
      FROM conversation_members a
      JOIN conversation_members b ON a.conversation_id = b.conversation_id
      JOIN conversations c ON c.id = a.conversation_id AND c.kind = 'direct'
      WHERE a.user_id = ? AND b.user_id = ?
        AND (SELECT COUNT(*) FROM conversation_members x WHERE x.conversation_id = c.id) = 2
      LIMIT 1`).get(req.user.id, peerId);
    let id = existing?.id;
    if (!id) {
      id = randomUUID();
      const now = Date.now();
      db.exec('BEGIN');
      try {
        db.prepare("INSERT INTO conversations (id, kind, created_at) VALUES (?, 'direct', ?)").run(id, now);
        const insertMember = db.prepare('INSERT INTO conversation_members (conversation_id, user_id, joined_at) VALUES (?, ?, ?)');
        insertMember.run(id, req.user.id, now);
        insertMember.run(id, peerId, now);
        db.exec('COMMIT');
      } catch (error) {
        db.exec('ROLLBACK');
        throw error;
      }
    } else {
      db.prepare('UPDATE conversation_members SET hidden_at = 0 WHERE conversation_id = ? AND user_id = ?')
        .run(id, req.user.id);
    }
    res.status(existing ? 200 : 201).json({ conversation: { id, kind: 'direct', peer: publicUser(peer) } });
  });

  app.get('/api/conversations/:id/messages', authenticate, requireMember, (req, res) => {
    const before = Number(req.query.before || Number.MAX_SAFE_INTEGER);
    const limit = Math.min(Math.max(Number(req.query.limit || 50), 1), 100);
    const rows = db.prepare(`SELECT m.*, u.display_name AS sender_name,
        EXISTS(
          SELECT 1 FROM conversation_members reader
          WHERE reader.conversation_id = m.conversation_id
            AND reader.user_id <> m.sender_id
            AND reader.last_read_at >= m.created_at
        ) AS read_by_peer
      FROM messages m JOIN users u ON u.id = m.sender_id
      WHERE m.conversation_id = ? AND m.created_at < ?
      ORDER BY m.created_at DESC LIMIT ?`).all(req.params.id, before, limit).reverse();
    res.json({ messages: rows.map(messageView), hasMore: rows.length === limit });
  });

  app.post('/api/conversations/:id/read', authenticate, requireMember, (req, res) => {
    const readAt = Date.now();
    db.prepare('UPDATE conversation_members SET last_read_at = ? WHERE conversation_id = ? AND user_id = ?')
      .run(readAt, req.params.id, req.user.id);
    app.locals.realtime?.broadcastConversation(req.params.id, req.user.id, 'read', {
      conversationId: req.params.id,
      userId: req.user.id,
      readAt
    });
    res.json({ ok: true, readAt });
  });

  app.delete('/api/conversations/:id', authenticate, requireMember, (req, res) => {
    const hiddenAt = Date.now();
    db.prepare('UPDATE conversation_members SET hidden_at = ? WHERE conversation_id = ? AND user_id = ?')
      .run(hiddenAt, req.params.id, req.user.id);
    app.locals.realtime?.send(req.user.id, 'conversation:hidden', {
      conversationId: req.params.id,
      userId: req.user.id,
      hiddenAt
    });
    res.json({ ok: true, hiddenAt });
  });

  app.delete('/api/conversations/:id/messages', authenticate, requireMember, (req, res) => {
    const media = db.prepare(`SELECT DISTINCT media_url AS url FROM messages
      WHERE conversation_id = ? AND media_url LIKE '/uploads/%'`).all(req.params.id);
    const messageCount = db.prepare('SELECT COUNT(*) AS count FROM messages WHERE conversation_id = ?')
      .get(req.params.id).count;
    const callCount = db.prepare('SELECT COUNT(*) AS count FROM calls WHERE conversation_id = ?')
      .get(req.params.id).count;
    const clearedAt = Date.now();
    db.exec('BEGIN IMMEDIATE');
    try {
      db.prepare('DELETE FROM calls WHERE conversation_id = ?').run(req.params.id);
      db.prepare('DELETE FROM messages WHERE conversation_id = ?').run(req.params.id);
      db.prepare('UPDATE conversation_members SET last_read_at = ? WHERE conversation_id = ?')
        .run(clearedAt, req.params.id);
      db.exec('COMMIT');
    } catch (error) {
      db.exec('ROLLBACK');
      throw error;
    }
    for (const item of media) {
      if (db.prepare('SELECT 1 FROM messages WHERE media_url = ? LIMIT 1').get(item.url)) continue;
      const filename = path.basename(item.url);
      if (!filename || item.url !== `/uploads/${filename}`) continue;
      try { fs.unlinkSync(path.join(config.uploadDir, filename)); } catch (error) {
        if (error.code !== 'ENOENT') console.error(error);
      }
    }
    app.locals.realtime?.broadcastConversation(req.params.id, null, 'conversation:cleared', {
      conversationId: req.params.id,
      userId: req.user.id,
      clearedAt
    });
    res.json({ ok: true, deletedMessages: messageCount, deletedCalls: callCount });
  });

  app.post('/api/conversations/:id/messages', authenticate, requireMember, (req, res) => {
    const type = String(req.body?.type || '');
    const content = String(req.body?.content || '').trim();
    const mediaUrl = String(req.body?.mediaUrl || '');
    const mimeType = String(req.body?.mimeType || '');
    const clientId = String(req.body?.clientId || randomUUID());
    const durationMs = Math.max(0, Number(req.body?.durationMs || 0));
    if (!MESSAGE_TYPES.has(type)) return res.status(400).json({ error: '不支持的消息类型' });
    if ((type === 'text' || type === 'emoji') && (!content || content.length > 4000)) {
      return res.status(400).json({ error: '消息内容不能为空且不能超过 4000 字' });
    }
    if ((type === 'image' || type === 'video') && !mediaUrl.startsWith('/uploads/')) {
      return res.status(400).json({ error: '请先上传媒体文件' });
    }
    const duplicate = db.prepare(`SELECT m.*, u.display_name AS sender_name,
        EXISTS(
          SELECT 1 FROM conversation_members reader
          WHERE reader.conversation_id = m.conversation_id
            AND reader.user_id <> m.sender_id
            AND reader.last_read_at >= m.created_at
        ) AS read_by_peer
      FROM messages m JOIN users u ON u.id = m.sender_id
      WHERE m.sender_id = ? AND m.client_id = ?`)
      .get(req.user.id, clientId);
    if (duplicate) return res.json({ message: messageView(duplicate) });
    const id = randomUUID();
    const createdAt = Date.now();
    db.prepare('UPDATE conversation_members SET hidden_at = 0 WHERE conversation_id = ? AND user_id <> ?')
      .run(req.params.id, req.user.id);
    db.prepare(`INSERT INTO messages
      (id, conversation_id, sender_id, client_id, type, content, media_url, mime_type, duration_ms, created_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`)
      .run(id, req.params.id, req.user.id, clientId, type, content, mediaUrl, mimeType, durationMs, createdAt);
    const row = db.prepare(`SELECT m.*, u.display_name AS sender_name, 0 AS read_by_peer
      FROM messages m JOIN users u ON u.id = m.sender_id WHERE m.id = ?`).get(id);
    const message = messageView(row);
    app.locals.realtime?.broadcastConversation(req.params.id, req.user.id, 'message:new', message);
    res.status(201).json({ message });
  });

  app.post('/api/uploads', authenticate, upload.single('file'), (req, res) => {
    if (!req.file) return res.status(400).json({ error: '请选择图片或视频文件' });
    const relativeUrl = `/uploads/${req.file.filename}`;
    res.status(201).json({
      url: relativeUrl,
      absoluteUrl: `${requestOrigin(req, config)}${relativeUrl}`,
      mimeType: req.file.mimetype,
      size: req.file.size
    });
  });

  app.use((error, _req, res, _next) => {
    if (error.status === 404) return res.status(404).json({ error: '文件不存在' });
    console.error(error);
    if (error instanceof multer.MulterError && error.code === 'LIMIT_FILE_SIZE') {
      return res.status(413).json({ error: `文件不能超过 ${config.maxUploadMb} MB` });
    }
    res.status(error.status || 500).json({ error: error.status ? error.message : '服务器内部错误' });
  });

  return { app, db };
}
