# 跑步引擎与轨迹生成规格

本文覆盖两个互不重叠的实现切片：

- **route slice**：`run/RouteEngine.kt`、`run/RunArtifacts.kt`、`run/PngCanvas.kt`、`run/RouteValidator.kt`（可选）及对应单测。输入固定走廊 + 种子，输出可上传的轨迹/截图/传感器字节。
- **run slice**：`run/AutoPlanner.kt`、`run/RunController.kt`、`run/RunEngine.kt`、`run/RunForegroundService.kt`、`run/Verifier.kt` 及对应单测。负责规划、状态机、心跳、上传、结束、回查、恢复。
- 二者共享 `run/RouteEngine.kt` 的公开 API。run slice 先按现有 stub 编写；route slice 落地后即可联调。
- 不得修改 `core/**`、`capture/**`、`ui/**`、`res/**`、Gradle。需要新增 core 字段时先找 Lead。

## 0. 已验证的参考事实

- 2026-11-04 记录 `259921`：2.03 km / 741 s / 1690 步 / 2 个打卡点 / 4 次位置上报 / 截图+轨迹+传感器均 OSS PUT 200 / stop 返回 `record_id` / `recordDetail` 全项匹配。
- 位置上报阈值来自 `Run/getTimestampV278` 响应的 `location_septal`（实测 60），首次 60 s，之后 `next = elapsed + 上一次阈值`，实测累计触发 60/120/240/480 s。
- 结束请求字段共 38 个（PC 实现），`record_str = md5((school_id + uid) + formal_str + salt[uid%10])`。
- 轨迹 371 点、传感器 1 Hz 742 条、截图 PNG；服务端只做基本校验，但必须保持原客户端写入 schema。

## 1. 固定画像与自动参数

所有参数由系统推导，界面不提供输入：

```
TARGET_DISTANCE = 2.03 km
TARGET_PACE     = 365 s/km
STEPS_PER_KM    = 832.5
STEP_INTERVAL   = 60 s
```

`AutoPlanner.plan(context, rules, identity, nowEpoch)`：

1. `rules.blockers()` 非空 → 失败。
2. `rules.schoolId == identity["school_id"]`，否则失败。
3. `rules.windowCheck(nowEpoch)`：不在入口时间窗/星期/唯一时段规则 → 失败并给出原因。
4. 里程：`distance = clamp(2.03, min_distance + 0.01, max_distance - 0.01)`，保留两位小数（HALF_UP）。若 `min >= max` 或 `< 0.2` → 失败。
5. 配速：目标 365，若不在 `[lower+1, upper-1]` 则夹取；区间非法 → 失败。
6. `duration = round(distance * pace)`，`duration >= 60`；`stepNum = round(distance * 832.5)`。
7. 种子：`System.currentTimeMillis()`（每次不同）。
8. 生成轨迹分两步：先 `RouteEngine.buildTrack(context, duration, seed, [])` 得到基准轨迹，在基准轨迹上**先取全部必经点（`point_list[].type == "1"`），再按“最近采样点距离从近到远、相邻打卡时刻 `>= 30 s`、距离 `<= 264 m`”补足 `needed` 个绕行目标**；再用 `buildTrack(context, duration, seed, targets)` 生成绕行轨迹（见 §6.2）。只绕行选中的点，避免为无关点位把整条路线拖出校园。
9. 打卡点数量 `needed = max(2, min(rule.minLogNum, 4), min(rules.pointNumOnline, 3))`；`rules.checkpoints.size >= needed`，否则失败。必经点不受 `needed` 限制：必经点数多于 `needed` 时按必经点数量扩额；必经点超出绕行上限（264 m）或不在打卡时间窗内 → 直接 blocker（“必经打卡点 …”），不再提交必然被判无效的记录。
10. 每个候选点找轨迹最近样本（haversine），保留 `gap <= 20 m` 且 `5 <= t <= duration-5` 的点；**先放必经点，再按 t 补普通点**，相邻打卡时间间隔 `>= 30 s`；凑不齐 `needed` → 失败（“点位与轨迹不匹配：需要 N 个 20 m 内的打卡点，匹配到 M 个”）。
11. 打卡点坐标取轨迹样本本身（与 PC 相同），`distance=0.65`，`arcM=样本弧长`；`checkinT=样本 t`。
12. `passTotal = max(60, track.size - 5)`，`passEffectiveNum=0`，`areaValid="1"`，`collector="android-app"`；`plan.via` 持久化点位表，供服务重建同一条绕行轨迹。
13. 警告：里程被夹取、配速被夹取、点位偏移 15–20 m 时各追加一条。
14. 返回 `ReadinessView(ok, blockers, warnings, rule, plan, rules)`。

