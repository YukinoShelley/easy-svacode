# 睡岗检测 · easySVA 原始项目接入文档（交接给角色3 · 分析器 C++）

> 本文件汇总三件事：**C++ 接入契约 + 需修改的原始文件清单 + 落地顺序**。
> 判定算法设计详见《算法规则.md》（同目录）；Python 原型在 `algo/`。
> 目标：把"YOLO-Pose 关键点 + hd 姿态规则 + 时序状态机"接进 easySVA SVA-server C++ 管线，
> 多人/单人通用、告警全链路复用、可 git 回滚。

---

## 0. 一句话架构（先看这个）

```
[RTSP/视频流] → Worker 解码(每帧)
  → Analyzer::handleVideoFrame → runAlgorithmTask → 【本次新增: pose 算法引擎】
       AlgorithmOnYolo 加载 yolo11n-pose → 输出 person框+17关键点 → DetectObject 带关键点
  → Scheduler::updateTemporalTracks(已存在, 天然多人: IoU匹配→trackId/speed/motionState/regionStates)
  → Worker 行为段: 对每个 detect 调 evaluateAtomicBehavior
       【本次改造: isSleepHit 判据 = 平台旧判据(静止+时长) 增强为 "静止+时长+pose低头hd"】
       → 命中 → detect.happen=true + behaviorType="sleep"
  → 【全链路复用, 零改动】上报/告警截图录像/WS推送/Java入库/前端展示
```

**核心思想：只改"判定依据"这一环，其余全复用。**
平台 Worker 行为段(Worker.cpp:726-800)已是 per-detect 流水线且天然多人——我们只换 isSleepHit 的判据来源。

---

## 1. C++ 接入契约（输入/输出逐层对齐）

### 1.1 输入侧（引擎层：与现有 YOLO 引擎差异）

| 环节 | 现有 on_yolo11n_80/on_yolo26n_80 | 睡岗 pose (on_yolo11n_pose) | 处理 |
|---|---|---|---|
| 输入尺寸 | 模型读(640×640) | 640×640 | 复用 |
| 预处理 | blobFromImage /255, 有 pad 差异 | **letterbox 灰边 114** | 沿用 Python 验证口径(灰边114)，勿用平台黑边0 |
| 输入节点 | images | images | 复用 |
| 推理 | Ort::Session.Run | 同 | 复用框架 |

### 1.2 输出侧（与现有检测差异最大）

| 环节 | 现有 | pose | 处理 |
|---|---|---|---|
| 输出张量 | (1,84,8400)/(1,85,8400) | **(1,56,8400)** | — |
| 通道含义 | 4框+1conf+80类 | **4框+1conf+17×3关键点(x,y,conf)** | 需新解码 |
| 解码器 | decodeDenseOutputWithNms / decodeDirectDetections | **无现成 → 新写 decodePoseOutput** | 新增 |
| 坐标空间 | 解码时映射回源图 | 关键点同样要映射回源图 | 写进解码器 |
| 多目标 | NMS 保留多框 | 单人取 top1 / 多人 NMS | 平台已有 per-detect 多人 |
| 结果载体 | DetectObject(框+类) | DetectObject + **关键点字段(需加)** | 见 1.3 |

### 1.3 DetectObject 扩展（Algorithm.h:43）

```cpp
// struct DetectObject 内新增(建议):
std::vector<cv::Point2f> keypoints;     // 17 点, 源图坐标(COCO17顺序)
std::vector<float>       keypointConf;  // 17 个置信度
// 可选派生量(判定层自算或引擎填):
float hd = 0.0f;                        // 头肩距离比 (肩中心Y-头Y)/框高, 低头→小/负
bool  poseOk = false;                   // 头部点+双肩可见且够格判定
```

### 1.4 判定层契约（sleep 触发 = 平台门槛 AND pose 姿态）

