import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, getJson, initData, postJson } from './api';

/**
 * Web API 封装的核心行为——错误语义（{ok,error} 契约）与请求形态。
 * 后端契约：成功 {ok:true,...}；失败 {ok:false,error} + 相应 HTTP 状态。
 */

function stubFetch(status: number, body: unknown) {
  const spy = vi.fn(async () => new Response(JSON.stringify(body), { status }));
  vi.stubGlobal('fetch', spy);
  return spy;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('api 封装', () => {
  it('成功：透传响应数据', async () => {
    stubFetch(200, { ok: true, orderId: 7, state: 'LOCKED' });

    await expect(getJson<{ orderId: number }>('/api/x')).resolves.toMatchObject({ orderId: 7 });
  });

  it('ok:false → ApiError 且带服务端 error 文案', async () => {
    stubFetch(400, { ok: false, error: '订单不存在' });

    const err = await getJson('/api/x').catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).message).toBe('订单不存在');
    expect((err as ApiError).status).toBe(400);
  });

  it('非 2xx 且无 error 字段 → 兜底 HTTP 状态文案（不吞错）', async () => {
    stubFetch(500, {});

    const err = await getJson('/api/x').catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).message).toContain('500');
  });

  it('POST：JSON body 与 Content-Type 头齐备', async () => {
    const spy = stubFetch(200, { ok: true });

    await postJson('/api/y', { initData: 'q', orderId: 1 });

    const init = (spy.mock.calls as unknown as Array<[string, RequestInit]>)[0][1];
    expect(init.method).toBe('POST');
    expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/json');
    expect(init.body).toBe('{"initData":"q","orderId":1}');
  });

  it('initData：非 Telegram 环境返回空串（后端据此以 401 拒绝——前端不伪造身份）', () => {
    vi.stubGlobal('window', {}); // node 环境无 window——注入空壳，覆盖「无 Telegram」分支
    expect(initData()).toBe('');
  });
});
