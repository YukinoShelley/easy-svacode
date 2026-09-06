package com.ruoyi.waring.service.impl;


import com.github.pagehelper.PageHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.core.domain.entity.SysDept;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.page.PageDomain;
import com.ruoyi.common.core.page.TableSupport;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.mapper.SysDeptMapper;
import com.ruoyi.system.mapper.SysUserMapper;
import com.ruoyi.waring.domain.HDevice;
import com.ruoyi.waring.domain.Gb28181Channel;
import com.ruoyi.waring.domain.ZlmServer;
import com.ruoyi.waring.gb.Gb28181MediaClient;
import com.ruoyi.waring.mapper.HDeviceMapper;
import com.ruoyi.waring.mapper.ZlmServerMapper;
import com.ruoyi.waring.service.HDeviceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;


@Service
@Component
public class HDeviceServiceImpl implements HDeviceService {

    private static final Logger log = LoggerFactory.getLogger(HDeviceServiceImpl.class);

    private static final String STREAM_SOURCE_TYPE_DIRECT = "DIRECT";
    private static final String STREAM_SOURCE_TYPE_PLATFORM = "PLATFORM";
    private static final int MAX_APE_ID_GENERATE_RETRY = 20;
    private static final Pattern STREAM_NAME_PATTERN = Pattern.compile("[^A-Za-z0-9_-]");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String MONITOR_STATUS_RUNNING = "RUNNING";
    private static final String MONITOR_STATUS_STOPPED = "STOPPED";
    private static final String MONITOR_STATUS_STARTING = "STARTING";
    private static final String MONITOR_STATUS_STOPPING = "STOPPING";
    private static final String MONITOR_STATUS_ERROR = "ERROR";
    private static final long DEFAULT_SERVER_ID = 1L;
    private static final String DEFAULT_ZLM_APP = "live";

    /** 设备类型:RTSP(主动拉流/直连) */
    private static final String DEVICE_TYPE_RTSP = "RTSP";
    /** 设备类型:GB28181(国标接入) */
    private static final String DEVICE_TYPE_GB28181 = "GB28181";
    /** 契约:国标流在 ZLM 上默认挂载的 app(媒体组按契约对齐后可调整) */
    private static final String GB_DEFAULT_APP = "gb";
    private static final String ONLINE_FLAG = "1";
    private static final String OFFLINE_FLAG = "0";

    @Autowired
    HDeviceMapper hDeviceMapper;

    @Autowired
    SysUserMapper userMapper;

    @Autowired
    SysDeptMapper sysDeptMapper;

    @Autowired
    ZlmServerMapper zlmServerMapper;

    @Autowired
    private Gb28181MediaClient gb28181MediaClient;

    @Autowired(required = false)
    private RestTemplate restTemplate;

    @PostConstruct
    private void initRestTemplate() {
        if (restTemplate == null) {
            restTemplate = new RestTemplate();
        }
    }

    @Override
    public void insertDevice(HDevice device) {
        hDeviceMapper.insertDevice(device);
    }

    @Override
    public void deleteDevice() {
        hDeviceMapper.deleteDevice();
    }

    @Override
    public HDevice selectDeviceByApeId(String apeId) {
        return hDeviceMapper.selectDeviceByApeId(apeId);
    }

    @Override
    public int insertDeviceCrud(HDevice device) {
        normalizeDeviceType(device, null);
        normalizeStreamSourceType(device, null);
        validateStreamSourceRule(device, null);
        if (StringUtils.isBlank(device.getOrg_name())) {
            throw new ServiceException("组织名称不能为空");
        }
        device.setOrg_index(normalizeOrgIndex(device.getOrg_index()));

        if (StringUtils.isBlank(device.getApe_id())) {
            device.setApe_id(generateUniqueApeId());
        } else if (hDeviceMapper.selectDeviceByApeId(device.getApe_id()) != null) {
            throw new ServiceException("设备编码已存在: " + device.getApe_id());
        }

        return hDeviceMapper.insertDeviceCrud(device);
    }

    @Override
    public int updateDevice(HDevice device) {
        if (StringUtils.isBlank(device.getApe_id())) {
            throw new ServiceException("设备编码不能为空");
        }

        HDevice existedDevice = hDeviceMapper.selectDeviceByApeId(device.getApe_id());
        if (existedDevice == null) {
            throw new ServiceException("设备不存在: " + device.getApe_id());
        }

        normalizeDeviceType(device, existedDevice);
        normalizeStreamSourceType(device, existedDevice);
        validateStreamSourceRule(device, existedDevice);
        device.setOrg_index(normalizeOrgIndex(device.getOrg_index()));

        return hDeviceMapper.updateDevice(device);
    }

