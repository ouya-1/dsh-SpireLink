# 错误与恢复

## 通用原则

1. **先重读，再行动**：任何拒绝之后的第一件事是 `get_state` + `legal_actions`，不是重发旧载荷。
2. **同一个 `action_id` 是同一个动作**：重发只能用原 id；想换动作就必须换 id，并且重新取列表。
3. **不知道就停**：连续两次拿不到可用动作，就只读状态、向用户说明卡在哪，不要开始「试探性」提交。

## 处理矩阵

| 错误 / 结果 | 含义 | 该怎么处理 |
|---|---|---|
| `NOT_READY` | 队列里的命令 10 秒没轮到、动画还没结束、前置没做（如 Boss 宝箱没开）、**存档正在载入**（`awaiting:"loading"`，`continue_run`/`embark` 后 1~5 s，上界 30 s；#121·C12-01），或**正在离开本局**（`awaiting:"transition"`、`ui:"other"`，放弃后 0.8~1.6 s，上界 10 s；#123·S2），或**刚进战斗还在初始化**（`combat_settling`：怪挂着 `Intent.DEBUG` 占位、手牌空，`awaiting:"transition"` + `blocked_ui:"combat"`，4 秒上界；#125·C16-04） | 200–400ms 后再 `get_state`；不要立刻连续重试，不要打模型空转。载入那种**别重发 `continue_run`**；离开那种**别照上一帧的动作表动手**，只轮询到 `ui=main_menu` / `ready:true` |
| `ILLEGAL_ACTION` | 这个动作在当前画面/回合根本不适用（如非战斗时 `end_turn`、节点不可达） | **不要等**：重新看 `ui` 与 `legal_actions`，换个动作 |
| `applied` + `settled:false`（**不是错误**） | 提交型动作（`continue_run`/`embark`/放弃类）已经交出去、世界在过渡（#123）。`ready:false`、`actions:[]`、`awaiting` 是 `loading`/`transition` | **不要重发**（会被 `NOT_READY` 拒），只轮询 `get_state` 直到新屏 `ready:true`。回包耗时 **0.55~0.70 s**（不是 ~0.1 s），验收口径「≤1 s 且 `outcome` 不是 `queued`」 |
| `ACTION_IN_FLIGHT` | 两个来源（#108·C7-02 定码、#125·C16-02 收紧）：① 上一个 `act` 回过 `queued`、`state_version` 还没推进（3 秒墙钟护栏，回包带 `retry_after_ms`）；② **单飞队列 BUSY 且确实有动作在落地**（`queue_status:"BUSY"`，**没有** `retry_after_ms`）。**BUSY 但没有动作在飞 → 回 `NOT_READY`**（主线程被过渡/动画占着，message 写「主线程正忙着执行别的东西…」） | **禁止再发任何动作**；只 `get_state` / `legal_actions` 轮询，版本一推进保护自动解除（见下节）；`NOT_READY` 那支隔 200~500 ms 再读 |
| `STALE_VERSION` | 版本号对不上：带的**比现场旧**（状态已经推进）或**比现场新**（这个版本号还不存在，#120·C11-05；code 不变，只是 message 分两支） | `get_state` + `legal_actions` 重取；**禁止**重发旧载荷，也别猜版本号 |
| `ILLEGAL_ACTION` | 这一版没有这个动作，或目标已失效 | 重读；如果同类型连续 3 次，检查 `ui` 是不是变了（例如弹窗、玩家开了界面） |
| `ok: true` + `outcome: applied` | 动作执行完并已回到稳定状态 | 读 `log`，进入下一拍。**别指望它总有数字**（issue #108·C7-11）：差值只覆盖出牌/结束回合/选牌这类动作，`open_shop` / `buy` / `take_reward` 往往就一句话（如 `看看商店`），金币等数值看 `get_state` |
| `ok: true` + `outcome: queued` | 动作**已经进游戏**，只是还没稳定 | 见下节：只轮询，不重发 |
| `ok: false` + 错误码 | 见上面各行 | |
| 连接失败 / 工具不存在 | 模组没起、端口错、或这一版还没上线该工具 | 确认 `get_state` 是否可达；不可达就告诉用户「服务不在」，不要自己想办法 |

