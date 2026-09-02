# easySVA（统一仓库）

easySVA（easy Surveillance Video Analytics）轻量化分布式 AI 视频分析系统。本仓库为**统一合并版**：将原分属 5 个 Gitee 仓库的源码合并为单一项目，自带依赖包与一键搭建脚本，**克隆到任意目录即可直接搭建整套环境**（已从零完整实测通过）。

## 一、仓库内容

| 目录/文件 | 内容 | 来源 |
|---|---|---|
| `SVA-backend/` | Java 后端（Spring Boot 3 / RuoYi，端口 9114） | Gitee `andersonwu/SVA-backend` |
| `SVA-web/` | 前端（Vue） | Gitee `andersonwu/SVA-web` |
| `SVA-server/` | C++ AI 分析器（FFmpeg+OpenCV+ONNX YOLO） | Gitee `andersonwu/SVA-server` |
| `SVA-mediaServer/` | ZLMediaKit 流媒体服务 | Gitee `andersonwu/SVA-mediaServer` |
| `easySVA/` | 官方一键安装脚本原始版、数据库备份 `data_20250520.sql` | Gitee `andersonwu/easySVA` |
| `deps/Analyzer-lib.zip` | 依赖包（约 4.76GB），**不入 git**，见第七节 | 夸克网盘（原 easySVA-lib） |
| `setup.sh` | **一键搭建脚本**（本仓库核心，见第二节） | 基于官方脚本改造并加固 |
| `scripts/start.sh` | 运行期一键启停/状态脚本 | 本仓库 |
| `scripts/devlink.sh` | 手动切换到软链接开发模式（setup.sh 已内置） | 本仓库 |
| `docs/` | 启动步骤 / 修改记录 / 测试流地址 | 本仓库 |

## 二、一键搭建（新机器，推荐全新 Ubuntu 22.04）

```bash
# 1. 克隆仓库（任何目录均可，如 ~/easySVA）
git clone <你的仓库地址>
cd easySVA

# 2. 把依赖包放入 deps/（唯一需要手动放置的文件，见第七节）
#    把 Analyzer-lib.zip 放到 deps/ 目录

# 3. 一键搭建（root 执行；全程自动，实测约 15~30 分钟）
sudo ./setup.sh cpu        # 无 NVIDIA GPU → CPU 版（推荐）
# 或
sudo ./setup.sh gpu        # 有 NVIDIA GPU（驱动 ≥ 590.48）→ GPU 版
```

搭建过程自动完成：依赖包解压 → ONNX Runtime → 模型 → FFmpeg → OpenCV → ZLMediaKit → 分析器 → MariaDB/Redis + 数据库导入（42 表）→ 后端构建（含 Maven 国内镜像）→ 前端构建 → Nginx 配置 → rc.local 开机自启 → **软链接部署**（部署位置指向本克隆目录）。

搭建完成后：

```
访问地址：http://<本机IP>/
账号密码：admin / admin123
数据库：easySVA（root / easySVA.EZ）
```

启动服务：`sudo ./scripts/start.sh start`，或重启系统（rc.local 自动拉起）。

## 三、开发模式（软链接部署，默认即此模式）

**克隆目录就是开发仓库**——部署位置全部是软链接指向本克隆：

| 部署位置 | 指向 |
|---|---|
| `/opt/SVA/backend/backend.jar` | `本克隆/SVA-backend/ruoyi-admin/target/ruoyi-admin.jar` |
| `/opt/SVA/server/Analyzer` | `本克隆/SVA-server/build/Analyzer` |
| `/opt/SVA/mediaServer/MediaServer` | `本克隆/SVA-mediaServer/release/linux/Release/MediaServer` |
| `/var/www/SVA-web/dist` | `本克隆/SVA-web/dist` |

日常开发流程：**改代码 → 重新编译 → 重启服务 → 自动生效**（无需拷贝）：

```bash
# 后端
cd SVA-backend && mvn clean package -Dmaven.test.skip=true
# 分析器 / ZLM
cd SVA-server/build && make -j$(nproc)
cd SVA-mediaServer/build && make -j$(nproc)
# 前端
cd SVA-web && export NODE_OPTIONS=--openssl-legacy-provider && npm run build:prod
# 重启
sudo ./scripts/start.sh restart
```

⚠️ 注意：
- **克隆目录请勿删除/移动**（软链接会断；若移动，重跑 `sudo ./setup.sh cpu` 即恢复）；
- 克隆目录位于家目录时，setup.sh 已自动处理 nginx 访问权限（家目录加 o+x）；
- 若系统是旧的"拷贝部署"安装，可用 `sudo ./scripts/devlink.sh` 切换为软链接模式。

## 四、使用流程

