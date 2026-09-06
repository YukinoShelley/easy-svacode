package com.ruoyi.waring.domain;

/**
 * GB28181 国标通道信息(来自流媒体/GB 网关的契约模型)。
 * 契约约定(详见 doc/GB28181 契约文档):每“通道”对应一条 h_device 记录。
 * 字段名与 ZLM/GB 网关返回 JSON 对齐,解析时兼容大小写差异。
 */
public class Gb28181Channel {

    /** 通道唯一标识(用作 h_device.ape_id) */
    private String channelId;
    /** 通道名称 */
    private String channelName;
    /** 设备国标编码(20 位) */
    private String gbDeviceId;
    /** 平台/上级国标编码 */
    private String gbPlatformId;
    /** 是否在线: 1 在线 / 0 离线 */
    private String online;
    /** 设备 IP(可选) */
    private String ipAddr;
    /** 厂商(可选) */
    private String manufacturer;

    public Gb28181Channel() {
    }

    public String getChannelId() {
        return channelId;
    }

    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    public String getChannelName() {
        return channelName;
    }

    public void setChannelName(String channelName) {
        this.channelName = channelName;
    }

    public String getGbDeviceId() {
        return gbDeviceId;
    }

    public void setGbDeviceId(String gbDeviceId) {
        this.gbDeviceId = gbDeviceId;
    }

    public String getGbPlatformId() {
        return gbPlatformId;
    }

    public void setGbPlatformId(String gbPlatformId) {
        this.gbPlatformId = gbPlatformId;
    }

    public String getOnline() {
        return online;
    }

    public void setOnline(String online) {
        this.online = online;
    }

    public String getIpAddr() {
        return ipAddr;
    }

    public void setIpAddr(String ipAddr) {
        this.ipAddr = ipAddr;
    }

    public String getManufacturer() {
        return manufacturer;
    }

    public void setManufacturer(String manufacturer) {
        this.manufacturer = manufacturer;
    }
}
