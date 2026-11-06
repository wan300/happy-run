# 乐跑伴跑 · Android 迁移架构

本目录是 PC 上已跑通链路的 Android 独立实现。目标运行环境是**未 root、日常不连接电脑**的 Android 真机（当前参考设备 Android 12 / API 31，应用 minSdk 29）。采集、解密、协议、轨迹生成、上传和回查全部在手机本地完成。

## 1. 运行链路

```
┌──────────────────────────── 手机本地 ────────────────────────────┐
│                                                                  │
│  官方乐跑 ──(HTTP CONNECT 127.0.0.1)──► CaptureVpnService         │
│      │                                   ├─ 本地 TLS 终止（自签证书）│
│      │                                   ├─ 请求/响应转发到真实服务端│
│      │                                   └─ 提取登录响应 + 业务上下文│
│      ▼                                                            │
│  SessionStore（AES-GCM，Android Keystore 保护）                    │
│      │                                                            │
│      ├─ ConfigService：Run2/beforeRunV260（只读）                  │
│      ├─ TokenService：Login/RefreshToken（轮换并原子写回）          │
│      ├─ RunController：固定参数规划 + 正式起跑                      │
│      ├─ RunForegroundService：心跳、轨迹、结束提交、传感器登记       │
│      └─ Verifier：Run/recordDetailV270 回查                        │
│                                                                  │
│  OSS：report/getOssSts → Header V1 PUT（截图/轨迹/传感器）         │
└──────────────────────────────────────────────────────────────────┘
```

关键点：

- **不修改系统证书**。抓包使用 `VpnService` 只放行 `com.lptiyu.tanke`，并给该 VPN 网络设置本地 HTTP 代理；官方应用在 PC 上已实测接受新的、未安装的代理 CA（其 TLS 校验足够宽松），因此本地自签证书即可完成 MITM，无需用户安装任何 CA。
- 应用自身**不在 VPN 放行名单内**，同时 OkHttp 固定 `Proxy.NO_PROXY`，避免请求回环。
- 写请求**没有自动重试**。发请求前必须先落盘阶段；任何超时/响应不确定都停在 `uncertain` / `stop_pending` / `upload_pending`，由只读回查或人工核对决定下一步。

## 2. 目录与职责

```
app/src/main/java/com/lepao/companion/
  core/        协议、会话、规则、状态、加密存储、HTTP/OSS 传输（Lead）
  capture/     VPN + 本地 MITM 代理 + 登录态提取（capture slice）
  run/         规划、轨迹/传感器/截图、状态机、前台服务、回查（run / route slices）
  ui/          两个 Activity、轮询渲染、用户操作（ui slice）
app/src/test/  纯 JVM 单测与协议对拍夹具
app/src/main/assets/corridor.json   已验收记录的 374 点校园环线（仅作几何走廊）
docs/          本目录的设计与验收说明
```

`core` 中的所有类都不依赖 Android UI，可被 JVM 单测直接覆盖（`SecureStore` 与 `DiagLog` 除外）。

## 3. 账户上下文

`SessionContext` 保存：

| 字段 | 来源 | 用途 |
| --- | --- | --- |
| `identity`（10 个 wire 字段 + `umid`） | 官方应用的一条成功业务请求 | 每个请求的公共上下文、设备动态字段 |
| `cookies` | 请求 Cookie + 响应 Set-Cookie | 维持会话 |
| `user_agent` | 业务请求头 | 请求头 |
| `device_fields`（`oaid`/`mac`） | 官方应用的 stopRun 请求（若捕获到） | 结束提交 |
| `refresh_token` / `refresh_expire` / `refresh_access_token` | 登录或刷新响应 | 自动续期；只有与当前 access token 同族时才续期 |

敏感数据落盘统一由 `SecureStore`（AES-256-GCM，密钥在 Android Keystore，不可导出）加密；写入使用临时文件 + `fsync` + 原子替换。审计日志逐行加密。

## 4. 固定运行参数

按需求“多数配置由系统写死或由系统调配”，界面不提供里程/配速/步数/点位选择。系统从 `beforeRun` 规则派生：

- 目标里程 2.03 km（已在服务端验收的数值），按学校 `min_distance`/`max_distance` 自动夹取；
- 目标配速 365 s/km，按 `min_pace`/`max_pace` 自动夹取；
- 步数 = 里程 × 832.5；步频分桶 60 s；
- 打卡点自动从当前 `beforeRun` 点位表选取（数量 ≥ `min_log_num`）：先按离基准轨迹的最近距离选出 `needed` 个点位（相邻打卡时刻 ≥30 s），生成器对它们做平滑局部位移绕行，保证最近采样点 ≤20 m（目标 4 m）；未选中的点位在 20 m 门限下被舍弃，再按轨迹最近点自动计算打卡时刻；
- 轨迹每次运行使用新的随机种子，在 374 点走廊上单向重新采样（随机正/反向、≤60 m 起点偏移与横向摆动），避免与历史轨迹逐点一致；走廊不是闭合环线，因此不做首尾取模，避免接缝瞬移；绕行目标是固定方向的平移（不是沿法向偏移），走廊急转弯不会让轨迹甩出去。

唯一需要用户操作的是：获取登录态、开始跑步、查看/回查结果。

## 5. 状态机

```
new → prepared → start_pending → running ⇄ location_pending
                                   │
                                   ▼
                              uploading → stop_pending → stop_acknowledged
                                                              │
                                                              ▼
                                                     sensor_pending → submitted → verified
```

- 写请求前先保存目标阶段（`start_pending`/`location_pending`/`uploading`/`stop_pending`）。
- 结果未知 → `uncertain`，禁止自动重发，也禁止开始新的跑步会话。
- `submitted` 表示结束请求已被服务端接受并返回 `record_id`，但回查未全部通过；`verified` 要求账号、起跑时间、跑区、里程、用时、步数、轨迹对象键与记录状态全部匹配。
- 学期统计（RunStat/H5）不在已验证范围，界面必须与“记录有效”分开显示。

## 6. 构建

```bash
cd android
./gradlew assembleDebug        # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # 纯 JVM 单测
```

`local.properties` 指向本机 Android SDK；AGP 8.7.3 / Gradle 8.9 / Kotlin 2.0.21 / compileSdk 35 / minSdk 29。

## 7. 明确边界

- 首次登录仍由官方乐跑完成（微信/QQ/手机号）；本应用只捕获同一账号的登录响应，不伪造第三方授权。
- 不上传用户真实运动数据；轨迹、步数、传感器、截图均由固定画像生成。
- 不覆盖：人脸/设备/极验/抓拍/范围校验等配置；遇到这些分支直接停止。
- OSS 的 STS、PUT、stopRun、gyroscope 已在 PC 上在线验收；Android 端的等价实现本期不做真机联调，需按 `docs/verification.md` 的清单在真机复验。