1. 设备管理 → 添加设备（类型 `DIRECT`，URL 填 RTSP/HLS 地址；测试流见 `docs/Vstream.txt`）；
2. 点「启动监控」→ 视频预览可播放；
3. 布控管理 → 新建布控（选算法、画布控区域）→ 启动；
4. 布控详情页/视频墙可播放算法画框输出流（h264+aac）。

## 五、组件与端口

| 组件 | 端口 |
|---|---|
| Nginx（前端页面 + 反向代理） | 80 |
| MariaDB / Redis | 3306 / 6379 |
| SVA-backend | 9114 |
| ZLMediaKit | 9992 HTTP / 9994 RTSP / 9995 RTMP |
| SVA-server 分析器 | 9991 admin / 9993 推理 |

## 六、运行期管理

```bash
sudo ./scripts/start.sh start|stop|restart|status
# 开机自启由 /etc/rc.local 负责（重启系统自动拉起全部服务）
# 可选测试组件（虚拟摄像头 mediamtx + 测试推流）未安装时自动跳过，不影响使用
```

## 七、依赖包（deps/Analyzer-lib.zip）说明

- 约 4.76GB（由分卷压缩包 `Analyzer-lib_zip.part1~3.rar` 解压得到），内容：YOLO 模型、ffmpeg-6.1.4、opencv-4.13.0+contrib、onnxruntime CPU/GPU、CUDA 安装包等；
- **不入 git 历史**（超出平台单文件限制）：放入 `deps/` 供 setup.sh 使用，通过网盘/硬盘随仓库分发；
- 已下载过一次的机器重跑 setup.sh 不会重新下载（缓存保护自动生效）。

## 八、已包含的修复（相对 Gitee 上游）

**源码级修复（4 处，均已实测）：**

1. `SVA-backend/.../DeploymentController.java`：新增 `POST /deployments/{id}/live-output` 接口——修复布控详情页/视频墙报 `No static resource .../live-output` 500；
2. `SVA-backend/.../application.yml`：上传路径 `D:/ruoyi/uploadPath`（Windows 残留）→ `/var/www/SVA-web/upload`；
3. `SVA-web/vue.config.js`：`transpileDependencies: [/@opentiny/]`——修复前端构建失败（上游依赖升级后 webpack4 无法解析 quill2/@opentiny 语法）；
4. `SVA-server/Analyzer/Core/AvPushStream.cpp`：h264_nvenc 打开失败时自动回退软件 H.264——修复无 NVIDIA GPU 机器推流失败。

**setup.sh 加固（安装可靠性）：** 数据库初始化兼容免密/带密码、不自编译 curl（避免遮蔽系统库）、构建前干净解压、OpenCV 下载缓存保留、模型缺失自动从依赖包恢复、软链接部署、家目录权限自动处理、Maven 国内镜像、GPU/CPU 可选。

**网络说明：** OpenCV 编译需联网下载少量文件；若下载超时（如 ippicv），OpenCV 会自动禁用对应可选模块（IPP 等）继续构建，**不影响 easySVA 功能**；重跑 setup.sh 可复用已下载部分。

详细修改对比见 `docs/修复记录汇总.md`。

## 九、实测验证记录

从零完整安装（真实首装路径）已通过：解包 → 全量编译 → 数据库 42 表 → 部署 → 登录/设备/布控/算法（3 条）→ live-output 200 → 画框输出流 ffprobe 实测 h264 1920x1080 + aac ✅。

## 十、常见问题

| 问题 | 处理 |
|---|---|
| 页面打不开 | 检查 IP（DHCP 可能变化，用 `ip addr` 查看当前 IP）；`nginx -t` 确认配置 |
| OpenCV 下载卡住 | 网络问题（非脚本问题）：Ctrl-C 后重跑 setup.sh，已下载部分自动保留续用 |
| 后端起不来 | MySQL/Redis 是否运行；`tail -f /opt/SVA/backend/run.log` |
| 预览黑屏 | 设备「启动监控」是否成功、ZLM 9992 是否通 |
| 分析器无输出 | `curl http://127.0.0.1:9993/api/health` 看 `detectFrameSent` 是否增长 |
| 无 NVIDIA GPU 用 GPU 版 | 会失败；必须用 `cpu` 版 |

## 十一、原仓库地址（上游）

- https://gitee.com/andersonwu/easySVA
- https://gitee.com/andersonwu/SVA-backend
- https://gitee.com/andersonwu/SVA-web
- https://gitee.com/andersonwu/SVA-server
- https://gitee.com/andersonwu/SVA-mediaServer

## 十二、目录文档索引

- `docs/启动步骤.md`：安装后启动/关闭/运维
- `docs/修复记录汇总.md`：源码修改对比、依赖包解压、验证结果
- `docs/Vstream.txt`：可用的 CCTV 测试视频流地址