    /**
     * 归一化 device_type(RTSP/GB28181):
     * <ul>
     *   <li>入参为空时,沿用旧值;无旧值时按 stream_source_type(DIRECT/PLATFORM-&gt;RTSP,GB28181-&gt;GB28181)推导</li>
     *   <li>GB28181 新建设备必须提供国标编码 gb_device_id</li>
     * </ul>
     */
    private void normalizeDeviceType(HDevice device, HDevice existedDevice) {
        String deviceType = device.getDevice_type();
        if (StringUtils.isBlank(deviceType) && existedDevice != null) {
            deviceType = existedDevice.getDevice_type();
        }
        if (StringUtils.isBlank(deviceType)) {
            String streamSourceType = StringUtils.isBlank(device.getStream_source_type())
                ? (existedDevice == null ? null : existedDevice.getStream_source_type())
                : device.getStream_source_type();
            if (DEVICE_TYPE_GB28181.equalsIgnoreCase(streamSourceType)) {
                deviceType = DEVICE_TYPE_GB28181;
            } else {
                deviceType = DEVICE_TYPE_RTSP;
            }
        }
        deviceType = StringUtils.upperCase(deviceType.trim());
        if (!DEVICE_TYPE_RTSP.equals(deviceType) && !DEVICE_TYPE_GB28181.equals(deviceType)) {
            throw new ServiceException("device_type 仅支持 RTSP 或 GB28181");
        }
        if (DEVICE_TYPE_GB28181.equals(deviceType)
            && existedDevice == null
            && StringUtils.isBlank(device.getGb_device_id())) {
            throw new ServiceException("GB28181 设备必须填写国标编码(gb_device_id)");
        }
        device.setDevice_type(deviceType);
    }

    private void normalizeStreamSourceType(HDevice device, HDevice existedDevice) {
        String streamSourceType = device.getStream_source_type();
        if (StringUtils.isBlank(streamSourceType) && existedDevice != null) {
            streamSourceType = existedDevice.getStream_source_type();
        }
        // 新建设备:device_type 优先决定 stream_source_type(GB28181 -> GB28181,其余 -> DIRECT)
        if (StringUtils.isBlank(streamSourceType)) {
            if (DEVICE_TYPE_GB28181.equalsIgnoreCase(device.getDevice_type())) {
                streamSourceType = DEVICE_TYPE_GB28181;
            } else {
                streamSourceType = STREAM_SOURCE_TYPE_DIRECT;
            }
        }

        streamSourceType = StringUtils.upperCase(streamSourceType.trim());
        if (!STREAM_SOURCE_TYPE_DIRECT.equals(streamSourceType)
            && !STREAM_SOURCE_TYPE_PLATFORM.equals(streamSourceType)
            && !DEVICE_TYPE_GB28181.equals(streamSourceType)) {
            throw new ServiceException("stream_source_type 仅支持 PLATFORM / DIRECT / GB28181");
        }
        device.setStream_source_type(streamSourceType);
        // 双字段保持一致:GB28181 设备同步 device_type,反之把历史值归一为 RTSP
        if (DEVICE_TYPE_GB28181.equals(streamSourceType)) {
            device.setDevice_type(DEVICE_TYPE_GB28181);
        } else if (DEVICE_TYPE_GB28181.equalsIgnoreCase(device.getDevice_type())) {
            device.setDevice_type(DEVICE_TYPE_RTSP);
        }
    }

    private void validateStreamSourceRule(HDevice device, HDevice existedDevice) {
        if (!STREAM_SOURCE_TYPE_DIRECT.equals(device.getStream_source_type())) {
            return;
        }

        String finalName = pickFinalValue(device.getName(), existedDevice == null ? null : existedDevice.getName());
        if (StringUtils.isBlank(finalName)) {
            throw new ServiceException("DIRECT 设备类型下，name 不能为空");
        }

        String finalDirectSourceUrl = pickFinalValue(device.getDirect_source_url(), existedDevice == null ? null : existedDevice.getDirect_source_url());
        if (StringUtils.isBlank(finalDirectSourceUrl)) {
            throw new ServiceException("DIRECT 设备类型下，direct_source_url 不能为空");
        }
    }

    private String pickFinalValue(String incomingValue, String existedValue) {
        if (incomingValue != null) {
            return incomingValue;
        }
        return existedValue;
    }

    private String generateUniqueApeId() {
        for (int i = 0; i < MAX_APE_ID_GENERATE_RETRY; i++) {
            String candidate = "cam" + String.format("%06d", ThreadLocalRandom.current().nextInt(1000000));
            if (hDeviceMapper.selectDeviceByApeId(candidate) == null) {
                return candidate;
            }
        }
        throw new ServiceException("自动生成设备编码失败，请稍后重试");
    }

    @Override
    public int deleteDeviceByApeIds(String[] apeIds) {
        return hDeviceMapper.deleteDeviceByApeIds(apeIds);
    }

