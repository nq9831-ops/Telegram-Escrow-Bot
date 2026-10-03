# Telegram 群管理 + 担保交易系统 · Web 前端（Vue 3）

> **验证状态：工程已建成，构建已验证**（2026-10-03：`npm install` 实测版本锁定于 lock 文件；
> `npm run build` 通过——vite 8.3 / vue 3.5.43 / @tonconnect/ui 3.0.2；`npm run preview` 服务与
> 资源 200 已验）。**渲染验证受限**：headless 预览因 `telegram.org` 运行时脚本在本地网络加载
> 超时（真实 Telegram 客户端不受影响）。**部署**：`npm run build` → `dist/` 静态托管
> （相对路径产物，可直接由后端静态目录或 nginx 承载）；dev 联调用 `npm run dev`（代理见 vite.config.ts）。

## 工程结构（现状——2026-10-03 按实现线校正）

```
web/
├── package.json          # vue3 + vite + @tonconnect/ui（依赖版本以线上 install 实测为准）
├── vite.config.ts        # 代理 /api → Admin API
└── src/
    ├── main.ts           # 仅挂载（createApp(App).mount('#app')）
    ├── App.vue           # 路由壳 + 主题同步（themeParams 手动映射 → CSS 变量）+ BackButton 映射（SPEC 7）
    ├── style.css         # 默认主题变量（非 Telegram 环境兜底）
    ├── pages/
    │   ├── TradeCreate.vue    # 发起/接受邀请（单文件流程迁移）
    │   └── TradeStatus.vue    # 订单中心：状态查询 + TON Connect 资金操作
    └── lib/
        ├── tonConnect.ts      # TON Connect 钱包连接（@tonconnect/ui；manifest 走本站 /api/escrow/tonconnect-manifest.json）
        ├── api.ts             # Admin API 客户端
        └── api.test.ts        # vitest 单测（postJson 口径）
```

## 实现约束（来自 SPEC，落地时逐条核对）

- **按钮颜色只作增强**：文字必须自带语义（旧客户端不显示样式、色盲不可分色）；
- **签名前展示人类可读交易摘要**（SPEC 6.3/6.5）；**不引入无限额度授权**；
- **主题同步**：实现**未用** `themeParams.bindCssVars()`，而是 `App.vue` 内手动映射 `themeParams` 6 键 → CSS 变量（与静态单文件同款）；返回按钮映射 `BackButton` → 流程回退；
- **错误恢复**：失败显示原因文本并保留已填信息；⚠️ **未实现**独立「重试」「返回」控件（重试＝再次点击原动作按钮）。

## 身份与授权口径（实测，2026-10-03）

Web / Mini App 侧**不使用 Cookie / Session**——身份只来自 Telegram `initData` 验签（`WebAppInitDataVerifier`），
授权另由**当事方限定**收口。逐条对应端点：

| 端点 | 身份门 | 越权响应 |
|---|---|---|
| `POST /api/trade/status`（`TradeStatusController`） | initData 验签失败/过期 → **401** | 非当事方 → **403**「只能查询自己参与的订单」 |
| `POST /api/escrow/wallet`（`EscrowChainTxController`） | 同上 **401** | 非当事方 → **403**「你不是该订单的当事方，无法绑定钱包地址」 |
| `POST /api/escrow/chain-tx` | 同上 **401** | 角色矩阵（FUND/CONFIRM→仅买方；DELIVER→仅卖方；REFUND/DISPUTE→任一当事方）越权 → **403** |
| `POST /api/escrow/order-actions` | 同上 **401** | 非当事方 → **403**「你不是该订单的当事方」 |
| `GET /api/escrow/tonconnect-manifest.json` | **公开**（无验签；动态返回 manifest） | — |

- **Mini App `start_param` 令牌门**：`TradeCreate.vue` 只用 `start_param` 做「显示哪个视图」的 UI 分流，**刻意不读未签名客户端字段**；权威判权在服务端以**已验签的 `start_param`**（`TradeInviteService`）重新做出。
- **资金路径守卫**：合约要求 `sender == storage.buyer/seller`——消息必须由当事方本人钱包签名（TON Connect）；后端从不接触用户私钥。

> **实现线说明（2026-10-03 更新）**：本目录的 Vue 工程**已落地**（vite 8 + vue 3.5 +
> @tonconnect/ui；两页：TradeCreate / TradeStatus；`npm run build` 通过、vitest 5/5；
> 构建产物接 `/api/trade/*` 与 `/api/escrow/*` 后端）。本 README 的规划若与实现冲突，以实现线为准。
