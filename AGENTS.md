# AGENTS.md

## Project

easySVA（easy Surveillance Video Analytics）轻量化分布式 AI 视频分析系统：Java 后端（Spring Boot 3 / RuoYi，端口 9114）+ Vue 前端 + C++ AI 分析器（FFmpeg + OpenCV + ONNX YOLO，端口 9991/9993）+ ZLMediaKit 流媒体（端口 9992/9994/9995），MariaDB/Redis/Nginx 支撑。

本仓库为**统一合并版**：原分属 5 个 Gitee 仓库（`easySVA` / `SVA-backend` / `SVA-web` / `SVA-server` / `SVA-mediaServer`）的源码合并为单一项目，自带依赖包与一键搭建脚本。**部署采用软链接模式：克隆目录即开发仓库**——运行位置（`/opt/SVA/*`、`/var/www/SVA-web/dist`）是指向本克隆目录内编译产物的软链接，改代码 → 重新编译 → 重启服务即生效。

## Architecture

- `setup.sh`：一键搭建入口（解包依赖 → 编译四个组件 → 建库导入 → 部署 → Nginx → rc.local → 软链接），已含多处可靠性加固（详见 `docs/修复记录汇总.md`）。
- `SVA-backend/`：Java 多模块（ruoyi-admin / ruoyi-system / ruoyi-framework …），核心代码在 `ruoyi-admin/src/main/java/com/ruoyi/`：
  - `web/controller/deployment/DeploymentController.java`：布控 CRUD、启停、`live-output`；
  - `web/service/deployment/DeploymentAnalyzerClient.java`：与 ZLM/分析器交互、流地址生成；
  - `waring/`：设备（`HDevice`）、告警、ZLM/SVA 服务器配置等。
- `SVA-web/`：Vue 2 + vue-cli 4 / webpack 4；`src/api/` 为接口层（`deployment.js`、`device.js`、`algorithm.js`…），`src/views/` 为页面。
- `SVA-server/`：C++17 分析器；`Analyzer/main.cpp` 入口，`Analyzer/Core/` 含 `Worker`（拉流/检测任务）、`Scheduler`、`AvPullStream`、`AvPushStream`、`Algorithm`（ONNX Runtime YOLO）、`Server`（HTTP 服务）等；模型在 `/opt/SVA/models`。
- `SVA-mediaServer/`：ZLMediaKit fork；构建产物 `release/linux/Release/MediaServer`，配置 `conf/config.ini`。
- `easySVA/`：上游启动器仓库（`install_source.sh` 原始脚本、`data_20250520.sql` 数据库全量备份 42 表）。
- `deps/Analyzer-lib.zip`：约 4.76GB 依赖包（YOLO 模型 + ffmpeg/opencv/onnxruntime 编译源），**不入 git**。
- `scripts/start.sh`：运行期一键启停；`scripts/devlink.sh`：拷贝部署 → 软链接模式切换。
- `docs/`：`启动步骤.md`、`修复记录汇总.md`、`Vstream.txt`（测试流）。

## Required Rules

- 保持补丁聚焦，不改动无关文件；不手动改动构建产物目录（`target/`、`node_modules/`、`dist/`、`build/`、`SVA-mediaServer/release/`、`deps/*.zip`，均已 gitignore）。
- **部署位置是软链接**：`/opt/SVA/backend/backend.jar`、`/opt/SVA/server/Analyzer`、`/opt/SVA/mediaServer/MediaServer`、`/var/www/SVA-web/dist` 指向本克隆。改代码必须在克隆内改源码并重新编译，**不要直接改 /opt 或 /var/www 下的文件**（下次编译即被覆盖）。
- 机器相关配置不进仓库：`/opt/SVA/config.json` 的 `host`（setup 时按本机 IP 生成）、`/etc/nginx/sites-enabled/default`、`/etc/rc.local`、数据库密码 `easySVA.EZ` 均为运行时产物。
- 相对 Gitee 上游已有 **4 处源码修复，不得回退**（详见 `docs/修复记录汇总.md` 第二节）：
  1. `SVA-backend` DeploymentController：新增 `POST /deployments/{id}/live-output`；
  2. `SVA-backend` application.yml：上传路径改为 `/var/www/SVA-web/upload`；
  3. `SVA-web` vue.config.js：`transpileDependencies: [/@opentiny/]`（webpack4 无法解析 @opentiny/quill2 语法，移除则前端构建失败）；
  4. `SVA-server` AvPushStream.cpp：h264_nvenc 打开失败自动回退 libx264（无 GPU 机器推流依赖此逻辑）。
