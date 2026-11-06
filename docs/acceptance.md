# Android 迁移验收记录（自动验证与真机复验清单）

> **结论声明：本应用尚未做真机验收。** 本文只记录可重复的 JVM 单测、构建产物与静态审查结论。
> “编译通过 / 单测通过”不等于“登录捕获、OSS 上传、stop 写入、前台服务存活已在真机通过”。

- 环境：Windows；JDK 17；Gradle 8.9（wrapper）；AGP 8.7.3；Kotlin 2.0.21；compileSdk 35 / minSdk 29。
- 参考实现：仓库根 `lepao_client`（PC 上已在线验收的 Python 客户端）。
- 测试命令：`cd android && ./gradlew :app:testDebugUnitTest --no-daemon`
- 构建命令：`cd android && ./gradlew :app:assembleDebug --no-daemon`
- 本记录日期：2026-11-04。

## 1. 已自动验证项

### 1.1 结果汇总

- `:app:testDebugUnitTest`：**158 个用例全部通过，0 失败 / 0 跳过 / 0 错误**（2026-11-06 复跑，含打卡点 20 m 门限与绕行用例）。
- `:app:assembleDebug`：**成功**；产物 `android/app/build/outputs/apk/debug/app-debug.apk`。
- 协议黄金夹具：`android/tools/gen_fixtures.py` → `android/app/src/test/resources/fixtures/protocol_golden.json`
  （仅假数据：固定 identity / UMID / `A`×32 token / 1793778542 / nonce 123456 / timeStr=`e`×32）。
  重新生成命令：`D:/conda/python.exe android/tools/gen_fixtures.py`（仓库根目录执行）。

| 测试类 | 用例数 | 结果 | 覆盖内容 |
| --- | ---: | --- | --- |
| `qa.ProtocolGoldenTest` | 12 | 通过 | 8 个 endpoint 与 Python 逐字节对拍：fields/顺序/字符串化/body 字节；4 个密文请求的 `PARAMS_NEW_GAME` 明文；stop 38 字段；formEncode；recordSignature；dynamicName/dynamicValue；b64Mime(76 列+换行)；jsonQuote/md5；decodeResponse 明文/密文/status!=1/不可解密；tryDecryptEnvelopeData |
| `core.ProtocolReferenceTest` | 3 | 通过 | Lead 独立生成的第二份参考夹具（8 endpoint 字节对拍、签名/动态值/form 编码） |
| `run.RouteEngineTest` | 21 | 通过 | 走廊长度 2070.8 m、`at()` 端点/插值；同 seed 字节确定性；单向遍历无 wrap 瞬移（单程起终点落在走廊两端、轨迹贴走廊 ≤ 参考阈值）；里程/速度>0；轨迹 schema；打卡点绕行（60–100 m 外合成点位最近采样点 <10 m、绕行确定性、起点偏移收窄、不可达点位忽略、2 s 位移无瞬移） |
| `run.RouteArtifactsTest` | 8 | 通过 | route 体可被 `Protocol.decryptText` 还原为 JSON 数组且 schema 精确；sensor 行数 `duration+1`、`t` 起点毫秒；PNG 头 `89 50 4E 47 0D 0A 1A 0A`、IHDR 720×1080、关键色存在 |
| `run.RunEngineFsmTest` | 20 | 通过 | 正常路径（心跳 60/120/240/480 → 上传 → stop → 传感器 → 回查）；写前落盘（LOCATION_PENDING/STOP_PENDING/upload_pending）；stop/location/PUT 超时不重发；恢复矩阵（prepared/start_pending/location_pending/stop_pending/sensor_pending/uploading±upload_pending/stop_acknowledged/submitted/verified/failed）；stop 校验失败禁止继续登记；STS 凭据重试边界（仅凭据调用） |
| `run.StopFieldsTest` | 6 | 通过 | stop 38 字段精确组装（deviceFields 回填、record_str 规则、测量/材料字段、保留字段不可覆盖请求上下文） |
| `run.ManifestValidatorTest` | 12 | 通过 | manifest 字段集合、距离两位小数、时间/步数/打卡/配速/里程/IP 校验 |
| `run.AutoPlannerTest` | 21 | 通过 | 固定画像 2.03 km / 365 s/km / 832.5 步、规则夹取、时间窗/星期/唯一时段、20 m 打卡门限内外、绕行目标选择、`plan.via` 持久化与旧版 plan 兼容、blockers/warnings |
| `run.VerifierTest` | 9 | 通过 | stop 5 项 / detail 8 项回查、数值类型宽容、URL decode 后对象键匹配、record_id 数字校验 |
| `capture.CaptureExtractorTest` | 10 | 通过 | 加密请求提取 identity+UMID（后缀 md5 校验）、普通表单不产 UMID、stop 动态字段不被当 UMID、加密/明文登录响应 token 对、token 别名规则、Cookie 解析 |
| `capture.CaptureSessionTest` | 9 | 通过 | 状态相位、账号切换丢弃旧 UMID、token 族不匹配禁用自动续期、endpoint 去重计数、stop 提供 deviceFields、生命周期保留材料 |
| `capture.DnsPacketTest` | 8 | 通过 | IPv4/UDP DNS 解析/响应/校验和、直连 443 SYN 统计、protect 回调用 |
| `capture.HttpWireTest` | 7 | 通过 | CONNECT/请求行/头部/Content-Length、绝对 URI、响应拼装、超长头拒绝 |
| `capture.LocalMitmProxyTest` | 3 | 通过 | 目标域名 CONNECT 后 TLS 终止 + keep-alive 多请求转发（含转发头/Set-Cookie）、非目标域名盲转发字节一致、chunked 请求在转发前被拒绝 |

