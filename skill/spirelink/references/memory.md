# 便签、教训与死亡交卷

现状一句话：**便签已上线**（`note_set` / `note_get` / `notes_list` / `event_note`，存在 `notes.json`，**跨局保留**）；**跨局教训那一套（`note_retract` / `submit_lesson` / `lesson_hits` / `run_log`）还没上线** —— `ToolCatalog` 里只有同名常量，没注册进 `tools/list`，调不到。下面「跨局教训」节是设计稿，只为将来对得上，**现在不要按它写自动化**。

## 便签（已实现）

- 写一条：`note_set { kind, id, text }`，例如 `{ "kind": "potion", "id": "FirePotion", "text": "留给精英" }`。
- `kind` 只有六个值：`event` / `potion` / `card` / `monster` / `relic` / `general`；其它（**包括旧设计稿的 `scope:"run"`**）一律 `BAD_REQUEST`，`message` 里会列出白名单。
- `id` 用游戏里的 id（`combat.monsters[].id`、`card_info.id`、`player.relics[].id`、`get_state.event.id`…）；`general` 的 `id` 自己起名（如 `run-1`）。
- 读：`note_get { kind, id }` → `{ok, key, text}`；没有这条时 `text` 是 `null`（**字段仍在，不是缺键**）。列表：`notes_list { kind?, sort? }`（`sort`：`recent` 默认、新的在前；`name` 按 `kind:id` 字典序）。
- 删单条：`note_set { kind, id, text: "" }`。**没有** `note_retract`，也**没有**任何批量删除/重置的 MCP 工具（全量清空只有人类能在游戏内便签面板点「清空全部」）。
- 事件房快捷写法：`event_note { text }` = 给当前事件写一条；不在事件房 → `NOT_READY`「当前不在事件房」。
- 存储：`mods/spirelink-data/notes.json`，键是 `kind:id`，**跨局、跨进程保留**（不是「只活这一局」，新局不会清空）。单条 2000 字**静默截断**；文件超 256 KB 会被改名 `notes.json.bad`（等于全丢）。
- 自动带回：`get_state` 会把便签挂到对应对象上（`combat.hand[]`/三堆/`combat.monsters[].note`、`player.relics[].note`、`potions.slots[]`/`player.potions[].note`、`event.note`、`card_info.note`、`memory.context_notes`、`memory.recent_notes`）。**对象上 `note` + `note_state` 每帧都在**（#127 三态）：`"none"` = 没便签（`note:null`，其余 `note_*` 不出现）；`"new"` = 本局第一次投递这个版本（`note` 是全文 + `note_history`/`note_updated_at`/`note_key`/`note_pushed:false`）；`"seen"` = 本局投过了（`note:null` + 另外四键、`note_pushed:true`）—— **`"none"`/`"seen"` 都是 `null`，看 `note_state` 区分**。`note_set` 改写会推进版本（`note_updated_at` 必进），下一帧重新投递全文。**`memory` 里的池子**仍按版本去重（同一局同一个 `updated_at` 只出现一次，`memory.notes_omitted_this_run` 会累加），要强制再看一次用 `get_state{force_notes:true}`。**「池子里没有」不等于便签没了** —— 对象上的 `note` 每帧都在，`note_get` / `notes_list` 永远给完整内容。
- 字段、容量、存储边界的权威版在 `docs/MCP-返回格式.md` §11（便签工具族）。

## 跨局教训（设计稿，未上线）

> 这一整节都**没上线**：`note_set { scope:"lesson", tags, condition, text, supersedes }`、`note_retract`、`lesson_hits`、`submit_lesson`、`run_log` 都不在 `tools/list` 里（`ToolCatalog` 只有常量）。现行 `note_set` 只认 `{kind, id, text}`，写 `scope` / `tags` 会被 `BAD_REQUEST` 拒。

将来上线时的口径（设计值，别现在用）：

- 一次死亡最多新建 **3** 条；`text` 8–80 字（英文 20–200 字符）；`condition` ≤ 40 字；`tags` 前缀白名单 `enemy` `elite` `boss` `card` `relic` `potion` `event` `act` `route` `keyword` `character`，且 id 必须在这一局的 `postmortem` 里出现过。
- `supersedes` 指向同标签集合里已 `active` 的旧条，旧条变 `superseded`（不无限堆）。
- 撤回用 `note_retract { lesson_id }` → 变 `retracted`；`lesson_hits` 只在三个时机出现（地图、战斗第一拍、奖励/商店/篝火/事件/Boss 遗物），最多 5 条，冲突两条都返回并标 `conflict:true`。

## 死亡 / 胜利：`postmortem`（已实现区块）

`ui=death` / `ui=victory` 那一拍 `get_state` 会带 `postmortem`（其它 `ui` 没有这个键）：

- 字段（当前实现）：`result`（`victory` / `death`）、`killed_by`（胜利时是空串）、`act`、`floor`、`hp`、`deck_size`、`deck[{id, upgraded}]`、`relics[]`、`potions[]`、`last_combat{monsters[], turns}`。
- 同拍可能带 `hints.suggest_note`（一句「建议记点什么」；`ready:true` 也会带，见 `docs/MCP-返回格式.md` §9）。写不写随你。
- 交卷 **`submit_lesson` 未上线**：那一拍只有 `proceed`（回主菜单）。别等它而卡住；等它出现在 `tools/list` 再照设计稿交卷。
- 每局结束会往 `mods/spirelink-data/history.json` 追加一条；下一局 `memory.briefing` 的 `recent_runs` / `death_causes` 就是从这里来的。

## 其他记忆工具

- `memory`（`get_state` 常驻区块）：`context_notes`（当前事件/药水/遗物/怪的便签）、`recent_notes`（最近 8 条）、`counts`、`total`、`notes_omitted_this_run`。
- `briefing`（在 `memory` 里）：只在这一局第一次、换幕后第一次、以及 `main_menu` / `char_select` / `neow` / `death` / `victory` 那几拍才给（其它时候 `include:["briefing"]` 点名要）；`run_history` 跟着它一起出现。
- `run_log`：**未上线**（只有常量），别调。
- 便签内容会当纯文本画到屏幕上（人类便签面板），不要写富文本或命令。

## 写便签的边界

- 写**下次还能用**的判断条件，不写策略口号（「先打精英再打 Boss」这种没用）。
- 写具体的、下次能验证的：「第 1 幕血低于 30 时不要进精英」，不写「要多注意血量」。
- 便签跨局保留：写对象 id（`GremlinNob`、`FirePotion`）才对得上，别写「那个精英」。
