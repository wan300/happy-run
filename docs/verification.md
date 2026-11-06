# 验收与测试规格（QA 切片）

范围：`android/app/src/test/java/com/lepao/companion/qa/**`、`android/tools/**`、`android/docs/acceptance.md`。不得修改产品代码；发现缺陷时报告 Lead（附最小复现），由对应切片修复。

## 1. 目标

在**不做真机联调**的前提下，用 PC 上已验收的 Python 参考实现生成黄金夹具，证明 Android 的协议实现逐字节一致；用纯 JVM 测试覆盖轨迹/传感器/截图、规划、状态机与恢复矩阵；独立审查“写前落盘、不自动重发、不泄漏令牌”三条硬约束。

## 2. 黄金夹具（必须先做）

脚本：`android/tools/gen_fixtures.py`，使用仓库根目录的 `lepao_client`：

```bash
cd D:/code/project/happy-run
D:/conda/python.exe android/tools/gen_fixtures.py
```

固定输入（写入脚本）：

```python
identity = {
  "version": "231", "version_name": "4.3.1", "mobileModel": "ADA-AL00U",
  "mobileDeviceId": "fixture-device-0001", "mobileOsVersion": "12", "ostype": "1",
  "student_num": "23301135", "school_id": "12", "uid": "12217924",
  "token": "A"*32, "umid": "fixture-umid-0123456789abcdef",
}
timestamp = 1793778542
nonce = "123456"
```

对每个 endpoint 生成：`endpoint / frames / fields / body_b64 / encrypted_plaintext(可选)`：

- `Run2/beforeRunV260`：extra `{}`
- `Run/getTimestampV278`：`{"game_id":"158","point_ids":"24560,2894"}`
- `Run/setRunLocationRecord`：`{"latitude":"39.9518","longitude":"116.3452","game_id":"158","point_ids":"24560,2894"}`
- `report/getOssSts`：`{}`
- `Run/stopRunV278`：`timeStr = "e"*32`，extra 用 PC `RunFlow.finish` 的 38 字段（`log_data`/`step_info` 用 `json.dumps(...,separators=(',',':'))`；`record_str` 用 `record_signature("12","12217924",timeStr)`）
- `Run2/gyroscope`：`{"record_id":"259921","gyroscope_file":"Public/Upload/file/run_gyroscope/2026-11-04/461/x.txt"}`
- `Run/recordDetailV270`：`{"record_id":"259921"}`
- `Login/RefreshToken`：`{"access_token":"A"*32,"refresh_token":"R"*32}`
- 响应解码样本：`{"status":1,"data": encrypt_text(json.dumps(payload))}` 与明文 `data` 两种。

输出 `android/app/src/test/resources/fixtures/protocol_golden.json`（**仅假数据**，不得放入真实 token/uid/UMID）。

## 3. Kotlin 测试（`qa/ProtocolGoldenTest.kt`）

- 对每个 endpoint：用 `Protocol.buildRequest` 重建，断言 `fields`（字符串化后）逐键相等、`body` 字节完全相等。
- 密文请求：用 `Protocol.decryptText(form["key"], "PARAMS_NEW_GAME")` 断言等于 Python 的 `encrypted_plaintext`（含 `\/` 转义）。
- `Protocol.formEncode`：覆盖空格、`+ / =`、换行、中文。
- `Protocol.recordSignature`、`dynamicName/dynamicValue`、`b64Mime`（76 列 + 结尾换行）与 Python 输出一致。
- `Protocol.decodeResponse`：明文/密文 data 均正确；status!=1 抛 `ProtocolException`。
- 失败信息必须打印 endpoint，便于定位。

## 4. 其它切片测试审查

阅读并运行：

- route slice：轨迹确定性、schema、PNG IHDR、传感器行数。
- run slice：planner 边界、manifest 校验、FSM 正常路径 + `stop_pending`/`upload_pending` 不重发 + 恢复矩阵。
- capture slice：提取器（加密/明文请求、UMID、登录 token 对）、DNS/HTTP 解析。
- 若测试缺失或断言不足以证明规格，补测试到 `qa/**`（不依赖 Android 运行时；必要时用 `org.json`、OkHttp MockWebServer）。

运行：

```bash
cd android
./gradlew testDebugUnitTest --no-daemon
./gradlew assembleDebug --no-daemon
```

## 5. 独立审查清单

1. **不自动重试写请求**：`Run/stopRunV278`、`Run/setRunLocationRecord`、`Run/getTimestampV278`、OSS PUT 的调用点没有循环/隐式重试；OkHttp `retryOnConnectionFailure(false)`。
2. **写前落盘**：每次写请求前 `RunStore.save` 已发生在 dispatch 之前；`upload_pending` 在 PUT 之前写入。
3. **重复提交防护**：`blockingRun()` 阻止新跑；恢复矩阵不会重发 `stop_pending`/`location_pending`。
4. **秘密**：明文日志不含 token/cookie/UMID/完整手机号；审计文件被 `SecureStore` 加密；Capture 不安装系统证书；应用无 root 依赖。
5. **线程/生命周期**：VPN 与前台服务退出释放 socket/线程/wake lock；主线程无网络。
6. **界面**：无“重新提交”入口；`verified` 与“学期统计”分开显示。
7. **协议**：请求公共字段顺序、sign 排除规则、动态字段、stop 的 `timeStr`、加密外层 `key` 与转义。

## 6. 交付物

- `android/tools/gen_fixtures.py` 与生成的 JSON 夹具。
- `qa/**` 测试。
- `android/docs/acceptance.md`：
  - 已自动验证项（逐条列测试名/结果）；
  - 未验证项（真机登录捕获、VPN 代理兼容性、OSS/stop 真机写入、Android Keystore、前台服务后台存活、通知权限）；
  - 复验步骤（真机 checklist，命令 + 预期观测）；
  - 任何已知偏差。
- 最终运行输出（测试数量、失败数、构建产物路径）。

不得声称完成真机验收，不得把“编译通过”等同于“链路通过”。
