# 登录态采集（capture slice）实现规格

范围：`app/src/main/java/com/lepao/companion/capture/**`、`app/src/test/java/com/lepao/companion/capture/**`。不得修改 `core/**`、`run/**`、`ui/**`、`res/**`、Gradle 文件；需要接口变化时先告知 Lead。

目标：用户点击“获取登录态”，授权 VPN，打开官方乐跑完成正常登录；本应用在手机本地解密并解析登录响应和随后的一条业务请求，得到同一账号的 `access_token`、`refresh_token`、身份字段、UMID、Cookie、UA，写入 `CaptureStore`，随后由 UI 调用 `CaptureController.assembleSession()` 生成 `SessionContext` 并保存。

## 1. 已证实的抓包条件（PC 实测，2026-11-04）

- 真机 Android 12、乐跑 4.3.2，未安装新 CA；电脑生成的全新 CA + 全局 HTTP 代理即可解密 `api2.lptiyu.com` 的业务 HTTPS。说明官方应用未做证书固定，且接受任意服务端证书。
- 官方应用**遵循系统 HTTP 代理**（OkHttp 默认 ProxySelector）。因此本地 `VpnService.Builder.setHttpProxy(ProxyInfo)` + 本地 CONNECT 代理是可行的，不需要自己实现 TCP 协议栈。
- 已捕获的登录/续期接口：`Login/RefreshToken`（响应 data 为 AES 密文，字段 `uid/access_token/refresh_token/refresh_expire`）。首次登录走 `Login/quickLoginV339` 或 `Login/LoginV14`，字段兼容处理即可。

## 2. 组件

| 文件 | 职责 |
| --- | --- |
| `CaptureVpnService.kt` | 前台 VpnService；建立 TUN、启动/停止代理与 DNS 中继；状态与通知；`onRevoke` 清理 |
| `LocalMitmProxy.kt` | 127.0.0.1 上的 HTTP 代理：CONNECT、TLS 终止、HTTP/1.1 转发、非目标域名盲转发 |
| `TlsTerminator.kt` | 用 `SSLServerSocket` + 双向管道把客户端 TLS 连接变为明文 HTTP 流 |
| `CertAuthority.kt` | 首次运行用 BouncyCastle 生成 CA + `api2.lptiyu.com` 服务端证书，PKCS12 加密落盘 |
| `DnsRelay.kt` | 处理 TUN 中的 IPv4/UDP:53，转发到系统 DNS 并写回；顺带统计“直连 443”用于诊断 |
| `CaptureExtractor.kt` | 纯 Kotlin：解析表单/密文请求，提取身份字段、UMID；解析登录响应 token；请求身份一致性 |
| `CaptureController.kt` | 已定义 UI 契约，补齐 `status()/assembleSession()` 等 |

所有解析/提取逻辑必须放在不依赖 Android 的类里，便于 JVM 单测。

## 3. VPN 建立

`CaptureVpnService.onStartCommand`：

1. `startForeground(1001, notification)`（先于任何耗时操作）。
2. 若 `VpnService.prepare(this) != null`（未授权）→ 置 `CaptureStatus(ERROR, error="需要 VPN 授权")` 并停止；授权由 UI 在启动前完成。
3. 启动 `LocalMitmProxy`（绑定 `127.0.0.1:0`，得到实际端口）与 `CertAuthority.ensure()`。
4. `Builder()`
   - `.setSession("lepao-capture")`
   - `.setMtu(1500)`
   - `.addAddress("10.66.0.2", 32)`
   - `.addRoute("0.0.0.0", 0)`（只对放行应用生效）
   - `.addDnsServer("10.66.0.1")`
   - `.addAllowedApplication("com.lptiyu.tanke")`（若未安装 → 状态错误“未安装官方乐跑”）
   - `.setHttpProxy(ProxyInfo("127.0.0.1", proxyPort))`（API 29+，本应用 minSdk 29）
   - `.setBlocking(true)`（**必须显式设置**：Builder 默认是非阻塞，空闲 read 会返回 0/EAGAIN）
   - `.establish()`
5. 启动 TUN 读取线程（`DnsRelay` + 诊断统计）。
6. 状态 `RUNNING`，通知文案“等待官方乐跑登录流量”。
7. 最长采集 10 分钟，超时自动停止（保留已捕获材料）。

注意：

- 不要再 `addDisallowedApplication`（与 allow 列表互斥），本应用天然不在放行名单内。
- 若系统已有其他 VPN（Clash 等），`VpnService.prepare` 会要求用户切换；UI 提示先关闭其他 VPN。可在 `onStartCommand` 用 `ConnectivityManager` 检测 `TRANSPORT_VPN` 并给出提示。
- `onRevoke()` / `onDestroy()`：关闭代理、DNS、TUN，`stopForeground(STOP_FOREGROUND_REMOVE)`，状态置 `IDLE`（保留材料），不得崩溃。
- 通知与状态更新频率 ≤ 1 次/秒。

