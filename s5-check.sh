#!/usr/bin/env bash
#
# 核销前钱包地址自检（S5 · B10 第 0 步）——读 env.local → 推导钱包地址 → 与 acton 对拍。
#
# 用法:
#   ./s5-check.sh              # 读项目根的 env.local
#   ./s5-check.sh <env 文件>   # 读指定文件（自测用）
#
# env.local 里需要一行（24 词助记词，acton wallet export-mnemonic 的产物）:
#   export TGG_CHAIN_UPGRADE_WALLET_MNEMONIC="word1 word2 …（24 词）"
# 可选: export TGG_EXPECTED_WALLET_ADDRESS="kQ…"（设了就自动断言对拍，不再靠肉眼）
#
# 期望输出: WALLET-ADDRESS(testnet kQ)=… 与 `acton wallet list` 显示的地址逐字符一致。
set -euo pipefail

cd "$(dirname "$0")"

ENV_FILE="${1:-env.local}"
if [[ ! -f "$ENV_FILE" ]]; then
  echo "缺少 $ENV_FILE——先导出助记词并写入（格式见本脚本头部注释）:" >&2
  echo '  acton wallet export-mnemonic tgg-federation   # 交互：输入钱包名确认' >&2
  echo '  cat > env.local << '"'"'EOF'"'"'' >&2
  echo '  export TGG_CHAIN_UPGRADE_WALLET_MNEMONIC="（24 词）"' >&2
  echo '  EOF' >&2
  exit 1
fi

# 兼容两类文件形态（标准 export 行 / 裸词行等非 shell 内容）。
# 注意：不能写成 `source file || true`——`set -e` 下 source 内部命令失败会直接终止脚本，
# `||` 保护不到内部（2026-10-02 最小复现确认）；因此短暂关闭 -e 执行 source。
set +e
set -a
# shellcheck source=/dev/null
source "$ENV_FILE" 2>/dev/null
set +a
set -e

if [[ -z "${TGG_CHAIN_UPGRADE_WALLET_MNEMONIC:-}" ]]; then
  # 兜底：文件可能是"裸词行"形态（编辑器里把整行替换成了 24 词，丢了 export 外壳）——
  # 逐行找"恰好 24 个连续小写词"的行（BIP-39 词全小写，提示/注释行不会恰好匹配）
  RAW_WORDS="$(grep -oE '[a-z]+( [a-z]+){23}' "$ENV_FILE" 2>/dev/null | head -1 || true)"
  if [[ -n "$RAW_WORDS" ]]; then
    export TGG_CHAIN_UPGRADE_WALLET_MNEMONIC="$RAW_WORDS"
    echo "（检测到裸词行形态，已自动识别——助记词不回显）"
  fi
fi

if [[ -z "${TGG_CHAIN_UPGRADE_WALLET_MNEMONIC:-}" ]]; then
  echo "$ENV_FILE 里没有可识别的助记词（应为 export TGG_CHAIN_UPGRADE_WALLET_MNEMONIC=\"…\" 一行，或一整行 24 词）" >&2
  exit 1
fi

echo "→ 自检中（助记词不回显）…"
OUT="$(mktemp)"
if mvn -B -pl tgg-chain -am test \
     -Dtest=WalletAddressSelfCheckTest \
     -Dsurefire.failIfNoSpecifiedTests=false > "$OUT" 2>&1; then
  grep -E "WALLET-|BUILD SUCCESS" "$OUT"
  rm -f "$OUT"
  echo
  echo "✓ 把上面 WALLET-ADDRESS(testnet kQ) 与 acton wallet list 显示的地址逐字符对比。"
  echo "  一致 → 告诉助手「自检对上了」即可开 B10 核销。不一致 → 先停下核对 wallet-id 与词。"
else
  echo "✗ 自检未通过。以下是关键错误行（助记词内容不会出现在任何输出里）：" >&2
  grep -E "WALLET-|ERROR|Exception|BUILD FAILURE|助记词|Tests run:" "$OUT" | head -25 >&2
  if grep -qE "占位符|24 ?词|word1" "$ENV_FILE" 2>/dev/null; then
    echo >&2
    echo "提示：$ENV_FILE 里看起来还是模板占位符——需要把占位文字整段替换成真实的 24 词助记词。" >&2
  fi
  rm -f "$OUT"
  exit 1
fi