```
对齐平台 isSleepHit(BehaviorEvaluator.cpp:281) 的既有门槛:
  ✓ track 有效(trackId>=0, 已 Tracked≥2帧)      [平台现成]
  ✓ 静止  motionState!="moving"(speed≤maxSpeedPxPerSec)
  ✓ 区域  (若规则配 geometry) regionState->inRegion 且停留≥thresholdMs
  ✓ 时长  activeDurationMs ≥ thresholdMs        (默认15s, 布控可设)

替换/增强的点(用 pose 替代平台"宽高比躺姿"):
  旧: width/height ≥ 1.2(横躺)  → 只认躺睡
  新: poseOk && hd ≤ θ_hd 持续    → 认 坐姿低头/伏案趴桌/托头睡(工位睡岗)
      [单帧 hd 由 C++ 版 SleepStateTracker 状态机累计, 参数见下]
```

> 判定阈值参数建议映射：
> θ_hd=0.12、T_suspect=8s(真实部署；演示 2s)、静止阈值按机位(固定监控默认6~12px/s)，
> 详见《算法规则.md》§6 参数表 + §8.5.6 平台对齐实现。

### 1.5 输出侧（告警全复用, 零新增）

| 层 | 位置 | 行为 | 改动 |
|---|---|---|---|
| 触发标记 | Worker.cpp:787 | detect.happen=true, behaviorType="sleep", ruleId | 复用 |
| 事件上报 | Worker.cpp:839+ DetectFrameEvent | WS 推送 + 可选截图 | 复用 |
| 告警录像 | GenerateAlarmVideo | happen→录像 | 复用 |
| 后端入库 | Java HWaringController | sleep→SVA_SLEEP 告警 | 见 §2 |
| 前端 | warning 列表 | sleep→"睡岗告警" | 见 §2 |

---

## 2. 需修改的原始项目文件清单

> 行号以当前 /opt/SVA 原始代码为准(git 干净)。改动全部是"加"，可 git checkout 回滚。

### 🔴 SVA-server（C++ 分析器）—— 主战场

| 文件 | 位置 | 改动 | 量级 |
|---|---|---|---|
| `Analyzer/Core/Algorithm.h` | :43 struct DetectObject | 加 keypoints/keypointConf/hd/poseOk | ~8行 |
| `Analyzer/Core/AlgorithmOnYolo.cpp` | postprocess 区 | 新增 `decodePoseOutput`(56通道→17点+映射回源图), 按算法码分发 | ~80行 |
| `Analyzer/Core/AlgorithmOnYolo.h` | — | 声明新解码/pose成员 | ~5行 |
| `Analyzer/Core/Analyzer.cpp` | :32-43 resolveAlgorithm | 加 `on_yolo11n_pose` → mScheduler->on_pose_sleep | 3行 |
| `Analyzer/Core/Scheduler.h` | 成员区 | `Algorithm *on_pose_sleep = nullptr;` | 1行 |
| `Analyzer/Core/Scheduler.cpp` | :167-192 initAlgorithm + 析构:136-143 | new pose 引擎(加载 pose onnx) + delete | ~6行 |
| `Analyzer/Core/BehaviorEvaluator.cpp` | :281 isSleepHit + :604 sleep 分支 | 判据改造(见 §1.4); 或引 C++ 版状态机类 | ~60行 |
| `Analyzer/Core/CMakeLists.txt` | 源文件列表 | 若新增 .cpp/.h 则登记 | 1-2行 |

**不动的文件**：Worker.cpp、TemporalContext.cpp/.h、Server.cpp、GenerateAlarmVideo、AvPullStream、Control.h(除非扩展规则字段)。

### 🟠 SVA-backend（Java）—— 告警链路(白名单不改=静默丢弃!)

| 文件 | 位置 | 改动 |
|---|---|---|
| `ruoyi-admin/.../HWaringController.java` | :78-79 常量区 | 加 `SVA_SLEEP_ALARM_TYPE="SVA_SLEEP"` + `..._NAME="睡岗告警"` |
| 同上 | :1238 normalizeBehaviorType | 识别 `sleep`(放行白名单) |
| 同上 | :1252 resolveAlarmTypeMeta | `sleep` → SVA_SLEEP 映射分支 |

### 🟡 SVA-web（前端）

| 文件 | 位置 | 改动 |
|---|---|---|
| `views/warning/index.vue` | :673-689 getBehaviorTypeLabel | 加 `case 'sleep': return '睡岗告警'`(否则显示 `---`) |
| `views/deployment/add.vue` | :1239 | **已含 sleep 选项, 无需改** |

### 🟢 数据库(仅在需要"独立算法/模板"时)

