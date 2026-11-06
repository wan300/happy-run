# 乐跑伴跑（Android）

把 PC 上已跑通的链路迁移为**未 root 安卓真机上独立运行**的应用：获取并维护登录态 → 读取学校跑步配置 → 模拟生成轨迹/步数/传感器 → 上传并结束提交 → 回查官方记录。

> 当前状态：代码已按 PC 验证过的协议与流程实现，**尚未做真机联调**。PC 端已在线验收的记录是 `259921`（2.03 km / 741 s / 1690 步，`record_status=1`，`Run/recordDetailV270` 全项匹配）。Android 端需要在真机上复验登录捕获、代理兼容性、前台服务存活和真实写入，清单见 [docs/acceptance.md](docs/acceptance.md)（由 QA 生成）。

## 构建

```bash
cd android
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # 纯 JVM 单测（协议对拍、轨迹、状态机）
```

环境：JDK 17、Android SDK（compileSdk 35 / build-tools 36.1.0）、Gradle 8.9（wrapper）、AGP 8.7.3、Kotlin 2.0.21。minSdk 29（Android 10），targetSdk 35。依赖：AppCompat、OkHttp、Coroutines、BouncyCastle（本地证书）。

## 首次使用

1. 安装 APK，允许通知权限（Android 13+）。
2. 关闭 Clash 等其他 VPN（本应用需要独占 VPN）。
3. 点「获取登录态」→ 允许 VPN 授权 → 点「打开乐跑」→ 在官方应用里正常登录（微信/QQ/手机号均可）→ 进入乐跑首页/跑步页停留几秒 → 返回本应用 → 点「完成并保存」。
   - 采集只放行 `com.lptiyu.tanke`，仅在本机 `127.0.0.1` 上终止 TLS；**不修改系统证书、不安装用户 CA、不需要 root**。
   - 最长采集 10 分钟，超时自动停止。
4. 回到首页点「刷新配置」。如果当前跑区在北京交通大学其他校区，点「切换东校区」并确认。应用会重新读取东校区的时段、里程和打卡规则。
5. 规划通过后点「开始跑步」，二次确认后等待约 13 分钟（前台服务 + wake lock）。
6. 结束后自动上传截图/轨迹/传感器、提交结束请求并回查。首页会显示 `record_id` 与 8 项回查结果；可在官方乐跑「我的记录」中看到同一条记录。

日常使用只需第 3 步（登录态失效时）与第 5 步；未结束/结果不确定的会话会阻止开始新跑步，避免重复写入。若会话已无法自动恢复，可在首页先点「打开乐跑」核对是否已有记录，再用「放弃本次会话（仅本地）」解除阻塞：该操作不发送请求、不删除服务端记录，只在本地把会话标记为放弃。

## 设计要点

- **登录态**：本地 VPN 只放行官方乐跑，通过 VPN 的 HTTP 代理 → 本地 CONNECT 代理 → 自签证书终止 TLS，解析登录响应与一条成功业务请求，得到 `access_token/refresh_token`、身份字段、UMID、Cookie、UA。官方应用在 PC 实测接受未安装的新 CA，故无需改系统证书。
- **存储**：AES-256-GCM + Android Keystore，临时文件 + fsync + 原子替换；审计日志逐行加密。应用重启后恢复会话与未完成状态。
- **协议**：`core/Protocol.kt` 是 PC `lepao_client/protocol.py` 的逐字节移植（MD5 签名、AES-CBC、Android 式 JSON 斜杠转义、MIME Base64、动态字段、记录签名），由 Python 生成的黄金夹具做 JVM 对拍。
- **东校区轨迹**：从西侧直道出发，逆时针沿卫星影像描绘的闭环跑道累计到 2.20 km。轨迹横向偏移最多 0.45 m。学校里程区间最多调整目标 20 m，配速按时段规则夹取。打卡点可在不同圈匹配，必经点和启用的围栏必须校验通过。保存的旧会话使用原走廊重建。
- **状态机**：`new → prepared → start_pending → running → uploading → stop_pending → stop_acknowledged → sensor_pending → submitted → verified`。所有写请求先落盘；超时/未知结果进入 `uncertain` 并禁止自动重发。
- **回查**：REST 只读 `Run/recordDetailV270`，核对账号、起跑时间、跑区、`record_status`、里程、用时、步数、轨迹对象键。学期统计（RunStat/H5）不在本应用校验范围，界面单独标注。

## 目录

```
app/src/main/java/com/lepao/companion/
  core/      协议/会话/规则/状态/加密存储/HTTP/OSS
  capture/   VPN + 本地 MITM 代理 + 登录态提取
  run/       规划/轨迹/材料/状态机/前台服务/回查
  ui/        MainActivity + CaptureActivity
app/src/main/assets/corridor.json            旧版校园走廊
app/src/main/assets/east-campus-track.json   东校区田径场闭环（GCJ-02）
docs/        架构、采集、跑步引擎、UI、验收规格
```

## 边界

- 首次登录必须由官方乐跑完成；本应用不伪造第三方授权、不接管账号密码。
- 新建跑步计划仅支持北京交通大学东校区。规则要求人脸、设备、极验或抓拍等尚未实现的分支时停止。
- 轨迹与传感器是合成的，不以本人真实运动为前提；写入的是官方服务上的一条真实记录，但**是否计入学期统计**需在官方应用另行核对。
- 真机验证尚未执行；不要在没有核对官方记录的情况下把“上传成功/HTTP 200”当作验收结果。

## 相关文档

- [架构](docs/architecture.md)
- [登录态采集规格](docs/capture.md)
- [跑步引擎与轨迹规格](docs/run-engine.md)
- [UI 规格](docs/ui.md)
- [验收与测试规格](docs/verification.md)
