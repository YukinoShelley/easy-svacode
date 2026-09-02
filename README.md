# easySVA（统一私有仓库）

easySVA（easy Surveillance Video Analytics）轻量化分布式 AI 视频分析系统。本仓库为**统一合并版**：将原分属 5 个 Gitee 仓库的源码合并为单一项目，并自带依赖包与一键搭建脚本，克隆后即可直接搭建整套环境。

## 一、仓库内容

| 目录 | 内容 | 来源 |
|---|---|---|
| `easySVA/` | 一键安装脚本原始版、数据库备份 `data_20250520.sql`、README | Gitee `andersonwu/easySVA` |
| `SVA-backend/` | Java 后端（Spring Boot 3 / RuoYi，端口 9114） | Gitee `andersonwu/SVA-backend` |
| `SVA-web/` | 前端（Vue） | Gitee `andersonwu/SVA-web` |
| `SVA-server/` | C++ AI 分析器（FFmpeg+OpenCV+ONNX YOLO） | Gitee `andersonwu/SVA-server` |
| `SVA-mediaServer/` | ZLMediaKit 流媒体服务 | Gitee `andersonwu/SVA-mediaServer` |
| `deps/Analyzer-lib.zip` | 依赖包（约 4.76GB：YOLO 模型 + ffmpeg/opencv/onnxruntime 编译依赖），**不入 git 历史**，见下方说明 | 夸克网盘（原 easySVA-lib） |
| `setup.sh` | **一键搭建脚本**（本仓库核心，见第二节） | 基于官方脚本改造 |
| `scripts/start.sh` | 运行期一键启停/状态脚本（nohup 方式） | 本仓库 |
| `docs/` | 操作文档：启动步骤、修改记录、测试流地址 | 本仓库 |

## 二、一键搭建（新机器，推荐全新 Ubuntu 22.04）

```bash
# 1. 获取仓库与依赖包
git clone <你的私有仓库地址>
# 将 deps/Analyzer-lib.zip（4.76GB）放入 deps/ 目录（可从夸克网盘 pan.quark.cn/s/b13f7c9baf9e 或移交介质获取）

# 2. 一键搭建（root 执行，安装约 30~60 分钟）
cd easySVA
sudo ./setup.sh cpu        # 无 NVIDIA GPU 用 CPU 版
# 或
sudo ./setup.sh gpu        # 有 NVIDIA GPU（驱动 ≥ 590.48）用 GPU 版
```

搭建过程自动完成：依赖包解压 → CUDA/ONNX Runtime（按版本）→ FFmpeg → OpenCV → curl → ZLMediaKit → 分析器 → MariaDB/Redis + 数据库导入（42 表）→ 后端构建部署 → 前端构建部署 → Nginx 配置 → rc.local 开机自启。

搭建完成后：

```
访问地址：http://<本机IP>/
账号密码：admin / admin123
数据库：easySVA（root / easySVA.EZ）
```

### 3. 文件位置清单（重要）

**需要你手动放置的输入文件**（只有这一个）：

| 文件 | 放到哪里 | 说明 |
|---|---|---|
| `Analyzer-lib.zip`（约 4.76GB） | 仓库内 **`deps/`** 目录 | 从夸克网盘或移交介质获取后放入即可，setup.sh 自动使用，**无需手动解压** |

**安装后各组件落位**（setup.sh 自动完成，供排查使用）：

| 路径 | 内容 |
|---|---|
| `/opt/easySVA-lib/` | 依赖包解压目录（由 deps/Analyzer-lib.zip 解压并改名而来） |
| `/opt/SVA/models/` | YOLO 模型（yolo11n / yolo26s / yolo26s_miner） |
| `/opt/SVA/backend/backend.jar` | 后端程序（手动启动：`cd /opt/SVA/backend && java -jar backend.jar`） |
| `/opt/SVA/mediaServer/` | ZLMediaKit 可执行文件 + config.ini |
| `/opt/SVA/server/Analyzer` | 分析器可执行文件 |
| `/opt/SVA/config.json` | 分析器配置（host 已自动写入本机 IP） |
| `/var/www/SVA-web/dist/` | 前端页面（Nginx root 指向这里） |
| `/var/www/SVA-web/upload/` | 上传文件（`alarm/` 告警媒体、`storage/` 流媒体） |
| `/etc/nginx/sites-enabled/default` | Nginx 站点配置（`/prod-api/`、`/websocket/` 反向代理） |
| `/etc/rc.local` | 开机自启（后端 / ZLM / 分析器） |
| MariaDB 数据库 `easySVA` | 业务数据库（42 表，root 密码 `easySVA.EZ`） |

## 三、使用流程

1. 设备管理 → 添加设备（类型 `DIRECT`，URL 填 RTSP/HLS 地址，如 `rtsp://127.0.0.1:8554/test` 或 CCTV 测试流见 `docs/Vstream.txt`）；
2. 点「启动监控」→ 视频预览可播放；
3. 布控管理 → 新建布控（选设备、算法、画布控区域）→ 启动；
4. 布控详情页/视频墙可播放算法画框输出流（h264+aac）。

## 四、组件与端口

| 组件 | 端口 |
|---|---|
| Nginx（前端页面 + 反向代理） | 80 |
| MariaDB / Redis | 3306 / 6379 |
| SVA-backend | 9114 |
| ZLMediaKit | 9992 HTTP / 9994 RTSP / 9995 RTMP |
| SVA-server 分析器 | 9991 admin / 9993 推理 |

## 五、运行期管理

```bash
sudo scripts/start.sh start|stop|restart|status   # 启停（nohup 方式，无开机自启时重启系统后手动执行）
# 开机自启由 setup.sh 配置的 /etc/rc.local 负责
```

## 六、已包含的必要修正（相对 Gitee 原版源码）

1. `SVA-backend`：新增 `POST /deployments/{id}/live-output` 接口（`DeploymentController.java`），修复布控详情页/视频墙报 `No static resource .../live-output` 500 的问题；
2. `SVA-backend`：`application.yml` 上传路径 `D:/ruoyi/uploadPath`（Windows 残留）→ `/var/www/SVA-web/upload`。

详细说明见 `docs/修复记录汇总.md`。

## 七、依赖包（deps/Analyzer-lib.zip）说明

- 大小约 4.76GB（分卷压缩包 `Analyzer-lib_zip.part1~3.rar` 解压得到，见 `docs/修复记录汇总.md` 第四节）；
- 内容：YOLO 模型（yolo11n/yolo26s/yolo26s_miner）、ffmpeg-6.1.4、opencv-4.13.0 + contrib、onnxruntime CPU/GPU、curl、nv-codec-headers、CUDA 安装包（GPU 版用）；
- **不入 git 历史**（体积超出 Gitee 免费仓库限制）：文件放在 `deps/` 目录供 setup.sh 使用，通过网盘/移交介质随仓库分发；如你的代码托管平台支持大文件 LFS（且空间足够），可执行 `git lfs track "deps/*.zip"` 后纳入管理。

## 八、原仓库地址（上游）

- https://gitee.com/andersonwu/easySVA
- https://gitee.com/andersonwu/SVA-backend
- https://gitee.com/andersonwu/SVA-web
- https://gitee.com/andersonwu/SVA-server
- https://gitee.com/andersonwu/SVA-mediaServer

## 九、目录文档索引

- `docs/启动步骤.md`：组件总览与手动启动/关闭步骤
- `docs/修复记录汇总.md`：源码修改对比、依赖包解压步骤、构建部署与验证
- `docs/Vstream.txt`：可用的 CCTV 测试视频流地址
