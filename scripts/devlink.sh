#!/bin/bash
# ============================================================
# easySVA 开发模式：软链接部署
# ------------------------------------------------------------
# 作用：把分散在 /opt 等位置的运行产物，改为指向本仓库克隆的
#       软链接。之后在克隆里改代码 → 重新编译 → 重启服务，
#       部署位置自动生效，无需手动拷贝。
#
# 用法：
#   sudo ./scripts/devlink.sh                # 使用本仓库路径
#   sudo ./scripts/devlink.sh /path/clone    # 指定克隆路径
#
# 恢复生产模式（复制而非链接）：重新执行 setup.sh，或手动
#   cp <克隆>/.../产物 /opt/SVA/...
#
# 注意：/opt/SVA/config.json 与各 config.ini 不含软链接
#       （内含本机 IP 等环境配置，保持独立，不要提交进仓库）
# ============================================================
set -u
CLONE="$(cd "$(dirname "$0")/.." && pwd)"
[ $# -ge 1 ] && CLONE="$(cd "$1" && pwd)"

[ -d "$CLONE/SVA-backend" ] || { echo "❌ 不是有效克隆目录：$CLONE"; exit 1; }
[ "$(id -u)" = "0" ] || { echo "请以 root 执行（需要写 /opt /var/www）"; exit 1; }

echo "=============================================="
echo " 创建开发模式软链接"
echo " 克隆目录：$CLONE"
echo "=============================================="

# 1. 后端 jar：/opt/SVA/backend/backend.jar -> 克隆内 mvn 产物
if [ -f "$CLONE/SVA-backend/ruoyi-admin/target/ruoyi-admin.jar" ]; then
  ln -sf "$CLONE/SVA-backend/ruoyi-admin/target/ruoyi-admin.jar" /opt/SVA/backend/backend.jar
  echo "✅ /opt/SVA/backend/backend.jar -> SVA-backend/ruoyi-admin/target/ruoyi-admin.jar"
  echo "   （改后端代码后：cd SVA-backend && mvn clean package -Dmaven.test.skip=true）"
else
  echo "⚠️  跳过后端：未找到 $CLONE/SVA-backend/ruoyi-admin/target/ruoyi-admin.jar（先编译）"
fi

# 2. 分析器：/opt/SVA/server/Analyzer -> 克隆内编译产物
if [ -f "$CLONE/SVA-server/build/Analyzer" ]; then
  ln -sf "$CLONE/SVA-server/build/Analyzer" /opt/SVA/server/Analyzer
  echo "✅ /opt/SVA/server/Analyzer -> SVA-server/build/Analyzer"
  echo "   （改分析器代码后：cd SVA-server/build && make -j\$(nproc)）"
else
  echo "⚠️  跳过分析器：未找到 $CLONE/SVA-server/build/Analyzer（先编译）"
fi

# 3. ZLMediaKit：/opt/SVA/mediaServer/MediaServer -> 克隆内编译产物
if [ -f "$CLONE/SVA-mediaServer/release/linux/Release/MediaServer" ]; then
  ln -sf "$CLONE/SVA-mediaServer/release/linux/Release/MediaServer" /opt/SVA/mediaServer/MediaServer
  echo "✅ /opt/SVA/mediaServer/MediaServer -> SVA-mediaServer/release/linux/Release/MediaServer"
  echo "   （改 ZLM 代码后：cd SVA-mediaServer/build && make -j\$(nproc)）"
else
  echo "⚠️  跳过 ZLM：未找到 $CLONE/SVA-mediaServer/release/linux/Release/MediaServer（先编译）"
fi

# 4. 前端：/var/www/SVA-web/dist -> 克隆内构建产物
if [ -f "$CLONE/SVA-web/dist/index.html" ]; then
  rm -rf /var/www/SVA-web/dist
  ln -s "$CLONE/SVA-web/dist" /var/www/SVA-web/dist
  # 克隆位于家目录时保证 nginx(www-data) 可穿越（Ubuntu 家目录默认 750，否则 404）
  case "$CLONE" in
    "$HOME"/* | "$HOME") chmod o+x "$HOME" 2>/dev/null || true ;;
  esac
  chmod o+x "$CLONE" 2>/dev/null || true
  echo "✅ /var/www/SVA-web/dist -> SVA-web/dist"
  echo "   （改前端代码后：cd SVA-web && npm run build:prod）"
else
  echo "⚠️  跳过前端：未找到 $CLONE/SVA-web/dist/index.html（先 npm run build:prod）"
fi

echo
echo "=============================================="
echo " 完成。开发流程：改代码 → 重新编译 → 重启服务"
echo " 重启服务：sudo scripts/start.sh restart"
echo " 恢复生产模式：sudo ./setup.sh 重装，或手动 cp 覆盖软链接"
echo "=============================================="
