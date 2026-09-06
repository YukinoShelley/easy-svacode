# easySVA 后端升级交付说明(GB28181 + 睡岗预留)

日期:2026-09-03 | 模块:后端(sva-backend) | 方式:仅增量、向后兼容、可回滚

## 1. 做了什么

### 1.1 数据库(迁移 `gb28181_backend_up.sql`,回滚 `gb28181_backend_down.sql`)
- `h_device`:+`device_type`(RTSP/GB28181)、`gb_device_id`、`gb_platform_id` + 索引;存量行归 RTSP
- `zlm_server`:+`gb28181_enabled`、`gb_sip_port`(媒体组就绪后置 1)
- `deployment_task_algorithm`:+`params_json`(睡岗等算法自定义参数,后端透传)
- `av_algorithm`:+种子行 `on_yolopose_sleep`(睡岗检测元数据)
- `sys_dict_type/data`:+`sva_device_type` 设备类型字典(前端下拉用)
- (部署后执行)`sys_job`:+定时任务 `gbDeviceSyncTask.syncGbDeviceStatus`(每 30 秒同步国标上下线)

### 1.2 后端代码(均在 ruoyi-admin / ruoyi-system,见 git diff)
| 文件 | 变更 |
|---|---|
| `HDevice.java` / `HDeviceMapper.xml|java` | 新字段读写、按 device_type 过滤、`selectByGbDeviceId`、`updateOnlineStateByApeId` |
| `ZlmServer.java` / `ZlmServerMapper.xml` | 读取 gb28181_enabled/gb_sip_port |
| `Gb28181MediaClient.java`(新) | GB28181 媒体契约客户端(列出/点播/停止通道,未启用→友好降级) |
| `GbDeviceSyncTask.java`(新) | 国标上下线定时同步任务(Quartz 无参) |
| `HDeviceService(.java/Impl)` | 设备类型归一;GB 导入/远程列表/状态同步/GB 播放地址;启停监控与预览按类型分流 |
| `HDeviceController.java` | 新增:`/gb/remote-channels`、`/gb/import`、`/gb/status/sync`、`/live/gb/{apeId}`、`/gb/notify` |
| `SecurityConfig.java` | 放行 `/waring/device/gb/notify` |
| `DeploymentAnalyzerClient.java` | 布控载荷加 `sourceType/deviceType`;姿态类算法允许空目标;`params`/`paramsJson` 透传 |
| `DeploymentTaskAlgorithm(.java / Mapper.xml / ServiceImpl)` | 持久化与下发透传 `params_json` |

未改动:MediaServer/C++分析器/前端实现逻辑;原有 REST 与字段全部保留。

## 2. 契约文档(团队协作)
- `GB28181_媒体契约.md`:媒体组需实现的 ZLM 通道 REST/回调 + 验收清单
- `睡岗检测_后端预留契约.md`:AI/前端组需对齐的 code/载荷/告警约定

## 3. 部署/验证要点
1. 先执行 up SQL,再替换 jar 重启(或反之,jar 对缺列会启动失败,务必先建列)。
2. 置开关:`UPDATE zlm_server SET gb28181_enabled=1 WHERE id=1;`(媒体就绪后)
3. 冒烟:
   - `POST /prod-api/login`(admin/admin123)
   - `GET  /prod-api/waring/device/list?deviceType=GB28181`(返回空表,不报错)
   - `POST /prod-api/waring/device/gb/import`(未启用时应返回“媒体未启用GB28181…”友好提示,不 500)

## 4. 回滚(任选)
- 整体:从快照还原 `/opt/sva_backup_20260903_100503`(见其 README/restore 脚本)。
- 仅代码:git checkout 本仓库;或把 `deploy/backend.jar` 快照拷回。
- 仅 DB:执行 `gb28181_backend_down.sql`。