| 对象 | 位置 | 说明 |
|---|---|---|
| `av_algorithm` | data_20250520.sql :72-74 后 | 插 `on_yolo11n_pose`(若走"算法下拉") |
| `deployment_business_event_template` | 模板区 | 插 `BEHAVIOR_SLEEP`(若走"事件编排模板") |

> 若判定仍复用 `sleep` 行为码(推荐), 后端 DB 模板可不插, 只加 Java 映射。

---

## 3. 落地顺序（四阶段, 每阶段可独立验证/回滚）

### Phase 1 — 链路打通(Java/DB/前端, 无 C++ 改动)
```
1. HWaringController 加 sleep 三处(常量/白名单/映射)   [风险: 白名单漏=静默丢弃]
2. warning/index.vue 加 sleep label
3. 重启后端 → 伪造 sleep 事件(用既有 on_yolo 布控+手工置 behaviorType)
4. 验收: 告警记录页出现"睡岗告警" + WS 推送
```

### Phase 2 — pose 引擎接入(C++)
```
5. DetectObject 加关键点字段
6. AlgorithmOnYolo 加 decodePoseOutput(56通道)
7. Scheduler.h/.cpp 注册 on_pose_sleep + initAlgorithm 加载
8. Analyzer.cpp resolveAlgorithm 加映射
9. 验收: 启动日志显示 pose 模型加载成功; resolveAlgorithm 命中新码不报"不支持的算法"
```

### Phase 3 — 姿态判定(C++)
```
10. C++ 版 SleepStateTracker(翻译 algo/sleep_state_machine.py: C1/C2/C3+gap_tol+GAP)
11. isSleepHit 增强: poseOk && hd≤θ 持续 ≥T_suspect(替代/叠加宽高比)
12. 参数接线: θ_hd/T_suspect/maxSpeed 与布控 rule JSON 字段映射
13. 验收: 布控配 pose 算法+sleep 规则 → 喂真实视频(趴桌睡/点头) → 前端出睡岗告警
```

### Phase 4 — 多人/区域/健壮性(真实场景)
```
14. 多人: 复用平台 per-detect; 确认低conf帧处理、track 确认期(New→Tracked≥2帧)
15. 区域: rule.geometryId 已有 → 布控按座位区域配; 区域外不判
16. 健壮性回归: 断供保护(目标丢失>GAP 清状态)、静止阈值按机位、云台转动暂停
17. 验收: 双设备/多人布控端到端 + 原功能回归(yolo 检测/告警/录像不受影响)
```

---

## 4. 交接给角色3 的注意点(TL;DR)

1. **判据语义差异**：平台旧 sleep=横躺(宽高比≥1.2); 我们 pose 版=工位伏案/坐姿低头睡。后者是业务真实场景, 前者平台测不了。
2. **静止阈值按机位**：固定监控默认6~12px/s; 手机/近拍验证素材需 30~50(平台 maxSpeedPxPerSec 是规则可配的, 不违背平台语义)。
3. **T_suspect**：真实部署 8s(实测抑制"趴低写字"误报, 3次→0); 演示要快 2s; 平台 thresholdMs 默认15s 是更保守口径。
4. **预处理坑**：pose 用 letterbox 灰边114, 勿沿用平台 DenseWithNms 黑边0(模型效果差异)。
5. **白名单是静默杀手**：Java 三处漏任何一处 → 全线"看起来通但无告警且不报错"。Phase1 先做它。
6. **新增算法注册点 4 处同步**：initAlgorithm / resolveAlgorithm / av_algorithm(若走算法表) / 前端算法下拉——漏一处即"不支持的算法"。
7. **截图/坐标**：pose 检测的框/关键点要映射回源图坐标再进 DetectObject; 放大(ROI)只在推理输入层做, 不改 Worker 传入 image(否则告警截图被裁坏)。
8. Python 原型对照物：`algo/sleep_state_machine.py`(状态机, C++直接翻译)、`algo/multi_pose_engine.py`(解码/追踪语义已对齐平台)、`algo/live_view.py`(可视化验证)。

---

## 5. 附: 参考文献/验证数据(答辩引用)