`RunController.inspect`：加载会话 → 若 `RunStore.blockingRun() != null` 失败 → `ConfigService.fetch`（只读）→ `AutoPlanner.plan`。全部异常转成 blockers，不抛出到 UI。

## 2. 起跑（`RunController.start`）

在调用线程（UI 的 executor）同步执行：

1. `session = SessionStore.load()`，不存在 → 失败。
2. `RunStore.blockingRun()` 非空 → 失败（避免重复记录）。
3. `TokenService.maybeAutoRefresh(context, session, minDays=3)`；失败只警告，继续用原 session。
4. `rules = ConfigService.fetch(context, session)`（这一次 `beforeRun` 同时充当 `prepare`，30 s 新鲜度内直接 start）。
5. `readiness = AutoPlanner.plan(context, rules, updatedSession.identity, now)`；不 ok → 失败。
6. 创建 `RunState(id=RunStore.newId(), stage=PREPARED, rulesJson=rules.rawJson, planJson=plan.toJson(), school/game/term ...)` 并 `RunStore.save`。
7. 组装正式起跑：
   - `extra = { game_id, point_ids = plan.pointIds.joinToString(",") }`
   - `Protocol.buildRequest(Run/getTimestampV278, identity, extra, now)`
   - 发请求前 `stage=START_PENDING` + persist。
   - 返回校验：`offlinePoints == []`；`data.str` 匹配 `[0-9a-f]{32}`；`data.timestamp` 为数字；`data.location_septal` 为正整数。任一不符 → `stage=UNCERTAIN`，保留错误，不重试。
   - 成功 → `stage=RUNNING, formalStr, startTime, startedEpoch=now, startedElapsedRealtime=SystemClock.elapsedRealtime(), nextLocationElapsed=interval, heartbeatCount=0`，persist。
8. 启动 `RunForegroundService`；返回 `OperationResult(ok=true, runId)`。
9. 任何 `DeliveryUncertain` → 对应阶段保留并 `UNCERTAIN`；不得自动重发 start。

## 3. 前台运行（`RunEngine`）

`RunForegroundService`：

- `onCreate`：`PARTIAL_WAKE_LOCK`（10 分钟超时上限 30 分钟），通知渠道 `CHANNEL_RUN`。
- `onStartCommand`：幂等启动单个 coroutine；`START_STICKY`。
- `onDestroy`：取消协程、释放 wake lock；已持久化状态不受影响。
- 通知每 15 s 更新：`已跑 mm:ss / 距离 / 打卡数`。
- 服务停止条件：状态进入 `verified`、`submitted`、`uncertain`、`failed`。

`RunEngine.run(state)`（IO 线程）：

```
transport = ApiTransport(session, write=true, audit=AuditLog(context, RunStore.auditFile(id)), persist=SessionStore::save)
uploader  = OssUploader(audit)
track     = RouteEngine.buildTrack(context, plan.durationSec, plan.seed)

loop:
  elapsed = (SystemClock.elapsedRealtime() - state.startedElapsedRealtime)/1000
  if elapsed >= duration + 1.6: break
  if elapsed >= state.nextLocationElapsed:
     state.stage = LOCATION_PENDING; save
     pos = RouteEngine.positionAt(track, elapsed)
     response = api(Run/setRunLocationRecord, {latitude: pos.lat.toString(),
                longitude: pos.lng.toString(), game_id, point_ids})
     要求 response.offlinePoints == []
     state.stage=RUNNING; heartbeatCount++; nextLocationElapsed = elapsed.toInt() + nextLocationElapsed; save
  delay(500)
```

