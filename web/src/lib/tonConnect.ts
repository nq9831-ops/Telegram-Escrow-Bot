import { TonConnectUI, type SendTransactionFeature } from '@tonconnect/ui';

/**
 * TON Connect 封装（npm 包形态——与静态单文件用的官方 SDK 同源同版本线）。
 * manifestUrl 走本站 /api/escrow/tonconnect-manifest.json；后端从不接触用户私钥。
 *
 * 对外除连接实例与签名发送外，还提供「连接相位」状态机（onWalletPhase）：
 * 连接中/已取消/失败都有可订阅的状态，UI 不必再靠"页面看起来没反应"猜。
 * 相位口径以 SDK 3.0.2 实测语义为准：openModal() 在弹窗打开时即 resolve（≠ 连接成功），
 * 连接结果经 onStatusChange、错误经其第二参 errorsHandler、弹窗关闭经 onModalStateChange。
 */

let ui: TonConnectUI | null = null;

export function tonConnect(): TonConnectUI {
  if (!ui) {
    ui = new TonConnectUI({
      manifestUrl: location.origin + '/api/escrow/tonconnect-manifest.json',
    });
  }
  return ui;
}

/** 后端 /api/escrow/chain-tx 返回的交易参数（字段与单文件迁移同款）。 */
export interface ChainTxParams {
  validUntil: number;
  network?: string;
  address: string;
  amount: string;
  payload: string;
}

/** 交用户钱包签名广播。 */
export function sendChainTx(tx: ChainTxParams): Promise<unknown> {
  return tonConnect().sendTransaction({
    validUntil: tx.validUntil,
    network: tx.network as SendTransactionFeature['network'],
    messages: [{ address: tx.address, amount: tx.amount, payload: tx.payload }],
  });
}

// ── 连接相位状态机 ────────────────────────────────────────────────────────

/**
 * 连接相位：
 * - `idle`       未连接（初始，或连接建立后断开）
 * - `connecting` 弹窗已打开、或用户已选钱包，等待钱包侧授权
 * - `connected`  已拿到账户地址
 * - `cancelled`  用户主动关掉弹窗（中性提示，可重试——不算失败）
 * - `error`      SDK 报错或弹窗未能打开
 */
export type WalletPhase = 'idle' | 'connecting' | 'connected' | 'cancelled' | 'error';

export interface WalletPhaseSnapshot {
  phase: WalletPhase;
  /** `connected` 时的钱包地址；其余相位为空串。 */
  address: string;
  /** `cancelled` / `error` 时的人读原因；其余相位为空串。 */
  detail: string;
}

const IDLE: WalletPhaseSnapshot = { phase: 'idle', address: '', detail: '' };

let snapshot: WalletPhaseSnapshot = IDLE;
const listeners = new Set<(s: WalletPhaseSnapshot) => void>();
let wired = false;

function publish(next: WalletPhaseSnapshot): void {
  snapshot = next;
  listeners.forEach((fn) => fn(snapshot));
}

/** 订阅 SDK 事件一次（模块级单例；与 tonConnect() 同生命周期）。 */
function wire(): void {
  if (wired) return;
  wired = true;
  const instance = tonConnect();

  instance.onStatusChange(
    (wallet) => {
      const address = wallet?.account?.address ?? '';
      if (address) {
        publish({ phase: 'connected', address, detail: '' });
      } else if (snapshot.phase !== 'error') {
        // SDK 恢复不出账户即视为未连接；错误已由 errorsHandler 记账，不被此处覆盖。
        publish(IDLE);
      }
    },
    (err) => {
      publish({ phase: 'error', address: '', detail: err?.message || 'TON Connect 连接失败。' });
    },
  );

  instance.onModalStateChange((state) => {
    if (state.status === 'opened') {
      if (snapshot.phase !== 'connecting') {
        publish({ phase: 'connecting', address: '', detail: '' });
      }
    } else if (
      state.status === 'closed' &&
      !instance.connected &&
      snapshot.phase === 'connecting' &&
      state.closeReason === 'action-cancelled'
    ) {
      // 'wallet-selected' 不当失败：用户已选钱包，连接被交给钱包侧，仍在进行中。
      publish({ phase: 'cancelled', address: '', detail: '已取消连接钱包——可重试。' });
    }
  });
}

/**
 * 订阅连接相位变化。订阅时立即回调当前快照（UI 无需自己初始化状态）。
 * @returns 取消订阅函数。
 */
export function onWalletPhase(cb: (s: WalletPhaseSnapshot) => void): () => void {
  wire();
  listeners.add(cb);
  cb(snapshot);
  return () => {
    listeners.delete(cb);
  };
}

/**
 * 打开 TON Connect 弹窗（连接结果经 onWalletPhase 回流，本函数不等待连接完成）。
 * 已连接时直接回发 connected 快照；弹窗打不开时落到 error 相位而不是静默失败。
 */
export async function openWalletModal(): Promise<void> {
  const instance = tonConnect();
  if (instance.connected) {
    publish({ phase: 'connected', address: instance.account?.address ?? '', detail: '' });
    return;
  }
  publish({ phase: 'connecting', address: '', detail: '' });
  try {
    await instance.openModal();
  } catch (e) {
    publish({
      phase: 'error',
      address: '',
      detail: e instanceof Error ? e.message : '无法打开 TON Connect 弹窗。',
    });
  }
}
