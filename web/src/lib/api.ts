/** Web API 客户端——统一携带 initData 与错误语义（后端回执 {ok,error} 约定）。 */

/** Telegram Mini App 的 initData（非 Telegram 环境为空串——后端会以 401 拒绝）。 */
export function initData(): string {
  return (window as unknown as { Telegram?: { WebApp?: { initData?: string } } })
    .Telegram?.WebApp?.initData ?? '';
}

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
  }
}

async function parse(resp: Response): Promise<Record<string, unknown>> {
  return (await resp.json().catch(() => ({}))) as Record<string, unknown>;
}

/** GET 请求。 */
export async function getJson<T>(path: string): Promise<T> {
  const resp = await fetch(path);
  const data = await parse(resp);
  if (!resp.ok || data.ok === false) {
    throw new ApiError(String(data.error ?? `请求失败（HTTP ${resp.status}）`), resp.status);
  }
  return data as T;
}

/** POST 请求（JSON body）。 */
export async function postJson<T>(path: string, body: unknown): Promise<T> {
  const resp = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  const data = await parse(resp);
  if (!resp.ok || data.ok === false) {
    throw new ApiError(String(data.error ?? `请求失败（HTTP ${resp.status}）`), resp.status);
  }
  return data as T;
}
