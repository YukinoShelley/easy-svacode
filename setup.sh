#!/bin/bash
# ============================================================
# easySVA 统一仓库 · 一键搭建脚本
# ------------------------------------------------------------
# 适用系统：Ubuntu 22.04（推荐全新系统）
# 用法：
#   sudo ./setup.sh            # 交互式选择 GPU / CPU 版本
#   sudo ./setup.sh cpu        # 直接安装 CPU 版本
#   sudo ./setup.sh gpu        # 直接安装 GPU 版本（需 NVIDIA 驱动 ≥ 590.48）
#
# 原理：全部使用仓库内自带内容，无需联网下载源码、无需网盘：
#   - 源码：SVA-backend / SVA-web / SVA-server / SVA-mediaServer（仓库内）
#   - 依赖：deps/Analyzer-lib.zip（含 YOLO 模型与 ffmpeg/opencv/onnxruntime 等编译依赖）
#   - 数据库：easySVA/data_20250520.sql
# 安装时长：CPU 版约 30~60 分钟（取决于机器），GPU 版额外含 CUDA 安装。
# ============================================================

ROOT="$(cd "$(dirname "$0")" && pwd)"
DEPS_ZIP="$ROOT/deps/Analyzer-lib.zip"

# ---------------- 0. 前置检查 ----------------
if [ "$(id -u)" -ne 0 ]; then
  echo "请以 root 执行：sudo ./setup.sh [cpu|gpu]"
  exit 1
fi

if [ ! -f "$DEPS_ZIP" ]; then
  echo "❌ 缺少依赖包：$DEPS_ZIP"
  echo "   请将 Analyzer-lib.zip（约 4.76GB，可从夸克网盘或移交介质获取）放入 deps/ 目录后重试"
  exit 1
fi

for d in easySVA SVA-backend SVA-web SVA-server SVA-mediaServer; do
  [ -d "$ROOT/$d" ] || { echo "❌ 缺少源码目录：$ROOT/$d"; exit 1; }
done

# GPU/CPU 选择
GPU=""
if [ $# -ge 1 ]; then
  case "$1" in
    g|G|gpu|GPU) GPU="g" ;;
    c|C|cpu|CPU) GPU="c" ;;
    *) echo "用法: sudo ./setup.sh [cpu|gpu]"; exit 1 ;;
  esac
else
  echo "如果你的系统有 NVIDIA GPU 并且显卡驱动 ≥ 590.48，建议选择 GPU 版本，否则选择 CPU 版本。"
  read -p "G:编译GPU版本; C:编译CPU版本: " gpu_answer
  case "$gpu_answer" in
    g|G) GPU="g" ;;
    c|C) GPU="c" ;;
    *) echo "输入错误，请输入G或C"; exit 1 ;;
  esac
fi

echo "=============================================="
echo " easySVA 一键搭建开始（$([ "$GPU" = "g" ] && echo GPU || echo CPU) 版本）"
echo " 源码目录：$ROOT"
echo " 依赖包：$DEPS_ZIP"
echo " 安装过程可能需要 30 分钟以上，请勿关闭终端"
echo "=============================================="
sleep 2

# ---------------- 1. 解压依赖包到 /opt/easySVA-lib ----------------
apt update
apt install -y build-essential unzip

cd /opt
if [ -d /opt/easySVA-lib ]; then
  echo "✅ /opt/easySVA-lib 已存在，跳过解压"
else
  unzip -o "$DEPS_ZIP" -d /opt/
  # zip 内顶层目录为 Analyzer-lib/，统一改名为 easySVA-lib（脚本后续路径写死）
  if [ -d /opt/Analyzer-lib ] && [ ! -d /opt/easySVA-lib ]; then
    mv /opt/Analyzer-lib /opt/easySVA-lib
  fi
fi
[ -d /opt/easySVA-lib ] || { echo "❌ 依赖包解压失败（/opt/easySVA-lib 不存在）"; exit 1; }

