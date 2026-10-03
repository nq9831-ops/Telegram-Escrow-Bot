<script setup lang="ts">
/**
 * 订单中心（ET-02 Web 形态）——查状态（域视图文案同源）+ 链上资金操作（TON Connect）。
 * 从静态单文件迁移（功能等价）：钱包连接/绑定、动作过滤（order-actions 预检）、
 * 交易签名广播（chain-tx → 钱包）；后端从不接触用户私钥。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { postJson } from '../lib/api';
import {
  onWalletPhase,
  openWalletModal,
  sendChainTx,
  type WalletPhase,
} from '../lib/tonConnect';

const orderId = ref('');
const status = ref<{ state: string; summary: string; nextStep: string; amount: string; currency: string } | null>(null);
const busy = ref(false);
const result = ref('');
const resultKind = ref<'ok' | 'err' | ''>('');

const walletAddress = ref('');
const connected = ref(false);
/** 连接相位（来自 lib 状态机）：连接中/已取消/失败都有可显示的状态，不必再靠"没反应"猜。 */
const walletPhase = ref<WalletPhase>('idle');
const walletDetail = ref('');
const actions = ref<string[] | null>(null); // null = 尚未取到（不过滤——渐进增强）
const deployed = ref<boolean | null>(null);
const actionsHint = ref('');

/** 签名相位：签名期间页面要有提示，且旧结果必须先清掉（不残留上一条回执）。 */
const signPhase = ref<'idle' | 'preparing' | 'signing'>('idle');

/** 钱包状态行文案（连接中 / 已取消 / 失败各有其词）。 */
const walletStatusText = computed(() => {
  switch (walletPhase.value) {
    case 'connected':
      return `已连接：${walletAddress.value}`;
    case 'connecting':
      return '正在连接钱包…请在 TON Connect 弹窗中完成授权。';
    case 'cancelled':
      return walletDetail.value || '已取消连接钱包——可重试。';
    case 'error':
      return `连接失败：${walletDetail.value || '未知原因'}`;
    default:
      return '未连接钱包';
  }
});

let unwatchPhase: (() => void) | null = null;

/** 待二次确认的链上动作（null = 无）：单击按钮只进入此态，不发起任何请求。 */
const pendingAction = ref<string | null>(null);

const ACTION_LABELS: Record<string, string> = {
  FUND: '支付入金（买方）',
  DELIVER: '确认交付（卖方）',
  CONFIRM: '确认收货（买方）',
  DISPUTE: '发起争议（任一方）',
  REFUND: '请求退款（任一方）',
};

function initData(): string {
  return (window as unknown as { Telegram?: { WebApp?: { initData?: string } } })
    .Telegram?.WebApp?.initData ?? '';
}

onMounted(() => {
  unwatchPhase = onWalletPhase((s) => {
    walletPhase.value = s.phase;
    walletDetail.value = s.detail;
    walletAddress.value = s.address;
    connected.value = s.phase === 'connected' && s.address !== '';
    if (connected.value && orderId.value) {
      void bindWallet();
    }
  });
});

onUnmounted(() => {
  unwatchPhase?.();
  unwatchPhase = null;
});

async function queryStatus(): Promise<void> {
  const id = Number(orderId.value.trim());
  if (!Number.isFinite(id) || id <= 0) {
    resultKind.value = 'err';
    result.value = '请填写订单号（数字）。';
    return;
  }
  busy.value = true;
  result.value = '';
  resultKind.value = '';
  try {
    const body = await postJson<{
      orderId: number;
      state: string;
      summary: string;
      nextStep: string;
      amount: string;
      currency: string;
    }>('/api/trade/status', { initData: initData(), orderId: id });
    status.value = body;
    await refreshActions(id);
  } catch (e) {
    resultKind.value = 'err';
    result.value = e instanceof Error ? e.message : '查询失败，请稍后重试。';
  } finally {
    busy.value = false;
  }
}

/** 动作可见性（order-actions 预检：角色 × 终态 × 部署）；失败保持"全部可见"（渐进增强）。 */
async function refreshActions(id: number): Promise<void> {
  try {
    const b = await postJson<{ actions: string[]; deployed: boolean }>(
      '/api/escrow/order-actions',
      { initData: initData(), orderId: id },
    );
    actions.value = b.actions ?? [];
    deployed.value = b.deployed ?? null;
    if (actions.value.length === 0) {
      actionsHint.value =
        deployed.value === false ? '订单尚未部署链上合约——暂无可执行的链上操作。' : '交易已结束，无链上操作。';
    } else {
      actionsHint.value = '';
    }
  } catch {
    /* 保持不过滤 */
  }
}

function visibleActions(): string[] {
  const all = Object.keys(ACTION_LABELS);
  return actions.value === null ? all : all.filter((a) => actions.value!.includes(a));
}

/** 打开连接弹窗：连接中/取消/失败经 onWalletPhase 落到状态行；弹窗打开本身不代表连接成功。 */
async function connectWallet(): Promise<void> {
  await openWalletModal();
}

