-- =====================================================================
-- easySVA 后端升级回滚(GB28181 国标设备 + 睡岗算法元数据预留)
-- 配套 gb28181_backend_up.sql;顺序与 up 相反。
-- =====================================================================

-- 删除 av_algorithm 睡岗种子(仅当仍为预留空配置时删除)
DELETE FROM `av_algorithm` WHERE `code` = 'on_yolopose_sleep'
  AND IFNULL(`api_url`,'') = '' AND IFNULL(`object_str`,'') = '';

-- 删除设备类型字典
DELETE FROM `sys_dict_data` WHERE `dict_type` = 'sva_device_type';
DELETE FROM `sys_dict_type` WHERE `dict_type` = 'sva_device_type';

-- deployment_task_algorithm 去掉参数预留列
ALTER TABLE `deployment_task_algorithm` DROP COLUMN `params_json`;

-- zlm_server 去掉国标预留列
ALTER TABLE `zlm_server` DROP COLUMN `gb_sip_port`, DROP COLUMN `gb28181_enabled`;

-- h_device 去掉国标字段与索引
ALTER TABLE `h_device`
    DROP INDEX `idx_hdevice_type`,
    DROP INDEX `idx_hdevice_gb`,
    DROP COLUMN `gb_platform_id`,
    DROP COLUMN `gb_device_id`,
    DROP COLUMN `device_type`;

-- 校验
SELECT 'h_device 剩余 device_type 列' t, COUNT(*) cols FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='easySVA' AND TABLE_NAME='h_device' AND COLUMN_NAME='device_type'
UNION ALL SELECT 'av_algorithm 剩余睡岗行', COUNT(*) FROM `av_algorithm` WHERE `code`='on_yolopose_sleep';