- 后端 `live-output` 接口必须同时返回 `algorithmStreamUrl` 与 `algorithm_stream_url` 两个 key（前端两种取值兼容）。
- 前端构建必须 `NODE_OPTIONS=--openssl-legacy-provider npm run build:prod` 且必须通过。
- 分析器 CPU 构建用 `cmake .. -DSVA_ONNXRUNTIME_GPU=OFF`；保留 nvenc→x264 回退分支。
- `SVA-mediaServer` 与 `easySVA/` 是上游原样，保持最小差异。
- **不要**通过 `WITH_IPP=OFF` 等方式修改 OpenCV 构建以跳过下载（用户已明确要求与上游一致）：网络受限时 ippicv/ade 下载失败是 OpenCV 的"警告 + 禁用可选模块 + 继续"，属预期降级，不影响 easySVA 功能。
- 数据库：`easySVA` 库由 `easySVA/data_20250520.sql` 导入，校验标准为 **42 张表**；root 密码 `easySVA.EZ`。
- 模型（`/opt/SVA/models`，yolo11n/yolo26s/yolo26s_miner 等）来自依赖包，setup.sh 在缺失时自动恢复，勿手工删改。

## Deployment Chain

一键搭建链路（新机器）：

`git clone`（任意目录）→ 放入 `deps/Analyzer-lib.zip` → `sudo ./setup.sh cpu|gpu`
→ `/opt/easySVA-lib`（依赖解包）→ `/usr/local`（onnxruntime/ffmpeg/opencv）→ 编译 `SVA-server`、`SVA-mediaServer`、`SVA-backend`、`SVA-web` → 建库导入（42 表）→ Nginx 配置 → `/etc/rc.local` 开机自启 → 软链接 `/opt/SVA/*`。

运行态：`/etc/rc.local` 或 `scripts/start.sh` 拉起后端（9114）、ZLM（9992/9994/9995）、分析器（9993）；Nginx 提供页面与 `/prod-api/`、`/websocket/` 反代。

## Commands

（均从克隆目录执行）

```sh
# 一键搭建（root）
sudo ./setup.sh cpu          # 无 NVIDIA GPU（本机为 CPU 版）
sudo ./setup.sh gpu          # 有 NVIDIA GPU（驱动 ≥ 590.48）

# 运行期管理
sudo ./scripts/start.sh start|stop|restart|status

# 后端构建
cd SVA-backend && mvn clean package -Dmaven.test.skip=true

# 分析器构建（CPU）
cd SVA-server/build && cmake .. -DSVA_ONNXRUNTIME_GPU=OFF && make -j$(nproc)

# ZLMediaKit 构建
cd SVA-mediaServer/build && cmake -D CMAKE_BUILD_TYPE=Release -D ENABLE_WEBRTC=OFF \
  -D ENABLE_SRT=OFF -D ENABLE_TESTS=OFF -D ENABLE_MEM_DEBUG=OFF .. && make -j$(nproc)

# 前端构建
cd SVA-web && export NODE_OPTIONS=--openssl-legacy-provider && npm run build:prod
```

验证接口（服务运行后）：

```sh
# 登录取 token
curl -X POST http://127.0.0.1/prod-api/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}'

# 核心接口 live-output（需 Authorization: Bearer <token>）
curl -X POST http://127.0.0.1/prod-api/deployments/<deploymentId>/live-output \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}'

# 分析器健康（detectFrameSent 应随布控运行增长）
curl http://127.0.0.1:9993/api/health

# 画框输出流（ffprobe 应读出 h264 + aac）
/usr/local/bin/ffprobe -v error -i http://127.0.0.1:9992/analyzer/<deploymentId>.live.flv \
  -show_streams -of json

# 数据库
mysql -uroot -peasySVA.EZ easySVA
```

## Validation

- 后端接口改动：验证 登录 / 布控列表 / 布控详情 / `live-output`（200 且含两个 key）；不存在的 id 应返回"布控任务不存在"。
- 分析器改动：CPU 构建通过；布控运行后 `detectFrameSent` 持续增长、ZLM `getMediaList` 出现 `analyzer/<id>` 流、ffprobe 可读 h264/aac。
- 前端改动：`npm run build:prod` 必须通过；页面 200；改动页面涉及接口正常。
- ZLM 改动：构建通过；9992/9994/9995 监听；`addStreamProxy`/`getMediaList` 正常。
- 数据库/脚本改动：从零重跑一次 `setup.sh`（或至少校验 42 表 + 服务可拉起）。
- 完整链路（设备 DIRECT + CCTV 测试流 → 启动监控 → 新建布控 + 画布区域 → 启动 → live-output → 流可播）模板见 `docs/修复记录汇总.md` 验证一节。
- **总是报告测了什么**；构建成功 ≠ 端到端验证（需要设备/流源），未测部分要明确说明。

## Authoritative Documentation

- `README.md`：总览 / 安装 / 开发模式 / 修复清单 / 常见问题
- `docs/启动步骤.md`：启动 / 运维 / 排障
- `docs/修复记录汇总.md`：相对上游的修改明细、依赖包解压、验证结果
- `docs/Vstream.txt`：可用 CCTV 测试流
- `setup.sh` / `scripts/start.sh` / `scripts/devlink.sh`：部署与运行管理

详细或易变的信息放在上述文件中，本文件只保留给 Agent 的规则；如需子系统级专属说明，再在该子目录添加嵌套 `AGENTS.md`。
