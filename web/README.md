# SMS Web Gateway - 前端（web/）

Vue 3 + TypeScript + Naive UI + Vite + Pinia + vue-router（history 模式）。界面为中文。
API 契约见 `../docs/API.md`（唯一真相），类型定义在 `src/api/types.ts`。

## 命令

```bash
npm install
npm run dev        # 开发服务器 http://localhost:5173，/api 代理到 http://127.0.0.1:8080（可用 VITE_API_TARGET 覆盖）
npm run build      # vue-tsc 类型检查 + 构建，输出到 web/dist
npm test           # vitest 单元测试（时间 / 号码 / 高亮等纯函数）
npm run mock       # 自测用 mock 服务端（见下），默认 8080，同时托管 dist
```

生产部署：Nginx 托管 `web/dist`，对未知路径回退 `index.html`（history 路由），`/api/` 反代到 PHP。

## 目录

```
src/
  api/          类型化客户端（client.ts 信封解包 / CSRF / 401 / 429；index.ts 各资源接口；types.ts 对应 API.md）
  stores/       pinia：auth settings devices conversations chat realtime(SSE+轮询) theme
  views/        Install Login Messages Devices Settings Search
  components/   AppShell AppHeader ConversationList/Item ChatPane MessageBubble MessageComposer
                NewMessageModal ResolveTaskDialog DeviceCard PairDeviceModal AuditLogPanel ...
  composables/  useSendMessage(422/423 流程) useConversationActions useMedia useNow
  utils/        time(时区/相对时间) sms(状态/号码/高亮) notify debounce
mock/server.mjs 仅供前端自测的 mock，不是交付物主体
```

## 实现要点

- 所有写请求带 `X-CSRF-Token`；`403 csrf_failed` 会重新拉取 `auth/me` 并自动重试一次；401 清状态并跳转登录（保留 redirect）。
- 实时：`EventSource('/api/v1/events')` 处理 6 种事件；连续 3 次连接失败后退化为 5 秒轮询，右下角（手机端右上角）显示连接状态，轮询期间每 30 秒尝试恢复 SSE。
- 短信内容一律以文本插值渲染，不使用 `v-html`；搜索高亮通过切片 + `<mark>` 实现。
- 二维码用本地 `qrcode` 包生成 data URL，无外部请求。
- 主题：跟随系统 / 浅色 / 深色，持久化到 `localStorage['smsgate.theme']`。

## mock 的用法

```bash
npm run build && node mock/server.mjs 8080     # 登录 admin / password123
MOCK_NO_SSE=1 node mock/server.mjs             # /events 返回 503，用于验证轮询降级
MOCK_INCOMING=1 node mock/server.mjs           # 每 8 秒推送一条新短信
MOCK_INSTALLED=0 node mock/server.mjs          # 未安装状态，用于安装向导
```