- 姿态边界/ROI/多人实测: 见《算法规则.md》§8.5~8.5.6(SCB 教室全景、ROI 放大、密集邻座、平台对齐)
- Python 原型演示视频: `algo/output/demo_*.mp4`(写字误报 T=2 vs T=8 对比、真睡检出)
- 44 段素材事件级评估: `algo/output/param_sweep.txt`(θ=0.12/T=2s: 检出15/22、误报4)

---

## 6. 实施状态记录（2026-09-04 · 角色2 在 SVA 上端到端落地）

> 本节为"代码已实现 + 实测通过"的进展快照, 供角色3 接手复核/收编。代码在工作区未提交, 基线快照 `baseline-2026-09-04`(tag) 可回退; DB 全量备份 `easySVA-backup/2026-09-04/`。

### 6.1 阶段进度（对照 §3 落地顺序）

| 阶段 | 状态 | 验证记录 |
|---|---|---|
| Phase 1 链路打通 | ✅ | WS 假事件 w_id=744 落库+推送; 告警列表显示"睡岗告警" |
| Phase 2 pose 引擎接入 | ✅ | 启动日志 3 模型载入; `on_yolo11n_pose` 布控 RUNNING 无"不支持的算法" |
| Phase 3 姿态判定 | ✅ | 端到端: 伏案正例 T=8 出告警; 写字反例 T=8 0 误报; T=2 误报复现 Python 结论 |
| Phase 4 多人/区域/回归 | 🔶 部分 | 双布控同流并发稳定; 原 yolo 检出告警受"设备媒体绑定"限制未通; 区域专项未做 |

### 6.2 已落地的代码改动（位置与要点）

**SVA-server（已编译部署到 /opt/SVA/server/Analyzer）**
- `Algorithm.h` DetectObject 新增 `keypoints(17)/keypointConf/hd/poseOk`（契约 §1.3）
- `AlgorithmOnYolo.h/.cpp`：新解码器枚举 `Pose`；按算法码选 profile；`runInference` 增加 letterbox 灰114 预处理(**Pose 通道序=RGB, swapRB 复核后已修, 见 §7.2**)；新增 `decodePoseOutput`（56 通道→4框+1conf+17×3关键点, NMS+源图映射+`hd/poseOk` 计算, 与 Python `multi_pose_engine.py` 逐行对齐）；**CPU 治理: `SetIntraOpNumThreads(4)/SetInterOpNumThreads(1)`**(主+GPU回退两处)
- `Control.h`：BehaviorRuleConfig 新增 `hdThreshold`(θ_hd, JSON: hdThreshold/thetaHd/theta_hd)
- `BehaviorEvaluator.cpp`：
  - C++ SleepPoseState 状态机（C1/C2/C3+gap_tol/GAP/滑窗, 移植自 `sleep_state_machine.py`）
  - `isSleepHit` 混合判据：pose 通道(静止+poseOk+hd≤θ 持续≥T) 未命中则旧横躺兜底(躺姿不回归)
  - **状态键 = control.code+rule.id+trackId**(R1: 一布控多 sleep 规则不串状态)
  - **30s 定期清理超时状态**(R2: lastFeedMs, 防 map 增长)
- `Scheduler.h/.cpp`：`on_pose_sleep` 注册/加载 `yolo11n-pose.onnx`/析构
- `Analyzer.cpp`：`resolveAlgorithm` 加 `on_yolo11n_pose`/`on_pose_sleep`

**SVA-backend（已编译部署到 /opt/SVA/backend/backend.jar）**
- `HWaringController`：SVA_SLEEP 常量/白名单/映射(Phase1)；**告警名统一为"睡岗告警", 不被 customEventName("sleep警告1") 覆盖**——插入(buildRuleWaring)与 update/end 回写两处均处理(Y1)

**前端** `warning/index.vue`：`sleep → 睡岗告警` label（diff 仅 +1 行, CRLF 已保持）

### 6.3 关键参数与语义（防角色3 踩坑）

- **T_suspect 双关**：规则 `thresholdMs` 在 pose 路径=连续低头时长 T_suspect; 在旧横躺兜底=区域内停留时长。演示 2000ms / 真实部署 8000ms（写字误报 3→0 的实测点）
- θ_hd 默认 0.12（rule.hdThreshold 可覆盖）; θ_desk=0.0; W=4s; p=0.5; gap_tol=0.5s; GAP=1.5s
- C1 段计时用"段起点"（`down_run = t - segStart`），勿写成累加 dt(×2 bug)
- 断供>GAP 清状态; 目标丢失平台 TemporalContext 30帧/4s 自清
- 判定输入全部在源图坐标; 放大(ROI)只做推理层, 不改 Worker 传入 image

