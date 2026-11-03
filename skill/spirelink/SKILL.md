---
name: spirelink
description: 用 SpireLink 的 MCP 工具操作《杀戮尖塔》/ Slay the Spire：读局面、看牌看怪看药水、选地图节点、出牌、记本局便签与跨局教训。Load before playing Slay the Spire (SpireLink mod); covers state reading, legal actions, inspect, routing, and lessons.
disable-model-invocation: true
---

# SpireLink：操作《杀戮尖塔》

本技能只讲**怎么操作**：该调哪个工具、该看哪个字段、出错了怎么办。游戏知识（卡牌效果、关键词、怪物招式、进阶规则）一律用模组的 `compendium` / `glossary` 查，不要凭记忆写。这里也不教怎么赢——策略由调用方决定。

## 什么时候用

本技能设了 `disable-model-invocation: true`：**不会出现在模型平时的 skill 目录里，也不会被自动加载**，只能由用户显式调用（例如在会话里打出 `/spirelink`）。

- 用户显式调用它 → 说明这一局由你按本规程操作，从「每一拍」第 1 步开始。
- 用户只说「打一局《杀戮尖塔》」但没加载本技能 → 先提醒他显式调用本技能，别凭印象直接调工具。
- 想确认服务在不在：`get_state` 能返回 `ok: true`，说明模组和 MCP 都活着。

## 硬规则

1. 动作只有一个来源：`legal_actions`。把返回的 `type` + `payload` + `state_version` 原样交给 `act`，不要自己拼字段，不要编 `uuid`、坐标、怪物下标。
2. 每次 `act` 生成一个新的 `action_id`（UUID）；同一个 `action_id` 重复提交是幂等的，不会打两次。
3. 被拒绝（`STALE_VERSION` / `ILLEGAL_ACTION`）就重新 `get_state` + `legal_actions`，**禁止重试旧载荷**。
4. `act` 返回 `queued`：动作已经进游戏了，**不要再 `act`**，只能 `get_state` 轮询到 `ready: true`。这期间再发动作会被 `ACTION_IN_FLIGHT` 拒——别换 id 重试，只轮询。**`applied` + `settled:false` 同理**（`continue_run`/`embark`/放弃类：已提交、世界在过渡，`ready:false`/`actions:[]`）——别重发，也别当失败。**`continue_run` 之后也一样**：run 还没出来时（`awaiting:"loading"`）三个入口都回 `NOT_READY`，别把「主菜单还在画」当没点动去重发 `continue_run`（#121）。
5. 事实以工具返回为准。查不到的 id 就是不知道，不要补一段「看起来像」的效果说明。
6. 看牌不用自己去点界面：要 `deck` / `discard_pile` / `exhaust_pile` / `draw_pile` 就在 `get_state{include:[…]}`（或 `card_info`）里要，mod 会把牌表真的翻开给旁边的人看。**peek 不挡动作**（issue #98）：你下一条 `act` 会**先关掉 peek 再执行** —— 不必等它 4 秒自关，也不必先 `dismiss_tip`（有 peek 时 `dismiss_tip` 只关 peek、不动底下的屏）。peek 开着的那几拍 **`ui` 和 `legal_actions` 与 peek 前完全一致**（#115：四种视图都算，商店里 peek 仍能 `buy`/`purge_card`/`leave`）。**别自己手动去开官方界面**（牌组/地图/弃牌/消耗）：那才是会改掉 `ui`、让下一步合法动作全变的那种操作。
7. 工具清单以 `tools/list` 为准：里程碑早期可能只上线了一部分工具，缺的就换一条路。
8. 死亡/胜利先读 `postmortem`（真的区块，字段见 `references/memory.md`）；`submit_lesson` 还没上线，那一拍只有 `proceed`，别等它。

## 每一拍

