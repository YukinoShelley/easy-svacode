-- =====================================================================
-- easySVA 后端升级迁移(GB28181 国标设备 + 睡岗算法元数据预留)
-- 文件: gb28181_backend_up.sql  (配套回滚见 gb28181_backend_down.sql)
-- 原则: 全部为增量(加列/加行/加索引),不修改/删除既有列与行,可随时回滚。
-- =====================================================================

-- 1) h_device: 设备类型与国标编码字段(对应课程文档: device_type / gb_device_id / gb_platform_id)
ALTER TABLE `h_device`
    ADD COLUMN `device_type`     varchar(16) NOT NULL DEFAULT 'RTSP'  COMMENT '设备类型: RTSP(主动拉流/直连) / GB28181(国标接入)' AFTER `stream_source_type`,
    ADD COLUMN `gb_device_id`    varchar(64) NULL COMMENT 'GB28181 设备国标编码(20位)' AFTER `device_type`,
    ADD COLUMN `gb_platform_id`  varchar(64) NULL COMMENT 'GB28181 平台/上级国标编码' AFTER `gb_device_id`,
    ADD KEY `idx_hdevice_gb` (`gb_device_id`,`gb_platform_id`),
    ADD KEY `idx_hdevice_type` (`device_type`);

-- 存量数据归一: 旧的 stream_source_type(DIRECT/PLATFORM) 均视为 RTSP 类型
UPDATE `h_device` SET `device_type` = 'RTSP' WHERE `device_type` = 'RTSP' OR `device_type` IS NULL OR `device_type` = '';

-- 2) zlm_server: 预留国标(SIP)能力开关与端口(媒体组启用后置 1)
ALTER TABLE `zlm_server`
    ADD COLUMN `gb28181_enabled` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否启用GB28181能力(媒体组按契约实现后置1)' AFTER `secret`,
    ADD COLUMN `gb_sip_port`     int(11)     NULL COMMENT 'GB28181 SIP 服务端口(默认5060)' AFTER `gb28181_enabled`;

-- 3) deployment_task_algorithm: 预留算法自定义参数(睡岗阈值/角度/时序等,后端透传,AI/前端组扩展)
ALTER TABLE `deployment_task_algorithm`
    ADD COLUMN `params_json` text NULL COMMENT '算法自定义参数(JSON),如睡岗: {"headPitch":30,"durationSec":5}';

-- 4) av_algorithm: 注册“睡岗检测”算法元数据(模型/推理由 SVA-server 组按契约接入)
--    code 约定: on_yolopose_sleep(与 SVA-server/前端组对齐,可在契约文档中统一修订)
INSERT INTO `av_algorithm` (`sort`,`code`,`name`,`api_url`,`object_count`,`object_str`,`remark`,`state`,`create_time`,`update_time`)
SELECT 10, 'on_yolopose_sleep', '睡岗检测(姿态关键点)', '', 0, '', '睡岗行为检测: 基于YOLO-Pose人体关键点+时序判断;后端仅注册元数据并透传参数', 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM `av_algorithm` WHERE `code` = 'on_yolopose_sleep');

-- 5) sys_dict 预留(可选): 设备类型字典,便于前端下拉
INSERT INTO `sys_dict_type` (`dict_name`,`dict_type`,`status`,`create_by`,`create_time`,`remark`)
SELECT '设备类型','sva_device_type','0','admin',NOW(),'easySVA设备类型(RTSP/GB28181)'
WHERE NOT EXISTS (SELECT 1 FROM `sys_dict_type` WHERE `dict_type`='sva_device_type');

INSERT INTO `sys_dict_data` (`dict_sort`,`dict_label`,`dict_value`,`dict_type`,`css_class`,`list_class`,`is_default`,`status`,`create_by`,`create_time`,`remark`)
SELECT 1,'RTSP(主动拉流)','RTSP','sva_device_type','','','N','0','admin',NOW(),'直接拉流摄像头' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_dict_data` WHERE `dict_type`='sva_device_type' AND `dict_value`='RTSP')
UNION ALL
SELECT 2,'GB28181(国标)','GB28181','sva_device_type','','','N','0','admin',NOW(),'国标接入摄像头' FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_dict_data` WHERE `dict_type`='sva_device_type' AND `dict_value`='GB28181');

-- 校验
SELECT 'h_device' t, COUNT(*) cols FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='easySVA' AND TABLE_NAME='h_device' AND COLUMN_NAME IN ('device_type','gb_device_id','gb_platform_id')
UNION ALL
SELECT 'zlm_server', COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='easySVA' AND TABLE_NAME='zlm_server' AND COLUMN_NAME IN ('gb28181_enabled','gb_sip_port')
UNION ALL
SELECT 'deployment_task_algorithm', COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='easySVA' AND TABLE_NAME='deployment_task_algorithm' AND COLUMN_NAME='params_json';
