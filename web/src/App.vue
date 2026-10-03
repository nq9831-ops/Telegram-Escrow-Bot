<script setup lang="ts">
/**
 * 路由壳（web/README 规划）——三个视图切换 + Telegram 主题同步（SPEC 7）+
 * BackButton 映射（非首页返回首页——流程回退的最小形态）。
 */
import { onMounted, ref } from 'vue';
import TradeCreate from './pages/TradeCreate.vue';
import TradeStatus from './pages/TradeStatus.vue';

type Page = 'create' | 'status';
const page = ref<Page>('create');

interface TelegramWebApp {
  ready?: () => void;
  expand?: () => void;
  themeParams?: Record<string, string>;
  BackButton?: { show?: () => void; hide?: () => void; onClick?: (cb: () => void) => void };
}

function webApp(): TelegramWebApp | undefined {
  return (window as unknown as { Telegram?: { WebApp?: TelegramWebApp } }).Telegram?.WebApp;
}

/** BackButton 可见性（非首页显示）。 */
function syncBackButton(): void {
  const wa = webApp();
  if (page.value === 'create') {
    wa?.BackButton?.hide?.();
  } else {
    wa?.BackButton?.show?.();
  }
}

function go(p: Page): void {
  page.value = p;
  syncBackButton();
}

onMounted(() => {
  const wa = webApp();
  if (!wa) {
    return; // 浏览器里开发预览（非 Telegram）——主题用 style.css 的默认值
  }
  wa.ready?.();
  wa.expand?.();
  // 主题同步：themeParams → CSS 变量（与静态单文件同款映射；变量缺失走 style.css 默认值）
  const p = wa.themeParams ?? {};
  const root = document.documentElement.style;
  const map: Array<[string, string | undefined]> = [
    ['--tg-theme-bg-color', p.bg_color],
    ['--tg-theme-text-color', p.text_color],
    ['--tg-theme-hint-color', p.hint_color],
    ['--tg-theme-button-color', p.button_color],
    ['--tg-theme-button-text-color', p.button_text_color],
    ['--tg-theme-secondary-bg-color', p.secondary_bg_color],
  ];
  for (const [name, value] of map) {
    if (value) {
      root.setProperty(name, value);
    }
  }
  // BackButton → 回首页
  wa.BackButton?.onClick?.(() => go('create'));
  syncBackButton();
});
</script>

<template>
  <main class="app">
    <nav class="tabs">
      <button :class="{ active: page === 'create' }" @click="go('create')">发起担保</button>
      <button :class="{ active: page === 'status' }" @click="go('status')">订单状态</button>
    </nav>
    <TradeCreate v-if="page === 'create'" @navigate="go" />
    <TradeStatus v-else />
  </main>
</template>
