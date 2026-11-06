# 1.0.9 Release：高级选项（自定义里程与配速）

## 需求

东校区田径场绕圈跑的基础上，增加「高级选项」：自定义跑步长度与配速。

- 跑步长度必须 **> 1.00 km**；
- 配速范围 **3:00–9:00 /km**（180–540 s/km）；
- 路线仍是 `east-campus-track.json` 的 408.5 m 闭环，圈数由「长度 ÷ 周长」自动计算；
- 学校的里程/配速区间仍是最终约束，显式值超出区间时给出 blocker，不再静默夹取。

## 实现

- `core/RunOptions.kt`：选项模型 + 输入解析（`"5:30"` 或 `"5.5"` 分钟两种写法）+ 边界常量。
- `run/RunOptionStore.kt`：SharedPreferences 持久化，留空即恢复默认。
- `PlanRequest` 增加 `customDistanceKm` / `customPaceSecPerKm`；`PlanEvaluator`：
  - 本地边界校验（>1 km、3–9 min/km）→ 越界 blocker；
  - 学校区间校验（`[min_distance, max_distance]`、`[min_pace, max_pace]`）→ 越界 blocker；
  - 通过后 `duration = round(distance × pace)`，圈数里程 = `distance × 1000` 交给闭环生成器。
- `AutoPlanner` / `RunController` 读取本机选项并参与规划；计划仍持久化最终 `distanceKm`、`durationSec` 与 `route_template`，服务重启/上传重建一致。
- UI：配置卡新增「高级选项」按钮，弹窗两项输入 + 「恢复默认」；保存后自动刷新配置。计划行按实际 `duration/distance` 显示配速，不再固定 365 s/km。

## 验证

- `:app:testDebugUnitTest`：**169 个用例通过**（新增 6 个：自定义 2.50 km @ 5:30 → 825 s / 2500 m；≤1 km、超出学校里程、超出 3–9 min/km、超出学校配速分别 blocker；文本解析边界）。
- `:app:assembleRelease`：`dist/lepao-1.0.9-east-campus-release.apk`，versionCode 10 / versionName 1.0.9，同一 keystore。
- 已安装到真机 `2UCUT23C27103357`（覆盖升级，登录态与会话保留）。

## 边界

- 学校区间仍是硬约束：例如学校要求 2.00–10.00 km 时，1.5 km 的自定义会被 blocker 拒绝，而不是偷偷改成 2.01 km。
- 田径场模式不做打卡点绕行，环线必须自然经过点位（>20 m 会给出“田径场轨迹无法命中必经打卡点”）。
- 更长的里程意味着更长的前台服务时间（里程 × 配速），例如 5 km @ 5:30 ≈ 27.5 分钟。
