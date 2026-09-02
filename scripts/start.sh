#!/bin/bash
# ============================================================
# easySVA 一条龙管理脚本（nohup 版）
# 用法:  sudo ./start.sh start    启动全部组件
#        sudo ./start.sh stop     停止全部组件
#        sudo ./start.sh restart  重启全部组件
#        sudo ./start.sh status   查看全部状态
# （不带参数默认 start）
#
# 说明：本机无开机自启，进程用 nohup 后台方式管理；
#       重启系统后需手动执行本脚本。MariaDB/Redis/Nginx 仍为系统服务。
# ============================================================
set -u

GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; NC='\033[0m'
OK="[${GREEN}OK${NC}]"; FAIL="[${RED}FAIL${NC}]"; INFO="[${YELLOW}..${NC}]"

# ---- 自动提权（jun 已配免密 sudo）----
if [ "$(id -u)" -ne 0 ]; then
  echo "需要 root 权限，自动用 sudo 重新执行..."
  exec sudo "$0" "$@"
fi

WEB_URL="http://$(hostname -I | awk '{print $1}')/"

# ---------------- 各组件：启动 / 停止 / 状态 ----------------
# 判断是否在运行：pgrep -f 匹配进程命令行

is_run()   { pgrep -f "$1" >/dev/null 2>&1; }
kill_pat() { pkill -f "$1" >/dev/null 2>&1; }

start_backend() {
  if is_run 'java -jar .*backend\.jar'; then echo -e "$OK  后端(9114) 已在运行"; return; fi
  cd /opt/SVA/backend && nohup java -jar backend.jar > run.log 2>&1 &
  echo -e "$INFO 后端(9114) 启动中...（Spring Boot 需 30~60 秒，日志 /opt/SVA/backend/run.log）"
}

start_zlm() {
  if is_run 'MediaServer -c .*config\.ini'; then echo -e "$OK  ZLMediaKit(9992) 已在运行"; return; fi
  cd /opt/SVA/mediaServer && nohup ./MediaServer -d -c /opt/SVA/mediaServer/config.ini > zlm.log 2>&1 &
  echo -e "$INFO ZLMediaKit(9992/9994/9995) 启动中..."
}

start_analyzer() {
  if is_run 'Analyzer -f .*config\.json'; then echo -e "$OK  分析器(9993) 已在运行"; return; fi
  cd /opt/SVA/server && nohup ./Analyzer -f /opt/SVA/config.json > log.out 2>&1 &
  echo -e "$INFO 分析器(9993) 启动中...（日志 /opt/SVA/server/log.out）"
}

# ===== 可选测试组件（虚拟摄像头）=====
# 未安装时自动跳过，不影响正常使用。可配置：
#   MEDIAMTX_DIR：mediamtx 所在目录（默认 /opt/sva-test）
#   TEST_VIDEO：  测试视频文件（默认 /usr/local/share/sintel.mp4）
MEDIAMTX_DIR="${MEDIAMTX_DIR:-/opt/sva-test}"
TEST_VIDEO="${TEST_VIDEO:-/usr/local/share/sintel.mp4}"

start_mediamtx() {
  if [ ! -x "$MEDIAMTX_DIR/mediamtx" ]; then
    echo -e "$INFO 跳过虚拟摄像头（未安装：$MEDIAMTX_DIR/mediamtx，可选组件）"
    return 1
  fi
  if is_run 'mediamtx'; then echo -e "$OK  虚拟摄像头(8554) 已在运行"; return 0; fi
  cd "$MEDIAMTX_DIR" && nohup ./mediamtx mediamtx.yml > mediamtx.log 2>&1 &
  echo -e "$INFO 虚拟摄像头(8554) 启动中..."
}

start_teststream() {
  if [ ! -f "$TEST_VIDEO" ]; then
    echo -e "$INFO 跳过测试推流（未找到测试视频：$TEST_VIDEO，可选组件）"
    return 1
  fi
  if is_run 'sintel\.mp4'; then echo -e "$OK  测试推流 已在运行"; return 0; fi
  FF=$(command -v ffmpeg || echo /usr/local/bin/ffmpeg)
  nohup "$FF" -re -stream_loop -1 -i "$TEST_VIDEO" \
    -c:v libx264 -preset ultrafast -tune zerolatency -c:a aac -listen 1 \
    -f rtsp -rtsp_transport tcp rtsp://127.0.0.1:8554/test > "$MEDIAMTX_DIR/teststream.log" 2>&1 &
  echo -e "$INFO 测试推流 启动中...（rtsp://127.0.0.1:8554/test）"
}

stop_backend()   { kill_pat 'java -jar .*backend\.jar' && echo -e "$OK  后端 已停止" || echo -e "$INFO 后端 本来就没运行"; }
stop_zlm()       { kill_pat 'MediaServer -c .*config\.ini' && echo -e "$OK  ZLMediaKit 已停止" || echo -e "$INFO ZLMediaKit 本来就没运行"; }
stop_analyzer()  { kill_pat 'Analyzer -f .*config\.json' && echo -e "$OK  分析器 已停止" || echo -e "$INFO 分析器 本来就没运行"; }
stop_mediamtx()  { kill_pat 'mediamtx' && echo -e "$OK  虚拟摄像头 已停止" || echo -e "$INFO 虚拟摄像头 本来就没运行"; }
stop_teststream(){ kill_pat 'sintel\.mp4' && echo -e "$OK  测试推流 已停止" || echo -e "$INFO 测试推流 本来就没运行"; }