- 心跳 `observed_epoch` 直接用当前时刻；位置来自轨迹在 elapsed 处的插值。
- 任一心跳出现 `DeliveryUncertain`/业务异常 → `stage=UNCERTAIN`，保存错误，停止循环（不补发、不继续）。
- 时钟一致性：恢复时若 `abs((nowEpoch-startedEpoch) - elapsed) > 120` → `UNCERTAIN`（“设备时钟变化”）。
- 进程被杀后：若阶段仍为 `RUNNING`，服务重启后按 `elapsedRealtime` 继续；若 `elapsed >= duration+2` 直接进入完成流程。

## 4. 结束提交

完成流程（`RunEngine.finish`）：

1. 重新生成 `track`（同 seed），`RunArtifacts.build(context, plan, startEpoch)` 得到 `route`、`screenshot`、`sensor` 三份 body。
2. 构造 manifest（与 PC 完全一致的字段）：
   ```
   session_id=state.id, source_kind="device_measurements"
   measurements:
     end_time=nowEpoch, used_time=duration, distance="2.03"（两位小数字符串）
     step_num, step_info={interval:60, list:[每分钟增量]}
     log_data=[{distance:0.65, latitude:f32, longtitude:f32, point_id:int, time:startTime+round(checkinT)}]
     pass_total, pass_effective_num=0, is_running_area_valid="1",
     ip=Net.localIpv4(), run_check_res=[]
   ```
3. 本地校验（照搬 PC `validate_finish`）：
   - 字段集合完全一致；distance 正则 + `>=0.2`；整数非负；`0 < used_time <= end_time-start_time`，`end_time <= now+5`。
   - `step_info.interval==60`，list 全为非负整数；`log_data.size == plan.points.size`，每条键完全为 `distance/latitude/longtitude/point_id/time`；坐标范围；time 在起止之间（或 ≤0 且坐标为 0）；point_id 集合一致。
   - `pass_effective_num <= pass_total`；里程/配速/最少打卡满足 `plan.rule`；`ip` 可解析。
   - 必需材料：`screenshot`+`route`，`gyroscope_type=="1"` 时加 `sensor`。
4. 对象键：`ArtifactKinds.objectKey(kind)`（日期 + 1..999 + uuid），生成一次后固定；保存 `object_keys_json`。
5. `stage=UPLOADING`，保存 manifest、artifact sha256、keys。
6. 上传顺序 `screenshot` → `route`（与 PC 一致）：
   - `report/getOssSts`（写接口）→ 校验 bucket/Expiration；
   - 保存 `upload_pending=kind`；
   - `OssUploader.upload`；
   - 成功 → `uploads[kind]={key,sha256,bytes,etag}`，`upload_pending=null`，persist。
   - PUT 结果不明 → 保留 `upload_pending`，`stage=UNCERTAIN`，停止。
7. 结束请求：
   - 字段（38 个）：`{**session.deviceFields, term_id, game_id, start_time, end_time, used_time, distance, step_num, file_img=key(screenshot), record_file=key(route), record_img="", log_data=compactJson, step_info=compactJson, run_check_res="[]", pass_total, pass_effective_num, is_running_area_valid, ip, record_str=Protocol.recordSignature(school,uid,formal_str), gee_token="", newMobileDeviceId="", run_face_verify_id="1231231231231", is_timeout_zfb=0}`
   - **先** `stage=STOP_PENDING` + persist，再调用 `Run/stopRunV278`（带 `timeStr=formalStr`）。
   - 响应 `data.record_id` 必须为数字；否则 `UNCERTAIN`。
   - 检查 `uid/start_time/game_id/distance/time` 与本地一致；保存 `stop_result`、`stop_result_checks`、`record_id`，`stage=STOP_ACKNOWLEDGED`。
   - 若检查不全为 true：保留阶段与检查，标记错误，停止（不注册传感器、不 verify）。
8. 传感器（`gyroscope_type=="1"`）：`_upload("sensor")` → `stage=SENSOR_PENDING` → `Run2/gyroscope`（`record_id`,`gyroscope_file`）→ 成功 `stage=SUBMITTED`。
   - 设备字段：`oaid`/`mac` 优先用官方 App stop 抓包值；正常登录采集通常没有，回退为 `mac=02:00:00:00:00:00`、`oaid=UUID.nameUUIDFromBytes("lepao-oaid:"+mobileDeviceId)`（稳定、不随机）。该回退未在线验证；若服务端校验这两个字段，stop 会停在 `stop_pending→uncertain`（不会重复写入）。
