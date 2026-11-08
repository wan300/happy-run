# UI 切片规格

范围：`app/src/main/java/com/lepao/companion/ui/**`、`app/src/main/res/**`（`values/strings.xml`、`values/colors.xml`、`values/themes.xml` 可扩展）、`app/src/test` 中的 UI 相关纯逻辑测试（可选）。不得修改 `core/**`、`capture/**`、`run/**`、`AndroidManifest.xml`、Gradle。

目标：单机、无电脑、低操作成本。界面只暴露三个动作：获取登录态、开始跑步、查看/回查结果；其余参数全部只读展示。

## 页面结构

### MainActivity（启动页）

`ScrollView > LinearLayout(vertical)`，纵向卡片：

1. **标题栏**：`乐跑伴跑`。
2. **登录态卡片**
   - 状态：`未获取` / `已就绪` / `已过期，请重新获取`。
   - `uid`（`Identity.maskedUid()`）、学校、抓取时间、`refresh` 剩余天数（无刷新令牌时显示 `仅 access_token，到期后需重新登录`）。
   - 按钮：`获取登录态`（打开 `CaptureActivity`）；`检查并续期`（后台执行 `TokenService.maybeAutoRefresh(context, session, minDays = 3.0)`，完成后 Toast 结果并刷新）；`清除登录态`（AlertDialog 二次确认，`SessionStore.clear()`）。
3. **跑步配置卡片**
   - 只读显示：学校/跑区、入口时间窗、有效时段规则、里程范围、配速范围、最少打卡、点位数量、`gyroscope_type`。
   - 计划参数：`2.03 km · 约 12:21 · 365 s/km · 1690 步 · 2 个打卡点`（来自 `ReadinessView.plan`）。
   - blockers（红色列表）与 warnings（橙色列表）。
   - 按钮：`刷新配置`（`RunController.inspect`）、`开始跑步`（仅 ok 时可用；AlertDialog：`将向官方服务写入一条真实跑步记录，是否继续？`）。
   - 若 `RunStore.blockingRun() != null`：顶部红色提示“存在未结束会话，禁止开始新跑步”，开始按钮禁用，并显示该会话进度。
4. **当前/最近会话卡片**
   - 阶段中文映射：`prepared=已准备`、`start_pending=起跑结果待核对`、`running=跑步中`、`location_pending=位置上报待核对`、`uploading=上传中`、`stop_pending=结束结果待核对`、`stop_acknowledged=服务端已接受`、`sensor_pending=传感器登记中`、`submitted=已提交待回查`、`verified=回查通过`、`uncertain=结果不确定`、`failed=失败`。
   - 进度：`elapsed/duration`（`RunState.elapsedSeconds(SystemClock.elapsedRealtime())`）、里程（规划值）、心跳次数、`record_id`。
   - `uncertain/stop_pending/upload_pending`：红色说明“为避免重复提交，已停止自动操作；请回查或人工核对”，并显示 `lastError`。
   - `submitted/verified`：8 项回查结果（✅/❌ + 中文名）、记录详情（`record_status`、`distance`、`time`、`step_num`、`run_zone_name`）；显著提示：`记录已写入官方服务；学期统计（RunStat/H5）不在本应用校验范围`。
   - 按钮：`重新回查`（`RunController.verify`，仅 submitted/verified 可用）、`打开乐跑`（`CaptureController.openOfficialApp`）。
5. **历史记录**：最近 5 条（时间、阶段、里程、记录号），点击展开原始 JSON（`RunState.toJson().toString(2)`）。
6. **运行日志**：可折叠 `DiagLog.recent()` 文本 + `复制`。

### CaptureActivity（获取登录态）

- 顶部说明：`1. 关闭其他 VPN（Clash 等） 2. 开始采集并允许 VPN 授权 3. 打开乐跑完成登录 4. 进入首页/跑步页后返回本页完成`。
- 进度 checklist（`CaptureStore.status`）：
  - 本地采集通道已建立（`active/vpnActive`）
  - 已捕获登录响应（`loginSeen`）
  - 已捕获设备与账号上下文（`identitySeen`）
  - 登录态可保存（`contextReady`）
  - 最近接口（最多 5 个 endpoint）
- 按钮：`开始采集`（`CaptureController.prepareConsent` → `startActivityForResult` → `startService`）、`打开乐跑`、`完成并保存`（`assembleSession()` 非空 → `SessionStore.save` → Toast → `finish()`）、`停止采集`。
- 错误/提示显示 `status.error` 与 `status.hints`。
- 返回键：若有活跃采集，提示是否停止。

## 交互约定

- 网络/磁盘操作一律放到单线程 `ExecutorService`；主线程只做渲染，按钮在操作期间禁用并显示 `ProgressBar`。
- Activity `onResume` 启动 500 ms 轮询（`Handler`），`onPause` 停止；不使用额外依赖。
- Android 13+ 首次进入时请求 `POST_NOTIFICATIONS` 权限（`ActivityCompat.requestPermissions`），拒绝也不阻断功能。
- 所有用户可见文案放 `strings.xml`（中文），不硬编码在 Kotlin 里（日志/动态值除外）。
- 不使用 Compose、Navigation、ViewModel、Room；仅 AppCompat + 现有依赖。
- 视觉：白卡片 + 8 dp 圆角 + 1 dp 边框 `#E3E6EA`；主色 `#266CCE`；错误 `#C63D3D`；成功 `#2E9E5B`；按钮高度 ≥ 48 dp。
- `MainActivity` 保持 `exported=true` 的 LAUNCHER；`CaptureActivity` 不导出。

## 必须覆盖的边界

- 未获取登录态：配置卡片显示“先获取登录态”，按钮禁用。
- `inspect` 返回 blockers：逐条展示，开始按钮禁用。
- 恢复运行中的服务：进入首页即显示进度；若服务已死但状态为 running，提供“恢复会话”（启动 `RunForegroundService`）按钮，文案说明会从已保存状态继续。
- `uncertain`：不允许“重新提交”，只允许“回查记录”（有 recordId 时）、“打开乐跑核对”，或“放弃本次会话（仅本地）”（二次确认；不发送请求、不删除服务端记录，只解除本地阻塞）。
- 恢复会话：`RUNNING`、`UPLOADING`（无歧义 PUT）、`STOP_ACKNOWLEDGED` 显示“恢复会话”；有歧义写的阶段只提供回查/放弃。
- 无官方乐跑：按钮给出“未安装官方乐跑”提示。
- 深色模式不做特殊适配（固定 Light 主题）。

## 验收

- `./gradlew :app:assembleDebug` 通过。
- 在 Android Studio 预览或模拟器中手工走查：首次启动、无会话、Capture 页面状态变化、长文案不重叠、按钮禁用态。
- 不修改其他切片文件；与 `core` 的公开 API 有任何缺口时先告知 Lead。