# ---------------- 系统服务（MariaDB/Redis/Nginx） ----------------
start_sys() {   # $1=服务名
  if systemctl is-active --quiet "$1"; then
    echo -e "$OK  $1 已在运行"
  elif systemctl start "$1" 2>/dev/null; then
    echo -e "$OK  $1 已启动"
  else
    echo -e "$FAIL $1 启动失败（systemctl start $1）"
  fi
}

wait_port() {   # $1=端口 $2=名称 $3=超时秒
  local waited=0
  while [ "$waited" -lt "$3" ]; do
    if ss -tln 2>/dev/null | grep -q ":$1 "; then
      echo -e "$OK  $2 就绪 (端口 $1)"
      return 0
    fi
    sleep 2; waited=$((waited+2))
  done
  echo -e "$FAIL $2 等待超时 (端口 $1 未监听)"
  return 1
}

cmd_start() {
  echo "========== easySVA 一键启动（nohup 版） =========="
  echo -e "$INFO 第1步/7 数据库与缓存..."
  start_sys mariadb;      wait_port 3306 "MySQL"       20
  start_sys redis-server; wait_port 6379 "Redis"       10
  echo -e "$INFO 第2步/7 前端 Nginx..."
  start_sys nginx;        wait_port 80   "Nginx"       10
  echo -e "$INFO 第3步/7 后端 Java (需等待启动 30~60s)..."
  start_backend;          wait_port 9114 "后端"        90
  echo -e "$INFO 第4步/7 流媒体 ZLMediaKit..."
  start_zlm;              wait_port 9992 "ZLMediaKit"  20
  echo -e "$INFO 第5步/7 AI 分析器..."
  start_analyzer;         wait_port 9993 "分析器"      30
  echo -e "$INFO 第6步/7 可选测试组件（虚拟摄像头+推流，未安装自动跳过）..."
  if [ -x "$MEDIAMTX_DIR/mediamtx" ]; then
    start_mediamtx;         wait_port 8554 "虚拟摄像头"  15
    start_teststream
  else
    echo -e "$INFO 跳过（未安装 mediamtx，需要测试流可自行放入 $MEDIAMTX_DIR/）"
  fi
  echo "=================================================="
  echo -e "✅ 访问地址: ${GREEN}${WEB_URL}${NC}"
  echo -e "✅ 登录账号: admin / admin123"
  echo -e "✅ 虚拟摄像头源: rtsp://127.0.0.1:8554/test"
  echo -e "⚠️ 本机无开机自启（已对齐源码安装），重启后请重新执行本脚本"
  echo "=================================================="
}

cmd_stop() {
  echo "========== easySVA 一键停止 =========="
  stop_analyzer; stop_zlm; stop_backend; stop_mediamtx; stop_teststream
  echo -e "$INFO Nginx/MySQL/Redis 为系统服务，保持运行（如需停止：systemctl stop nginx mariadb redis-server）"
  echo "========== 停止完成 =========="
}

cmd_status() {
  echo "========== easySVA 组件状态 =========="
  for svc in mariadb redis-server nginx; do
    if systemctl is-active --quiet "$svc"; then echo -e "$OK  $svc"; else echo -e "$FAIL $svc"; fi
  done
  for item in "后端:java -jar .*backend\.jar" "ZLM:MediaServer -c .*config\.ini" "分析器:Analyzer -f .*config\.json" "虚拟摄像头:mediamtx" "测试推流:sintel\.mp4"; do
    name="${item%%:*}"; pat="${item#*:}"
    if is_run "$pat"; then echo -e "$OK  $name"; else echo -e "$FAIL $name"; fi
  done
  echo "---------- 端口监听 ----------"
  for p in 3306 6379 80 9114 9992 9993 8554; do
    if ss -tln 2>/dev/null | grep -q ":$p "; then echo -e "$OK  端口 $p 监听中"; else echo -e "$INFO 端口 $p 未监听"; fi
  done
}

check_all() {
  local bad=0
  for item in "mariadb:mariadb" "redis-server:redis-server" "nginx:nginx" "后端:java -jar .*backend\.jar" "ZLM:MediaServer -c .*config\.ini" "分析器:Analyzer -f .*config\.json"; do
    name="${item%%:*}"; pat="${item#*:}"
    if systemctl is-active --quiet "$name" 2>/dev/null; then :;
    elif is_run "$pat"; then :;
    else echo -e "$FAIL $name 未运行"; bad=1; fi
  done
  [ "$bad" = "0" ] && echo -e "$OK  核心组件全部在线" || echo -e "$FAIL 存在未运行组件，请检查上方日志"
}

cmd_menu() {
  while true; do
    echo
    echo "========== easySVA 管理菜单 =========="
    echo "  1) 一键启动全部组件"
    echo "  2) 一键停止全部组件"
    echo "  3) 查看运行状态"
    echo "  4) 重启全部组件"
    echo "  0) 退出"
    echo "======================================"
    read -rp "请选择操作 [0-4]: " choice
    case "$choice" in
      1) cmd_start ;;
      2) cmd_stop ;;
      3) cmd_status ;;
      4) cmd_stop; cmd_start ;;
      0) echo "再见！"; exit 0 ;;
      *) echo -e "$FAIL 无效选择，请输入 0-4" ;;
    esac
  done
}

case "${1:-start}" in
  start|run|启动)      cmd_start ;;
  stop|shutdown|停止)  cmd_stop ;;
  restart|重启)        cmd_stop; cmd_start ;;
  status|st|状态)      cmd_status ;;
  menu|菜单|交互)      cmd_menu ;;
  *) echo "用法: $0 [start|stop|restart|status|menu]"; exit 1 ;;
esac