关键用例（按 verification.md 第 2/3 节要求）：

- `ProtocolGoldenTest.everyRequestMatchesReferenceFieldsAndBodyBytes`：8 个 endpoint 的 fields（顺序+字符串化）与 body 字节完全相等。
- `ProtocolGoldenTest.encryptedRequestsDecryptToReferencePlaintext`：beforeRun / ossSts / stop / refreshToken 的密文外层 `key` 解密后与 Python 明文逐字符相等（含 `\/` 转义）。
- `ProtocolGoldenTest.stopRequestCarriesTheThirtyEightFieldShape`：stop 为 38 字段、动态字段名 `lpd3`、值=formal `timeStr`、`record_str` 与 `record_signature` 一致。
- `RunEngineFsmTest.normalPathRunsHeartbeatsUploadsStopSensorAndVerify`：LOCATION 调用时持久化阶段必为 `location_pending`、STOP 为 `stop_pending`、PUT 前 `upload_pending` 已落盘。
- `RunEngineFsmTest.stopTimeoutBecomesUncertainAndIsNeverResent` / `putTimeoutKeepsUploadPendingAndNeverRepeats` / `locationTimeoutBecomesUncertainWithoutRepeating`：超时后进入 `uncertain` 且再次 `execute()` 不新增任何请求。
- `RunEngineFsmTest.stoppedRunWithFailedChecksDoesNotContinueOnRecovery`：stop 校验失败后不会登记传感器/回查。

### 1.2 构建产物

- 路径：`android/app/build/outputs/apk/debug/app-debug.apk`（debug、未混淆）。
- 最终产物：18,293,218 bytes；sha256 `2436fe1be46dd241878853bae6f4f7424a44b1fc812df4707f8f61257a32bd48`（2026-11-04 22:26 构建，含“放弃本次会话（仅本地）”入口；测试仍 136 项全绿）。QA 冻结轮（22:19，18,083,778 bytes，sha256 `2b4ab918…`）之后 Lead 追加了该入口与文档更新，故以本行为最终产物。
- `assembleDebug` 通过只证明资源/Manifest/Kotlin/Java 可打包，不证明真机行为。

