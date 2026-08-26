# App Center

统一管理移动端 APK。公开接口提供按 App 分组的版本列表、最新版本查询与下载；管理员登录后可以上传版本、勾选批量删除，或删除某个 App 的全部版本。

## 本地运行

```bash
npm install
npm test
npm start
```

默认管理账号为 `admin` / `admin123!`。生产环境应通过 `.env` 覆盖密码。数据和 APK 默认保存在 `data/`，该目录不会提交到 Git。

## 接口

```text
GET  /api/health
GET  /api/apps
GET  /api/apps/{appId}/latest
GET  /downloads/{appId}/{filename}
POST /api/auth/login
POST /api/admin/releases
DELETE /api/admin/releases
DELETE /api/admin/apps/{appId}
```

生产 Compose 只监听宿主机 `127.0.0.1:18083`，公网入口由 Cloudflare Tunnel 提供，域名为 `https://apps.lxvb.top`。
