package com.ruoyi.waring.domain;

import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;
import lombok.*;

@Data
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class HDevice extends BaseEntity {
    @Excel(name = "设备编码")
    private String ape_id;
    @Excel(name = "设备名称")
    private String name;
    private String stream_source_type;
    /** 设备类型: RTSP(主动拉流/直连) / GB28181(国标接入),兼容课程文档 device_type */
    @Excel(name = "设备类型")
    private String device_type;
    /** GB28181 设备国标编码 */
    private String gb_device_id;
    /** GB28181 平台/上级国标编码 */
    private String gb_platform_id;
    private String direct_source_url;
    private String play_url;
    private String zlm_proxy_key;
    private String resource_type;
    private String sub_type;
    @Excel(name = "IP地址")
    private String ip_addr;
    @Excel(name = "端口号")
    private Integer port;
    private String org_index;
    private String org_name;
    private String place_code;
    private String place;
    private String is_online;
    private String producer;
    private String producer_name;
    private String parent_code;
    private Long zlm_server_id;
    private Long sva_server_id;
    @Excel(name = "监控状态")
    private String monitor_status;
    private String create_time;
    private String update_time;
}
