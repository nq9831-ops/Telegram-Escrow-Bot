import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * TON Connect 连接状态机——「连接中 / 失败 / 已取消」必须可被 UI 订阅到。
 * 真机证据（@tonconnect/ui 3.0.2）：openModal() 在弹窗打开时即 resolve（不代表连接成功），
 * 连接结果经 onStatusChange 回流、错误经其第二参 errorsHandler、弹窗关闭经 onModalStateChange
 * （closeReason: 'action-cancelled' 用户主动关；'wallet-selected' 已选钱包、连接仍在进行）。
 */

// SDK 存根：捕获订阅回调，供测试驱动相位流转。
const sdk = vi.hoisted(() => {
  const statusCbs: Array<(wallet: unknown) => void> = [];
  const errorCbs: Array<(err: unknown) => void> = [];
  const modalCbs: Array<(state: unknown) => void> = [];
  const ui = {
    connected: false,
    account: null as { address: string } | null,
    openModal: (): Promise<void> => Promise.resolve(),
    onStatusChange(cb: (wallet: unknown) => void, errCb?: (err: unknown) => void) {
      statusCbs.push(cb);
      if (errCb) errorCbs.push(errCb);
      return () => undefined;
    },
    onModalStateChange(cb: (state: unknown) => void) {
      modalCbs.push(cb);
      return () => undefined;
    },
  };
  return { statusCbs, errorCbs, modalCbs, ui };
});

vi.mock('@tonconnect/ui', () => ({
  TonConnectUI: function TonConnectUIStub() {
    return sdk.ui;
  },
}));

/** 每个用例重置模块级单例（ui / 相位 / 订阅）后重新加载。 */
async function load() {
  vi.resetModules();
  sdk.statusCbs.length = 0;
  sdk.errorCbs.length = 0;
  sdk.modalCbs.length = 0;
  sdk.ui.connected = false;
  sdk.ui.account = null;
  sdk.ui.openModal = () => Promise.resolve();
  return import('./tonConnect');
}

type Mod = Awaited<ReturnType<typeof load>>;

function track(mod: Mod) {
  const states: Array<{ phase: string; address: string; detail: string }> = [];
  mod.onWalletPhase((s) => states.push({ ...s }));
  return {
    states,
    last: () => states[states.length - 1],
  };
}

beforeEach(() => {
  vi.stubGlobal('location', { origin: 'https://mini.example' });
});

describe('TON Connect 连接状态机', () => {
  it('初始相位为 idle（未连接）', async () => {
    const mod = await load();
    const t = track(mod);

    expect(t.states).toHaveLength(1);
    expect(t.last().phase).toBe('idle');
  });

  it('打开弹窗即进入 connecting——「点了没反应」的空窗被填上', async () => {
    const mod = await load();
    const t = track(mod);

    await mod.openWalletModal();

    expect(t.last().phase).toBe('connecting');
    expect(t.last().detail).toBe('');
  });

  it('用户主动关掉弹窗（action-cancelled）落到 cancelled，且文案可重试', async () => {
    const mod = await load();
    const t = track(mod);
    await mod.openWalletModal();

    sdk.modalCbs[0]({ status: 'closed', closeReason: 'action-cancelled' });

    expect(t.last().phase).toBe('cancelled');
    expect(t.last().detail).toContain('取消');
  });

  it('已选钱包关闭弹窗（wallet-selected）不误报失败——连接仍在钱包侧进行', async () => {
    const mod = await load();
    const t = track(mod);
    await mod.openWalletModal();

    sdk.modalCbs[0]({ status: 'closed', closeReason: 'wallet-selected' });

    expect(t.last().phase).toBe('connecting');
  });

  it('拿到账户地址即 connected，并带出地址', async () => {
    const mod = await load();
    const t = track(mod);
    await mod.openWalletModal();
    sdk.modalCbs[0]({ status: 'closed', closeReason: 'wallet-selected' });

    sdk.statusCbs[0]({ account: { address: 'EQAbc123' } });

    expect(t.last().phase).toBe('connected');
    expect(t.last().address).toBe('EQAbc123');
  });

  it('SDK 报错经 errorsHandler 落到 error 相位（错误不被静默吞掉）', async () => {
    const mod = await load();
    const t = track(mod);
    await mod.openWalletModal();

    sdk.errorCbs[0]({ message: 'Wallet connection failed' });

    expect(t.last().phase).toBe('error');
    expect(t.last().detail).toBe('Wallet connection failed');
  });

  it('弹窗未能打开（openModal 抛错）落到 error 相位', async () => {
    const mod = await load();
    const t = track(mod);
    sdk.ui.openModal = () => Promise.reject(new Error('SDK 未就绪'));

    await mod.openWalletModal();

    expect(t.last().phase).toBe('error');
    expect(t.last().detail).toBe('SDK 未就绪');
  });

  it('连接后断开（wallet=null）回落 idle', async () => {
    const mod = await load();
    const t = track(mod);
    sdk.statusCbs[0]({ account: { address: 'EQAbc123' } });
    expect(t.last().phase).toBe('connected');

    sdk.statusCbs[0](null);

    expect(t.last().phase).toBe('idle');
    expect(t.last().address).toBe('');
  });

  it('取消订阅后不再收到相位变化', async () => {
    const mod = await load();
    const seen: string[] = [];
    const off = mod.onWalletPhase((s) => seen.push(s.phase));
    off();

    await mod.openWalletModal();

    expect(seen).toEqual(['idle']);
  });
});
