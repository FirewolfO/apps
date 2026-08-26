# 连线 IM

一套客户端、服务端完全分离的即时通讯应用。Android 客户端位于 `client/`，服务端位于 `server/`；账号统一由 People 管理，APK 统一由 App Center 管理。

## 已实现

- Android 使用 People 企业账号登录，不提供独立账号和自助注册
- 联系人搜索、一对一会话、历史消息、未读数与输入状态
- 文字、表情、图片和视频消息
- WebRTC 一对一语音/视频通话，含接听、拒绝、静音、扬声器和摄像头翻转
- 前台直接显示来电，后台进程存活时发送来电通知
- Android 系统 Photo Picker，无需申请整库照片/视频读取权限
- `compileSdk 36` / `targetSdk 36`，适配 Android 15 和 Android 16 强制边到边及通话前台服务权限

## 本地运行

服务端需要 Node.js 22.5 或更高版本：

```bash
cd server
cp .env.example .env
# 配置 JWT_SECRET、People OAuth Client Secret 和服务地址
npm install
npm start
```

启动后健康检查为 `http://localhost:3000/api/health`，Android 模拟器通过 `http://10.0.2.2:3000` 访问服务端。服务端需要配置 People OAuth Client Secret；首次登录会按 People 身份创建 IM 联系人记录。生产环境应同时备份 `data/im.db` 和 `data/uploads/`。

## 构建 Android APK

需要 JDK 17 与 Android SDK Platform 36：

```bash
cd client
JAVA_HOME=/path/to/jdk17 ./gradlew testDebugUnitTest assembleDebug
```

调试 APK 输出到：

```text
client/app/build/outputs/apk/debug/app-debug.apk
```

真机调试时服务端不能使用 `10.0.2.2`，需替换成电脑在局域网中的地址：

```bash
./gradlew assembleDebug -PIM_SERVER_URL=http://192.168.1.20:3000
```

Release 默认禁止明文 HTTP，正式构建应使用 HTTPS：

```bash
./gradlew assembleRelease -PIM_SERVER_URL=https://im.example.com
```

版本检查固定访问 App Center 的 `GET https://apps.lxvb.top/api/apps/linkup-im/latest`，也可在构建时使用 `-PAPP_CENTER_URL=https://apps.example.com` 覆盖。

## 生产部署

可使用 Docker Compose：

```bash
export PUBLIC_URL=https://im.example.com
export JWT_SECRET="$(openssl rand -hex 32)"
export PEOPLE_CLIENT_SECRET='与 People 的 linkup-im OAuth 客户端一致'
docker compose up -d --build
```

在公网部署时还需要：

- 在服务端前放置支持 WebSocket Upgrade 的 HTTPS 反向代理。
- 配置 coturn 或其他 TURN 服务；仅使用默认 STUN 时，部分企业网和对称 NAT 下无法建立 P2P 通话。
- 在环境变量中填写 `TURN_URLS`、`TURN_USERNAME`、`TURN_CREDENTIAL`。多个地址用逗号分隔。
- 将 `PUBLIC_URL` 设为外部 HTTPS 地址，并定期备份 `im_data` 数据卷。
- Android 后台进程可能被系统回收。正式推送离线消息和进程被回收后的来电，需要接入 FCM 或厂商推送通道；当前版本不包含第三方推送凭据。

## 验证

```bash
cd server && npm test
cd client && JAVA_HOME=/path/to/jdk17 ./gradlew testDebugUnitTest assembleDebug
```

服务端测试覆盖 People OAuth 身份交换、用户登录、资料同步、联系人查找、建立会话、消息持久化、WebSocket 实时消息，以及旧管理与下载入口已移除。