## 2. 独立静态审查（`docs/verification.md` 第 5 节）

结论：**7 项全部通过**；其中第 1 项存在一处有意例外（`report/getOssSts` 凭据调用可重试），记录在 §5 已知偏差 1。

### 2.1 不自动重试写请求——通过（唯一例外：`report/getOssSts` 凭据调用）

- `core/ApiTransport.kt:42` 每次请求仅一次 `execute()`；`:95` `retryOnConnectionFailure(false)`；`:93-94` 不跟随重定向。
- `core/OssUploader.kt:109` 每次 PUT 仅一次 `execute()`；默认 client 复用 `ApiTransport.defaultClient()`（同上无重试）。
- `run/RunEngine.kt:142`（location）、`:277`（stop）、`:346`（gyroscope）均为单次分派；任异常分支立即转 `uncertain`，无循环。
- `run/RunController.kt:132`（start）单次分派，失败即 `startUncertain`。
- `run/RunApi.kt:52` 接口注释明确实现不得重试。
- **例外**：`run/RunEngine.kt:416-435` 对 `report/getOssSts` 在 `DeliveryUncertain` 时最多重试 3 次（`:483` `STS_ATTEMPTS=3`，`:484` 间隔 2 s），每次先持久化 `lastError`；该调用只取 STS 凭据、无服务端业务副作用。其余任何写请求都不在此循环内（单测 `transientStsFailureIsRetriedWithinTheCredentialCallOnly`、`persistentStsFailureStopsInUploadingWithoutDispatch`）。

### 2.2 写前落盘——通过

- start：`run/RunController.kt:129-130` 先 `START_PENDING` + `RunStore.save`，`:131-132` 再发请求。
- location：`run/RunEngine.kt:132-133` 先 `LOCATION_PENDING` + `save`，`:134-143` 再发请求。
- 上传：`run/RunEngine.kt:255-256` 先写 `upload_pending` + `save`，`:258` 再 PUT；`:211-212` 上传序列前先 `UPLOADING` + `save`。
- stop：`run/RunEngine.kt:274-275` 先 `STOP_PENDING` + `save`，`:276-296` 再发请求。
- 传感器：`run/RunEngine.kt:342-343` 先 `SENSOR_PENDING` + `save`，`:346-360` 再登记。
- 单测断言：`RunEngineFsmTest.normalPathRunsHeartbeatsUploadsStopSensorAndVerify`（LOCATION/STOP 调用时阶段、`pendingAtCall` 顺序）。

### 2.3 重复提交防护（blockingRun + 恢复矩阵）——通过

- `core/RunState.kt:22-26`：`new/prepared/start_pending/running/location_pending/uploading/stop_pending/stop_acknowledged/sensor_pending/uncertain` 均阻止新跑。
- `core/RunStore.kt:72-75` `blockingRun()`；`run/RunController.kt:48-52`（inspect）与 `:67-72`（start）先检查。
- 恢复矩阵：`run/RunEngine.kt:89-94` 对 `prepared/start_pending/location_pending/stop_pending/sensor_pending/uploading(upload_pending!=null)` 一律 `uncertain`，不重发。
- 单测：`RunEngineFsmTest` 的 `preparedAndStartPendingBecomeUncertainWithoutDispatch`、`locationPendingAndStopPendingAndSensorPendingBecomeUncertain`、`uploadingWithPendingUploadBecomesUncertain`、`stopTimeoutBecomesUncertainAndIsNeverResent` 等。

### 2.4 秘密不落明文日志——通过（静态）