# ---------------- 2. ONNX Runtime 与模型 ----------------
if [ "$GPU" = "g" ]; then
  echo "将安装 cuda13.1 耗时较长，请耐心等待"
  chmod +x /opt/easySVA-lib/cuda_13.1.2_590.48.01_linux.run
  /opt/easySVA-lib/cuda_13.1.2_590.48.01_linux.run --silent --toolkit --tmpdir /opt
  tar -zxvf /opt/easySVA-lib/onnxruntime-linux-x64-gpu_cuda13-1.26.0.tgz -C /usr/local/
  mv /usr/local/onnxruntime-linux-x64-gpu-1.26.0 /usr/local/onnxruntime
  echo 'export PATH="/usr/local/cuda/bin:$PATH"' >> /etc/profile
  echo 'export LD_LIBRARY_PATH="/usr/local/cuda/lib64:$LD_LIBRARY_PATH"' >> /etc/profile
  source /etc/profile
  nvcc -V
  apt -y install cudnn9-cuda-13 2>/dev/null || echo "⚠️ cudnn 安装失败请手动处理（可选）"
else
  tar -zxvf /opt/easySVA-lib/onnxruntime-linux-x64-1.26.0.tgz -C /usr/local/
  mv /usr/local/onnxruntime-linux-x64-1.26.0 /usr/local/onnxruntime
fi

mkdir -p /opt/SVA/tmp/trt_cache
mv /opt/easySVA-lib/models /opt/SVA/

# ---------------- 3. 基础依赖与 FFmpeg ----------------
apt install -y git cmake yasm libfaac-dev libmp3lame-dev libopus-dev libx264-dev \
  libx265-dev libtheora-dev libvorbis-dev libxvidcore-dev libxext-dev libxfixes-dev \
  pkg-config libgtk2.0-dev libssl-dev libevent-dev libjsoncpp-dev net-tools \
  tmux zip wget gnupg libjpeg-dev libpng16-16 libavcodec-dev libavformat-dev \
  libswscale-dev libgl1 libglib2.0-0

cd /opt/easySVA-lib
unzip -o nv-codec-headers.zip
cd nv-codec-headers && make && make install
cd /opt/easySVA-lib
tar xf ffmpeg-6.1.4.tar.xz && cd ffmpeg-6.1.4

if [ "$GPU" = "g" ]; then
  ./configure --prefix=/usr/local --enable-pic --enable-shared --enable-gpl \
    --enable-libmp3lame --enable-libopus --enable-libx264 --enable-libx265 \
    --enable-nonfree --enable-pthreads --enable-cuda --enable-cuvid \
    --enable-nvenc --enable-ffnvcodec \
    --extra-cflags=-I/usr/local/cuda/include --extra-ldflags=-L/usr/local/cuda/lib64
else
  ./configure --prefix=/usr/local --enable-pic --enable-shared --enable-gpl \
    --enable-libmp3lame --enable-libopus --enable-libx264 --enable-libx265 \
    --enable-nonfree --enable-pthreads
fi

make -j$(($(nproc)>6?6:$(nproc)))
make install
ldconfig

# ---------------- 4. OpenCV ----------------
cd /opt/easySVA-lib
unzip -o opencv-4.13.0.zip
unzip -o opencv_contrib-4.13.0.zip
mv -f opencv-4.13.0 opencv 2>/dev/null || true
mv -f opencv_contrib-4.13.0 opencv_contrib 2>/dev/null || true
cd /opt/easySVA-lib/opencv && mkdir -p build && cd build

