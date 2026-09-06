package com.ruoyi.waring.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Data
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ZlmServer {

    private Long id;

    private String name;

    private String app;

    private String host;

    private Integer api_port;

    private Integer media_http_port;

    private Integer media_rtsp_port;

    private String secret;

    /** 是否启用 GB28181 能力(SIP/通道 REST),媒体组按契约实现后置 1 */
    private Integer gb28181_enabled;

    /** GB28181 SIP 服务端口(默认 5060) */
    private Integer gb_sip_port;

    private Integer enabled;
}
