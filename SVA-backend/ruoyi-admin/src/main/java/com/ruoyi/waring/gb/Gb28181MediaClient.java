package com.ruoyi.waring.gb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.waring.domain.Gb28181Channel;
import com.ruoyi.waring.domain.ZlmServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * GB28181 媒体能力契约客户端(后端 ↔ ZLM/国标媒体网关)。
 *
 * <p>职责:本后端所有“调用国标媒体能力”都收敛于此,便于流媒体组按契约实现后
 * 只改一处开关即可全量生效,后端其余代码零改动。</p>
 *
 * <p>当前阶段(ZLM 侧 GB28181 SIP/通道 REST 尚未启用,见 zlm_server.gb28181_enabled=0)
 * 所有方法都会抛出带明确提示的 {@link ServiceException},接口层将其转换为友好错误,
 * 保证原有 RTSP 功能不受影响。</p>
 *
 * <p>契约端点(待媒体组实现,路径可在 zlm_server 扩展字段/本类常量中调整):</p>
 * <ul>
 *   <li>GET /index/api/gb/listChannels   → {code:0, data:{channels:[...]}} 列出已注册国标通道</li>
 *   <li>GET /index/api/gb/play?channelId=xx → {code:0, data:{playUrl}} 点播某通道(可选)</li>
 *   <li>POST /index/api/gb/bye?channelId=xx → 停止点播(可选)</li>
 * </ul>
 */
@Component
public class Gb28181MediaClient {

    private static final Logger log = LoggerFactory.getLogger(Gb28181MediaClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 契约端点(默认值;媒体组实现时按实际路由覆盖) */
    public static final String API_LIST_CHANNELS = "/index/api/gb/listChannels";
    public static final String API_GB_PLAY = "/index/api/gb/play";
    public static final String API_GB_BYE = "/index/api/gb/bye";

    private RestTemplate restTemplate;

    @PostConstruct
    private void initRestTemplate() {
        restTemplate = new RestTemplate();
    }

    /** 是否已声明启用 GB28181 媒体能力(由 DB 开关控制,管理员在流媒体就绪后置 1) */
    public boolean isGbEnabled(ZlmServer zlmServer) {
        return zlmServer != null
            && zlmServer.getGb28181_enabled() != null
            && zlmServer.getGb28181_enabled() == 1;
    }

    /** 列出 ZLM 上已注册的国标通道(契约) */
    public List<Gb28181Channel> listChannels(ZlmServer zlmServer) {
        if (!isGbEnabled(zlmServer)) {
            throw new ServiceException("流媒体服务器未启用GB28181能力(zlm_server.gb28181_enabled=0),"
                + " 请媒体组按契约开启 SIP/通道服务后再试");
        }
        JsonNode data = doGetJson(zlmServer, API_LIST_CHANNELS, null, "listChannels");
        JsonNode channels = findArray(data, "channels", "list", "items", "devices");
        if (channels == null || !channels.isArray()) {
            return Collections.emptyList();
        }
        List<Gb28181Channel> result = new ArrayList<>();
        for (JsonNode node : channels) {
            if (node == null || !node.isObject()) {
                continue;
            }
            Gb28181Channel ch = new Gb28181Channel();
            ch.setChannelId(textOf(node, "channelId", "channel_id", "id", "channel"));
            ch.setChannelName(textOf(node, "channelName", "channel_name", "name"));
            ch.setGbDeviceId(textOf(node, "gbDeviceId", "gb_device_id", "deviceId", "device_id"));
            ch.setGbPlatformId(textOf(node, "gbPlatformId", "gb_platform_id", "platformId", "platform_id"));
            ch.setOnline(textOf(node, "online", "status", "isOnline", "is_online"));
            ch.setIpAddr(textOf(node, "ip", "ipAddr", "ip_addr"));
            ch.setManufacturer(textOf(node, "manufacturer", "producer", "producer_name"));
            if (ch.getChannelId() != null && !ch.getChannelId().isEmpty()) {
                result.add(ch);
            }
        }
        return result;
    }

    /** 点播某国标通道,返回播放地址(契约;媒体组实现后可返回真实地址) */
    public String playChannel(ZlmServer zlmServer, String channelId) {
        if (!isGbEnabled(zlmServer)) {
            throw new ServiceException("流媒体服务器未启用GB28181能力(zlm_server.gb28181_enabled=0)");
        }
        JsonNode data = doGetJson(zlmServer, API_GB_PLAY, Map.of("channelId", channelId), "gb/play");
        JsonNode playUrl = data == null ? null : findText(data, "playUrl", "play_url", "url", "rtsp", "flv");
        if (playUrl == null) {
            return null;
        }
        return playUrl.asText();
    }

    /** 停止点播(可选,失败不阻断) */
    public void byeChannel(ZlmServer zlmServer, String channelId) {
        if (!isGbEnabled(zlmServer)) {
            return;
        }
        try {
            doGetJson(zlmServer, API_GB_BYE, Map.of("channelId", channelId), "gb/bye");
        } catch (Exception e) {
            log.warn("GB28181 bye 失败 channelId={}, err={}", channelId, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 私有工具
    // ------------------------------------------------------------------

    private JsonNode doGetJson(ZlmServer zlmServer, String api, Map<String, String> params, String action) {
        if (zlmServer == null || zlmServer.getHost() == null || zlmServer.getApi_port() == null) {
            throw new ServiceException("ZLM 服务器配置缺失,无法调用 " + action);
        }
        String base = "http://" + zlmServer.getHost() + ":" + zlmServer.getApi_port();
        UriComponentsBuilder ub = UriComponentsBuilder.fromUriString(base + api);
        if (params != null) {
            params.forEach(ub::queryParam);
        }
        Optional.ofNullable(zlmServer.getSecret())
            .filter(s -> !s.isBlank())
            .ifPresent(s -> ub.queryParam("secret", s));
        String url = ub.build(true).toUriString();
        try {
            ResponseEntity<String> resp = restTemplate.getForEntity(url, String.class);
            String body = resp.getBody();
            if (body == null || body.isBlank()) {
                throw new ServiceException("调用GB28181接口(" + action + ")无响应: " + zlmServer.getHost());
            }
            JsonNode root = OBJECT_MAPPER.readTree(body);
            int code = root.path("code").asInt(-9999);
            String msg = root.path("msg").asText("");
            if (code != 0) {
                // 媒体组尚未实现契约端点或内部错误时,ZLM 通常返回 code != 0
                throw new ServiceException("GB28181接口(" + action + ")未就绪: code=" + code
                    + " msg=" + msg + "(媒体组按契约实现后可用)");
            }
            return root.path("data");
        } catch (ServiceException e) {
            throw e;
        } catch (RestClientException e) {
            throw new ServiceException("调用GB28181接口(" + action + ")失败: " + e.getMessage());
        } catch (Exception e) {
            throw new ServiceException("调用GB28181接口(" + action + ")响应解析失败: " + e.getMessage());
        }
    }

    private JsonNode findArray(JsonNode data, String... names) {
        if (data == null) {
            return null;
        }
        for (String n : names) {
            JsonNode v = data.get(n);
            if (v != null && v.isArray()) {
                return v;
            }
        }
        return null;
    }

    private JsonNode findText(JsonNode data, String... names) {
        if (data == null) {
            return null;
        }
        for (String n : names) {
            JsonNode v = data.get(n);
            if (v != null && !v.isNull() && (v.isTextual() || v.isNumber())) {
                return v;
            }
        }
        return null;
    }

    private String textOf(JsonNode node, String... names) {
        JsonNode v = findText(node, names);
        return v == null ? null : v.asText();
    }
}
