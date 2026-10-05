#!/usr/bin/env bash
# 对账心跳（2026-10-04 审查修复版）：批量对账 + 结构化判定。
#
# 作用：定时调用 POST /admin/reconcile（不带 orderId = 批量模式，遍历「活跃且已上链」的订单），
# 把「链上领先」的订单按序回填；有 DANGER 时以退出码 2 结束（供监控捕获）。
# 应用侧在批量对账中发现 DANGER 时已直接推送管理会话（AdminReconcileController.notifyDanger），
# 本脚本不再重复推送（审查修复：旧版经 /admin/ops-alert 二次投递同一事件属重复告警）。
#
# 部署示例（每 10 分钟；TGG_ADMIN_USER/PASS 来自服务器 env）：
#   */10 * * * * TGG_ADMIN_USER=admin TGG_ADMIN_PASS=*** /www/wwwroot/tgg/heartbeat.sh >> /var/log/tgg-heartbeat.log 2>&1
# 验证：bash heartbeat.sh --dry-run
set -euo pipefail

BASE="${TGG_HEARTBEAT_URL:-http://127.0.0.1:8080}"

if [[ "${1:-}" == "--dry-run" ]]; then
  echo "[dry-run] 将调用: POST ${BASE}/admin/reconcile（批量模式——遍历活跃订单）"
  echo "[dry-run] 响应含 dangers[] 时以退出码 2 结束（供监控捕获）"
  exit 0
fi

USER_NAME="${TGG_ADMIN_USER:?需要 TGG_ADMIN_USER（面板 env.sh 中的 TGG_ADMIN_*）}"
PASS="${TGG_ADMIN_PASS:?需要 TGG_ADMIN_PASS}"

echo "[$(date -u +%FT%TZ)] reconcile(batch) → ${BASE}/admin/reconcile"
RESP="$(curl -fsS -u "${USER_NAME}:${PASS}" -X POST "${BASE}/admin/reconcile")"
echo "$RESP"

# 超时提醒巡检（D1=A+，2026-10-05）：超期未提醒的单 → 给买方推一键深链（不执行链上动作）。
# 调用失败不改变本脚本的退出码语义（主职责是批量对账的 DANGER 判定）；但仍留痕可见。
echo "[$(date -u +%FT%TZ)] notify-timeouts → ${BASE}/admin/notify-timeouts"
if ! REMIND="$(curl -fsS -u "${USER_NAME}:${PASS}" -X POST "${BASE}/admin/notify-timeouts")"; then
  echo "⚠️ 超时提醒巡检调用失败（不改变 DANGER 退出码语义；提醒为辅助通道）"
else
  echo "$REMIND"
fi

# 结构化判定（审查修复：不再对响应体做大小写敏感全文 grep——verdict 是结构化字段）：
# 取 dangers 数组长度；解析失败按异常处理（保守报警，而非静默当一致）。
DANGERS="$(printf '%s' "$RESP" | python3 -c 'import json,sys; print(len(json.load(sys.stdin).get("dangers") or []))' 2>/dev/null || echo "ERR")"
if [[ "$DANGERS" == "ERR" ]]; then
  echo "⚠️ 响应无法结构化解析（缺 python3 或响应形态变化）——请人工查看上方响应体"
  exit 3
fi
if [[ "$DANGERS" != "0" ]]; then
  echo "⚠️ 发现 $DANGERS 单对账 DANGER（资金滞留/方向相反）——已由应用侧推送管理会话；需人工复查"
  exit 2
fi
exit 0
