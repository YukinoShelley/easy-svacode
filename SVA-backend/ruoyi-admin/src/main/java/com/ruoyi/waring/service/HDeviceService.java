package com.ruoyi.waring.service;

import com.ruoyi.waring.domain.HDevice;

import java.util.List;
import java.util.Map;

public interface HDeviceService {
    void insertDevice(HDevice device);

    void deleteDevice();

    HDevice selectDeviceByApeId(String apeId);

    int insertDeviceCrud(HDevice device);

    int updateDevice(HDevice device);

    int deleteDeviceByApeIds(String[] apeIds);

    List<HDevice> selectDeviceList(HDevice device, Long userId);

    Map<String, Object> getDeviceNum(Long userId);

    Map<String, Object> getDirectLiveUrl(String apeId);

    /** 获取 ZLM 上已注册的国标(GB28181)通道(媒体能力未启用时抛友好错误) */
    Map<String, Object> getGbRemoteChannels();

    /** 一键将 ZLM 已注册国标通道导入/同步到 h_device(返回统计) */
    Map<String, Object> importGbDevices();

    /** 同步国标设备上下线状态(轮询 ZLM 通道表,更新 h_device.is_online) */
    int syncGbOnlineStatus();

    /** 获取国标设备实时播放地址(契约:/index/api/gb/play) */
    Map<String, Object> getGbLiveUrl(String apeId);

    /** GB28181 上下线状态回调落库(供 /waring/device/gb/notify 使用) */
    int updateGbOnlineState(String apeId, String online);

    List<HDevice> selectLDeviceList(HDevice device, Long userId);

    int startMonitor(String apeId);

    int stopMonitor(String apeId);

    Map<String, Object> previewMonitor(String apeId);
}