## 4. 本地代理

### CONNECT

读取首行与头部（上限 64 KiB）。`CONNECT api2.lptiyu.com:443 HTTP/1.1`：

1. 回写 `HTTP/1.1 200 Connection established\r\n\r\n`。
2. 目标域名为 `api2.lptiyu.com`（大小写不敏感，允许 443）→ TLS 终止后按 HTTP/1.1 处理。
3. 其他 `host:port` → 建立真实 TCP，之后原样双向拷贝字节（不解密、不记录内容）。这样 OSS/CDN 等上传下载仍可工作。
4. 其他方法/绝对 URI 的明文 HTTP：目标为 `api2.lptiyu.com` 时同样转发，否则盲转发；若无法解析则回 400。

### TLS 终止（推荐实现）

每个 MITM 连接：

1. 创建一个绑定 `127.0.0.1:0` 的 `SSLServerSocket`（`SSLContext` 使用 `CertAuthority` 的 KeyManager，不要求客户端证书）。
2. 用普通 `Socket` 连接该端口，起两个线程在“客户端原始 socket ↔ 本地 SSL socket”之间拷贝（16 KiB 缓冲，`shutdownOutput` 传播半关闭）。
3. 在 `SSLServerSocket.accept()` 得到的 `SSLSocket` 上读取明文 HTTP 请求。
4. 结束/异常时关闭全部 socket 与 2 个拷贝线程。

不允许把客户端原始 socket 直接当成明文 HTTP 读；握手必须完成。

### HTTP 转发

- 解析请求行/头部/`Content-Length` 请求体；上限：头 64 KiB、体 2 MiB。
- 用固定 `OkHttpClient`（`Proxy.NO_PROXY`、`retryOnConnectionFailure(false)`、连接 10 s / 读 25 s）转发到 `https://api2.lptiyu.com` + 原 path（含 query）。
- 请求头：保留 `Cookie`、`User-Agent`、`Accept`、`Accept-Language`、`Content-Type`；丢弃 `Host`、`Connection`、`Proxy-Connection`、`Content-Length`、`Transfer-Encoding`、`Accept-Encoding`（让 OkHttp 解压）。方法原样（POST/GET）。
- 响应回给官方应用：状态行 + 头（去掉 `Connection`、`Transfer-Encoding`、`Content-Length`、`Content-Encoding`）+ 重新计算的 `Content-Length` + body。`Set-Cookie` 必须原样保留。
- 不得修改业务 JSON 内容；只有头部按上述规则规范化。
- 单连接支持 keep-alive 多请求循环（`Connection: close` 时结束）。
- 每连接独立线程/协程，异常只影响该连接；不得让异常冒泡终止服务。

## 5. 抓取内容与判定

每完成一次 `api2.lptiyu.com` 的请求/响应，调用 `CaptureExtractor`：

### 5.1 登录响应（token 对）

- 命中路径包含 `RefreshToken` / `quickLogin` / `LoginV14` 且 HTTP 200。
- 响应 JSON 外层 `status == 1`；`data` 若为字符串 → `Protocol.decryptText(data, "NEW_GAME")` 后解析；若为对象直接使用。
- 递归查找 `access_token`、`refresh_token`、`refresh_expire`、`uid`。兼容 `token` 作为 access token 的别名（仅当 `access_token` 缺失）。
- 至少要有 `access_token` 与 `refresh_token` 才算命中；`refresh_expire` 缺失则存 null。
- 记录 `CapturedTokenPair(endpoint, capturedAt=now)`；同一响应重复到达时以最新为准；不得把非登录接口的 `token` 当刷新令牌。

### 5.2 业务请求上下文（identity + UMID）

请求体两种形态：

- `key=<密文>`：用 `Protocol.decryptText(key, "PARAMS_NEW_GAME")` 得到 JSON（其中 `/` 被转义为 `\/`，`JSONObject` 可正常解析）。
- 普通表单：`name=value&...`，直接 URL 解码。

判定为有效上下文：字段包含全部 `COMMON_FIELDS`，且存在形如 `lp[a-z0-9]{2}` 的动态字段。

