#!/usr/bin/env bash
# GM-34 数据备份：MySQL 逻辑备份（mysqldump + gzip + 保留策略）。
#
# 用法：
#   TGG_DB_NAME=prod_db [TGG_DB_HOST=127.0.0.1] [TGG_DB_PORT=3306] \
#   [TGG_DB_USER=root] [TGG_DB_PASSWORD=...] \
#     ./scripts/backup-db.sh [输出目录=/var/backups/tgg] [保留份数=7]
#
# 环境变量与应用的 datasource 配置**同源**（见 application.yml 的 ${TGG_DB_*} 占位符）——
# 运维不需要记第二套变量名。
#
# 一致性：--single-transaction（InnoDB 快照、不锁表）+ --routines --triggers --events。
# --set-gtid-purged=OFF：备份面向"恢复到一个普通实例"，不携带源库 GTID 集
# （否则恢复目标会被要求 GTID 对齐，跨实例恢复报错）。
# 密码经 MYSQL_PWD 传递（不上命令行，不出现在进程列表）。
#
# 关于 Redis：SPEC 原文是「MySQL + Redis」备份，但本代码库**没有 Redis 依赖**
# （滑动窗口为进程内实现，见 README「已知边界」）——故本脚本只备份 MySQL；
# 若未来引入 Redis，按同一模式补一段 BGSAVE + 拷贝即可。
#
# 建议接入 cron（示例，每日 03:30，保留 14 份）：
#   30 3 * * * TGG_DB_NAME=prod_db TGG_DB_PASSWORD=*** /path/to/scripts/backup-db.sh /var/backups/tgg 14
set -euo pipefail

DB_NAME="${TGG_DB_NAME:?必须提供 TGG_DB_NAME（与应用的数据库配置同源）}"
DB_HOST="${TGG_DB_HOST:-127.0.0.1}"
DB_PORT="${TGG_DB_PORT:-3306}"
DB_USER="${TGG_DB_USER:-root}"
DB_PASS="${TGG_DB_PASSWORD:-}"
OUT_DIR="${1:-/var/backups/tgg}"
KEEP="${2:-7}"

if ! [[ "$KEEP" =~ ^[1-9][0-9]*$ ]]; then
  echo "保留份数必须是正整数，实为：$KEEP" >&2
  exit 1
fi

mkdir -p "$OUT_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
FILE="$OUT_DIR/${DB_NAME}-${STAMP}.sql.gz"

export MYSQL_PWD="$DB_PASS"
mysqldump --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USER" \
  --single-transaction --routines --triggers --events --set-gtid-purged=OFF \
  --default-character-set=utf8mb4 "$DB_NAME" | gzip > "$FILE"
unset MYSQL_PWD

BYTES=$(wc -c < "$FILE" | tr -d ' ')
# 空库的 dump 也有几 KB；小于 100 字节基本意味着 mysqldump 失败但被管道吞了退出码。
# 宁可报错让人来看，也不留下一个"看起来成功"的空备份。
if [ "$BYTES" -lt 100 ]; then
  echo "备份文件过小（${BYTES} B）——mysqldump 可能失败了，请人工检查：$FILE" >&2
  exit 1
fi
echo "已备份：$FILE（${BYTES} B）"

# 保留策略：按修改时间倒序，删除第 KEEP 个之后的所有本库备份
ls -1t "$OUT_DIR/${DB_NAME}-"*.sql.gz 2>/dev/null | tail -n +$((KEEP + 1)) | while read -r old; do
  rm -f -- "$old"
  echo "已清理旧备份：$old"
done