if [ "$GPU" = "g" ]; then
  CUDA_ARCH_BIN=$(nvidia-smi --query-gpu=compute_cap --format=csv,noheader | sort -u | tr '\n' ' ' | sed 's/ $//')
  [ -n "$CUDA_ARCH_BIN" ] || { echo "❌ 未检测到 nvidia-smi，无法编译 GPU 版 OpenCV"; exit 1; }
  cmake -D CMAKE_BUILD_TYPE=RELEASE -D CMAKE_INSTALL_PREFIX=/usr/local \
    -D OPENCV_EXTRA_MODULES_PATH=../../opencv_contrib/modules \
    -D INSTALL_C_EXAMPLES=OFF -D INSTALL_PYTHON_EXAMPLES=OFF \
    -D BUILD_opencv_python3=OFF -D BUILD_opencv_python3_tests=OFF \
    -D BUILD_EXAMPLES=OFF -D WITH_TBB=ON -D WITH_CUDA=ON \
    -D CUDA_ARCH_BIN="$CUDA_ARCH_BIN" -D CUDA_ARCH_PTX="" \
    -D WITH_CUDNN=ON -D OPENCV_DNN_CUDA=ON \
    -D CUDNN_INCLUDE_DIR=/usr/include/x86_64-linux-gnu/ \
    -D CUDNN_LIBRARY=/usr/lib/x86_64-linux-gnu/libcudnn.so \
    -D CUDNN_LIBRARY_PATH=/usr/lib/x86_64-linux-gnu/ \
    -D WITH_CUBLAS=ON -D ENABLE_FAST_MATH=ON -D CUDA_FAST_MATH=ON \
    -D WITH_V4L=ON -D WITH_QT=ON -D WITH_OPENGL=ON \
    -D OPENCV_GENERATE_PKGCONFIG=ON -D OPENCV_PC_FILE_NAME=opencv4.pc \
    -D OPENCV_ENABLE_NONFREE=ON -D WITH_TENSORRT=ON -D TENSORRT_DIR=/usr/ \
    -D BUILD_TESTS=OFF -D BUILD_PERF_TESTS=OFF -D BUILD_TIFF=OFF \
    -D OPENCV_GENERATE_SETUPVARS=OFF ..
else
  cmake -D CMAKE_BUILD_TYPE=RELEASE -D CMAKE_INSTALL_PREFIX=/usr/local \
    -D OPENCV_EXTRA_MODULES_PATH=../../opencv_contrib/modules \
    -D INSTALL_C_EXAMPLES=OFF -D INSTALL_PYTHON_EXAMPLES=OFF \
    -D BUILD_opencv_python3=OFF -D BUILD_opencv_python3_tests=OFF \
    -D BUILD_EXAMPLES=OFF -D WITH_TBB=ON -D WITH_CUDA=OFF \
    -D WITH_CUDNN=OFF -D OPENCV_DNN_CUDA=OFF -D WITH_TENSORRT=OFF \
    -D WITH_V4L=ON -D WITH_QT=ON -D WITH_OPENGL=ON \
    -D OPENCV_GENERATE_PKGCONFIG=ON -D OPENCV_PC_FILE_NAME=opencv4.pc \
    -D OPENCV_ENABLE_NONFREE=ON -D BUILD_TESTS=OFF -D BUILD_PERF_TESTS=OFF \
    -D BUILD_TIFF=OFF -D OPENCV_GENERATE_SETUPVARS=OFF ..
fi

make -j$(($(nproc)>6?6:$(nproc)))
make install

# ---------------- 5. curl 与 MediaServer ----------------
cd /opt/easySVA-lib
unzip -o curl-7.83.0.zip
cd curl-7.83.0
./configure --with-openssl --enable-http3 --enable-threaded-resolver --enable-versioned-symbols
make -j$(($(nproc)>6?6:$(nproc)))
make install

cd "$ROOT/SVA-mediaServer"
mkdir -p build && cd build
cmake -D CMAKE_BUILD_TYPE=Release -D ENABLE_WEBRTC=OFF -D ENABLE_SRT=OFF \
  -D ENABLE_TESTS=OFF -D ENABLE_MEM_DEBUG=OFF ..
make -j$(($(nproc)>6?6:$(nproc)))
cp "$ROOT/SVA-mediaServer/conf/config.ini" "$ROOT/SVA-mediaServer/release/linux/Release/"

# ---------------- 6. Analyzer ----------------
cd "$ROOT/SVA-server"
mkdir -p build && cd build
if [ "$GPU" = "g" ]; then
  cmake ..
else
  cmake .. -DSVA_ONNXRUNTIME_GPU=OFF
fi
make -j$(($(nproc)>6?6:$(nproc)))

# 分析器配置：复制并写入本机 IP
sed "s|\"host\": \"10.129.52.114\"|\"host\": \"$(hostname -I | awk '{print $1}')\"|" \
  "$ROOT/SVA-server/config.json" > /opt/SVA/config.json

# ---------------- 7. 数据库（MariaDB + Redis） ----------------
apt install -y redis-server mariadb-server
systemctl enable mariadb || true
/etc/init.d/mariadb start || service mariadb start || true

