<script setup lang="ts">
/**
 * 发起担保 / 接受邀请（ET-07 的 Web 形态）——从静态单文件迁移（功能等价，交互细节逐条保留：
 * 风险提示随响应、去尾零金额、本地时区时刻、错误恢复保留已填信息）。
 *
 * 有 start_param（邀请令牌）→ 接受视图；无 → 发起视图。令牌只用于「显示哪个视图」的 UI
 * 判断，权威判定在服务端重新验签后做出。
 */
import { onMounted, ref } from 'vue';
import { postJson } from '../lib/api';

const emit = defineEmits<{ navigate: [page: 'status'] }>();

const amount = ref('');
const currency = ref('TON');
const risk = ref('');
const riskStrong = ref(false);
const busy = ref(false);
const result = ref('');
const resultKind = ref<'ok' | 'err' | ''>('');
const inviteLink = ref('');
const acceptMode = ref(false);

function initData(): string {
  return (window as unknown as { Telegram?: { WebApp?: { initData?: string } } })
    .Telegram?.WebApp?.initData ?? '';
}

/** 从 initData 原始串解析 start_param（只做 UI 分流；刻意不读未签名字段）。 */
function startParamFromInitData(): string | null {
  const raw = initData();
  for (const part of raw.split('&')) {
    const eq = part.indexOf('=');
    if (eq > 0 && part.slice(0, eq) === 'start_param') {
      const v = part.slice(eq + 1);
      try {
        return decodeURIComponent(v);
      } catch {
        return v;
      }
    }
  }
  return null;
}

/** 金额去尾零（"100.00000000" → "100"）——纯字符串，不转数字（与单文件/MoneyFormat 同口径）。 */
function trimZeros(v: unknown): string {
  const s = String(v ?? '');
  return s.includes('.') ? s.replace(/0+$/, '').replace(/\.$/, '') : s;
}

/** ISO 时刻 → 本地展示；解析失败原样返回（不显示 Invalid Date）。 */
function localTime(iso: unknown): string {
  if (!iso) return '';
  const d = new Date(String(iso));
  return Number.isNaN(d.getTime()) ? String(iso) : d.toLocaleString();
}

onMounted(() => {
  acceptMode.value = startParamFromInitData() !== null;
});

async function createInvite(): Promise<void> {
  if (!amount.value.trim()) {
    resultKind.value = 'err';
    result.value = '请填写金额。';
    return;
  }
  busy.value = true;
  result.value = '';
  resultKind.value = '';
  try {
    const body = await postJson<{
      amount: string;
      currency: string;
      expiresAt: string;
      inviteUrl: string;
      riskPrompt?: string;
    }>('/api/trade/invite', {
      initData: initData(),
      amount: amount.value.trim(),
      currency: currency.value,
    });
    resultKind.value = 'ok';
    result.value = `已生成邀请（${trimZeros(body.amount)} ${body.currency}）。有效期至 ${localTime(body.expiresAt)}。`;
    inviteLink.value = body.inviteUrl;
    if (body.riskPrompt) {
      risk.value = '风险提示：' + body.riskPrompt;
      riskStrong.value = true;
    }
  } catch (e) {
    resultKind.value = 'err';
    result.value = e instanceof Error ? e.message : '生成失败，请稍后重试。';
  } finally {
    busy.value = false;
  }
}

async function acceptInvite(): Promise<void> {
  busy.value = true;
  result.value = '';
  resultKind.value = '';
  try {
    const body = await postJson<{ orderId: number; amount: string; currency: string }>(
      '/api/trade/accept',
      { initData: initData() },
    );
    resultKind.value = 'ok';
    result.value = `已接受，订单 #${body.orderId}（${trimZeros(body.amount)} ${body.currency}）。请连接钱包以完成链上资金操作。`;
  } catch (e) {
    resultKind.value = 'err';
    result.value = e instanceof Error ? e.message : '接受失败，请稍后重试。';
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <section class="card">
    <template v-if="acceptMode">
      <p>你被邀请接受一笔担保交易。确认后你将成为本单卖方，发起人随后托管资金。</p>
      <button class="primary" :disabled="busy" @click="acceptInvite">
        {{ busy ? '提交中…' : '确认接受' }}
      </button>
    </template>
    <template v-else>
      <label for="amount">金额</label>
      <input id="amount" v-model="amount" inputmode="decimal" placeholder="例如 100" />

      <label for="currency">币种</label>
      <select id="currency" v-model="currency">
        <!-- 币种白名单（Wave 0）：只结算 TON 与 TON 链上的 USDT；value 与服务端 TradeCurrency 一致 -->
        <option value="TON">TON</option>
        <option value="USDT">USDT（TON 链）</option>
      </select>

      <p v-if="risk" :class="riskStrong ? 'error' : 'hint'">{{ risk }}</p>
      <p v-else class="hint">提交前请确认：金额与币种。生成链接后发给对方，对方点开即接单。</p>

      <button class="primary" :disabled="busy" @click="createInvite">
        {{ busy ? '生成中…' : '生成邀请链接' }}
      </button>

      <div v-if="inviteLink" class="card" style="margin-top: 12px">
        <p class="hint">把下面链接发给对方，对方点开即自动接单：</p>
        <p style="word-break: break-all">{{ inviteLink }}</p>
      </div>
    </template>

    <p v-if="result" :class="resultKind">{{ result }}</p>
  </section>
</template>