1. `get_state`（默认区块）：先看 `meta.ui` 判断是什么界面，再看 `hints`（它只在「有事要说」时出现，出现就要看）。
2. 若 `hints.last_error_fresh == true`：刚才那次 `act` 被拒，按 `hints.last_error` 里的码处理，别原样重发（见 `references/recovery.md`）。`true` 要三条件同时成立（#119）：`last_error_seq == act_seq`、是**动作**的错误（`get_state`/`legal_actions` 的读失败不算）、此后 `state_version` 没再推进 —— 序号相等不等于新鲜，读失败也不算。
3. 若 `ui == "map"`：`get_state` 带 `include: ["map"]`，看清可达节点与连线后再选（`plan_route` / `compendium` 还没上线，别调）。
4. 拿不准的词或牌面文本：`glossary` 查（`hints.keywords_unseen` / `needs_inspect` 目前恒为 `[]`，是占位字段，别等它）。
5. `legal_actions` 拿这一版的动作列表。
6. 选一个，原样 `act`（带新的 `action_id`）。
7. 按 `outcome` 走：`applied` → 读返回的 `log`，回第 1 步；`queued` → 只轮询 `get_state`；`rejected` → 按错误码处理，见 `references/recovery.md`。

## 按界面（`meta.ui`）决定下一步

- `combat`：出牌 / 用药 / 结束回合，**最后一条恒为「放弃本局（删存档并回主菜单）」（`abandon_mid_run`，只在能操作帧出现）**。`combat.energy`/`energy_max`/`playable_cards` 给数字；`combat.play_blocked` **恒定存在**：有阻塞时是一句「能量不足…play_card 因此不在 legal_actions 里，不是接口出错」，没阻塞时是 `null`——字符串出现时只该 `end_turn`/用药水。**刚进战斗头几帧**（`combat_settling`，怪还挂 `Intent.DEBUG` 占位）是 `ready:false` + `blocked_ui:"combat"`，等意图滚出来（4 秒上界）。字段怎么读见 `references/combat.md`；这局没救了才用它，它删存档回主菜单。
- 选择类界面（`combat_reward` `card_reward` `boss_reward` `shop` `event` `rest` `treasure` `grid` `hand_select` `choose_one` `transform` `neow`）：只在列表里选；`cancel` 只在**这一下真能退出**时才出现（网格要有 CancelButton；手牌选择只有「没选牌、点确认能关屏」那类屏才有，必选屏没有 cancel）。
- `map`：选 `reachable` 里的坐标（末行只剩 Boss 用 `choose_map_node{x:-1,y:<行数>}`）；只有「无节点、无 Boss、当前房间没结束」才给 `proceed`（关闭地图），**死角只给 `abandon_mid_run`**。
- `death` / `victory`：目前只有 `proceed`（回主菜单）；`submit_lesson` 未上线，等它出现在 `tools/list` 再交卷。
- `main_menu`：按钮组按**存档在不在**二选一 —— **有存档**只给 `continue_run` / `abandon_run`（这一拍没有 `open_mode_select`）；**无存档**只给 `open_mode_select`（这一拍也没有 `continue_run`，别等它出现）。点了 `abandon_run` 会弹确认框，那一拍换成 `confirm_abandon` / `cancel_abandon`，先处理弹窗。**点完 `continue_run` 到 run 出来之前是载入窗口**：三个入口都回 `NOT_READY`（`awaiting:"loading"`），只轮询、别重发（`embark` 同理）。
- `view`：玩家自己开了官方界面，先退出这个画面（按 `legal_actions` 给的，常见 `dismiss_tip` / `proceed`），不要出牌。
- `idle` / `other`：不做动作，等下一拍（`idle` 这一拍只有 `proceed`「打开地图」，**没有** `abandon_mid_run`）。

每个 `ui` 允许哪些 type 的完整对照表在 `references/tools.md`。

## 常见错误速查