- `core/DiagLog.kt:9-11` 明确只记录阶段/端点/结果；全仓库 `DiagLog.*` 调用未出现 token/cookie/UMID/手机号（检索 `grep -rn "DiagLog\."` 后逐条核对）。
- `core/SecureStore.kt:20-24` AES-256-GCM + Android Keystore 不可导出密钥；`:66-68`、`:115-133` 临时文件 + fsync + 原子替换。
- 审计文件由 `SecureStore` 加密落盘：`core/AuditLog.kt:13-21,64`。
- 抓包侧明确不安装系统证书：`capture/CaptureVpnService.kt:37`、`capture/CertAuthority.kt:41`；全仓库无 root/`su`/`Runtime.exec` 依赖（检索确认）。

### 2.5 线程/生命周期——通过（静态；真机待验）

- `capture/LocalMitmProxy.kt:95-104` `stop()` 关闭 ServerSocket 并 `shutdownNow()` 线程池；连接线程异常只影响单连接（`:137-145`）。
- `capture/CaptureVpnService.kt:264-288` `shutdown()` 关闭 DNS relay、proxy、TUN，interrupt TUN 读线程并关闭两个 executor；`:94-106` `onDestroy` 调 `stopForeground(STOP_FOREGROUND_REMOVE)`。
- `run/RunForegroundService.kt:177-193` `onDestroy` 取消 job/scope、释放 wake lock；`:61-78` 幂等启动，`START_STICKY`。
- 通知频率（本轮复核）：`run/RunEngine.kt:120` 在心跳循环每个 tick 回调进度，服务按 15 s 节流（`run/RunForegroundService.kt:115-130`、`:200` `NOTIFICATION_INTERVAL_MS=15_000`），满足 `run-engine.md §3`。
- UI 网络/磁盘均在后台 executor：`ui/MainActivity.kt:145-146`（single-thread work/refresh executor）、`:169-170`（onDestroy shutdownNow）；500 ms Handler tick 只重渲染已加载数据（`:46-47,137`）。

### 2.6 界面——通过（“重新提交”不存在；验证与学期统计分离；可恢复阶段有恢复入口）

- 全仓库无“重新提交/重新上报/重发”按钮或文案；`res/values/strings.xml:90` 的“重新回查”走只读 `Endpoints.RECORD_DETAIL`（`run/RunController.kt:252-258` `write=false` transport），不写业务。
- “记录有效”与学期统计分开：`res/values/strings.xml:87` 明确 `学期统计（RunStat/H5）不在本应用校验范围`；`MainActivity.kt:755` 的 verify 仅对已提交/已验证/特定待核对阶段开放。
- **恢复入口（本轮复核）**：`ui/MainActivity.kt` 对 `RUNNING`、`UPLOADING`（`upload_pending` 为空时）、`STOP_ACKNOWLEDGED` 显示“恢复会话”，分别命中 `RunEngine` 的继续心跳/续传/续登记恢复路径；有歧义写的阶段只提供只读回查。
- **手动放弃（本轮新增）**：`RunController.abandon` 仅对 `PREPARED/START_PENDING/LOCATION_PENDING/STOP_PENDING/SENSOR_PENDING/UNCERTAIN` 开放，只把本地状态置为 `FAILED` 并记录审计，**不发送任何请求、不删除服务端记录**；UI 二次确认明确提示先去官方乐跑核对，若服务端已有记录再跑可能产生第二条。详见 §5 偏差 2。

### 2.7 协议细节——通过（黄金夹具逐字节）

- 公共字段顺序与 sign 排除规则：`core/Protocol.kt:159-167`（排除 `sign`/`file`，newer 再排除 `mobileDeviceId`，按 key 排序后 md5）。
- 密文外层：`core/Protocol.kt:236-259`（未签字段排序 → 追加 `sign` → 紧凑 JSON → 每个 `"/"` 转义为 `"\/"` → `PARAMS_NEW_GAME` 加密 → `key=` 表单）。
- 动态字段：`core/Protocol.kt:169-188`（`lp`+model[1]+timestamp[3]；`DYNAMIC_KEYS[timestamp%15]` AES-CBC）。
- stop 的 `timeStr`：`core/Protocol.kt:283-293`（仅 stop 从 formal start 取 `timeStr`，缺失即抛错）；38 字段组装见 `run/RunEngine.kt:498+`（`StopFields`）。
- 以上全部由 `qa/ProtocolGoldenTest` 与 `core/ProtocolReferenceTest` 用 Python 参考实现输出逐字节证明。

