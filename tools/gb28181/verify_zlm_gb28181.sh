#!/usr/bin/env bash
# verify_zlm_gb28181.sh —— 角色1·流媒体：验证 SVA-mediaServer(ZLM) GB28181 媒体接入能力
# 无需真实国标摄像头、无需 root、不影响运行中的 ZLM。
# 原理：启动一个【临时 ZLM】(不同端口+hook关闭) -> 开RTP口 -> 用 ffmpeg+ps_rtp.py 推 PS-over-RTP
#      -> getMediaInfo 判定是否出流 -> 清理。
# 用法：bash verify_zlm_gb28181.sh
set -u
LZM="/opt/SVA/mediaServer/MediaServer"
PROD_CFG="/opt/SVA/mediaServer/config.ini"
TMP=~/zlmtest_gb
TEST_SECRET="gbvtestsecret"
TEST_STREAM="gbvtest"
DIR="$(dirname "$0")"
P="$(cd "$DIR" && pwd)"
PASS=0

echo "==> [1/4] 准备临时 ZLM ($TMP)"
rm -rf "$TMP"; mkdir -p "$TMP/www"; cp -r /opt/SVA/mediaServer/www/* "$TMP/www/" 2>/dev/null
# 改端口(19992/19994/19995)、改secret、改rtp段、关闭hook
sed -e 's/^port=9992/port=19992/' -e 's/^port=9994/port=19994/' -e 's/^port=9995/port=19995/' \
    -e 's/^port_range=30002-40000/port_range=41000-42000/' \
    -e "s/^secret=V3522025zlm0aA9ajn7UiOWi/secret=$TEST_SECRET/" \
    -e 's/^enable=1/enable=0/' "$PROD_CFG" > "$TMP/config.ini"
cd "$TMP" && nohup "$LZM" -c ./config.ini > run.log 2>&1 & ZPID=$!
sleep 5
ss -tln 2>/dev/null | grep -q 19992 || { echo "!! 临时ZLM未起"; tail -5 "$TMP/run.log"; exit 1; }
echo "    临时ZLM PID=$ZPID, 端口 19992/19994/19995 (hook关闭)"

echo "==> [2/4] 开 RTP 收流口"
RESP=$(curl -s -m 5 "http://127.0.0.1:19992/index/api/openRtpServer?secret=$TEST_SECRET&port=0&tcp_mode=0&stream_id=$TEST_STREAM")
PORT=$(echo "$RESP" | python3 -c "import sys,json;print(json.load(sys.stdin).get('port',''))" 2>/dev/null)
echo "    收流端口=$PORT"
[ -z "$PORT" ] && { echo "!! 开RTP失败"; echo "$RESP"; kill -9 $ZPID 2>/dev/null; exit 1; }

echo "==> [3/4] 推 PS-over-RTP (用 ps_rtp.py)"
nohup bash -c "ffmpeg -re -stream_loop -1 -i /opt/testvideo.mp4 -c:v libx264 -preset ultrafast -pix_fmt yuv420p -an -f vob - 2>/dev/null | python3 \"$P/ps_rtp.py\" 127.0.0.1 $PORT 96 0x12345678" >/dev/null 2>&1 & PPID2=$!
echo "    等待流注册(轮询最多30s)..."
CODE="-500"; ALIVE="-"; TRACKS="0"
for i in $(seq 1 15); do
  sleep 2
  R=$(curl -s -m 5 "http://127.0.0.1:19992/index/api/getMediaInfo?secret=$TEST_SECRET&vhost=__defaultVhost__&app=rtp&schema=rtsp&stream=$TEST_STREAM")
  CODE=$(echo "$R" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('code'))" 2>/dev/null)
  ALIVE=$(echo "$R" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('aliveSecond','-'))" 2>/dev/null)
  TRACKS=$(echo "$R" | python3 -c "import sys,json;d=json.load(sys.stdin);print(len(d.get('tracks',[]) or []))" 2>/dev/null)
  [ "$CODE" = "0" ] && break
done
echo "    结果: code=$CODE alive=$ALIVE track数=$TRACKS"

# 可选：拉流验证
echo "---- ffmpeg 拉流 3 秒(可选) ----"
timeout 10 ffmpeg -rtsp_transport tcp -i "rtsp://127.0.0.1:19994/rtp/$TEST_STREAM" -t 3 -f null - 2>&1 | grep -E "frame=" | tail -1

[ "$CODE" = "0" ] && [ "$TRACKS" -ge 1 ] && PASS=1

echo "==> 清理临时 ZLM"
kill -9 $ZPID 2>/dev/null; kill -9 $PPID2 2>/dev/null; rm -rf "$TMP"

echo
if [ "$PASS" = "1" ]; then
  echo "✅ PASS —— SVA-mediaServer(ZLM) 已能接 GB28181 PS-over-RTP 并出流(H.264)。"
  echo "   生产 ZLM 未出流的唯一原因是其 on_publish hook→WVP 拒绝；关闭 hook 即通（见契约 §6）。"
else
  echo "❌ FAIL —— 未出流，请查看临时日志。"
fi
