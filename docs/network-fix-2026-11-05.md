# 1.0.4 Release：采集网络与入口时间窗修复

修复目录：`D:/code/project/happy-run/android`。聊天初始目录 `D:/code/project/lepao/android` 仍是旧工程，未将两个目录混合覆盖。

## 诊断结论

报告中的 1.0.2 已包含 DNS 单地址路由、KeyStore/KeyManagerFactory 和非目标 CONNECT 透传。但检查源码又发现：

1. `ThreadPoolExecutor(2, 8, queue=64)` 先排队再扩容，常见负载实际只运行两个工作线程。长连接透传占住这两个线程后，目标接口即使未达到连接上限也会等待。
2. `TlsTerminator` 在 TLS 握手及应用数据阶段遇到半条记录时，没有继续读取剩余字节，可能在 `BUFFER_UNDERFLOW` 状态空转。真实网络分片更容易触发此问题。
3. 一秒 keep-alive 超时会关闭闲置连接；原实现还会把同一超时带入下一条请求正文的读取。
4. 非目标普通 HTTP 仍返回 502。CONNECT 虽然能透传，但工作线程及额外泵线程被长期占用。
5. DNS 使用两个工作线程、无界队列、每查询新建 socket，没有缓存。单个上游超时会放大并发请求等待。

报告中“所有上游请求都固定为 HTTP/1.1”与本目录的代码不一致：OkHttp 未禁用上游 HTTP/2。应用到本地 TLS 终止器的连接采用 HTTP/1.1；这是两个不同的连接阶段。

## 已落实的修改

- 保留 1.0.2 的 `10.66.0.1/32` 路由和 `KeyManagerFactory` 修复。
- TLS 半包时继续读网络；缓冲区溢出显式失败，避免静默丢数据。按现有阻塞实现的设计明确使用 TLS 1.2。
- 目标域名 keep-alive 延长至 15 秒。下一条请求首字节到达后恢复 30 秒请求读取超时。
- 代理使用最多 16 个工作线程、直接交接任务，不再让请求在 64 长度队列中等待闲置连接。达到容量上限时关闭新连接并记录容量日志。
- 新增 `TunnelRelay`：一个 Selector 线程承载最多 24 条非目标隧道，支持背压、双向传输和半关闭；空闲 60 秒释放。非目标普通 HTTP 也透传，不采集正文。
- 停止采集时主动关闭代理 socket、透传连接、DNS socket 和 OkHttp 连接池。
- DNS 使用 4 个工作线程和 32 项有界队列；每个工作线程/上游复用已保护的 connected UDP socket。单次上游等待 1.5 秒，包含排队的查询总预算 4 秒，最多尝试两个上游。
- 新增最多 128 项正向 DNS 缓存，遵循并递减 TTL，最长缓存 60 秒。错误、截断、零 TTL 或不支持的回答不缓存。校验响应来源、事务 ID 和问题段，避免复用 socket 时错配迟到响应。
- `CaptureNetwork` 日志记录 CONNECT、TLS、上游响应头、完整响应、总请求及 DNS 排队耗时。请求 ID 可关联上游与完整请求日志；不输出凭据、Cookie、请求头或正文。

没有采用 VPN PAC：Android 的 `VpnService.Builder.setHttpProxy` 明确不支持 PAC。采用有界透传而非“代理返回 DIRECT”；后者也不是 CONNECT 协议中的直连指令。

官方依据：

- [Android VpnService.Builder.setHttpProxy](https://developer.android.com/reference/android/net/VpnService.Builder#setHttpProxy(android.net.ProxyInfo))
- [Java ThreadPoolExecutor 队列行为](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html)

目标 API 响应仍保留最多 8 MB 的整包缓冲以支持现有采集流程；未获得真机计时证据前，没有重写为流式采集。非目标隧道通过 Selector 直接转发字节，不受这个缓冲限制。

## 22:00 入口截止

新增统一的 `RunEntryWindow.effectiveEnd`，取服务器截止时间与 22:00 的较早值：

- 首页显示、缓存配置重新读取、运行前检查和规划器均使用这一截止时间。
- 22:00 整及之后不允许新起跑；更早的服务器限制不被延长。
- 原始服务器 JSON 保留不变，便于审计；这不是修改服务器规则。

## 验证结果与边界

执行 `gradlew.bat testReleaseUnitTest assembleRelease lintRelease` 成功：

- 148 项测试，0 失败、0 错误、0 跳过。
- 回归覆盖真实环回 TLS、生产 `CertAuthority.serverContext()`、TLS 分片、超过一秒的连接复用、24 条透传隧道与目标 API 并行、容量拒绝、双向半关闭、停止释放、普通 HTTP 透传、DNS 缓存过期/错配/超时/socket 复用，以及 22:00 边界。
- lint 无错误，26 条警告未在本轮全部清理。
- 1.0.3 Release 已在华为 ADA-AL00U（Android 12）覆盖安装。用户确认“登录正常，不需要重复测试了”，据此停止重复登录和独立 TLS 探针测试。
- 不将用户登录确认等同于完成量化性能对比：本轮没有采集真机 CONNECT/TLS/上游耗时，也没有宣称页面加速比例。

可选的后续诊断脚本：`tools/probe_capture_tls.py`，仅在用户要求继续测量且手机已开始采集时运行。脚本通过临时 adb forward 发出两次无凭据的 `GET /`，间隔两秒，输出阶段耗时并清理转发。TLS 测试仅信任本机代理；代理向真实上游仍使用正常证书校验。此次没有运行该真机探针。

## 发布交付

- 安装包：`dist/lepao-1.0.4-release.apk`
- 包名：`com.lepao.companion`
- 版本：1.0.4，versionCode 5
- 最低系统：Android 10 / API 29
- 独立 APK，无 split、debuggable 或 testOnly 标记；不依赖 ADB 或开发服务运行。
- 沿用原发布证书，APK Signature Scheme v2 验证通过。
- APK SHA-256：`3306f8119e3a0292844bea0f9ef5d871d26b1e690922449be47910ffd6cc547c`
- 发布证书 SHA-256：`0ccd51229463c3178fee3eaf17b49f180b222960293a44c433f11861bd5ef760`

1.0.4 已在华为覆盖安装成功。安装后核对 versionCode 5、versionName 1.0.4，包标记无 DEBUGGABLE；设备上已安装 APK 的 SHA-256 与交付文件完全一致。向华为更新和向用户交付使用同一个 `dist` 文件。设备安装只允许正式 Release 包的要求已写入 `android/AGENTS.md`。

修改前采集源码备份保留于 `diagnostics/before-network-fix-20261105/`。本目录不是 Git 工作树，因此本轮没有提交或推送。
