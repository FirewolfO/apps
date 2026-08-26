# App Center

统一管理移动端 APK。首页无需登录即可查看和下载；`/ALL` 展示所有 App，`/{appId}` 展示指定 App 的全部版本。右上角通过 People OAuth 登录，只有 People 系统管理员可以上传版本、勾选批量删除，或删除某个 App 的全部版本。

## 本地运行

```bash
npm install
npm test
npm start
```

本地运行前需要配置 People OAuth 客户端，默认 Client ID 为 `app-center`，回调地址为 `http://localhost:3000/oauth/callback`。数据和 APK 默认保存在 `data/`，该目录不会提交到 Git。

## 接口

```text
GET  /api/health
GET  /api/apps
GET  /api/apps/{appId}/latest
GET  /downloads/{appId}/{filename}
GET  /api/auth/oauth/url
POST /api/auth/oauth/callback
GET  /api/auth/me
POST /api/auth/logout
POST /api/admin/releases
DELETE /api/admin/releases
DELETE /api/admin/apps/{appId}
```

生产 Compose 只监听宿主机 `127.0.0.1:18083`，公网入口由 Cloudflare Tunnel 提供，域名为 `https://apps.lxvb.top`。
