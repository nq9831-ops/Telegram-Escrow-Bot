#!/bin/bash
# ============================================================================
# tgg-app 启动脚本（systemd Type=forking 调用的模板）
# ----------------------------------------------------------------------------
# 作用：注入环境变量（env.sh）→ 后台启动 fat jar → 写 PID 文件。
# 使用：1) 把本文件放到部署目录（如 /opt/tgg/run-app.sh）并 chmod +x
#       2) 按实际路径修改下方 JAVA_BIN / APP_DIR
#       3) 配套 unit：spring_tgg-bot.service（见同目录模板）
# ============================================================================
set -a
# 主配置（必改路径）：所有 TGG_* / TELEGRAM_* 凭据都放这里，chmod 600
source /opt/tgg/env.sh
# 可选：运营覆盖层（同名键后者胜；不用可删）
if [ -f /opt/tgg/env-override.sh ]; then . /opt/tgg/env-override.sh; fi
set +a

JAVA_BIN=/usr/lib/jvm/java-21-openjdk-amd64/bin/java   # ← 按实际 JDK21 路径修改
APP_DIR=/opt/tgg                                        # ← 按实际部署目录修改

mkdir -p "${APP_DIR}/run"
nohup "${JAVA_BIN}" -Xmx768m -Xms256m \
  -jar "${APP_DIR}/tgg-app.jar" --server.port=8080 \
  >> "${APP_DIR}/app.log" 2>&1 &
echo $! > "${APP_DIR}/run/tgg-bot.pid"