| 现象 | 处理 |
|---|---|
| `STALE_VERSION` | **版本不对时它一定先说话**（拒绝优先级 `NOT_READY`→`STALE_VERSION`→`ACTION_IN_FLIGHT`→执行器，#121）。重新 `get_state` + `legal_actions` 再选；不要拿旧列表重发。**两支话术**（#120·C11-05）：带**比现场旧**的版本 →「已过期（当前 M）」；带**比现场新**的版本（还不存在）→「比当前版本更新…按最近一次读到的 M 重发」。**别猜版本号** |
| `ILLEGAL_ACTION` | 载荷不在这一版列表里，或目标已失效；重读，不要重试 |
| `NOT_READY` | 动画没结束；200–400ms 后再问，不要空转打模型。**放弃过渡**（`awaiting:"transition"`、`ui:"other"`，0.8~1.6 s）：旧动作表不作数，等到 `ui=main_menu`。**存档载入窗口**（`awaiting:"loading"`，`continue_run` 后 1~5 s，上界 30 s）：只轮询 `get_state`，**别重发 `continue_run`**（#121·C12-01）。**刚进战斗的 `combat_settling`**（#125·C16-04：怪挂 `Intent.DEBUG` 占位、手牌空，`awaiting:"transition"` + `blocked_ui:"combat"`，4 秒上界）：等意图滚出来，别出牌。**空动作也是 `NOT_READY`**（#120·C11-03）：`actions:[]` 一定带 `ready:false` + `blocked_ui` + `error.code:"NOT_READY"`（`get_state` 连 `ok` 都 `false`）；屏在切就约 1 秒后重试 `legal_actions`，**不是没出路** |
| `act` 返回 `queued` | 动作已经进游戏；只 `get_state` 轮询，要重发只能用同一个 `action_id` |
| `ACTION_IN_FLIGHT` | 上一个动作还在飞（`queued` 后的 **3 秒墙钟护栏**）**或**主线程队列里已经有一条在排队**且确实有动作在落地**（`queue_status:"BUSY"`，毫秒级、无 `retry_after_ms`）；**注意 #125 起：BUSY 但没有动作在飞时回的是 `NOT_READY`**（主线程被过渡/动画那一帧占着），别再把所有 BUSY 都记成 `ACTION_IN_FLIGHT`；**禁止再 act**，轮询 `get_state` 等 `state_version` 推进。护栏 3 秒到点会**真释放**；**护栏那种**回包带 `retry_after_ms`（剩余毫秒，拿它定轮询间隔），**队列 BUSY 那种没有** |
| 动作像卡住、`queued` 迟迟不来 | `act` 会先等最多 **10 秒**再回 `queued`，所以最坏感知 ≈ 13 秒；这个窗口里重发只会换来 `ACTION_IN_FLIGHT` |
| `GAME_DEAD`（或 `meta.game_dead: true`） | 游戏主线程已死，重试和轮询都不会好，**请人重启游戏**；`render_crashed` 只表示崩过，别混 |
| `TERM_NOT_FOUND` | 图鉴里查不到这个词；`glossary` 回包带 `suggestions`，照它再查，别编效果 |

组合情况与恢复剧本见 `references/recovery.md`。

## 不要做

- 不要调 schema 里没有的工具名，也不要一次提交多个动作（v1 一步一动作）。
- 不要把 `compendium` 里的「以后可能怎么打」当成这一手的意图。
- 便签**跨局保留**（`notes.json`），别把它当成「上一局的残留」；跨局只信 `note_get` / `notes_list` / `memory.briefing`（`lesson_hits` 还没上线）。**自动带出来的便签是历史，正文只投一次、`note_state` 说三态**（#124·C15-02 / #127）：`"new"` = 本局第一次投递这个版本（`note` 全文 + `note_history`/`note_updated_at`/`note_key`/`note_pushed:false`），`"seen"` = 本局投过了（`note:null` + 另外四键、`note_pushed:true`），`"none"` = 没便签（`note:null`、无其余键）—— **`"none"`/`"seen"` 都是 `null`，看 `note_state`**；`hints.note_history` 只在 `"new"` 那帧出现。别拿便签里的旧血量当实时值。药水便签也能直接读（`potions.slots[].note` / `player.potions[].note`）。
- 不要把策略建议写进教训（「应该先打精英」这种）；教训只写这一局能复用的判断条件。

## 细节索引

- 工具怎么用、每个 ui 能做什么：`references/tools.md`
- 战斗里哪些字段可信、怎么读牌读怪：`references/combat.md`
- 地图与路线：`references/map-route.md`
- 便签、教训、死亡交卷：`references/memory.md`
- 错误码处理矩阵：`references/recovery.md`