    @Override
    public Map<String, Object> getDirectLiveUrl(String apeId) {
        if (StringUtils.isBlank(apeId)) {
            throw new ServiceException("apeId 不能为空");
        }

        HDevice device = hDeviceMapper.selectDeviceByApeId(apeId);
        if (device == null) {
            throw new ServiceException("设备不存在: " + apeId);
        }

        if (!STREAM_SOURCE_TYPE_DIRECT.equalsIgnoreCase(device.getStream_source_type())) {
            throw new ServiceException("仅支持 DIRECT 设备类型");
        }

        if (StringUtils.isBlank(device.getDirect_source_url())) {
            throw new ServiceException("DIRECT 设备类型下，direct_source_url 不能为空");
        }

        Long zlmServerId = device.getZlm_server_id() == null ? DEFAULT_SERVER_ID : device.getZlm_server_id();
        ZlmServer zlmServer = zlmServerMapper.selectEnabledById(zlmServerId);
        if (zlmServer == null) {
            throw new ServiceException("设备未绑定可用ZLM服务器");
        }
        if (StringUtils.isBlank(zlmServer.getHost()) || zlmServer.getApi_port() == null || zlmServer.getMedia_http_port() == null) {
            throw new ServiceException("可用ZLM服务器配置缺失");
        }

        String zlmApp = StringUtils.isBlank(zlmServer.getApp()) ? DEFAULT_ZLM_APP : zlmServer.getApp().trim();

        String stream = sanitizeStreamName(apeId);
        String addProxyUrl = UriComponentsBuilder
            .fromUriString("http://" + zlmServer.getHost() + ":" + zlmServer.getApi_port() + "/index/api/addStreamProxy")
                .queryParam("vhost", "__defaultVhost__")
                .queryParam("app", zlmApp)
                .queryParam("stream", stream)
                .queryParam("url", device.getDirect_source_url())
            .queryParam("enable_mp4", 1)
            .queryParam("auto_close", 0)
                .queryParamIfPresent("secret", StringUtils.isNotBlank(zlmServer.getSecret())
                        ? java.util.Optional.of(zlmServer.getSecret())
                        : java.util.Optional.empty())
                .build(true)
                .toUriString();

            if (log.isDebugEnabled()) {
                log.debug("调用ZLM addStreamProxy, apeId={}, url={}", apeId, maskSensitiveUrl(addProxyUrl));
            }

        ResponseEntity<String> response = restTemplate.getForEntity(addProxyUrl, String.class);
        String body = response.getBody();
        if (StringUtils.isBlank(body)) {
            throw new ServiceException("调用 ZLM addStreamProxy 失败: empty response");
        }

        int code;
        String msg;
        String zlmProxyKey;
        try {
            JsonNode root = OBJECT_MAPPER.readTree(body);
            code = parseCode(root.path("code").asText());
            msg = root.path("msg").asText("");
            zlmProxyKey = root.path("data").path("key").asText("");
        } catch (Exception e) {
            throw new ServiceException("调用 ZLM addStreamProxy 失败: 响应解析异常");
        }

        boolean addProxySuccess = code == 0;
        boolean addProxyAlreadyExists = code != 0 && isAddProxyAlreadyExists(msg);

        if (!addProxySuccess && !addProxyAlreadyExists) {
            throw new ServiceException("调用 ZLM addStreamProxy 失败: " + msg);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("apeId", apeId);
        result.put("stream", stream);
        result.put("playUrl", "ws://" + zlmServer.getHost() + ":" + zlmServer.getMedia_http_port() + "/" + zlmApp + "/" + stream + ".live.flv");
        result.put("zlmProxyKey", StringUtils.isBlank(zlmProxyKey) ? null : zlmProxyKey);
        result.put("addProxySuccess", addProxySuccess);
        result.put("addProxyAlreadyExists", addProxyAlreadyExists);
        result.put("protocol", "ws-flv");
        return result;
    }

    @Override
    public Map<String, Object> getGbRemoteChannels() {
        ZlmServer zlmServer = resolveDefaultZlmServer();
        List<Gb28181Channel> channels = gb28181MediaClient.listChannels(zlmServer);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gbEnabled", gb28181MediaClient.isGbEnabled(zlmServer));
        result.put("gbSipPort", zlmServer.getGb_sip_port());
        result.put("channels", channels);
        result.put("total", channels.size());
        return result;
    }

    @Override
    public Map<String, Object> importGbDevices() {
        ZlmServer zlmServer = resolveDefaultZlmServer();
        List<Gb28181Channel> channels = gb28181MediaClient.listChannels(zlmServer);
        int imported = 0;
        int updated = 0;
        int online = 0;
        int offline = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();

        for (Gb28181Channel ch : channels) {
            try {
                String channelId = ch.getChannelId();
                if (StringUtils.isBlank(channelId)) {
                    skipped++;
                    continue;
                }
                HDevice existed = hDeviceMapper.selectByGbDeviceId(ch.getGbDeviceId(), ch.getGbPlatformId());
                if (existed == null) {
                    existed = hDeviceMapper.selectDeviceByApeId(channelId);
                }
                HDevice device = new HDevice();
                device.setApe_id(channelId);
                device.setName(StringUtils.isBlank(ch.getChannelName()) ? channelId : ch.getChannelName());
                device.setDevice_type(DEVICE_TYPE_GB28181);
                device.setStream_source_type(DEVICE_TYPE_GB28181);
                device.setGb_device_id(ch.getGbDeviceId());
                device.setGb_platform_id(ch.getGbPlatformId());
                device.setIp_addr(ch.getIpAddr());
                device.setProducer_name(ch.getManufacturer());
                device.setIs_online(toOnlineFlag(ch.getOnline()));
                device.setZlm_server_id(zlmServer.getId());
                device.setSva_server_id(DEFAULT_SERVER_ID);
                device.setMonitor_status(existed == null ? "STOPPED" : existed.getMonitor_status());
                device.setOrg_name(StringUtils.isBlank(ch.getGbPlatformId()) ? "国标接入" : "国标接入-" + ch.getGbPlatformId());

                if (existed == null) {
                    hDeviceMapper.insertDeviceCrud(device);
                    imported++;
                } else {
                    device.setApe_id(existed.getApe_id());
                    hDeviceMapper.updateDevice(device);
                    updated++;
                }
                if (ONLINE_FLAG.equals(device.getIs_online())) {
                    online++;
                } else {
                    offline++;
                }
            } catch (Exception e) {
                errors.add("channel=" + ch.getChannelId() + ": " + e.getMessage());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gbEnabled", gb28181MediaClient.isGbEnabled(zlmServer));
        result.put("total", channels.size());
        result.put("imported", imported);
        result.put("updated", updated);
        result.put("online", online);
        result.put("offline", offline);
        result.put("skipped", skipped);
        result.put("errors", errors);
        return result;
    }

    @Override
    public int syncGbOnlineStatus() {
        ZlmServer zlmServer;
        try {
            zlmServer = resolveDefaultZlmServer();
        } catch (ServiceException e) {
            log.warn("GB28181 状态同步跳过: {}", e.getMessage());
            return 0;
        }
        List<Gb28181Channel> channels;
        try {
            channels = gb28181MediaClient.listChannels(zlmServer);
        } catch (ServiceException e) {
            log.warn("GB28181 状态同步跳过: {}", e.getMessage());
            return 0;
        }
        Map<String, String> onlineMap = new HashMap<>();
        for (Gb28181Channel ch : channels) {
            if (StringUtils.isNotBlank(ch.getChannelId())) {
                onlineMap.put(ch.getChannelId(), toOnlineFlag(ch.getOnline()));
            }
        }
        // 本库中全部国标设备
        HDevice query = new HDevice();
        query.setDevice_type(DEVICE_TYPE_GB28181);
        List<HDevice> gbDevices = hDeviceMapper.selectDeviceList(query);
        int changed = 0;
        for (HDevice d : gbDevices) {
            String target = onlineMap.getOrDefault(d.getApe_id(), OFFLINE_FLAG);
            if (!target.equals(d.getIs_online())) {
                changed += hDeviceMapper.updateOnlineStateByApeId(d.getApe_id(), target);
            }
        }
        return changed;
    }

    @Override
    public Map<String, Object> getGbLiveUrl(String apeId) {
        if (StringUtils.isBlank(apeId)) {
            throw new ServiceException("apeId 不能为空");
        }
        HDevice device = hDeviceMapper.selectDeviceByApeId(apeId);
        if (device == null) {
            throw new ServiceException("设备不存在: " + apeId);
        }
        if (!isGbDevice(device)) {
            throw new ServiceException("仅支持 GB28181 设备类型");
        }
        ZlmServer zlmServer = resolveDefaultZlmServer();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("apeId", device.getApe_id());
        result.put("streamSourceType", device.getStream_source_type());
        result.put("deviceType", device.getDevice_type());
        result.put("gbDeviceId", device.getGb_device_id());
        result.put("gbPlatformId", device.getGb_platform_id());
        result.put("gbEnabled", gb28181MediaClient.isGbEnabled(zlmServer));
        result.put("monitorStatus", device.getMonitor_status());

        String zlmApp = StringUtils.isBlank(zlmServer.getApp()) ? GB_DEFAULT_APP : zlmServer.getApp().trim();
        String stream = sanitizeStreamName(device.getApe_id());

        // 媒体能力启用后:先按契约点播,拿到真实地址;否则给出契约约定的默认播放地址(媒体组对齐后生效)
        String playUrl = device.getPlay_url();
        if (StringUtils.isBlank(playUrl) && gb28181MediaClient.isGbEnabled(zlmServer)) {
            String realUrl = gb28181MediaClient.playChannel(zlmServer, device.getApe_id());
            if (StringUtils.isNotBlank(realUrl)) {
                playUrl = realUrl;
                hDeviceMapper.updatePlayUrlByApeId(device.getApe_id(), playUrl);
            }
        }
        if (StringUtils.isBlank(playUrl)) {
            playUrl = "ws://" + zlmServer.getHost() + ":" + zlmServer.getMedia_http_port()
                + "/" + zlmApp + "/" + stream + ".live.flv";
        }
        result.put("playUrl", playUrl);
        result.put("protocol", "ws-flv");
        result.put("gbReady", gb28181MediaClient.isGbEnabled(zlmServer) && StringUtils.isNotBlank(playUrl));
        return result;
    }

    @Override
    public int updateGbOnlineState(String apeId, String online) {
        if (StringUtils.isBlank(apeId)) {
            return 0;
        }
        return hDeviceMapper.updateOnlineStateByApeId(apeId,
            ONLINE_FLAG.equals(online) ? ONLINE_FLAG : OFFLINE_FLAG);
    }

    private ZlmServer resolveDefaultZlmServer() {
        ZlmServer zlmServer = zlmServerMapper.selectEnabledById(DEFAULT_SERVER_ID);
        if (zlmServer == null) {
            throw new ServiceException("未启用可用ZLM服务器(id=" + DEFAULT_SERVER_ID + ")");
        }
        if (StringUtils.isBlank(zlmServer.getHost()) || zlmServer.getApi_port() == null) {
            throw new ServiceException("ZLM服务器配置缺失(host/api_port)");
        }
        return zlmServer;
    }

    private boolean isGbDevice(HDevice device) {
        return device != null && DEVICE_TYPE_GB28181.equalsIgnoreCase(device.getDevice_type());
    }

    private String toOnlineFlag(String online) {
        if (StringUtils.isBlank(online)) {
            return OFFLINE_FLAG;
        }
        String v = online.trim().toLowerCase();
        if ("1".equals(v) || "true".equals(v) || "yes".equals(v) || "online".equals(v)) {
            return ONLINE_FLAG;
        }
        return OFFLINE_FLAG;
    }

    @Override
    public List<HDevice> selectDeviceList(HDevice device, Long userId) {
        device.setOrg_index(normalizeOrgIndex(device.getOrg_index()));
        List<HDevice> devices;
        SysUser user = userMapper.selectUserById(userId);
        SysDept dept = sysDeptMapper.selectDeptById(user.getDeptId());
        List<String> orgIndexs = null;
        if (!com.ruoyi.common.utils.SecurityUtils.isAdmin(userId)) {
            // 如果登录账号不为admin 账号
            if (device.getOrg_index() == null && !dept.getOrgIndex().equals("10")) {
                orgIndexs = sysDeptMapper.getOrgIndex(dept.getOrgIndex());
                orgIndexs.add(dept.getOrgIndex());
                String[] org_index = orgIndexs.toArray(new String[orgIndexs.size()]);
                device.getParams().put("org_indexs", org_index);
            } else if (device.getOrg_index() != null && !dept.getOrgIndex().equals("10")) {
                orgIndexs = sysDeptMapper.getOrgIndex(device.getOrg_index());
                orgIndexs.add(device.getOrg_index());
                String[] org_index = orgIndexs.toArray(new String[orgIndexs.size()]);
                device.getParams().put("org_indexs", org_index);
            } else if (device.getOrg_index() != null) {
                if (!device.getOrg_index().equals("10")) {
                    orgIndexs = sysDeptMapper.getOrgIndex(device.getOrg_index());
                    orgIndexs.add(device.getOrg_index());
                    String[] org_index = orgIndexs.toArray(new String[orgIndexs.size()]);
                    device.getParams().put("org_indexs", org_index);
                }
            }
        } else if (!dept.getOrgIndex().equals("10")) {
            // 如果登录账号不为 hy 账号
            if (device.getOrg_index() == null) {
                orgIndexs = sysDeptMapper.getOrgIndex(dept.getOrgIndex());
                orgIndexs.add(dept.getOrgIndex());
                String[] org_index = orgIndexs.toArray(new String[orgIndexs.size()]);
                device.getParams().put("org_indexs", org_index);
            } else {
                orgIndexs = sysDeptMapper.getOrgIndex(device.getOrg_index());
                orgIndexs.add(device.getOrg_index());
                String[] org_index = orgIndexs.toArray(new String[orgIndexs.size()]);
                device.getParams().put("org_indexs", org_index);
            }
        } else {
            // 如果登录账号为 hy/admin 账号
            if (device.getOrg_index() != null) {
                if (!device.getOrg_index().equals("10")) {
                    orgIndexs = sysDeptMapper.getOrgIndex(device.getOrg_index());
                    orgIndexs.add(device.getOrg_index());
                    String[] org_index = orgIndexs.toArray(new String[orgIndexs.size()]);
                    device.getParams().put("org_indexs", org_index);
                }
            } else {
                if (!dept.getOrgIndex().equals("10")) {
                    orgIndexs = sysDeptMapper.getOrgIndex(device.getOrg_index());
                    orgIndexs.add(device.getOrg_index());
                    String[] org_index = orgIndexs.toArray(new String[orgIndexs.size()]);
                    device.getParams().put("org_indexs", org_index);
                } else if (!com.ruoyi.common.utils.SecurityUtils.isAdmin(userId)) {
                    orgIndexs = sysDeptMapper.getOrgIndex(device.getOrg_index());
                    orgIndexs.add(device.getOrg_index());
                    String[] org_index = orgIndexs.toArray(new String[orgIndexs.size()]);
                    device.getParams().put("org_indexs", org_index);
                }
            }
        }

        PageDomain pageDomain = TableSupport.getPageDomain();
        PageHelper.startPage(pageDomain.getPageNum(), pageDomain.getPageSize(), pageDomain.getOrderBy());
        devices = hDeviceMapper.selectDeviceList(device);

        return devices;
    }

    @Override
    public Map<String, Object> getDeviceNum(Long userId) {
        SysUser user = userMapper.selectUserById(userId);
        SysDept dept = sysDeptMapper.selectDeptById(user.getDeptId());
        int deviceNum;
        int deviceEnableNum;
        if (com.ruoyi.common.utils.SecurityUtils.isAdmin(userId) || dept.getOrgIndex().equals("10")) {
            // 如果登录账号为 集团管理员和系统管理员 查询所有数量的设备
            deviceNum = hDeviceMapper.getDeviceNum();
            deviceEnableNum = hDeviceMapper.getDeviceEnableNum();
        } else {
            // 如果登录账号为 别的账号 根据大组织查询
            List<String> orgIndexs = sysDeptMapper.getOrgIndex(dept.getOrgIndex());
            orgIndexs.add(dept.getOrgIndex());
            String[] org_index = orgIndexs.toArray(new String[orgIndexs.size()]);
            HDevice device = new HDevice();
            device.getParams().put("org_indexs", org_index);
            deviceNum = hDeviceMapper.getDeviceNumByOrg(device);
            deviceEnableNum = hDeviceMapper.getDeviceEnableNumByOrg(device);
        }
        int deviceli = deviceNum - deviceEnableNum;
        Map<String, Object> map = new HashMap<>();
        map.put("deviceNum", deviceNum);
        map.put("deviceEnableNum", deviceEnableNum);
        map.put("deviceli", deviceli);
        return map;
    }

    @Override
    public List<HDevice> selectLDeviceList(HDevice device, Long userId) {
        device.setOrg_index(normalizeOrgIndex(device.getOrg_index()));
        List<HDevice> devices;
        SysUser user = userMapper.selectUserById(userId);
        SysDept dept = sysDeptMapper.selectDeptById(user.getDeptId());
        List<String> orgIndexs = null;
        if (!com.ruoyi.common.utils.SecurityUtils.isAdmin(userId)) {
            // 如果登录账号不为admin 账号
            if (device.getOrg_index() == null && !dept.getOrgIndex().equals("10")) {
                orgIndexs = sysDeptMapper.getOrgIndex(dept.getOrgIndex());
                orgIndexs.add(dept.getOrgIndex());
                String[] org_index = orgIndexs.toArray(new String[orgIndexs.size()]);
                device.getParams().put("org_indexs", org_index);
            } else if (device.getOrg_index() != null && !dept.getOrgIndex().equals("10")) {
                orgIndexs = sysDeptMapper.getOrgIndex(device.getOrg_index());
                orgIndexs.add(device.getOrg_index());
                String[] org_index = orgIndexs.toArray(new String[orgIndexs.size()]);
                device.getParams().put("org_indexs", org_index);
            } else if (device.getOrg_index() != null) {
                if (!device.getOrg_index().equals("10")) {
                    orgIndexs = sysDeptMapper.getOrgIndex(device.getOrg_index());
                    orgIndexs.add(device.getOrg_index());
                    String[] org_index = orgIndexs.toArray(new String[orgIndexs.size()]);
                    device.getParams().put("org_indexs", org_index);
                }
            }
        } else if (!dept.getOrgIndex().equals("10")) {
            // 如果登录账号不为 hy 账号
            if (device.getOrg_index() == null) {
                orgIndexs = sysDeptMapper.getOrgIndex(dept.getOrgIndex());
                orgIndexs.add(dept.getOrgIndex());
                String[] org_index = orgIndexs.toArray(new String[orgIndexs.size()]);
                device.getParams().put("org_indexs", org_index);
            } else {
                orgIndexs = sysDeptMapper.getOrgIndex(device.getOrg_index());
                orgIndexs.add(device.getOrg_index());
                String[] org_index = orgIndexs.toArray(new String[orgIndexs.size()]);
                device.getParams().put("org_indexs", org_index);
            }
        } else {
            // 如果登录账号为 hy/admin 账号
            if (device.getOrg_index() != null) {
                if (!device.getOrg_index().equals("10")) {
                    orgIndexs = sysDeptMapper.getOrgIndex(device.getOrg_index());
                    orgIndexs.add(device.getOrg_index());
                    String[] org_index = orgIndexs.toArray(new String[orgIndexs.size()]);
                    device.getParams().put("org_indexs", org_index);
                }
            } else {
                if (!dept.getOrgIndex().equals("10")) {
                    orgIndexs = sysDeptMapper.getOrgIndex(device.getOrg_index());
                    orgIndexs.add(device.getOrg_index());
                    String[] org_index = orgIndexs.toArray(new String[orgIndexs.size()]);
                    device.getParams().put("org_indexs", org_index);
                } else if (!com.ruoyi.common.utils.SecurityUtils.isAdmin(userId)) {
                    orgIndexs = sysDeptMapper.getOrgIndex(device.getOrg_index());
                    orgIndexs.add(device.getOrg_index());
                    String[] org_index = orgIndexs.toArray(new String[orgIndexs.size()]);
                    device.getParams().put("org_indexs", org_index);
                }
            }
        }

        PageDomain pageDomain = TableSupport.getPageDomain();
        PageHelper.startPage(pageDomain.getPageNum(), pageDomain.getPageSize(), pageDomain.getOrderBy());
        devices = hDeviceMapper.selectLDeviceList(device);

        return devices;
    }

    @Override
    public int startMonitor(String apeId) {
        if (StringUtils.isBlank(apeId)) {
            throw new ServiceException("apeId 不能为空");
        }

        HDevice existedDevice = hDeviceMapper.selectDeviceByApeId(apeId);
        if (existedDevice == null) {
            throw new ServiceException("设备不存在: " + apeId);
        }

        // GB28181 国标设备:按契约点播并落库 play_url(媒体能力未启用时抛出友好错误)
        if (isGbDevice(existedDevice)) {
            Map<String, Object> gbLive = getGbLiveUrl(apeId);
            Object playUrlObj = gbLive.get("playUrl");
            if (playUrlObj != null) {
                hDeviceMapper.updatePlayUrlByApeId(apeId, String.valueOf(playUrlObj));
            }
            int gbUpdated = hDeviceMapper.updateMonitorStateByApeId(apeId, MONITOR_STATUS_RUNNING);
            if (gbUpdated <= 0) {
                throw new ServiceException("启动监控失败: " + apeId);
            }
            return gbUpdated;
        }

        String startAddProxyUrl = buildDirectAddProxyUrl(existedDevice);
        String startPlayUrl = buildDirectPlayUrl(existedDevice);
        if (isDirectDevice(existedDevice)) {
            Map<String, Object> directLiveInfo = getDirectLiveUrl(apeId);
            boolean addProxyAlreadyExists = Boolean.TRUE.equals(directLiveInfo.get("addProxyAlreadyExists"));
            if (addProxyAlreadyExists) {
                throw new ServiceException("设备监控已经启动过");
            }

            Object playUrlObj = directLiveInfo.get("playUrl");
            Object zlmProxyKeyObj = directLiveInfo.get("zlmProxyKey");
            if (playUrlObj != null) {
                startPlayUrl = String.valueOf(playUrlObj);
            }
            String zlmProxyKey = zlmProxyKeyObj == null ? null : String.valueOf(zlmProxyKeyObj);
            hDeviceMapper.updatePlayUrlByApeId(apeId, startPlayUrl);
            if (StringUtils.isNotBlank(zlmProxyKey)) {
                hDeviceMapper.updateZlmProxyKeyByApeId(apeId, zlmProxyKey);
            }
        }

        int updated = hDeviceMapper.updateMonitorStateByApeId(apeId, MONITOR_STATUS_RUNNING);
        if (updated <= 0) {
            throw new ServiceException("启动监控失败: " + apeId);
        }
        return updated;
    }

    @Override
    public int stopMonitor(String apeId) {
        if (StringUtils.isBlank(apeId)) {
            throw new ServiceException("apeId 不能为空");
        }

        HDevice existedDevice = hDeviceMapper.selectDeviceByApeId(apeId);
        if (existedDevice == null) {
            throw new ServiceException("设备不存在: " + apeId);
        }

        // GB28181 国标设备:停止监控(点播结束通知尽力而为,不阻断状态更新)
        if (isGbDevice(existedDevice)) {
            try {
                ZlmServer zlm = zlmServerMapper.selectEnabledById(
                    existedDevice.getZlm_server_id() == null ? DEFAULT_SERVER_ID : existedDevice.getZlm_server_id());
                if (zlm != null) {
                    gb28181MediaClient.byeChannel(zlm, existedDevice.getApe_id());
                }
            } catch (Exception e) {
                log.warn("GB28181 bye 失败(忽略), apeId={}, err={}", apeId, e.getMessage());
            }
            int gbUpdated = hDeviceMapper.updateMonitorStateByApeId(apeId, MONITOR_STATUS_STOPPED);
            hDeviceMapper.updatePlayUrlByApeId(apeId, null);
            if (gbUpdated <= 0) {
                throw new ServiceException("停止监控失败: " + apeId);
            }
            return gbUpdated;
        }

        boolean directProxyDeleted = false;
        if (isDirectDevice(existedDevice) && StringUtils.isNotBlank(existedDevice.getZlm_proxy_key())) {
            try {
                directProxyDeleted = deleteDirectStreamProxy(existedDevice);
            } catch (Exception e) {
                log.error("调用ZLM delStreamProxy失败, apeId={}, key={}", apeId, existedDevice.getZlm_proxy_key(), e);
            }
        }

        int updated = hDeviceMapper.updateMonitorStateByApeId(apeId, MONITOR_STATUS_STOPPED);
        if (updated <= 0) {
            throw new ServiceException("停止监控失败: " + apeId);
        }

        hDeviceMapper.updatePlayUrlByApeId(apeId, null);
        if (directProxyDeleted) {
            hDeviceMapper.updateZlmProxyKeyByApeId(apeId, null);
        }
        return updated;
    }

    @Override
    public Map<String, Object> previewMonitor(String apeId) {
        if (StringUtils.isBlank(apeId)) {
            throw new ServiceException("apeId 不能为空");
        }

        HDevice device = hDeviceMapper.selectDeviceByApeId(apeId);
        if (device == null) {
            throw new ServiceException("设备不存在: " + apeId);
        }

        // GB28181 国标设备:预览信息(媒体未启用时给出契约约定地址与 gbReady=false)
        if (isGbDevice(device)) {
            Map<String, Object> gbLive = new LinkedHashMap<>();
            gbLive.putAll(getGbLiveUrl(apeId));
            gbLive.put("name", device.getName());
            gbLive.put("monitorStatus", device.getMonitor_status());
            gbLive.put("ipAddr", device.getIp_addr());
            gbLive.put("port", device.getPort());
            gbLive.put("supportedMonitorStatuses", new String[] {
                MONITOR_STATUS_RUNNING,
                MONITOR_STATUS_STOPPED,
                MONITOR_STATUS_STARTING,
                MONITOR_STATUS_STOPPING,
                MONITOR_STATUS_ERROR
            });
            return gbLive;
        }

        String previewAddProxyUrl = buildDirectAddProxyUrl(device);
        String previewPlayUrl = device.getPlay_url();
        if (StringUtils.isBlank(previewPlayUrl)) {
            previewPlayUrl = buildDirectPlayUrl(device);
        }
        if (StringUtils.isBlank(previewPlayUrl)) {
            previewPlayUrl = device.getDirect_source_url();
        }

        Map<String, Object> result = new HashMap<>();
        result.put("apeId", device.getApe_id());
        result.put("name", device.getName());
        result.put("streamSourceType", device.getStream_source_type());
        result.put("monitorStatus", device.getMonitor_status());
        result.put("directSourceUrl", device.getDirect_source_url());
        result.put("playUrl", previewPlayUrl);
        result.put("ipAddr", device.getIp_addr());
        result.put("port", device.getPort());
        result.put("supportedMonitorStatuses", new String[] {
            MONITOR_STATUS_RUNNING,
            MONITOR_STATUS_STOPPED,
            MONITOR_STATUS_STARTING,
            MONITOR_STATUS_STOPPING,
            MONITOR_STATUS_ERROR
        });
        return result;
    }

    private boolean isDirectDevice(HDevice device) {
        return device != null && STREAM_SOURCE_TYPE_DIRECT.equalsIgnoreCase(device.getStream_source_type());
    }

    private String normalizeOrgIndex(String orgIndex) {
        if (StringUtils.isBlank(orgIndex)) {
            return orgIndex;
        }

        String trimmed = orgIndex.trim();
        if (!trimmed.matches("\\d+")) {
            return orgIndex;
        }

        try {
            SysDept dept = sysDeptMapper.selectDeptById(Long.valueOf(trimmed));
            if (dept != null && StringUtils.isNotBlank(dept.getOrgIndex())) {
                return dept.getOrgIndex();
            }
        } catch (NumberFormatException ex) {
            log.warn("org_index 不是有效 deptId，按组织编码原样使用: {}", trimmed);
            return orgIndex;
        }

        return orgIndex;
    }

    private String sanitizeStreamName(String apeId) {
        String stream = STREAM_NAME_PATTERN.matcher(apeId == null ? "" : apeId).replaceAll("");
        if (StringUtils.isBlank(stream)) {
            return "cam" + System.currentTimeMillis();
        }
        return stream;
    }

    private String buildDirectAddProxyUrl(HDevice device) {
        if (device == null || !STREAM_SOURCE_TYPE_DIRECT.equalsIgnoreCase(device.getStream_source_type())
            || StringUtils.isBlank(device.getDirect_source_url())) {
            return "";
        }

        ZlmServer zlmServer = resolveEnabledZlmServer(device);
        if (zlmServer == null || StringUtils.isBlank(zlmServer.getHost()) || zlmServer.getApi_port() == null) {
            return "";
        }

        String zlmApp = StringUtils.isBlank(zlmServer.getApp()) ? DEFAULT_ZLM_APP : zlmServer.getApp().trim();
        String stream = sanitizeStreamName(device.getApe_id());
        return UriComponentsBuilder
            .fromUriString("http://" + zlmServer.getHost() + ":" + zlmServer.getApi_port() + "/index/api/addStreamProxy")
            .queryParam("vhost", "__defaultVhost__")
            .queryParam("app", zlmApp)
            .queryParam("stream", stream)
            .queryParam("url", device.getDirect_source_url())
            .queryParam("enable_mp4", 1)
            .queryParam("auto_close", 0)
            .queryParamIfPresent("secret", StringUtils.isNotBlank(zlmServer.getSecret())
                ? java.util.Optional.of(zlmServer.getSecret())
                : java.util.Optional.empty())
            .build(true)
            .toUriString();
    }

    private String buildDirectPlayUrl(HDevice device) {
        if (device == null || !STREAM_SOURCE_TYPE_DIRECT.equalsIgnoreCase(device.getStream_source_type())) {
            return "";
        }

        ZlmServer zlmServer = resolveEnabledZlmServer(device);
        if (zlmServer == null || StringUtils.isBlank(zlmServer.getHost()) || zlmServer.getMedia_http_port() == null) {
            return "";
        }

        String zlmApp = StringUtils.isBlank(zlmServer.getApp()) ? DEFAULT_ZLM_APP : zlmServer.getApp().trim();
        String stream = sanitizeStreamName(device.getApe_id());
        return "ws://" + zlmServer.getHost() + ":" + zlmServer.getMedia_http_port() + "/" + zlmApp + "/" + stream + ".live.flv";
    }

    private ZlmServer resolveEnabledZlmServer(HDevice device) {
        if (device == null) {
            return null;
        }
        Long zlmServerId = device.getZlm_server_id() == null ? DEFAULT_SERVER_ID : device.getZlm_server_id();
        return zlmServerMapper.selectEnabledById(zlmServerId);
    }

    private boolean deleteDirectStreamProxy(HDevice device) {
        ZlmServer zlmServer = resolveEnabledZlmServer(device);
        if (zlmServer == null || StringUtils.isBlank(zlmServer.getHost()) || zlmServer.getApi_port() == null) {
            log.error("删除代理流失败，设备未绑定可用ZLM服务器或配置缺失, apeId={}", device.getApe_id());
            return false;
        }

        String delProxyUrl = UriComponentsBuilder
            .fromUriString("http://" + zlmServer.getHost() + ":" + zlmServer.getApi_port() + "/index/api/delStreamProxy")
            .queryParam("key", device.getZlm_proxy_key())
            .queryParamIfPresent("secret", StringUtils.isNotBlank(zlmServer.getSecret())
                ? java.util.Optional.of(zlmServer.getSecret())
                : java.util.Optional.empty())
            .build(true)
            .toUriString();

        if (log.isDebugEnabled()) {
            log.debug("调用ZLM delStreamProxy, apeId={}, url={}", device.getApe_id(), maskSensitiveUrl(delProxyUrl));
        }

        ResponseEntity<String> response = restTemplate.getForEntity(delProxyUrl, String.class);
        String body = response.getBody();
        if (StringUtils.isBlank(body)) {
            log.error("调用 ZLM delStreamProxy 返回空响应, apeId={}", device.getApe_id());
            return false;
        }

        try {
            JsonNode root = OBJECT_MAPPER.readTree(body);
            int code = parseCode(root.path("code").asText());
            boolean flag = root.path("data").path("flag").asBoolean(false);
            if (code == 0 && flag) {
                return true;
            }
            String msg = root.path("msg").asText("");
            log.error("调用 ZLM delStreamProxy 失败, apeId={}, key={}, code={}, flag={}, msg={}",
                device.getApe_id(), device.getZlm_proxy_key(), code, flag, msg);
        } catch (Exception e) {
            log.error("调用 ZLM delStreamProxy 响应解析异常, apeId={}, key={}",
                device.getApe_id(), device.getZlm_proxy_key(), e);
        }
        return false;
    }

    private boolean isAddProxyAlreadyExists(String msg) {
        if (StringUtils.isBlank(msg)) {
            return false;
        }
        String lower = msg.toLowerCase();
        return lower.contains("already exists");
    }

    private int parseCode(Object code) {
        if (code instanceof Number) {
            return ((Number) code).intValue();
        }
        if (code == null) {
            return -1;
        }
        try {
            return Integer.parseInt(String.valueOf(code));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String maskSensitiveUrl(String url) {
        if (StringUtils.isBlank(url)) {
            return url;
        }
        return url.replaceAll("(?i)([?&](secret|token|access_token|auth|sign|signature)=)[^&]*", "$1***");
    }
}