### 6.4 测试素材/推流/布控操作记录

- 本地视频入平台: `/opt/SVA/push_loop.sh <mp4> live cam439081`（ffmpeg 循环推流; 大分辨率建议加 `-vf scale=720:1280 -threads 1`）
- 布控(演示): `controlL03AYjSpvdIR9m`(测试1.1), 算法 `on_yolo11n_pose`, sleep 规则 thresholdMs=8000, region 全幅
- 验证素材: 伏案正例 `video/真实睡觉（...）/从工作进入睡觉.mp4`(17.8s, 低头连续段≈11s); 写字反例 `video/认真工作/6.mp4`(视频5.12s, 低头连续段<1s); 横躺 `video/躺着/*.mp4`(仅 legacy)
- DB 配套: `av_algorithm` 注册 `on_yolo11n_pose`(id=19); `h_waring_type` 补 16 条类型字典(cam439081); 模型已放 `/opt/SVA/models/yolo11n-pose.onnx`
- CPU 实测(限线程后): 双布控并发≈7核(原17核); 单 pose 路≈0.4核; ffmpeg -threads1≈1核
- CPU 看门狗: `/tmp/sva_cpu_watchdog.sh`（档A 空转>40%@20s / 档B 失控>900%@30s 自动关停; dryrun 可观测）

### 6.5 审查修复与遗留（2026-09-04 代码审查输出）

已修: **R1** 状态机键加 ruleId; **R2** 30s 清理状态; **Y1** 告警名统一(两处); **Y4** 前端行尾噪音还原
遗留待决:
- **Y2** `thresholdMs` 双关语义建议加显式 `poseMode` 规则字段(避免配置歧义)
- **Y3** 解码阈值(conf 0.25/NMS 0.5)硬编码, 不随任务 score_threshold(平台既有局限)
- **平台遗留** `av_algorithm` 有 `on_yolo26s_miner` 但 C++ `resolveAlgorithm` 无映射 → 前端可选但启动报"不支持的算法"
- Phase4 未完成项: yolo 经典检出告警需设备媒体绑定; 同屏多人同时睡岗素材缺失; 区域专项未演示
- 分工口径: 上述 C++ 改动为**算法侧参考实现(已实测)**, 由角色3 复核收编/负责后续维护

### 6.6 服务运维速查

启动(全部 root): `cd /opt/SVA/{backend,mediaServer,server}` + `nohup java -jar backend.jar` / `./MediaServer` / `./Analyzer -f /opt/SVA/config.json`
停止: 先停布控(`POST /deployments/{id}/stop`, admin token), 再按 PID kill 三个进程与 ffmpeg; 详细见 `/opt/SVA/其他/命令行.md`

---

## 7. 复核结论与修复记录（2026-09-04 · 第二审计后）

### 7.1 复核判定（审计方：文档 §6 落地声明可采信）

代码真改、真编译部署、端到端出 33 条 SVA_SLEEP 告警（含 14.6s/14.8s 长持续行, 与 T=8 伏案语义吻合）。§6 无夸大；收编前需处理事项见 7.3/7.4。

### 7.2 本轮已修复（高优先 1/2 + 对齐项）

1. **【真 Bug 已修】Pose 分支通道序**：`AlgorithmOnYolo.cpp runInference` 原 `swapRB = (mDecoder==DenseWithNms)` → Pose 分支实为 **BGR 进模型**(与 Python 原型 RGB 相反, 注释却自称 rgb)。已改为 `swapRB = (mDecoder != DirectDetections)`(Pose/Dense=RGB, Direct yolo26s 保持平台 BGR)。
   - **RGB 修复后补证**(证据文件 `phase3补证-20260904.txt`)：
     - 伏案正例 T=8 → 照常出告警(修复后首条 w_id=776, dur 5.7s)
     - 写字反例 6.mp4(5.12s) T=8, 40s 窗口 → **0 新增**(最新仍 776)
     - 写字反例 T=2, 35s → 未复现误报(注: 历史 T=2 误报 w758-760 系旧 BGR 版产物; RGB 修复后判定更稳——比审计预期更乐观, 答辩可引用"通道序修复提升判定质量")