9. 回查：`Verifier.verify(state, transport)`，读取 `Run/recordDetailV270`：
   ```
   uid, start_time, game_id, record_status=="1", distance, time, step_num,
   file 路径（URL decode 后）以 "/"+routeKey 结尾
   ```
   全 true → `stage=VERIFIED`；否则 `SUBMITTED` + 检查明细。写请求失败只影响回查，保留 `SUBMITTED` 供手动重试。

## 5. 恢复矩阵

`RunEngine.resume(state)`（服务启动/进程重启）：

| 阶段 | 动作 |
| --- | --- |
| `prepared` / `start_pending` | `UNCERTAIN`：start 结果未知，禁止重发 |
| `running` | 校验时钟一致性后继续心跳循环 |
| `location_pending` | `UNCERTAIN`：心跳结果未知，禁止重发 |
| `uploading`，`upload_pending==null` | 复用 manifest/hashes/keys，继续未完成的上传 |
| `uploading`，`upload_pending!=null` | `UNCERTAIN` |
| `stop_pending` | `UNCERTAIN`：停止请求结果未知，禁止重发 |
| `stop_acknowledged` | 继续传感器上传/登记，然后回查 |
| `submitted` | 自动做一次只读回查（不重复写） |
| `verified` / `failed` | 结束 |

`RunController.verify(context)`：仅允许 `submitted`/`verified`/`stop_acknowledged`（无 record_id 拒绝）；只读 transport 回查并更新状态。`RunController.start` 与 `inspect` 必须先检查 `blockingRun()`。

## 6. route slice：轨迹、截图、传感器

### 6.1 走廊

`assets/corridor.json`：`{"points":[[lat,lng],...]}`，374 点、2070.8 m。加载后按 ≤0.5 m 弧长重采样为 `lat[]/lng[]/arc[]`，支持 `at(distance)` 线性插值与 `bearing(distance)`。

### 6.2 轨迹

输入 `durationSec`、`seed`。使用 `java.util.Random(seed)`（不需要与 Python 随机序列一致）：

- 方向 `dir = if (seed % 2 == 0) 1 else -1`；起点偏移 `offset = ((seed ushr 3) % 1000) / 1000.0 * 60 m`。
- **单向遍历走廊一次，不取模**：走廊是开放折线（首尾相距 574 m），若按环线取模会在接缝处瞬移 574 m，并让终点与起点重合。`traversable = length - offset`，正向 `arc = offset + travelled`，反向 `arc = length - offset - travelled`，均 clamp 到 `[0, length]`。
- **打卡点绕行（`via`）**：每个目标点取走廊最近弧长 `a_k`、距离 `d_k`，把轨迹在 `a_k` 附近沿固定方向 `u_k`（走廊指向点位）平移 `d_k - 4 m`，权重是半径 `W = min(400, max(40, 120 + 1.2*(d_k - 4)))` 的升余弦窗；相邻点位过近时按间距收窄窗口，保证各自的峰值不被叠加拉低。位移是**固定方向的平移**而非沿走廊法向偏移，所以走廊的急转弯不会让绕行轨迹甩出去，结点处严格落在离点位 4 m 的位置，最近采样点稳定在 4–8 m。采样时先平移再叠加原有横向摆动；`offset` 会被夹到 `first_arc - 10 m`（反向为 `length - last_arc - 10 m`）以内，保证绕行段一定被经过。
- 速度画像（照搬 Python 形状）：
  ```
  ramp = 0.30 + 0.70*min(1, t/14)
  if duration - t < 16: ramp *= max(0.45, 0.45 + 0.55*((duration-t)/16))
  wobble = 1 + 0.11*sin(2πt/103 + phaseA) + 0.055*sin(2πt/29 + phaseB)
  raw = max(0.15, ramp*wobble)
  ```
  以 0.1 s 积分，缩放到总弧长 = `traversable`。
