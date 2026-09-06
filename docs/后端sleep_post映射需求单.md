# 后端角色需求单:睡岗告警(sleep_post)落库映射

> 提出:分析器开发(角色 3) · 日期:2026-09-03
> 目的:分析器已能产生 `behaviorType="sleep_post"`(睡岗)事件,但后端当前会**直接丢弃**该类型,睡岗告警无法落库展示。请后端角色按本单改动。
> 前置阅读:docs/分析器睡岗集成-修改说明与接口参照.md(第三节接口契约)

## 一、问题现象

分析器规则事件链路(WS detect.event → `HWaringController.consumeSvaDetectEvent` → `upsertRuleDetectEvent`)按 `normalizeBehaviorType` / `resolveAlarmTypeMeta` 把行为类型映射为告警类型;现有白名单 13 种(**cross_line/enter_region/exit_region/dwell/low_speed/loitering/absence/count_threshold/occupancy/direction_move/direction_reverse/relation_***),**没有 `sleep_post`,连已有的 `sleep`(躺卧)也没有** → 睡岗事件的 start 消息被当作未知类型处理、不落 h_waring。

## 二、需要你改的点(分析器侧调研结论,行号以当前代码为准)

1. `SVA-backend/.../waring/controller/HWaringController.java`
   - `normalizeBehaviorType`(约 1238-1250 行):白名单增补 `sleep_post`(建议顺带补 `sleep`,两者同为缺口,一次改完);
   - 告警类型常量区(约 66-93 行):新增常量,如 `public static final String SVA_SLEEP_POST = "SVA_SLEEP_POST";`(如顺带补 sleep 则加 `SVA_SLEEP`);
   - `resolveAlarmTypeMeta`(约 1252-1296 行):新增 `sleep_post` → `SVA_SLEEP_POST` 的分支;`sleep` → `SVA_SLEEP`;
   - `buildRuleWaring`(约 708-765 行):确认 `alarm_type_name` 取 `customEventName`(分析器已填"睡岗")的兜底链路对新类型同样生效(与现有 SVA_DWELL 等一致即可)。
2. 去抖/生命周期:沿用现有 `selectLatestSvaRuleWaringForInterval`(按 control_code+behavior_type+rule_id)机制,**无需新逻辑**。
3. 告警等级:建议与现有行为告警一致(默认 level "3"/一般,可在字典/常量区统一)。
4. (可选)前端字典:告警类型展示名建议"睡岗"(前端告警表格按 alarm_type_name 展示,通常无需额外代码,若页面有类型映射表请同步)。

## 三、事件字段样例(联调/自测时后端可直接打印验证)

分析器 detect.event 中与本类型相关字段(JSON):

```json
{
  "type": "detect.event",
  "eventState": "start",
  "controlCode": "布控编号",
  "streamCode": "设备编码",
  "behaviorType": "sleep_post",
  "customEventName": "睡岗",
  "ruleId": "sleep_rule_1",
  "regionId": "region_1",
  "regionName": "主区域",
  "trackId": 3,
  "timestampMs": 1752...
}
```

## 四、配套登记(后端顺手做)

`av_algorithm` 表插入算法字典行,前端布控才能选到睡岗算法:

```sql
INSERT INTO `av_algorithm` (`sort`,`code`,`name`,`api_url`,`object_count`,`object_str`,`remark`,`state`,`create_time`,`update_time`)
VALUES (3,'on_yolo11n_pose_sleep','睡岗检测(yolo11n-pose)','',1,'person','睡岗检测增量:持续低头超过阈值告警',0,NOW(),NOW());
```

## 五、验收建议

1. 无模型也可先验映射:向后端 WS noop 通道投递一条上面样例的 detect.event(start)→ 库中应新增一条 `alarm_type=SVA_SLEEP_POST`、`alarm_type_name=睡岗` 的 h_waring 记录(去抖逻辑按现有间隔生效);
2. 模型到位后:跑 `scripts/sleep_post_test.sh` 真实触发,核对告警截图/录像回写(`addFromSvaMediaCallback`);
3. 回归:原有 13 种行为告警(如 dwell)不受影响。

## 六、依赖链提醒

- 睡岗告警要"告警截图+录像"闭环,还依赖分析器端模型文件 `yolo11n_pose_sleep.onnx` 就位(AI 角色产出,我负责接入);
- 前端布控页出现"睡岗检测"选项依赖本单第 4 节 av_algorithm 行 + 前端角色页面改动。

---

## 七、现状更新(2026-09-06,对接各成员分支后)

- **dpp/backend 分支**:HWaringController 未改动,`sleep`/`sleep_post` 均无映射(与本文档第二节要求一致,仍待实施);其 up.sql 已注册 av_algorithm 种子 `on_yolopose_sleep`,分析器已按别名兼容。
- **dpp/algorithm 分支**:附带一个 HWaringController 补丁(`sleep`→`SVA_SLEEP`/`睡岗告警`,白名单放行 sleep、告警名防覆盖)。
- **建议最终方案(最小改动、两处兼容)**:把 algorithm 分支补丁同款逻辑扩为同时放行 `sleep_post`——即新增 `"sleep_post"` 到 normalize 白名单并映射到同一组常量 `SVA_SLEEP`/`SVA_SLEEP_ALARM_TYPE_NAME="睡岗告警"`(alarm_type 统一 SVA_SLEEP,前端/统计无需新增类型)。分析器以 `sleep_post` 事件上报,旧 `sleep` 规则(横躺)事件也可入库。
- 若坚持独立类型,按第二节 SVA_SLEEP_POST 方案实施亦可;分析器侧两者均可接受(只认 behaviorType 字符串)。

## 八、最终裁定(2026-09-06):以算法部分接口为主,本需求单关闭

分析器睡岗规则对外事件类型已统一为 `sleep`(不再外发 sleep_post);algorithm 分支的 HWaringController 补丁(sleep→SVA_SLEEP/睡岗告警,白名单放行 sleep)已并入 integration-all。**后端无需新增 SVA_SLEEP_POST**,本单仅保留历史参考;如后续要区分"姿态睡岗"与"横躺",可再议(建议沿用 ruleId 区分)。