2. **deployment_task 主表陈旧已对齐**：`controlL03AYjSpvdIR9m` 主表 algorithm_code 由 on_yolo26n_80 → **on_yolo11n_pose**(与子表/日志一致, 复核/演示不再打架)。
3. **Algorithm.h BOM 还原**：编辑期误删首行 BOM 已补回, git diff 无首行噪音。
4. **素材措辞修正**：6.mp4 标注"视频 5.12s, 低头连续段<1s"(原"<1s"易误读为视频时长)。

### 7.3 遗留复核清单（角色3 收编前）

- **AvPushStream.cpp**(+98/−108 NVENC→libx264 回退) 与"睡岗 8 文件"拆成两个变更单元收编(媒体层独立)。
- **Y2 poseMode 显式字段未实现**：`thresholdMs` 双关(pose=低头时长 / legacy=区域内停留)仍靠语境区分——建议 rule 加 `poseMode` 布尔消除歧义。
- **ALARM 电平语义**：C++ 状态机 ALARM 期间每帧返回 true(Python 为边缘触发)——"睡中抬头 2~3s"是否误断建议用 WS 事件模式实测确认。
- **R1 键无 streamCode**：单布控多流时状态键(control+rule+track)理论碰撞——已复核下发规则 id 唯一, 风险低; 多流场景再加 streamCode。
- **平台遗留**：`av_algorithm` 的 `on_yolo26s_miner` 在 C++ resolveAlgorithm 无映射(前端可选但启动报"不支持的算法")。
- **Phase4 未完成**：yolo 经典检出告警需设备媒体绑定; 同屏多人睡岗素材缺失; 区域专项未演示。
- 分工口径: C++ 改动为**算法侧参考实现**, 角色3 复核收编/负责后续。

### 7.4 代码提交建议（未执行, 待批准）

按"睡岗 8 文件(+backend/front 各 1)"与"AvPushStream 媒体补丁"拆两个本地 commit, 再视需要 push; 当前改动均在基线 tag `baseline-2026-09-04` 之上, 可随时回退。

### 7.5 平台既有缺陷修复：`POST /deployments/{id}/live-output` 缺失（2026-09-04 补全）

- **来源**：团队文件《No static resource deployments-…-live-output.md》(同学侧已修)。对照自查：**本环境同样存在** —— 前端(源码+dist) 3 处调用(布控详情 add.vue / 列表上墙 index.vue / 视频墙 center-switch-panel.vue), 后端 `DeploymentController` 无对应路由, Spring 静态资源兜底返回 `code:500 No static resource ...`（已对真实布控 `controlL03AYjSpvdIR9m` 复现）。
- **根因**：前后端接口不同步(前端完成、后端漏实现), 与睡岗链路无关, 属平台既有缺陷; 触发面=布控"上墙/详情取算法画框输出流"。
- **修复**：`DeploymentController.java` 在 `stop` 与 `get` 之间新增 `@PostMapping("/{id}/live-output")` —— 复用既有 `DeploymentAnalyzerClient.buildAlgorithmStreamUrl(deviceId, deploymentId)`(返回 `ws://{zlm}:9992/analyzer/{deploymentId}.live.flv`), 同时返回 `algorithmStreamUrl` 与 `algorithm_stream_url` 两键(兼容前端两种取法); 布控不存在/无绑定→明确报错。
- **构建部署**：`mvn clean package -Dmaven.test.skip=true -o` → `cp ruoyi-admin/target/ruoyi-admin.jar /opt/SVA/backend/backend.jar`(旧包备份 `backend.jar.bak.liveoutput`) → 重启后端。
- **验证(本机)**: ① 真实布控 → `code:200` 返回正确 `ws://10.122.207.41:9992/analyzer/controlL03AYjSpvdIR9m.live.flv`(双键); ② 不存在 ID → `布控任务不存在`; ③ 布控列表回归正常。
- **回滚**：`cp /opt/SVA/backend/backend.jar.bak.liveoutput /opt/SVA/backend/backend.jar && 重启后端`。
