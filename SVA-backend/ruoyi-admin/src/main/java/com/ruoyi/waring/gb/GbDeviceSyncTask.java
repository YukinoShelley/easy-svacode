package com.ruoyi.waring.gb;

import com.ruoyi.waring.service.HDeviceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * GB28181 设备上下线定时同步任务(供若依 sys_job 定时调用)。
 *
 * <p>invoke_target = {@code gbDeviceSyncTask.syncGbDeviceStatus}(无参方法)</p>
 * <p>逻辑:轮询 ZLM 国标通道表,将不在线/新出现的通道状态同步到 h_device。
 * 媒体能力未启用或 ZLM 不可达时静默降级(不抛异常,避免 Quartz 任务刷错)。</p>
 */
@Component("gbDeviceSyncTask")
public class GbDeviceSyncTask {

    private static final Logger log = LoggerFactory.getLogger(GbDeviceSyncTask.class);

    @Autowired
    private HDeviceService hDeviceService;

    /** 无参方法:同步国标设备上下线状态 */
    public void syncGbDeviceStatus() {
        try {
            int changed = hDeviceService.syncGbOnlineStatus();
            if (changed > 0) {
                log.info("GB28181 设备状态同步完成,更新 {} 条", changed);
            }
        } catch (Exception e) {
            log.warn("GB28181 设备状态同步异常(忽略): {}", e.getMessage());
        }
    }
}
