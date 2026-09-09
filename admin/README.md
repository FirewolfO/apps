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

## 小林学习进度同步

使用 Node.js 22.13+ 的内置 SQLite，数据文件为持久化数据目录下的 `xiaolin-progress.sqlite`。
备份运行中的数据库时使用 SQLite 在线备份，或停止服务后同时保留数据库及 WAL 文件；不要只复制运行中的主文件。
此服务与 APK 目录、People 管理员会话隔离；没有同步码的请求不能读取或修改学习记录。
同步接口属于 Android 客户端内部协议，不复用管理员身份，也不授予应用管理权限。

```text
POST /api/xiaolin/sync/spaces       创建空间，返回 {code,state}
POST /api/xiaolin/sync/progress     Authorization: Bearer <32位小写十六进制同步码>
```

请求和返回的进度结构为 `{version:1,full:{URL:记录},compact:{URL:记录}}`。
每个记录可包含 `visit`、`completion`、`position` 三个独立字段，字段格式为 `{clock,device,value}`。
`clock` 是非负逻辑版本，`device` 是 32 位随机设备 ID。版本大者优先，同版本以设备 ID 字典序决定胜者。
值分别为 `{title,at}`、`{done,at}`、`{y,fraction}`；`at` 为显示用的毫秒时间戳，
`fraction` 为 0–1 的滚动比例（旧版只有像素位置时为 -1）。撤销完成也保留 `done:false` 字段。
客户端读取时发送两种模式均为空的进度；写入时发送本地快照，服务端事务合并后返回完整结果。

同步码由服务端安全随机生成，数据库只保存其 SHA-256 摘要。所有响应禁止缓存，错误日志不记录正文和凭据。
空间不存在返回 404，凭据格式错误返回 401，数据格式错误返回 400，超限返回 413，限流返回 429。
请求及合并结果上限 2 MiB，每模式最多 2000 篇；每个连接来源每分钟 600 次请求、每小时 20 次创建，
服务最多容纳 10000 个同步空间。代理后的来源按连接对端统计，不信任客户端传入的转发头。

部署时在开发机构建带不可变标签的镜像，通过私有 Registry 传输，目标机仅拉取和运行，保留现有 `/data` 卷及环境配置。