- 位置：`(lat,lng)=at(arc)`；横向摆动
  `side = 1.1*sin(arc/41+p1) + 0.45*sin(arc/9.7+p2) + gauss(0,0.18)`；
  `heading = bearing(arc)+π/2 + (dir<0 ? π : 0)`；`scale=1/111320`；`lat+=side*cos(heading)*scale`，`lng+=side*sin(heading)*scale/max(0.25,cos(lat))`。
- 采样：`t=0,2,4,...,duration`（含 0 与末尾）；`speed=(travelled[i+1]-travelled[i])/2`，最后一点 `min(prev,1.6)`。轨迹几何长度约 2010–2071 m，与上报里程 2.03 km 的偏差 ≤3%（PC 验收记录本身为 2048 m 对 2030 m）。
- 单次运行必须在 2 s 内生成完成；内存 ≤ 2 MB。

### 6.3 上传体

- **轨迹**：JSON 数组，紧凑无空格 + 末尾 `\n`；每点 `{o: f32(lng), a: f32(lat), s: f32(round(max(0.1,speed),2)), l:1}`；在随机 3 个下标（2..n-3，不含 n-2）加 `b=f32(round(uniform(0.995,1.012),6))`、`c∈{338,352,366,407}`；`n-2` 处加 `p=1`。然后 `Protocol.encryptText(text, "NEW_GAME")` 的 ASCII 字节（**加密**）。
- **传感器**：1 Hz，共 `duration+1` 条（>50），每行 `{n:"%.6f"(lng), a:"%.6f"(lat), t:startEpoch*1000+sec*1000, ax/ay/az/gx/gy/gz:"%.2f"}`；运动波形照搬 Python（2.27 Hz 心跳 + 高斯噪声）；紧凑 JSON + 末尾 `\n`，**不加密**。
- **截图**：720×1080 PNG。背景 `#F1EFEA`，60 px 网格 `#E8E5DF`；轨迹白色 11 px 底线 + `#266CCE` 6 px 主线；起点绿 `#34A853`、终点红 `#D63E3E` 半径 9 的圆。必须用纯 Kotlin PNG 编码（Deflater + CRC32 + 过滤行），保证 JVM 单测可验证。
- `ArtifactBlob.sha256` 为 body 的 SHA-256；`samples` 为点数/行数。

### 6.4 必需单测

- 走廊长度 2070.8 m ±1%；`at()` 端点/插值正确。
- 同 seed 两次生成字节完全一致；不同 seed 不同；时长/点数符合；速度 >0。
- 轨迹 schema 精确（键集合、数值有限、坐标范围、`l=1`）。
- 解密轨迹体可被 `JSONArray` 解析，字段合法；加密体用 `Protocol.decryptText` 可还原。
- 传感器行数 `duration+1`、schema 精确、`t` 起点毫秒正确。
- PNG 以 `89 50 4E 47 0D 0A 1A 0A` 开头，IHDR 宽高 720×1080。
- 用 374 点真实走廊生成 741 s 轨迹，`route.length ≈ 2070`；给定 60–100 m 外的合成点位时，绕行轨迹对每个点位最近采样点 < 10 m，且 2 s 位移无瞬移、同 seed 完全可复现。

## 7. run slice 必需单测

- `AutoPlannerTest`：窗口外/星期不符/不支持分支/里程夹取/配速夹取/点位不足；20 m 门限内外的点位分别通过/拒绝；plan 字段与规则一致，`plan.via` 随 JSON 往返保留。
- `ManifestTest`：合法 manifest 通过；里程越界、打卡数与点位不符、时间越界、步数桶非法分别被拒。
- FSM 测试（用 fake transport/uploader）：正常路径阶段序列；stop 超时 → `stop_pending → uncertain` 且不重发；PUT 超时 → `upload_pending` + `uncertain`；恢复矩阵各分支。
- `VerifierTest`：8 项检查的全通过/单项失败；URL decode 后对象键匹配。

## 8. 完成标准

- `assembleDebug` + `testDebugUnitTest` 通过。
- 无自动重发：在代码中搜索不到对 STOP/LOCATION 的循环重试；写前必 persist。
- UI 契约不变：`RunController.inspect/start/verify`、`RunState/RunStore/PlannedRun` 字段。
- 不声称真机验收通过；在 `docs/acceptance.md` 如实列出。