- 动态字段名 = `Protocol.dynamicName(mobileModel, timestamp)`。
- 动态字段值用 `DYNAMIC_KEYS[timestamp % 15]` AES-CBC 解密 → 明文应为 `umid + md5(timestamp)`；校验后缀，取前缀为 UMID。
- 排除 `Run/stopRunV278`：其动态字段值是 formal start 的 `str`，不是 UMID。
- 同一次采集内若出现多个候选，取时间最新者；若 UMID 或 uid/token 互相矛盾，保留最新并追加 warning。
- 记录 `User-Agent`；`Cookie` 请求头解析为 `SessionCookie`（domain 固定 `api2.lptiyu.com`）。
- 响应头 `Set-Cookie` 更新 cookie（同名覆盖）。
- 若观测到 `Run/stopRunV278`，把明文里的 `oaid`、`mac` 存入 `deviceFields`。

### 5.3 身份一致性

- 所有捕获字段必须属于同一 uid/token；token 变化时以最新请求为准并清空 UMID 候选不匹配的旧值。
- `assembleSession()` 只在 10 个 COMMON_FIELDS + UMID 齐全时返回；token 对可选（当前账号可能只有 access token），但 `tokenPairSeen` 状态要准确。

## 6. 状态与持久化

`CaptureStore.updateStatus(...)` 阶段：

| 阶段 | 条件 | 建议提示 |
| --- | --- | --- |
| `STARTING` | 服务启动 | 正在建立本地采集通道 |
| `RUNNING` | VPN 已建立 | 打开乐跑并完成登录 |
| `LOGIN_SEEN` | token 对已捕获 | 登录响应已捕获，进入乐跑首页/跑步页补全设备上下文 |
| `CONTEXT_READY` | identity + UMID + cookies 齐全 | 登录态已就绪，可返回本应用完成 |
| `ERROR` | 授权/代理/应用缺失等 | 具体原因 |

- `CaptureStore.updateMaterial(...)` 每次更新都会加密落盘；进程重启后 `CaptureStore.restore()` 可恢复。
- 状态里的 `endpoints` 保留最近 10 个 endpoint（去重、按时间），用于 UI 显示进度。
- 不在明文日志里输出 token/cookie/UMID。

## 7. 证书

`CertAuthority.ensure(context)`：

- 首次：EC P-256（或 RSA 2048）生成 CA（CN=`Lepao Companion Local CA`，10 年，BasicConstraints CA=true）与叶子证书（CN=`api2.lptiyu.com`，SAN=dns:api2.lptiyu.com，10 年，EKU serverAuth）。
- PKCS12 存于应用私有目录，使用 `SecureStore` 加密保存（或固定本地口令 + 文件权限，二者择一，优先 SecureStore）。
- 提供 `serverContext(): SSLContext` 与 `keyManagers()`。
- 证书只在本机运行期使用，不导出、不引导安装到系统。

## 8. 诊断

- TUN 线程统计：目标应用直连 TCP 443 的 SYN 数（排除代理端口）。若 >0 且 30 s 内无代理请求，状态 `ERROR`/hint：“官方应用未走代理，请停止后重试”。
- 记录代理 accept/转发错误计数到 `DiagLog`（不含敏感内容）。
- 若官方应用被杀死/切换网络，代理保持可用；VPN 重建由系统触发时重新 `establish`（可简化为停止并提示重试）。

## 9. 测试（必须）

纯 JVM 测试放 `app/src/test/java/com/lepao/companion/capture/`：

1. `CaptureExtractorTest`
   - 用 `Protocol.buildRequest(Endpoints.BEFORE_RUN, identity, emptyMap(), ts)` 生成真实形态的加密请求，再从表单 `key` 提取 identity/UMID，断言与输入一致。
   - 普通表单请求（如 `Run/getTimestampV278`）不产生 UMID。
   - `Run/stopRunV278` 的动态字段不被当作 UMID。
   - 加密的登录响应（用 `Protocol.encryptText` 构造 `{"status":1,"data":"..."}`）能提取 token 对；明文对象响应也能提取；缺 refresh_token 不命中。
2. `HttpWireTest`（若实现解析器为纯 Kotlin）：CONNECT 行、头部、Content-Length body 的解析与响应拼装。
3. `DnsPacketTest`：IPv4/UDP DNS 查询解析、响应构造、校验和。
4. `CertAuthorityTest` 可跳过（依赖 Android Keystore）。

## 10. 完成标准

- `./gradlew :app:assembleDebug` 通过。
- 上述单测通过。
- 代码中无明文 token/cookie 日志；退出/异常路径不泄漏线程与 socket。
- UI 契约保持：`CaptureController.status()/prepareConsent()/startService()/stop()/assembleSession()/openOfficialApp()`。
- 在 README/`docs/acceptance.md` 记录“未真机验证”的项，不得声称已在真机完成登录捕获。
