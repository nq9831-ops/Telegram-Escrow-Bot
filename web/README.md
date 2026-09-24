# Telegram 群管理 + 担保交易系统 · Web 前端（Vue 3）

> **验证状态：仅工程骨架，构建未验证**（本地未跑 `npm install`/`npm run build`）。
> 落地前必须：`npm install && npm run build`（线上阶段执行），并按 SPEC 6.3/7 节接 TON Connect。

## 工程结构（规划）

```
web/
├── package.json          # vue3 + vite + @tonconnect/ui（依赖版本以线上 install 实测为准）
├── vite.config.ts        # 代理 /api → Admin API
└── src/
    ├── main.ts           # 挂载 + tgWebAppThemeParams 主题同步（SPEC 7：themeParams.bindCssVars()）
    ├── App.vue           # 路由壳 + BackButton 映射（SPEC 7）
    ├── pages/
    │   ├── TradeCreate.vue    # ET-07 三步创建（选角色→填信息→确认签名）
    │   ├── TradeStatus.vue    # ET-02 状态查询（配合 TradeStatusView 文案）
    │   └── AdminWords.vue     # GM-06 违禁词在线管理（调 Admin API）
    └── lib/
        ├── tonConnect.ts      # 钱包连接（Gram 自托管优先，见 02/11 文档）
        └── api.ts             # Admin API 客户端
```

## 实现约束（来自 SPEC，落地时逐条核对）

- **按钮颜色只作增强**：文字必须自带语义（旧客户端不显示样式、色盲不可分色）；
- **签名前展示人类可读交易摘要**（SPEC 6.3/6.5）；**不引入无限额度授权**；
- Mini App 主题同步 `themeParams.bindCssVars()`；返回按钮映射 `BackButton` → 流程回退；
- 错误恢复：失败附「重试」+「返回」，重试保留已填信息。