## 3. 未做真机验证的项（必须后续复验）

1. **登录捕获**：VpnService 授权、本地 CA/MITM 解密官方乐跑请求、`Login/RefreshToken`（或 quickLogin/LoginV14）token 对、identity+UMID+Cookie 归档。
2. **VPN 代理兼容性**：官方应用是否在所有网络/系统版本下遵循 `setHttpProxy`；与其它 VPN（Clash 等）冲突时的提示与恢复。
3. **OSS 上传 + stop 真机写入**：`report/getOssSts` → Header V1 PUT（截图/轨迹/传感器）→ `Run/stopRunV278` 返回 `record_id` 的真实链路。
4. **Android Keystore**：`SecureStore` 密钥生成、加密状态文件、卸载/清除数据后的行为。
5. **前台服务后台存活**：锁屏/Doze/厂商省电策略下的 wake lock 与 START_STICKY 恢复。
6. **通知**：Android 13+ `POST_NOTIFICATIONS` 授权、前台通知展示与更新频率。
7. **真机 UI**：布局/滚动/按钮可用性、返回栈、采集页与主界面协同。
8. **真实 recordDetail 回查**：8 项检查在真实响应上全部为 true，且官方乐跑“学期统计”（RunStat/H5）与记录有效性分开。
9. **轨迹/截图/传感器的服务端效果**：服务端对截图、轨迹、传感器的实际展示与校验（PC 已验收 schema，Android 需真机复核）。
10. **未捕获 stop 时的设备字段回退**：正常登录采集拿不到官方 stop 请求里的 `oaid`/`mac`，代码回退为 `mac=02:00:00:00:00:00`、`oaid=nameUUIDFromBytes("lepao-oaid:"+mobileDeviceId)`。服务端是否校验这两个字段未验证；若校验，首次 stop 会停在 `stop_pending/uncertain`（不产生重复记录）。

## 4. 真机复验步骤（命令 + 预期观测）

前置：一台 Android 12（API 31）真机、官方乐跑已登录同一账号、USB 调试可用、本机 `adb`。

```bash
# 0. 安装与启动
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.lepao.companion/.ui.MainActivity
adb logcat -c
adb logcat -v time | findstr /i "lepao AndroidRuntime"
```

1. **登录捕获**
   - 应用内点“获取登录态” → 系统 VPN 授权 → “打开乐跑” → 官方应用内完成一次正常登录/进入跑步页。
   - 预期：采集页状态 `LOGIN_SEEN → CONTEXT_READY`；返回应用保存后主界面显示 uid/登录有效期。
   - 预期：`adb shell run-as com.lepao.companion cat files/session.enc.json` 只见 `{"v":1,"iv":...,"ct":...}` 密文；logcat 无 token/cookie/UMID 明文。
   - 反例：官方应用绕过代理时，30 s 内应提示“未走代理/请停止后重试”。
2. **起跑**
   - 主界面“检查配置” → “开始跑步”；预期进入 `running`、前台通知出现。
   - 锁屏 10 分钟：`adb shell dumpsys activity services com.lepao.companion` 仍能看到 `RunForegroundService`；通知时间/距离递增。
3. **断网/不确定分支**
   - 跑步中切换飞行模式 30 s 再恢复：预期该会话转 `uncertain`，logcat 中对应 location 请求只出现一次，恢复网络后不自动重发；界面提示人工核对。
   - 预期：若已有 `record_id`，“重新回查”可用；若没有，先“打开乐跑”核对，再走“放弃本次会话（仅本地）”二次确认；确认后本地会话变 `FAILED`、可开始新跑，logcat/DiagLog 记录 `abandoned_by_user`，全程无网络请求。
