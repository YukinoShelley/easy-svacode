#!/usr/bin/env bash
# =====================================================================
# 睡岗模型一键接入脚本(预留接口)——AI 角色把模型交过来后一条命令完成接入
# 用法:
#   sudo bash scripts/install_pose_model.sh <模型源路径>
#     <模型源路径>: 1) yolo11n-pose.onnx 文件路径
#                   2) 目录(自动在其中找 yolo11n-pose.onnx 或 yolo11n_pose_sleep.onnx)
#                   3) 省略 → 仅做"检查 + 重启 + 验证"(适用于文件已人工放好)
# 动作:校验 → 复制到 /opt/SVA/models/yolo11n-pose.onnx → 重启分析器 →
#       验证日志出现 decoder=dense_pose_with_nms preprocess=letterbox_gray114 且无"加载失败"
# =====================================================================
set -u

SRC="${1:-}"
DEST="/opt/SVA/models/yolo11n-pose.onnx"

if [ -n "$SRC" ]; then
    if [ -d "$SRC" ]; then
        for cand in yolo11n-pose.onnx yolo11n_pose_sleep.onnx; do
            [ -f "$SRC/$cand" ] && SRC="$SRC/$cand" && break
        done
    fi
    [ -f "$SRC" ] || { echo "!! 找不到模型文件: $SRC"; exit 1; }
    echo "== 校验文件 =="
    head -c 4 "$SRC" | od -An -tx1 | grep -q "8b 0c 00 00\|onnx" || true # ONNX magic 不强校验,交由分析器加载
    cp -f "$SRC" "$DEST" && chmod 644 "$DEST" && chown root:root "$DEST" 2>/dev/null
    echo "== 已复制到 $DEST =="
fi

[ -f "$DEST" ] || { echo "!! $DEST 不存在;请提供模型源路径或先人工放置"; exit 1; }
ls -l "$DEST"

echo "== 重启分析器 =="
# 用精确进程名结束旧实例(避免 -f 模式误伤本脚本所在进程组),setsid 脱离当前进程组
pkill -x Analyzer 2>/dev/null; sleep 2
cd /opt/SVA/server && setsid nohup ./Analyzer -f /opt/SVA/config.json > /opt/SVA/server/log.out 2>&1 < /dev/null &
disown
# 等待健康(最多 15s)
for i in $(seq 1 15); do
    sleep 1
    curl -s -o /dev/null -m 2 http://127.0.0.1:9993/api/health && break
done

echo "== 验证 =="
curl -s -o /dev/null -w "health HTTP %{http_code}\n" http://127.0.0.1:9993/api/health
grep -E "dense_pose_with_nms|睡岗" /opt/SVA/server/log.out | tail -3
if grep -q "preprocess=letterbox_gray114" /opt/SVA/server/log.out && ! grep -q "睡岗模型加载失败" /opt/SVA/server/log.out; then
    echo "✅ 睡岗模型接入成功,可执行睡岗布控联调(见 docs/分析器手测验收步骤.md C组)"
else
    echo "⚠️ 模型未成功加载,请查看 /opt/SVA/server/log.out 或检查模型文件(需 1×56×N、640 letterbox 导出的 YOLO-Pose)"
    exit 2
fi
