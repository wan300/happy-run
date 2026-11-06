# 1.0.10 Release：业务状态码中文化与登录态失效标记

## 现象

服务端返回业务状态 102 时，界面直接显示 `Business response requires handling (status=102)`，
登录卡片仍显示“已就绪”，用户无法判断这是账号会话被踢，还是网络/配置问题。

## 状态码含义（官方客户端 `entity/response/Result.java`）

| code | 常量 | 现在的中文提示 |
| --- | --- | --- |
| 101 | `LOGIN_FAIL` | 登录失败，请重新获取登录态 |
| 102 | `OTHER_DEVICE_HAS_LOGINED` | 账号已在其他设备登录，请重新获取登录态 |
| 103 | `REFRESH_TOKEN_DISABLED` | 登录续期已被禁用，请重新获取登录态 |
| 106 | `ACCOUNT_FORBIDDEN` | 账号访问受限，请检查账号状态或联系学校 |
| 其它 | — | 业务响应异常（status=N），请稍后重试 |

## 实现

- `ProtocolException` 增加 `businessStatus`；`Protocol.decodeResponse` 用
  `Protocol.businessMessage(code)` 生成中文提示并带上状态码。
- `SessionContext` 增加持久化的 `invalidatedAt`；`SessionStore.markInvalidated()` 写入当前时间。
- `TokenService.health()` 返回 `invalidated`；`TokenService.invalidateIfRejected(error)` 仅在
  收到 **102** 时落盘失效标记（`maybeAutoRefresh` 的失败路径也会调用）。
- `RunController` 的 `inspect` / `selectEastCampus` / `start`（含自动续期、起跑请求）/`verify`
  捕获到 102 时调用上述标记；界面轮询（约 1 s）重新读取会话后，登录卡片显示
  **“已失效，请重新获取登录态”**（红色）。
- 重新“获取登录态”并保存新会话时 `invalidatedAt` 为空，卡片自动回到“已就绪”。

## 验证

- `:app:testDebugUnitTest`：**171 个用例通过**（新增 `BusinessStatusTest`：四条中文映射、未知码保留数字、
  `invalidatedAt` 让 `LoginHealth.invalidated` 为 true）。
- `:app:assembleRelease`：`dist/lepao-1.0.10-east-campus-release.apk`，versionCode 11 / 1.0.10。
- 真机（`2UCUT23C27103357`）覆盖安装后，用当前已失效的 token 点“刷新配置”实测：界面显示中文 102 提示，
  登录卡片变为“已失效”。

## 边界

- 只有 102 会标记“已失效”；101/103/106 仅替换提示文案（access token 未必定失效）。
- 失效标记不会自动清除，必须重新抓取登录态覆盖会话。