> 这张表只列**当前实现真会返回**的码。设计稿里的 `UNKNOWN_ID` `CANNOT_DRAW` `VALIDATION` `NOTE_LIMIT` `UNAUTHORIZED` **不是错误码**（`act` 与各工具都不会回它们），别给它们写分支；token 不对属于传输层 —— HTTP `401` + JSON-RPC `-32001`（见 `docs/MCP-返回格式.md` §8.4）。

## 动作在飞：`queued` → `ACTION_IN_FLIGHT`

`act` 返回 `outcome=queued` 表示动作已经进游戏、只是在等状态推进。之后 mod 会进入**护栏状态**（另一种进入方式不是 `queued`：`act` 刚发出就撞上**单飞队列 BUSY**，同一个错误码，但回包没有 `retry_after_ms`）：

- 任何新的 `act`（哪怕换了 `type`、换了 `action_id`）都会被拒，错误码 `ACTION_IN_FLIGHT`，消息里带「上一个动作还在执行（queued，state_version N）」。
- 保护看的是 `state_version`：只要它推进过（超过 `queued` 那一版），护栏自动解除。
- 安全阀：如果 **3 秒**都没有推进（某些画面不改签名），护栏会自己放开并在日志里 `warn` 一行，不会把 agent 永久锁死。
  这 3 秒是唯一真源（`ActionInFlight.TIMEOUT_MS`），旧文档写的「30 秒」是过期数字。

正确做法只有一条：**轮询**。

```text
act → outcome=queued
  → 不要 act（任何动作都白费，还会把两次点击叠在同一个对话框上）
  → get_state / legal_actions（200–400ms 间隔），直到 state_version 超过 queued 那一版（或 ready 变化）
  → 想确认「那一下到底打出去没有」：看 state_version、log_tail、combat 是否变化
  → 真要重发同一动作：只能用**同一个 action_id**（幂等，返回第一次的结果），并且要等护栏解除
```

为什么必须这么严：`queued` 说明命令已经交给主线程在跑，重发就等于第二次点击。真实事故：篝火连点 4 次 `smith` 把同一套动作叠了四层；`GremlinWheelGame` 连点直接把渲染线程卡死（只能重启游戏）。

## 常见剧本

### 玩家在旁边插手

`state_version` 突然跳了、`legal_actions` 全变。处理：接受现实，重读状态，不要尝试「回到刚才」。

### 玩家自己打开了官方界面（牌组 / 地图 / 弃牌 / 消耗）

`ui` 变成 `view`（`tutorial_tip` 一类）：只能退出这个画面（按 `legal_actions` 给的来，常见是 `dismiss_tip` 或 `proceed`），不能出牌。不要尝试在当前界面做动作。

### 动画卡在 `NOT_READY`

连续三次 `NOT_READY`：先确认 `get_state` 的 `ready` 字段与 `ui`，再决定是否继续等。若已超过十几秒，向用户说明「游戏似乎停在动画里」，而不是无限重试。

### 同一动作被拒两次

停。重读状态，检查：`ui` 是否变、目标是否 `is_gone`、能量是否够、是不是 `view` 挡着。仍然不行就换一个动作，或者问用户。

### 结算 / 死亡界面

`ui` 变成 `death` / `victory` / `run_over`：不要再尝试出牌。当前 build 只提供 `proceed`（回主菜单）；`submit_lesson` 还没上线（⏳），等 `tools/list` 里出现再走交卷流程（见 `references/memory.md`）。

### 服务不可用

`get_state` 超时或连接被拒：明确告诉用户「SpireLink 服务不可达（127.0.0.1:8731）」，并建议检查游戏是否启动、模组是否加载、端口是否被改。不要伪造状态继续推演。
