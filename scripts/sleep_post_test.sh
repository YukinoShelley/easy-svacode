#!/usr/bin/env bash
# =====================================================================
# 睡岗检测(sleep_post)联调测试脚本 —— 分析器角色工作台工具
# 用法:
#   bash scripts/sleep_post_test.sh <视频文件或RTSP/HTTP流地址>
# 可选环境变量(阈值档位):
#   DURATION_MS=5000     规则 thresholdMs(持续低头时长)
#   PITCH_DEG=60         规则 headPitchThresholdDeg(俯角阈值)
#   DETECT_FPS=8         算法检测帧率
#   RUN_SECONDS=20       观察时长
#   ZLM_RTMP=rtmp://127.0.0.1:9995  ZLM RTMP 入口
#   ZLM_RTSP=rtsp://127.0.0.1:9994  ZLM RTSP 出口
#   ANALYZER=http://127.0.0.1:9993  分析器地址
# 前置:
#   1) /opt/SVA/models/yolo11n_pose_sleep.onnx 已就位,分析器已重启
#   2) 后端已按 docs/后端sleep_post映射需求单.md 补齐映射(否则本脚本验到事件层)
#   3) ZLMediaKit 与 ffmpeg 可用
# 产物:脚本自动建布控 sleep_post_test、运行 RUN_SECONDS 秒后输出结果并清理。
# =====================================================================
set -u

SRC="${1:-}"
[ -z "$SRC" ] && echo "用法: bash $0 <视频文件或流地址>" && exit 1
[ -f "$SRC" ] || [[ "$SRC" == rtmp://* || "$SRC" == rtsp://* || "$SRC" == http* ]] || { echo "输入不是存在的文件或流地址: $SRC"; exit 1; }

DURATION_MS="${DURATION_MS:-5000}"
PITCH_DEG="${PITCH_DEG:-60}"
DETECT_FPS="${DETECT_FPS:-8}"
RUN_SECONDS="${RUN_SECONDS:-20}"
ZLM_RTMP="${ZLM_RTMP:-rtmp://127.0.0.1:9995}"
ZLM_RTSP="${ZLM_RTSP:-rtsp://127.0.0.1:9994}"
ANALYZER="${ANALYZER:-http://127.0.0.1:9993}"
FFMPEG="$(command -v ffmpeg || echo /usr/local/ffmpeg/bin/ffmpeg)"

STREAM="sleep_post_test"
CODE="sleep_post_test"
TSPID=""
CONTROL_ADDED=0

cleanup() {
    # 取消布控
    [ "$CONTROL_ADDED" = "1" ] && curl -s -X POST "$ANALYZER/api/control/cancel" -d "{\"code\":\"$CODE\"}" >/dev/null 2>&1
    # 停测试推流
    [ -n "$TSPID" ] && kill "$TSPID" 2>/dev/null
}
trap cleanup EXIT

echo "== 1/4 推送测试流: $SRC → $ZLM_RTMP/$STREAM"
"$FFMPEG" -hide_banner -loglevel error -re -i "$SRC" -pix_fmt yuv420p -c:v libx264 -preset ultrafast -tune zerolatency -g 24 \
    -f flv "$ZLM_RTMP/live/$STREAM" &
TSPID=$!
sleep 3

echo "== 2/4 建立睡岗布控 (thresholdMs=${DURATION_MS}ms, pitch>${PITCH_DEG}°)"
BODY=$(cat <<JSON
{
  "code": "$CODE",
  "streamCode": "$STREAM", "streamApp": "live", "streamName": "$STREAM",
  "streamUrl": "$ZLM_RTSP/live/$STREAM",
  "algorithmCode": "on_yolo11n_pose_sleep",
  "objectCode": "person", "objectCodes": ["person"], "object_str": "person",
  "algorithmTasks": [{
    "algorithmCode": "on_yolo11n_pose_sleep", "objectCode": "person",
    "objectCodes": ["person"], "object_str": "person",
    "detectFps": $DETECT_FPS, "scoreThreshold": 0.5, "nmsThreshold": 0.5
  }],
  "regions": [{ "id": "region_1", "primary": true, "points": [0,0,1,0,1,1,0,1] }],
  "geometryConfig": {
    "regions": [{ "id": "region_1", "primary": true, "points": [0,0,1,0,1,1,0,1] }],
    "behaviorRules": [{
      "id": "sleep_rule_1", "name": "睡岗检测", "customEventName": "睡岗",
      "behaviorType": "sleep_post", "enabled": true,
      "geometryId": "region_1",
      "thresholdMs": $DURATION_MS, "headPitchThresholdDeg": $PITCH_DEG,
      "ruleObjectCode": "person"
    }]
  },
  "renderMode": "server_overlay", "pushStream": false,
  "saveImageEnabled": true, "saveVideoEnabled": true,
  "wsEventFps": 8, "minInterval": 60
}
JSON
)
RESP=$(curl -s -X POST "$ANALYZER/api/control/add" -d "$BODY")
echo "$RESP"
echo "$RESP" | grep -q '"code" : 1000' || { echo "!! 布控建立失败(检查模型是否就位/分析器日志)"; exit 1; }
CONTROL_ADDED=1

echo "== 3/4 观察 ${RUN_SECONDS}s,每 5s 输出帧计数与告警相关计数 =="
END=$((SECONDS + RUN_SECONDS))
while [ $SECONDS -lt $END ]; do
    H=$(curl -s "$ANALYZER/api/health")
    SENT=$(echo "$H" | grep -oE '"detectFrameSent" : [0-9]+' | grep -oE '[0-9]+')
    EVSENT=$(echo "$H" | grep -oE '"detectEventSent" : [0-9]+' | grep -oE '[0-9]+')
    ALARMS=$(grep -c "behavior_type\" : \"sleep_post\"" /opt/SVA/server/log.out 2>/dev/null || echo 0)
    echo "[$((END - SECONDS))s 剩余] detectFrameSent=$SENT detectEventSent=$EVSENT 上报sleep_post次数≈$ALARMS"
    sleep 5
done

echo "== 4/4 检查分析器日志中的睡岗命中/上报 =="
grep -E "sleep_post|睡岗" /opt/SVA/server/log.out | tail -10 || echo "(日志无 sleep_post 命中——检查视频内容或阈值档位)"
echo "完成。布控已自动取消,测试流已停止。"
echo "提示:告警落库需后端映射完成,见 docs/后端sleep_post映射需求单.md"