mysql <<EOF
ALTER USER 'root'@'localhost' IDENTIFIED BY 'easySVA.EZ';
DELETE FROM mysql.user WHERE User='';
DROP DATABASE IF EXISTS test;
DELETE FROM mysql.db WHERE Db='test' OR Db='test_%';
FLUSH PRIVILEGES;
create database easySVA default character set utf8mb4 collate utf8mb4_unicode_ci;
EOF

mysql -uroot -peasySVA.EZ easySVA < "$ROOT/easySVA/data_20250520.sql"
TABLES=$(mysql -uroot -peasySVA.EZ easySVA -N -e \
  "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='easySVA';")
[ "$TABLES" = "42" ] || { echo "❌ 数据库导入异常：仅 $TABLES 张表"; exit 1; }
echo "✅ 数据库导入完成（42 张表）"

# ---------------- 8. 后端构建与部署 ----------------
apt install -y openjdk-17-jdk maven
cd "$ROOT/SVA-backend"
mvn clean package -Dmaven.test.skip=true
mkdir -p /opt/SVA/backend
cp "$ROOT/SVA-backend/ruoyi-admin/target/ruoyi-admin.jar" /opt/SVA/backend/backend.jar

# ---------------- 9. 前端构建与部署 ----------------
apt install -y nginx-full
if ! command -v node >/dev/null 2>&1; then
  curl -sL https://deb.nodesource.com/setup_22.x | bash -
  apt install -y nodejs
fi
cd "$ROOT/SVA-web"
npm config set registry https://registry.npmmirror.com/
npm install
export NODE_OPTIONS=--openssl-legacy-provider
npm run build:prod
mkdir -p /var/www/SVA-web
rm -rf /var/www/SVA-web/dist
cp -r "$ROOT/SVA-web/dist" /var/www/SVA-web/
mkdir -p /var/www/SVA-web/upload/alarm /var/www/SVA-web/upload/storage

# ---------------- 10. Nginx 配置 ----------------
cat >/etc/nginx/sites-enabled/default <<'EOF'
upstream websocket_backend {
    server 127.0.0.1:9114;
}

server {
        listen 80 default_server;
        listen [::]:80 default_server;

        root /var/www/SVA-web/dist/;

        index index.html index.htm index.nginx-debian.html;

        server_name _;

        location / {
                try_files $uri $uri/ =404;
        }

        location /alarm/ {
               alias /var/www/SVA-web/upload/alarm/;
        }

        location /zlm/ {
               alias /var/www/SVA-web/upload/storage/;
        }

        location  /prod-api/ {
            proxy_pass  http://127.0.0.1:9114/;
            proxy_set_header Host $http_host;
        }

        location /websocket/ {
            proxy_pass http://websocket_backend;
            proxy_http_version 1.1;
            proxy_set_header Upgrade $http_upgrade;
            proxy_set_header Connection "upgrade";
            proxy_set_header Host $host;
            proxy_read_timeout 600s;
        }
}
EOF
nginx -t && systemctl restart nginx

# ---------------- 11. 开机自启（rc.local） ----------------
mkdir -p /opt/SVA/mediaServer /opt/SVA/server
cp "$ROOT/SVA-mediaServer/release/linux/Release/"* /opt/SVA/mediaServer/ -r
cp "$ROOT/SVA-server/build/Analyzer" /opt/SVA/server/Analyzer

cat <<EOF >/etc/rc.local
#!/bin/sh -e
sleep 3
cd /opt/SVA/backend/ && java -jar backend.jar &>log.out&
sleep 2
cd /opt/SVA/mediaServer/ && ./MediaServer -d &
sleep 2
cd /opt/SVA/server/ && ./Analyzer -f /opt/SVA/config.json &>log.out&
exit 0
EOF
chmod +x /etc/rc.local

# ---------------- 12. 完成 ----------------
IP=$(hostname -I | awk '{print $1}')
echo "=============================================="
echo "✅ easySVA 搭建完成"
echo "   访问地址：http://$IP/"
echo "   账号密码：admin / admin123"
echo "   数据库：easySVA（root / easySVA.EZ）"
echo "   开机自启：已配置 rc.local（重启后自动启动全部服务）"
echo "   手动管理：可执行本仓库 scripts/start.sh start|stop|status"
echo "=============================================="