4. **结束与回查**
   - 正常跑完：预期自动上传截图/轨迹（gyro 开启时含传感器）→ stop 返回 `record_id` → `submitted`；点“重新回查”后 8 项全 true → `verified`。
   - 预期：官方乐跑记录列表出现该记录；OSS 三个对象键存在；`Run/recordDetailV270` 的 uid/start_time/game_id/距离/用时/步数/记录状态/文件路径均匹配。
5. **恢复矩阵（杀进程）**
   - `adb shell am force-stop com.lepao.companion` 分别在各阶段后执行：`running` → 重新打开应用点“恢复会话”应继续；`upload_pending!=null`/`stop_pending`/`location_pending` → 重启后应 `uncertain` 且无重发；`submitted` → 只做只读回查。
6. **Keystore**
   - 清除应用数据/卸载重装后，旧 `session.enc.json` 不可解密；重新走登录捕获即可恢复（预期不崩溃）。
7. **通知权限**
   - 设置中拒绝通知权限后开始跑步：预期不会因通知失败而中断会话（代码容错）；Android 13+ 首次启动请求通知权限的路径需实测。
8. **其它 VPN 冲突**
   - 先开启 Clash 等 VPN，再启动采集：预期提示先关闭其它 VPN，且不会崩溃/泄漏。

> 复验时请把测试账号/时间/record_id/结果记入本文件附录；**任何一项未观测到都不算通过**。

## 5. 已知偏差与限制

1. **STS 凭据重试**：`report/getOssSts` 在 `DeliveryUncertain` 时最多重试 3 次（`run/RunEngine.kt:416-435`、`:483-484`）。该调用只发放临时凭据、无业务写副作用；stop/location/start/PUT 均不重试。这是相对 `architecture.md:31`“写请求没有自动重试”的唯一有意例外，需产品负责人确认接受。
2. **手动放弃的真实后果**：`RunController.abandon` 只清理本地阻塞状态，不发送请求、不删除服务端记录；它依赖用户自行到官方乐跑确认。若用户未核对就放弃并再次跑步，可能产生第二条记录（这是人工操作风险，不是自动重发）。该入口无 JVM 单测（依赖 Android Context/RunStore），需真机走查确认按钮可见性、二次确认文案与 `FAILED` 后新跑解锁。
3. **设备字段回退**：`StopFields.deviceFields` 优先使用官方 stop 抓包值；缺失时 `mac=02:00:00:00:00:00`、`oaid=UUID.nameUUIDFromBytes("lepao-oaid:"+mobileDeviceId)`（`run/RunEngine.kt:494-522`，`StopFieldsTest` 覆盖 38 字段形状）。服务端是否校验未在线验证。
4. **Verifier 裸 key 归一化**：`run/Verifier.kt:128-133` 对无 scheme 的裸对象键先补 `/` 再比较；PC 参考 `urlsplit("Public/...").path` 对裸键返回不匹配。生产响应 `file` 是完整 URL，二者等价；仅测试向量语义差异。
5. **测试覆盖盲区**：
   - 传感器 `SENSOR_PENDING` 写前落盘未直接断言（`RunEngineFsmTest` 的 `stageAtCall` 只断言 LOCATION/STOP）；仅由代码审查（`RunEngine.kt:342-343`）保证。
   - `NEW`/`UNCERTAIN` 作为恢复入参未单测（`RunEngine.kt:93-94`），逻辑简单但未覆盖。
   - `RunController.abandon`、`SecureStore`/Keystore、VPN、通知、真实 UI 交互无 JVM 覆盖（平台相关，必须真机验证）。
6. **未做真机验收**：本文所有“通过”仅指 JVM 单测/构建/静态审查。登录捕获、OSS+stop 真机写入、Keystore、前台服务后台存活、通知、VPN 代理兼容性、真实回查均未验证。
