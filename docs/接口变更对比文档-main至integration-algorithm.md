# 接口变更对比文档(main 最初版 → integration-algorithm)

> 基线:`dpp/main`(fe1b259,统一仓库原始版);现版:`integration-algorithm`(以 algorithm 分支为主干,2026-09-06)。
> 用途:①合并评审对照;②前端/测试/部署按新接口适配;③回归测试确认"哪些接口没变"。
> 原则声明:所有变更均为**新增/扩展**,核心链路接口形态不变。

## 一、未变更的既有接口(回归红线,勿按新语义改)

| 层 | 接口 | 说明 |
|---|---|---|
| 分析器 HTTP | `POST :9993/api/control/add`、`/api/control/cancel`、`GET /api/health`、`POST /api/controls`、`/api/control`、`/api/alarm/bind-media`、`GET /` | 路由与字段结构不变(main→现版 Server.cpp 零差异);仅新增算法/规则被正确消费 |
| 分析器告警上行 | `POST saveAlarmUrl(/waring/waring/addFromSvaSimple)`、WS detect.frame/detect.event → `/websocket/sva/noop`、`addFromSvaMediaCallback` | 通道不变 |
| 后端布控 | `/deployments` CRUD/start/stop/live-output(双 key) | 不变 |
| 设备(非 GB 路径) | `/waring/device/*`(list/add/update/remove/monitor start|stop|preview/live/direct) | 不变(新增字段兼容默认值) |
| 登录/权限/WS | `/login`、`/websocket/**` | 不变(新增 GB notify 免登录放行,见下) |

## 二、新增接口(后端,REST)

| 接口(前缀 /waring/device) | 方法 | 用途 | 鉴权 |
|---|---|---|---|
| `/gb/remote-channels` | GET | 从流媒体(ZLM gb API 或 WVP,待收口)拉国标远端通道 | 登录 |
| `/gb/import` | POST | 一键导入远端通道为 h_device(每通道一条,ape_id=channelId) | 登录 |
| `/gb/status/sync` | POST | 轮询同步设备上下线状态 | 登录 |
| `/gb/notify` | POST | 流媒体/WVP 上下线事件回调(更新 is_online) | 匿名放行(SecurityConfig) |
| `/live/gb/{apeId}` | GET | 国标设备播放地址(ws-flv,app=gb) | 登录 |

配套新增类:`Gb28181Channel`(域模型)、`Gb28181MediaClient`(ZLM gb API 客户端)、`GbDeviceSyncTask`(无参 `syncGbDeviceStatus` 供 sys_job 30s 调用,任务需手工配置)。

## 三、数据库变更(gb28181_backend_up.sql,已实测执行)

| 表 | 变更 |
|---|---|
| `h_device` | +`device_type`(RTSP/GB28181,默认 RTSP)、+`gb_device_id`、+`gb_platform_id` |
| `zlm_server` | +`gb28181_enabled`(默认 0)、+`gb_sip_port`(默认 5060) |
| `deployment_task_algorithm` | +`params_json`(算法自定义参数全链路透传,如睡岗阈值) |
| `av_algorithm` | +种子行 `on_yolopose_sleep`(睡岗检测(姿态关键点),sort=10) |
| 字典 | `sys_dict_type/data` +设备类型(RTSP/GB28181 等) |
| 回滚 | `gb28181_backend_down.sql` |

## 四、既有接口的行为扩展(后端)

| 接口/类 | 扩展点 |
|---|---|
| `HDeviceController/ServiceImpl` | 设备类型三态(DIRECT/PLATFORM/GB28181)+`device_type` 双向同步;GB 设备强校验 gb_device_id |
| `DeploymentAnalyzerClient`(→分析器 body) | 顶层新增只读 `sourceType`/`deviceType`(RTSP|GB28181);`isSpecialAlgorithm`(code 含 pose/`_sleep`/on_yolopose_sleep 允许空目标);`algorithmTasks[].paramsJson/params` 透传;streamUrl 拼接逻辑未改(仍 rtsp://zlm:9994/{app}/{apeId}) |
| `HWaringController`(告警映射) | +常量 `SVA_SLEEP`/`睡岗告警`;行为白名单 +`sleep`;`sleep→SVA_SLEEP` 映射;告警名固定"睡岗告警"不被 customEventName 覆盖 |

## 五、分析器侧契约(main → 现版)

| 项 | main 版 | 现版(新增) |
|---|---|---|
| HTTP 路由 | 无变化 | 无变化(见第一节) |
| 算法 code | on_yolo11n_80 / on_yolo26n_80(DB 另有 miner 无引擎) | +`on_yolo11n_pose`(规范)+`on_pose_sleep` 双认(引擎成员 on_pose_sleep);DB 种子 `on_yolopose_sleep` 由后端映射到 code 后经别名兼容 |
| 模型 | yolo11n/yolo26s.onnx | +`/opt/SVA/models/yolo11n-pose.onnx`(letterbox 灰边114/RGB/640,输出 1×56×8400) |
| 行为规则 | 13 类(含 sleep 横躺) | sleep 规则增强:关键点可用时按 hd 判据(规则字段 `hdThreshold` 默认 0.12,别名 thetaHd/theta_hd),不可用时回退原横躺判据;规则字段 +=`hdThreshold`;thresholdMs 在 pose 路径=连续低头时长(建议真实 8000/演示 2000) |
| 事件类型 | sleep(未被后端消费) | sleep→SVA_SLEEP/睡岗告警(现被消费,见四) |
| 启动健壮性 | 模型缺失即崩溃 | +try/catch:缺模型仅告警,算法按"不支持的算法"跳过 |

## 六、对外部组件(ZLM/WVP)的交互契约

- ZLM REST:开 RTP 收流 `openRtpServer(port=0|指定, stream_id=国标流ID, tcp_mode, ssrc)`;出流 app=`rtp`,实测形态 `rtp/{设备}_{通道}`(契约承诺面另有 `live/{gb_id}`,**形态待收口**);`getMediaInfo` 自证。
- ZLM gb API(`/index/api/gb/listChannels|play|bye`):后端客户端已按此编写,当前 ZLM fork 未实现 → **架构决策项**(改为 WVP 提供或扩展 ZLM,见 `docs/验收点3-自检与演示步骤.md` 三.2)。
- WVP(外部 SIP):5060 注册/保活/INVITE;模板 `tools/gb28181/application-dev.yml.template`;放行补丁 `docs/GB28181接入/WVP补丁/`(media.id 必须=ZLM mediaServerId)。
- 验证工具:`tools/gb28181/verify_zlm_gb28181.sh`(媒体收流自证,已 PASS)、`ps_rtp.py`、`minimal_device_sim.py`(REGISTER 骨架,非完整模拟器)。

## 七、建议前端适配点(角色5,未做)

1. 设备管理:设备类型标签(RTSP/GB28181)+"从流媒体同步国标设备"按钮(调 `/gb/remote-channels`+`/gb/import`);
2. 布控配置:算法可选"睡岗检测(姿态关键点)"(av_algorithm 驱动自动出现);sleep 规则参数(thresholdMs/hdThreshold)表单;
3. 告警:alarm_type_name="睡岗告警"(后端已固定,无需映射);设备在线状态展示(GB 上下线)。

## 八、测试对齐提示

- 手测:分析器见 `docs/分析器手测验收步骤.md`(已校准到现版日志/契约);后端升级按 `SVA-backend/doc/upgrade_gb28181/UPGRADE.md`(先 up.sql 后换 jar;sys_job 手工加);GB 链路见 `docs/验收点3-自检与演示步骤.md`。
