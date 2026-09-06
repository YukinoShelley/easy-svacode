# GB28181 国标接入 · 可融合代码与接口（easy-svacode / 分支 G28181国标接入）

> 角色1·流媒体（SVA-mediaServer / ZLMediaKit）+ WVP(SIP 信令) 的 GB28181 接入方案。
> 目标：让团队**直接融合**——把本包内容并入现有工程即可接入 GB/T 28181 国标设备。

## 方案要点（已端到端验证）
- **媒体**：SVA-mediaServer（ZLMediaKit）**原生支持** GB28181（`GB28181Process` 解 PS-over-RTP → 出 rtsp/rtmp/fmp4/ts 流）。
- **信令**：GB28181 的 SIP（5060 注册/保活/INVITE）由 **WVP**（或独立 SIP server）承担（主流 ZLMediaKit 主库**无** GB28181 SIP 模块）。
- **数据流**：`国标设备→SIP(WVP)→INVITE→PS/RTP→ZLM→rtp/{设备_通道}→rtsp/flv 可播`。
- **接口**：前端用 HTTP-FLV、分析器用 RTSP 拉流，与现有 RTSP 拉流一致。

## 目录
| 路径 | 内容 |
|------|------|
| `接口文档/` | 流媒体侧接口与能力契约、前端/设备接口契约、P0 验证手册 |
| `工具/` | `verify_zlm_gb28181.sh`(无真机自证媒体能力)、`ps_rtp.py`(PS-over-RTP打包器)、`ffps.sh`(GB28181客户端切PS推流)、`application-dev.yml.template` |
| `WVP补丁/` | `MediaServiceImpl.java`(放行国标RTP流) + `补丁说明.md` |

## 融合步骤（只需 2 处）
1. **WVP 侧**：按 `WVP补丁/补丁说明.md` 应用 `authenticatePublish` 放行国标 RTP 流；并将 WVP 配置 `media.id` 设为 **ZLM `[general] mediaServerId`**（当前环境为 `Aodpt9CbTmrRBOMv`），否则 WVP on_publish 报"找不到 mediaServer"。
2. **ZLM 侧**：无需改源码（GB28181Process 原生支持）；确保 `[hook]` 的 on_publish 指向能放行国标流的后端/WVP。

## 验证
```bash
bash 工具/verify_zlm_gb28181.sh   # 无需真机、无需 root，起临时ZLM自证媒体接入
```
结果：`✅ PASS —— ZLM 已能接 GB28181 PS-over-RTP 并出流(H.264)`。