async function bindWallet(): Promise<void> {
  try {
    await postJson('/api/escrow/wallet', {
      initData: initData(),
      orderId: Number(orderId.value.trim()),
      address: walletAddress.value,
    });
    resultKind.value = 'ok';
    result.value = `已绑定钱包：${walletAddress.value}`;
  } catch (e) {
    resultKind.value = 'err';
    result.value = e instanceof Error ? e.message : '钱包绑定失败。';
  }
}

/**
 * 危险链上动作的页面级二次确认（第一道）：
 * 单击只进入「待确认」态，不发任何请求；点「确认执行」才走 confirmAction。
 * 与 TON Connect 钱包签名弹窗（第二道）并列而非重复——前者确认「执行哪个动作」，
 * 在网络请求之前的页面层拦住误触；后者授权「这笔交易的参数」，在请求之后出现。
 */
function requestAction(action: string): void {
  if (!walletAddress.value) {
    // 未绑定钱包时明确提示（替代原先的静默 return：静默会让用户以为点了没反应）
    resultKind.value = 'err';
    result.value = '请先连接钱包。';
    return;
  }
  result.value = '';
  resultKind.value = '';
  pendingAction.value = action;
}

function cancelAction(): void {
  pendingAction.value = null;
}

/** 二次确认通过后执行：取交易参数 → 交用户钱包签名广播。 */
async function confirmAction(): Promise<void> {
  const action = pendingAction.value;
  if (!action) return;
  pendingAction.value = null;
  result.value = ''; // 先清旧结果：签名期间页面上不残留上一条无关回执
  resultKind.value = '';
  busy.value = true;
  signPhase.value = 'preparing';
  try {
    const body = await postJson<{
      validUntil: number;
      network?: string;
      address: string;
      amount: string;
      payload: string;
    }>('/api/escrow/chain-tx', {
      initData: initData(),
      orderId: Number(orderId.value.trim()),
      action,
    });
    signPhase.value = 'signing';
    await sendChainTx(body);
    resultKind.value = 'ok';
    result.value = '已提交到钱包签名。链上确认后订单状态将更新（可点「查询状态」复核）。';
  } catch (e) {
    resultKind.value = 'err';
    result.value = e instanceof Error ? e.message : '操作失败。';
  } finally {
    signPhase.value = 'idle';
    busy.value = false;
  }
}
</script>

<template>
  <section class="card">
    <label for="orderId">订单号</label>
    <div class="row">
      <input id="orderId" v-model="orderId" inputmode="numeric" placeholder="例如 1" />
      <button class="secondary" :disabled="busy" @click="queryStatus">查询状态</button>
    </div>

    <template v-if="status">
      <p class="ok">
        订单 #{{ orderId }}（{{ status.amount }} {{ status.currency }}）：{{ status.summary }}
      </p>
      <p class="hint">下一步：{{ status.nextStep }}</p>

      <div style="margin-top: 12px">
        <p class="hint" :class="{ err: walletPhase === 'error' }" role="status" aria-live="polite">
          {{ walletStatusText }}
        </p>
        <button
          v-if="!connected"
          class="secondary"
          :disabled="walletPhase === 'connecting'"
          @click="connectWallet"
        >
          {{ walletPhase === 'connecting' ? '连接中…' : '💳 连接钱包（TON Connect）' }}
        </button>
        <template v-else>
          <!-- 待确认态：危险链上动作需二次确认，确认前不发起任何请求 -->
          <div
            v-if="pendingAction"
            style="
              margin-top: 8px;
              padding: 10px 12px;
              border: 1px solid #e0a800;
              border-radius: 8px;
              background: rgba(255, 193, 7, 0.12);
            "
          >
            <p style="margin: 0 0 4px">
              确认对订单 #{{ orderId }}（{{ status.amount }} {{ status.currency }}）执行「{{
                ACTION_LABELS[pendingAction]
              }}」？
            </p>
            <p class="hint" style="margin: 0 0 8px">
              链上操作不可撤销。确认后才会调起钱包签名，请在签名前核对金额与接收地址。
            </p>
            <button class="secondary" style="margin-right: 8px" :disabled="busy" @click="confirmAction">
              确认执行
            </button>
            <button class="secondary" :disabled="busy" @click="cancelAction">取消</button>
          </div>
          <template v-else>
            <button
              v-for="a in visibleActions()"
              :key="a"
              class="secondary"
              style="margin: 4px 4px 0 0"
              :disabled="busy"
              @click="requestAction(a)"
            >
              {{ ACTION_LABELS[a] }}
            </button>
            <p v-if="actionsHint" class="hint" style="margin-top: 8px">{{ actionsHint }}</p>
          </template>
        </template>
      </div>
    </template>

    <p v-if="signPhase !== 'idle'" class="hint" role="status" aria-live="polite">
      {{
        signPhase === 'signing'
          ? '等待钱包签名——请在钱包中核对金额与接收地址后确认。'
          : '正在准备交易参数…'
      }}
    </p>

    <p v-if="result" :class="resultKind">{{ result }}</p>
  </section>
</template>

<style scoped>
/* 错误回执的视觉区分：全局 style.css 只定义了 .error，而本页 resultKind 取 'err'——就地补齐。 */
.err {
  color: var(--danger);
  font-size: 14px;
  margin-top: 8px;
}
</style>
