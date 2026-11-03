# MCP 返回格式手册

这份文档里的每一段 JSON 都是**从真实游戏里抓下来的**，原始样本在 `docs/samples/`（40 个文件，逐条对应见 [§10 样本索引](#10-样本索引)）。
抓法：一边跑图一边按 `meta.ui` 去重存 `get_state` 和 `legal_actions`，动作类每种动作各留一份回包；
脚本是 `scripts/capture_samples.py`（见 [§14](#14-抓样本脚本)），索引自检 `python scripts/capture_samples.py --check-index`。

> 每条结论后面都跟一句 **「核对方式」**：一条能自己复现的命令（多为 `curl`）。合不上就是文档过期了，请以那条命令的输出为准。

> 篇幅原因：长数组只留前 3 项、长字符串截到 160 字符，并标出 `... 共 N 项`。真实返回是完整的。

## 目录

0. [传输层](#0-传输层)
1. [通用信封](#1-通用信封)
2. [`get_state` 骨架](#2-get_state-的骨架)
3. [常驻区块：player / potions / memory](#3-常驻区块)
4. [`act` 的返回](#4-act-的返回)
5. [`legal_actions` 的返回](#5-legal_actions-的返回)
6. [各界面真实返回](#6-各界面真实返回)（开头是 [ui 词表](#61-ui-词表)）
7. [结构已知但样本不全的界面](#7-结构已知但样本不全的界面)
8. [错误码](#8-错误码)
9. [三个特殊情形](#9-三个特殊情形)
10. [样本索引](#10-样本索引)
11. [便签工具族](#11-便签工具族)
12. [鉴权与 `test_mode`](#12-鉴权与-test_mode)
13. [`card_info` 与 `glossary` 的字段](#13-card_info-与-glossary-的字段)
14. [抓样本脚本](#14-抓样本脚本)
15. [契约自检：enum 覆盖到哪了](#15-契约自检enum-覆盖到哪了issue-81)

---

## 0. 传输层

`POST http://127.0.0.1:8731/mcp`，JSON-RPC 2.0 over **Streamable HTTP 的降级实现**：只吃 `POST`，不提供
SSE 流。业务对象是 `result.content[0].text` 里的**一串 JSON 字符串**，要再解析一次；同一份对象也放在
`result.structuredContent`（能直接用就不用解两次）。

### 0.1 握手（三步）

**第 1 步 `initialize`** —— 唯一一个**不带会话头**的请求：

```bash
curl -s -D - -X POST http://127.0.0.1:8731/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"my-agent","version":"1.0"}}}'
```

响应头里的 `Mcp-Session-Id` 就是会话号，**存下来**（下面是真实响应头）：

```
HTTP/1.1 200 OK
Content-type: application/json; charset=utf-8
Mcp-session-id: e8e47761-dc81-4662-b9aa-9994c968bcba
Access-control-expose-headers: Mcp-Session-Id
```

```json
{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","capabilities":{"tools":{}},"serverInfo":{"name":"spirelink","version":"0.1.0"}}}
```

`protocolVersion` 只认 `2024-11-05`（默认）/ `2025-03-26` / `2025-06-18` 三个；报别的值**不报错**，回退成
`2024-11-05` —— 以响应里的值为准。

> **核对方式**：`curl -s -D - -X POST http://127.0.0.1:8731/mcp -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"x","version":"1"}}}'`，看响应头里的 `Mcp-session-id`。

**第 2 步 `notifications/initialized`**（没有 `id`，服务端回 `202` + 空 body）：

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:8731/mcp \
  -H 'Content-Type: application/json' -H "Mcp-Session-Id: $SID" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'
# 202
```

**第 3 步**：之后每个请求都带 `Mcp-Session-Id: $SID`。

会话的三条实测事实（最容易误判的地方）：

| 情形 | 真实行为 |
|---|---|
| 带头、但会话号不认识 | `404` + JSON-RPC `-32001`「未知会话 Mcp-Session-Id，请重新 initialize」，HUD 提示「会话失效，重连」 |
| **完全不带**会话头 | **照常执行**（`tools/list` → `200`）。服务端只在「带了但不认识」时才拒 —— 想要「重启游戏后老客户端立刻失效」的语义，就老实带 |
| `DELETE /mcp` + 会话头 | `204`，该会话号立刻作废（再带它 → `404 -32001`） |

> **核对方式**：同一条 `tools/list`，带 `-H 'Mcp-Session-Id: deadbeef-0000-0000-0000-000000000000'` → `404`；不带这个头 → `200`。

### 0.2 这些「看起来像故障」的返回都是正常的

| 请求 | 返回 | 说明 |
|---|---|---|
| `GET /mcp` | `405` + `Allow: POST, DELETE, OPTIONS`，body 是 `-32600`「本服务不提供 GET 事件流，请使用 POST …」 | 服务端没有 server→client 流。**不是端口错，也不是协议版本错** |
| `GET /` | `404` | 路由只有 `/mcp`、`/health` 两个 MCP/探活路由，外加两个**非 MCP** 端点 `/hud/say`、`/hud/driver`（下面五行） |
| `POST /hud/say` | `200` + `{shown,sessionId,ttlMs,…}`；body 坏 → `400` | **非 MCP**（issue #28）：把一行字推到游戏左上角的思考块。body `{"text":"…","ttlMs":12000}`，会话号放 `X-DSH-Session` 头（可省） |
| `GET /hud/driver` | `200` + `{sessionId,lastSeenAt,actions}` | **非 MCP**：谁在开车（哪个 DSH 会话在调这个游戏）；`sessionId:null` = 还没有人调过 |
| `GET /hud/say` / `POST /hud/driver` | `405` + `Allow: POST, OPTIONS` / `Allow: GET, OPTIONS` | **`/hud/*` 只有这两个端点**：方法用错是 405，不是端点不存在 |
| `/hud/nope`、`/hud` 等其它名字 | `404` + `{"error":"未知 HUD 端点: …"}` | 端点名写错。`/hud/*` 的 body 都不是 JSON-RPC（见 §8） |
| `OPTIONS /hud/*` | `204` | CORS 预检 |
| `GET /health` | `200`，`{"status":"ok","server":"spirelink","endpoint":"/mcp"}` | 探活用它，不需要会话头 |
| `OPTIONS /mcp` | `204` | CORS 预检 |
| 请求体不是合法 JSON | `400` + `-32700`「解析 JSON 失败」 | 必须是**单个** JSON-RPC 对象 |
| 请求体 > 1048576 字节（1 MB） | **`413`** + `-32600`，文案含 `1048576` | 读满上限就拒，这条请求**没执行**；缩小 body（分页/裁剪/只发改动字段）**换新 `id`** 重发，原样重试还是 413（issue #71）。细则见 §8.4 |

> **核对方式**：`curl -i http://127.0.0.1:8731/mcp`（GET，不带 body）→ 405；`curl -s http://127.0.0.1:8731/health` → 200；`curl -i http://127.0.0.1:8731/hud/say`（GET）→ 405；`curl -i http://127.0.0.1:8731/hud/nope` → 404；`curl -s http://127.0.0.1:8731/hud/driver` → 200 + `{sessionId,…}`（以上都在没配 token 时）。

### 0.3 `Accept` 头：规范要求带，本服务不校验

按 MCP 规范，客户端应发 `Accept: application/json, text/event-stream`。**本服务不看这个头**：只发
`application/json`、只发 `text/event-stream`、甚至一个 `Accept` 都不发，`tools/call` 都是 `200`。
换任何 HTTP 客户端都不必为它做特殊处理（带着也没坏处，别的 MCP 服务可能要）。

> **核对方式**：同一条 `tools/call` 分别加 `-H 'Accept: application/json'` / `-H 'Accept: text/event-stream'` / `-H 'Accept:'`，三次都 `200`、body 相同。

### 0.4 一次成功调用的完整形状

请求：


```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "get_state",
    "arguments": {
      "include": [
        "combat"
      ]
    }
  }
}
```

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "{\"ok\":true,\"ready\":true,\"state_version\":151,...}"
      }
    ],
    "structuredContent": {
      "ok": true,
      "ready": true,
      "state_version": 151
    }
  }
}
```

两条要点：

- `content[0].text` 与 `structuredContent` 是**同一个业务对象**（前者是字符串、后者是已解析的对象）。
- `isError` **只在工具级失败时出现**（成功时这个字段根本不出现，不是 `false`）：读到它才代表
  `content[0].text` 里是一份失败回包。客户端写 `result.get("isError", False)` 即可。

> **核对方式**：`curl -s -X POST … -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"note_set","arguments":{"kind":"bogus","id":"x","text":"y"}}}'` → 回包里**有** `"isError": true`；把 `kind` 换成 `general` 之外的合法值再试就**没有**这个字段。

### 0.5 `tools/list` 的返回格式

```json
{"jsonrpc":"2.0","id":2,"result":{"tools":[
  {"name":"get_state","description":"读取当前局面快照。…","inputSchema":{"type":"object","properties":{
     "include":{"type":"array","description":"白名单…","items":{"type":"string","enum":["player","potions",…]}},
     "force_notes":{"type":"boolean","description":"true 时忽略「本局已推送过」的去重…"},
     "exclude":{"type":"array","description":"黑名单…"}}}},
  {"name":"act","description":"执行一个 legal_actions 给出的动作。…","inputSchema":{"type":"object","properties":{…},"required":["type","state_version"]}}
]}}
```

- 每个工具三个字段：`name`（`tools/call` 的 `params.name`）、`description`（中文一句话）、`inputSchema`（JSON Schema）。
- `inputSchema.required` **只在真有必填项时出现**：`act`（`type` / `state_version`）、`card_info`（`uuid`）、`dev_use_card`（`id`）；`get_state` / `legal_actions` / `glossary` / `note_*` 都没有必填项（参数都可省）。
- **`act.action_id` 是可选参数**（issue #80 已收口，schema 与 description 对齐）：`required` 只有 `type` / `state_version`；`action_id` 的 description 是「客户端生成的 UUID，可选但强烈建议：用于幂等（同 id 只执行一次，重发不会把同一个动作再做一遍）」。
  **强烈建议照发**：省掉等于放弃幂等保护；传了但不是字符串 → `BAD_REQUEST`。
- **`act` 的顶层只认 4 个字段**：`type` / `state_version` / `payload` / `action_id`（#122·C13-04）。动作参数**必须**放进 `payload` 对象里 —— **摊平写在顶层会被 `BAD_REQUEST` 拒**，报文会列出多写的键并给模板：「act 的顶层只认 type / state_version / payload / action_id 四个字段，但收到了 index、uuid —— 这些是动作参数，必须放进 payload 对象里，不能摊平写在顶层。正确写法：{"type": "<动作类型>", "state_version": <上次读到的版本>, "payload": {"index": …}}；legal_actions 里 actions[].payload 原样搬进 payload 即可。」**`payload` 只接受 JSON 对象**：不需要载荷时直接省略，别传 `null` / 数组 / 字符串（同样 `BAD_REQUEST`）。
- **工具数只有两种：9 或 19** —— 常驻 9 个；`meta.test_mode == true` 时再多 10 个 `dev_*`（逐个清单见 [§12](#12-鉴权与-test_mode)）。
- 客户端可以在启动时拿 `tools/list` 做一次契约自检：名字集合与下面不符，就是模组版本变了。

常驻 9 个：`get_state` `legal_actions` `act` `card_info` `glossary` `note_set` `note_get` `notes_list` `event_note`。

> **核对方式**：`curl -s -X POST … -H "Mcp-Session-Id: $SID" -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' | python -c "import json,sys;print(len(json.load(sys.stdin)['result']['tools']))"` → 现场 **19**（`test_mode=true`）；把 `test_mode` 关掉重启游戏后是 **9**。

### 0.6 未知工具 / 未知方法

未知工具是 `-32602`，文案是「未知工具: <名字>（用 tools/list 取当前可用的工具清单，再按里面的 name 调用）」——
**里面不列任何工具名**：工具集合随 `test_mode` 在 9 / 19 之间变，唯一权威是 `tools/list`。
（issue #54 之前的旧文案硬编码了 4 个 P1 名字，已删。）

> **核对方式**：`tools/call` + `"name":"no_such_tool"` → `-32602`，`error.message` 里只出现 `no_such_tool` 和 `tools/list` 引导，不出现 `get_state` / `legal_actions` / `act` / `card_info`。

鉴权（`Authorization: Bearer <token>`）与 `test_mode` 开关见 [§12](#12-鉴权与-test_mode)。

## 1. 通用信封

| 字段 | 含义 |
|---|---|
| `ok` | 调用本身是否成功；`false` 时看 `error` |
| `ready` | 游戏是否在可接受动作的稳定状态 |
| `state_version` | 单调递增；`act` 必须带你看到的那一版。**只在「决策点/关键状态变了」时推进**，细则见下面 §1.1 |
| `error` | 成功 `null`，失败 `{code, message}` |

### 1.1 `state_version` 什么时候推进（issue #75）

`state_version` 不是「每帧 +1」，而是**签名变了才 +1**：签名包含 `ui`、血量/格挡/能量、手牌与牌堆指纹、当前这一拍能做的事（`actions` 的类型+载荷集合）、房间/界面阶段等「agent 要据此做决定」的东西。

- **会推进**：换屏、掉血/加格挡、出牌/弃牌、能量变化、回合切换、可动作集合变化、进入新房间/新幕……
- **不推进**：事件正文**一个字一个字打出来**（打字进度只刷新 `event` 区块的正文与 `text_typing`，版本号不动）、纯动画/渲染、以及任何不改变「可动作集合」的字段。
- 所以 `act` 用旧版本号并不会因为「正文多打了两个字」而 `STALE_VERSION`；反过来，**看到 `state_version` 没动就说明这一拍和上一拍在决策上是同一拍**，别为它编动作。
- 副作用：一张牌打出去可能一次跳好几格（多个签名变化叠在同一帧里合并成一次 bump），**不要假设一次动作只 +1**。

失败样本（用不存在的 uuid 去 `play_card`）：

```json
{
  "ok": false,
  "outcome": "rejected",
  "state_version": 3,
  "error": {
    "code": "NOT_READY",
    "message": "不是玩家回合或动画没结束",
    "state_version": 3
  },
  "action": {
    "type": "play_card",
    "payload": {
      "uuid": "not-a-real-uuid"
    }
  },
  "actions": [],
  "ui": "combat",
  "ready": false
}
```

未知动作类型：

```json
{
  "ok": false,
  "outcome": "rejected",
  "state_version": 3,
  "error": {
    "code": "UNKNOWN_ACTION_TYPE",
    "message": "不认识的动作类型: no_such_action",
    "state_version": 3
  },
  "action": {
    "type": "no_such_action",
    "payload": {}
  },
  "actions": [],
  "ui": "combat",
  "ready": false
}
```

## 2. `get_state` 的骨架

`get_state` 的顶层键：`ok` / `ready` / `state_version` / `error`（就绪时 `null`，未就绪时 `{code:"NOT_READY"|"GAME_DEAD", message, state_version}`）/ `blocked_ui`（**只在未就绪时出现**，= 这一拍的 `ui`，与 `legal_actions` 顶层同地址同值，#121·C12-04）＋各区块。

不给 `include` 按界面给默认区块；给了 `include` 就**只**回 `meta` + 点名的区块（`exclude` 从默认集合里减）。

| 区块 | 出现时机 |
|---|---|
| `meta` `player` `potions` `memory` | 永远 |
| `combat` | `ui=combat` |
| `map` | `ui=map`（整幕） |
| `event` | `ui=event` / `ui=neow` |
| `neow` | `ui=neow`（与 `event` 同时给，见 §7 的 `neow` 一节） |
| `rewards` `card_reward` `boss_reward` | 对应奖励界面 |
| `shop` | `ui=shop` |
| `char_select` | `ui=char_select` |
| `match_game` | GremlinMatchGame |
| `postmortem` | `ui=death` / `ui=victory` |
| `briefing` | 可以**单独**要了（issue #85）：`include:["briefing"]` → 顶层 `briefing` 区块；`include:["memory","briefing"]` → 仍然只在 `memory.briefing` 下（**同一份只发一次**，不会两边都有） |
| `hints` | 只在「有事要说」时给整块（`ready=false` / 上次 `act` 被拒 / `ui=char_select` / **本回包投递了新便签正文（`note_history`）** / hints 自带 `map_no_reachable`·`suggest_note`·`unknown_ui`·`event_missing`），字段逐条见 [§9](#9-三个特殊情形) |

`get_state.inputSchema.include.items.enum` 里是 **20 个名字**：上面表里的 16 个区块名（`player` `potions` `memory` `combat` `map` `event` `neow` `rewards` `card_reward` `boss_reward` `shop` `char_select` `match_game` `postmortem` `briefing` `hints`）
+ 四个看牌用的 `deck` / `draw_pile` / `discard_pile` / `exhaust_pile`。

`include` / `exclude` 的三条规则（issue #58 / #66 / #68），实测口径：

1. **未知块名 = 忽略**：不在上面名单里的名字既不报错、也不占 key（`include:["nosuchblock"]` 只回 `meta`，不会凭空多出一个 `nosuchblock: {}`）。
2. **`meta` 永不可排除**：`exclude:["meta"]` 不生效 —— `meta.ready` / `meta.state_version` 是 agent 判断「我手上的版本还有效吗」的唯一依据，空响应不是合法答案。
3. **`exclude` 压过 `include`**：同一个块名两边都写了，就是「不要它」——`include:["hints"], exclude:["hints"]` 回的是**没有 hints** 的响应，不会退化成空占位。

`exclude:["hints"]` 现在**能删干净**了（以前存活探针会把它补回 4 个字段，那是旧行为，issue #66/#68 已修）。

四个「看牌」专用的 `include`：`deck` / `draw_pile` / `discard_pile` / `exhaust_pile`。
它们会**真的在屏幕上打开那份牌表**（鱼先走到按钮再翻开），返回 `{count, cards[]}`，每张牌 `{uuid,id,name,type,rarity,cost,upgraded,upgraded_name?,upgraded_text?}`。`card_info` 也会开同一种**牌库** peek（除非那张牌正在手上，翻开反而挡住抬牌动画）。

**peek 的规则（issue #98 / #115，别按老文档「固定停 4 秒挡住你」理解）**：

- **下一条 `act` 会先把 peek 关掉、再执行那条动作** —— 看牌永远不会成为动作的障碍，你不需要等、也不需要先 `dismiss_tip`。
- 没人动作时它自己 **4 秒**后关（`DeckPeek.HOLD_MS`，关之前鱼会去点返回）。
- 唯一例外：**`dismiss_tip` 在有 peek 时只关 peek、不动底下的屏**（底下可能正是你在做的 `grid` / `hand_select`）。
- peek 开着的那几拍 `meta.ui` **仍报打开前那一屏**（`DeckPeek.underlyingUi()`），`legal_actions` 也照旧 —— 看牌不是决策点。实现里 `act` 的合法性闸读的是原始 `AbstractDungeon.screen`（此时是四种视图屏之一），所以才需要上面「先关再执行」这一步；对外表现就是**peek 不挡动作**。
- **#115：四种 peek 全按上面这条走**（`MASTER_DECK_VIEW` / `DISCARD_VIEW` / `EXHAUST_VIEW` / `GAME_DECK_VIEW`）。旧版本只认第一种，于是 `include:["discard_pile"]` / `["exhaust_pile"]` / `["draw_pile"]` 的那 4 秒会掉成 `ui=tutorial_tip`、`legal_actions` 缩成 `[dismiss_tip]`，而 `play_card` / `buy` 其实照跑（广告和执行器对不上）。现在**这四种 peek 期间 `ui` 与 `legal_actions` 和 peek 前完全一致**：商店里仍是 `buy` / `purge_card` / `leave`（不是 `shop_room` 的 `open_shop` / `leave`），战斗里仍是 `play_card` / `end_turn` / `use_potion` / `discard_potion`，`grid` / `hand_select` / `card_reward` 同理。**人类自己点顶栏看的视图不受这条约束**：那不是 peek，`ui=tutorial_tip`、用 `dismiss_tip` 关（见 §6.1）。

### `meta`

**现场固定 24 个键**（13 个界面逐个抓过，键集合一致），下表按真实顺序排。其中 `game_dead` 是 issue #69 之后新增的**常驻**键 —— 2026-11-03 之前抓的样本是 23 键（见 §10.1）。

| 字段 | 说明 |
|---|---|
| `ready` | 这一拍能不能动作；`false` 时看 `awaiting`。**屏上没有任何可执行动作时也会是 `false`**（#120·C11-03）：这时 `meta.awaiting` 常见 `screen_up`、另有 `meta.blocked_ui` = 当前 `ui`，`get_state` 连顶层 `ok` 都是 `false`（见 §5） |
| `test_mode` / `speed` | 测试模式 / 倍速 |
| `awaiting` | 在等什么（**闭集，11 个值**，见 §2.1）：未就绪时 `playing` / `enemy_turn` / `animating` / `screen_up` / `transition` / `battle_over` / `event_text_typing` / `loading`（存档正在载入）/ `unknown`；**就绪时也不只有 `playing`** —— `char_select`（选人屏）/ `dead`（死亡屏），其余才是 `playing` |
| `state_version` | 与顶层 `state_version` 同源，同一份回包里两个值一定相等（`act` 用哪个都行） |
| `in_game` | 是否在地牢里（主菜单 `false`） |
| `seed` / `seed_raw` | 种子：字符串形态 / 原始 `long` |
| `character` | 当前角色（`IRONCLAD`/`THE_SILENT`/`DEFECT`/`WATCHER`），还没选人时 `null` |
| `character_source` | `character` 这个值**是从哪读到的**：`player`（`AbstractDungeon.player.chosenClass`）或 `char_select`（选人界面高亮的那张卡）。选人界面里 `player` 还指着上一局，所以那一屏改读画面（issue #49）——值看着不对时先看这个字段 |
| `ascension` / `act` / `floor` | 进阶等级 / 第几幕 / 第几层。`ascension` 在 `ui=char_select` 那一屏取的是**屏幕上已经设好的等级**（`CharacterSelectScreen` 的 `ascensionLevel`；没开进阶模式就是 `0`），而不是 `AbstractDungeon.ascensionLevel` —— 后者要到点「启程」才写进去。所以刚 `choose_ascension` 完就能拿 `meta.ascension` 复核（issue #77） |
| `screen_type` | 游戏原始的 `AbstractDungeon.CurrentScreen`（`NONE`/`SHOP`/`CARD_REWARD`…）—— **调试值**，判定界面用 `ui` |
| `room_phase` / `room_type` | 房间阶段 / 房间类名（主菜单两者都 `null`；地图上固定 `NONE` + `MapRoom`） |
| `room_cleared` | 只在 Boss 清场那一拍为 `true`：`room_type=MonsterRoomBoss` 且 `room_phase=COMPLETE`（此时 `ui=boss_cleared`）；其余界面一律 `false`（issue #42） |
| `render_crashed` | **粘性**：渲染循环抛过异常就 `true`，重启游戏之前不会自己变回 `false`；`true` 时 `act` 一律 `NOT_READY`。崩过但此时还 `false` 的写法是 bug，别接受 |
| `crash` | 只在 `render_crashed:true` 时出现，是那条崩溃的一句摘要（异常类名/消息），方便直接贴进 issue |
| `game_dead` | **终态信号（issue #69）**：主线程真的死了（崩溃已捕获 **且** 帧计数器停住），再等也不会好 → 见 §8.1 的 `GAME_DEAD`。活着时恒为 `false` |
| `dead_for_ms` / `dead_hint` | 只在 `game_dead:true` 时出现：死了多久（毫秒）/ 一句给人看的处置提示。`game_dead` 变回 `false` 时这两个键会被**移除**（不留在快照里） |
| `ui` | **派生界面名**，判断界面用它，不要用 `screen_type` |
| `ui_group` | 归组：只有 `shop_room` 归一成 `shop`，其余与 `ui` 相同 |
| `suggest_include` | 该界面建议的 `include`（当前恒 `[]`，占位） |
| `mod_version` / `language` | 模组版本（`0.1.0`）/ 游戏语言（`ZHS`…，永远来自运行时设置，不看 `Locale.getDefault()`） |

另外几个**只在特定时刻**多出来的键：`in_flight: true`（有动作还在飞，`ready` 会被压成 `false`，见 §8.3）、`blocked_ui`（屏上没有可执行动作时 = 当前 `ui`，见 §5）、`crash`（崩过才有）、`dead_for_ms` / `dead_hint`（判死才有）。**别把键数写死**——`meta` 是「24 个常驻 + 这几个条件键」。

三者关系一句话：**`render_crashed` 是「崩过」**（粘性），**`game_dead` 是「已经救不回来了」**（崩溃 + 帧停），`dead_for_ms` / `dead_hint` 只跟着 `game_dead` 走。判断「要不要喊人重启」看 `game_dead`；判断「历史上有过崩溃」看 `render_crashed`。

真实 `meta`（`ui=event`，与 `docs/samples/state-event.json` 同一拍；那份样本抓于 `game_dead` 之前，所以是 23 键 —— 新构建在同一位置会多一个 `"game_dead": false`）：

```json
{
  "ready": true,
  "test_mode": true,
  "speed": 1.0,
  "awaiting": "playing",
  "state_version": 65,
  "in_game": true,
  "seed": "4VUVGLVBWKHPG",
  "seed_raw": -1950987540974881185,
  "character": "WATCHER",
  "character_source": "player",
  "ascension": 1,
  "act": 1,
  "floor": 1,
  "screen_type": "NONE",
  "room_phase": "EVENT",
  "room_type": "EventRoom",
  "room_cleared": false,
  "render_crashed": false,
  "ui": "event",
  "ui_group": "event",
  "suggest_include": [],
  "mod_version": "0.1.0",
  "language": "ZHS"
}
```

> **核对方式**：`get_state` 任取一屏，`jq -r '.meta|keys|length'`（或 `python -c "import json,sys;print(sorted(json.load(sys.stdin)['meta']))"`）→ **24 个键**（含 `game_dead`；旧构建是 23）；`character_source` 在 `ui=char_select` 那一拍应当是 `char_select`，其余界面是 `player`。

### 2.1 `meta.awaiting` 的全部取值（闭集，11 个）

`awaiting` 是「这一拍在等什么」的机器可读值。**别按目录猜，照这张表**（issue #94·B2-09：以前文档只列了 7 个，漏了 `char_select` 和 `dead`，还错写成「`ready=true` 时恒为 `playing`」）。

| `awaiting` | 什么时候 | `ready` |
|---|---|---|
| `playing` | 轮到玩家、且没有任何屏挡着 | `true`（`ui=char_select` / `ui=death` 除外） |
| `char_select` | `ui=char_select` —— 选人屏**是就绪状态**（能选人、能启程），但它不是 `playing`：还没开局，说 `playing` 是谎话（issue #49） | `true` |
| `dead` | `ui=death` —— 死亡画面同样是就绪的，但已经不是 `playing` | `true` |
| `enemy_turn` | 敌人回合 | `false` |
| `animating` | 玩家侧动画/牌在飞 | `false` |
| `screen_up` | 有屏挡着（选牌、奖励、弹窗、确认框） | `false` |
| `transition` | 换幕/淡入淡出/开局淡出，**以及「刚进战斗、意图还没滚出来」**（#125·C16-04，`combat_settling`：活着的怪还挂着引擎占位意图 `Intent.DEBUG`，手牌/能量还没发） | `false` |
| `battle_over` | 敌人已死、结算动画没完 | `false` |
| `event_text_typing` | **历史值，当前实现不会返回**：`ActionPlanner.eventTextTyping()` 恒 `false`（无选项的事件也会给 `event_continue`，不必等打字）。打字期实测是 `ready=true` + `awaiting=playing`，`event.text_typing` 只是「正文还在打」的提示，**不挡动作**——`choose_option` 这时发出去会成功 | `false` |
| `loading` | **存档正在载入**（#121·C12-01 / #122）：`continue_run` **或 `embark`** 已经交出去、run 屏还没出来。**判据 v3（别用 `CardCrawlGame.loadingSave` —— 那条 v1 已证伪：`resumeGame()` 第一帧就建好 `player`，真机日志里「存档正在载入」0 次）**：我们打点（把 `continue_run`/`embark` 交给执行器那一刻）＋ 状态层最近一次的 `ui` 仍属「开局前的屏」（`main_menu` / `char_select` / `mode_select` / 过渡桶 `other`）＋ 上界 30 s；run 屏一出现窗口即永久关闭（之后回主菜单不会再误报） | `false` |
| `unknown` | 模组确实判不出来（只在「未就绪且拿不到原因」时出现，正常局里不该看到） | `false` |

所以判读规则是：**`ready=true` ⇒ `awaiting ∈ {playing, char_select, dead}`**（不是只有 `playing`）；`ready=false` ⇒ 基本是后 8 个之一；看到 `awaiting:"loading"` 就**只轮询 `get_state`**，别重发 `continue_run`（那一下已经点过、正在跑）。**判断能不能动作只看 `ready`**，`awaiting` 只用来说明「在等什么」。

## 3. 常驻区块

`player` / `potions` / `memory` 每个界面都有，这里只放一次（样本主体取自战斗；`player.energy` 是 `null` 说明这份快照取自**非战斗帧**，见下面的 `energy_note`）。
主菜单是唯一的例外：**没有玩家对象**，所以 `player` 是 `{}`、`potions` 是 `{slot_count:0, slots:[]}` —— 这是真实值，不是抓漏了（见 `docs/samples/state-main_menu.json`）；判空时别把 `{}` 当成错误。

`player`：

```json
{
  "current_hp": 999,
  "hp": 999,
  "max_hp": 999,
  "block": 0,
  "energy": null,
  "energy_max": null,
  "energy_note": "非战斗态：能量只在战斗房里有意义（原版能量面板会保留上一战的数字，照抄会像「你还有 3 费」）；进战斗后 energy / energy_max 会回来。",
  "gold": 37,
  "stance": null,
  "orbs": [
    {
      "index": 0,
      "empty": false,
      "id": "Lightning",
      "name": "闪电",
      "passive": 3,
      "evoke": 8
    },
    {
      "index": 1,
      "empty": true,
      "id": null,
      "name": null
    },
    {
      "index": 2,
      "empty": true,
      "id": null,
      "name": null
    }
  ],
  "orb_slots": 3,
  "orbs_filled": 1,
  "orb_evoke_next": 0,
  "focus": 0,
  "potions": [
    {
      "slot": 0,
      "id": "FairyPotion",
      "name": "瓶中精灵",
      "can_use": false,
      "can_discard": true,
      "description": "当你要被杀死时，免死并回复到最大生命值的 30% ，丢弃这瓶药水。"
    },
    {
      "slot": 1,
      "id": "Explosive Potion",
      "name": "爆炸药水",
      "can_use": false,
      "can_discard": true,
      "description": "对所有敌人造成 10 点伤害。"
    },
    {
      "slot": 2,
      "id": "Block Potion",
      "name": "格挡药水",
      "can_use": false,
      "can_discard": true,
      "description": "获得 12 点 【格挡】 。",
      "keywords": [
        "格挡"
      ]
    }
  ],
  "powers": [],
  "relics": [
    {
      "id": "Cracked Core",
      "name": "破损核心",
      "text": "在每场战斗开始时， #y【生成】 #b1 个 #y【闪电】 充能球。",
      "keywords": [
        "生成",
        "闪电"
      ],
      "note": null
    },
    {
      "id": "NeowsBlessing",
      "name": "涅奥的悲恸",
      "text": "这件遗物的效果已经被用完。",
      "note": null
    },
    {
      "id": "Meat on the Bone",
      "name": "带骨肉",
      "text": "如果你在战斗结束时生命值等于或低于 #b50% ，回复 #b12 点生命。",
      "note": null
    },
    "... 共 8 项（此处只留前 3 项）"
  ]
}
```

`potions`：

```json
{
  "slot_count": 3,
  "slots": [
    {
      "slot": 0,
      "empty": false,
      "id": "FairyPotion",
      "name": "瓶中精灵",
      "can_use": false,
      "can_discard": true,
      "is_thrown": false,
      "target_required": false,
      "text": "当你要被杀死时，免死并回复到最大生命值的 #b30% ，丢弃这瓶药水。",
      "color": "FAIRY"
    },
    {
      "slot": 1,
      "empty": false,
      "id": "Explosive Potion",
      "name": "爆炸药水",
      "can_use": false,
      "can_discard": true,
      "is_thrown": true,
      "target_required": false,
      "text": "对所有敌人造成 #b10 点伤害。",
      "color": "EXPLOSIVE"
    },
    {
      "slot": 2,
      "empty": false,
      "id": "Block Potion",
      "name": "格挡药水",
      "can_use": false,
      "can_discard": true,
      "is_thrown": false,
      "target_required": false,
      "text": "获得 #b12 点 #y格挡 。",
      "color": "BLUE"
    }
  ]
}
```

`player.energy` / `energy_max` 只在**战斗房**里有数字（`currentRoom.phase == COMBAT`）；非战斗态这两个键是**显式 `null`**，并多一个 `energy_note`：「非战斗态：能量只在战斗房里有意义（原版能量面板会保留上一战的数字，照抄会像「你还有 3 费」）；进战斗后 energy / energy_max 会回来。」（#124·C15-04）**别把 `null` 当漏字段，也别自己拿上一战的数字推算当前费用**。

`memory`：

```json
{
  "context_notes": [],
  "recent_notes": [],
  "counts": {
    "general": 17,
    "monster": 18,
    "card": 4,
    "event": 7,
    "potion": 8,
    "relic": 1
  },
  "total": 55,
  "notes_omitted_this_run": 488,
  "run_history": [
    {
      "run": 38,
      "character": "IRONCLAD",
      "act": 1,
      "floor": 1,
      "result": "death",
      "killed_by": "邪教徒",
      "hp": 0,
      "deck_size": 10,
      "notes": 55,
      "finished_at": 1793627297
    },
    {
      "run": 39,
      "character": "IRONCLAD",
      "act": 1,
      "floor": 6,
      "result": "death",
      "killed_by": "乐加维林",
      "hp": 0,
      "deck_size": 10,
      "notes": 55,
      "finished_at": 1793628093
    },
    {
      "run": 40,
      "character": "IRONCLAD",
      "act": 1,
      "floor": 11,
      "result": "death",
      "killed_by": "尖刺史莱姆（小）, 尖刺史莱姆（小）, 酸液史莱姆（小）, 尖刺史莱姆（小）, 酸液史莱姆（小）",
      "hp": 0,
      "deck_size": 11,
      "notes": 55,
      "finished_at": 1793628727
    },
    "... 共 5 项（此处只留前 3 项）"
  ],
  "run_history_count": 42
}
```

## 4. `act` 的返回

`outcome` 三值：`applied`（当场生效）/ `queued`（已交给游戏但没等到稳定，下一拍生效）/ `rejected`（被拒）。

判定口径（issue #54 之后）：`act` 执行完**先等最多 10 秒**（`ACT_STABLE_TIMEOUT_MS`）看 `state_version` 动没动 —— 动了、或动作自检通过（比如屏真的关了）→ `applied`；10 秒没动 → `queued`，同时把这一版记成「在飞」并让后续 `act` 拿 `ACTION_IN_FLIGHT`。所以一次 `act` 的**最坏耗时 ≈ 10 秒**，别以为它挂了。

成功回包**直接带新的 `ui` 和 `actions[]`**，所以一次 `act` 之后通常不必再单独调 `legal_actions`。`queued` 例外：那时 `actions` 是空数组，`ui` 取**当前快照**的 `meta.ui`（与同一拍的 `get_state` 同源；只有一张快照都没有时才回占位值 `other`，#119·C10-02）。

**`applied` 回包带 `log`**（C6-01）：一句由**实测前后快照的差值** + 目标拼出来的中文短语，形如 `出牌 → 第1只(蛇花)：能量 3→2，手牌 5→4`、`结束回合：能量 0→3，回合 1→2`。**「数字差值」只覆盖一部分动作，别当成对所有动作的承诺**（issue #108·C7-11，全部对照 `ActionLog` / `HudPhrases` 词表）：

- **会去拼数字差值的只有这 9 种**：`play_card` `end_turn` `use_potion` `discard_potion` `choose_hand_cards` `confirm_hand_select` `cancel_hand_select` `take_reward` `proceed`；差值维度**只有 6 个**（能量 / 手牌 / 格挡 / HP / 回合 / 怪物 HP），而且**变了才写** —— 打完一张牌能量没动，`log` 就只剩 `出牌 → 第1只(蛇花)`。怪物 HP 从 >0 掉到 ≤0 写成 `第N只(名字) 击杀`，否则写 `第N只(名字) 5→2`。
- **`take_reward` / `proceed` 通常就是一句话**（`领取奖励` / `继续`）：它们虽在差值词表里，但金币、遗物、药水、牌组**都不在那 6 个维度里**，所以领金币/遗物那一拍基本不带数字（只有手牌/HP 真变了才带）。
- **其余动作一律只有一句话、没有数字**：`open_shop` `buy` `purge_card` `choose_option` `take_card` `skip_card` `claim_relic` `skip_relic` `open_chest` `rest` `smith` `confirm_grid` `cancel_grid` `choose_map_node` … 直接复用 HUD 那句，典型是 `看看商店` / `买了件东西` / `删掉一张牌` / `选了个选项` / `收下这张牌` / `不拿这张牌` / `收下遗物` / `打开宝箱` / `歇一会儿，回点血` / `打磨一张牌` / `就选这些` / `再想想，重选` / `走向 第3行 · 精英（左2）`（HUD 的兜底句 `调整了下动作` 不会出现在 `log` 里）。
- 两个词表都不认识时写 `<type> 已执行`（如 `choose_character 已执行`）；生成过程抛异常才是 `已执行 <type>`。
- **`log` 不是账单**：金币变化永远不在差值里（花了多少、还剩多少看 `get_state` 的 `player.gold`），遗物/药水/牌组同理。
- **只有 `applied` 有 `log`**：`queued` / `rejected` 都没有（别对它们读 `log`）。

**`applied` 也可能是「已提交、世界还在动」**（#123·C14-01/02/03）：`continue_run` / `embark` / 放弃类（`abandon_run` / `abandon_mid_run` / `confirm_abandon`）这类**提交型**动作，回包是 `ok:true, outcome:"applied", settled:false`（**目前只有这些回包带 `settled`，恒为 `false`；其它回包没有这个键**）。含义不是「没点上」，而是「点击已经交出去、世界正在过渡」：`ui` 是缓存快照、`ready:false`、`actions:[]`、`awaiting` 是 `loading` 或 `transition`，`log` 写明「已提交…不要重发；轮询 get_state 直到…」。**别重发、别拿 `settled:false` 当失败**；它和 `queued` 的区别是：`queued` 还没落地、会锁 3 秒 `ACTION_IN_FLIGHT`，而 `settled:false` 是已落地、由载入/退出窗口描述等待（见 §8.2）。**回包耗时**：这类动作实测 **0.55~0.70 s**（一次主线程执行 + 一帧过渡；**不是 ~0.1 s 的「立刻回」**），所以验收口径写成「**≤1 s 且 `outcome` 不是 `queued`**」，而不是「毫秒级」。

**`rejected` 回包的固定形状**（#121·C12-02）：`{ok:false, outcome:"rejected", state_version, error:{code,message}, action:{type,payload}, actions:[…], ui, ready}`。**每一次拒绝都有 `ui` / `ready` / `actions`**，与 `ILLEGAL_ACTION` 那版一致 —— 默认 `ready:false`（刚被拒，此刻别发动作）、`ui` 取缓存快照、`actions:[]`；能排到主线程的拒绝（`STALE_VERSION`／执行器的 `ILLEGAL_ACTION` 等）会用**新鲜计划**覆盖成真值（`ready` 可能是 `true`）。

**过渡窗口里（载入/放弃）拒绝还多两把钥匙**（#124·C15-01）：`blocked_ui` + `awaiting`。`ui` 不再是「缓存快照」而是**读接口同一拍报的值**（`StateBuilder.reportedUi()`，过渡期就是 `other`），`blocked_ui` 同值、`awaiting` 是 `loading`/`transition` —— 于是 `get_state` / `legal_actions` / `act` 三个入口对「现在是哪一屏、在等什么」给的是同一个答案，不会再出现「读说 other、act 说 combat」。几个附件：

- `STALE_VERSION` / `ILLEGAL_ACTION` 时 `actions[]` 是**现读的**新动作列表（省一次 `legal_actions`）。
- **队列类失败**（`act` 没进主线程，issue #108·C7-02）：**单飞队列 BUSY → `ACTION_IN_FLIGHT`**（队列里已经有一条动作在排队，这条没进去）；**等满 `COMMAND_TIMEOUT_MS` 仍未执行 → `NOT_READY`**（`queue_status:"TIMEOUT"`）。两种码都额外带三个诊断字段（issue #97·C3-06）：
  - `waited_ms` —— 从发出到被打回实际等了多久（`BUSY` 是毫秒级，`TIMEOUT` 才是等满上限）；
  - `queue_status` —— `BUSY`（这条**没进去** → 回 `ACTION_IN_FLIGHT`）/ `TIMEOUT`（等满 `COMMAND_TIMEOUT_MS` 仍没执行 → 回 `NOT_READY`）；只剩这两个取值（代码里还有个防御性的 `UNKNOWN` 分支，这条路走不到）；
  - `version_advanced` —— 等待期间 `state_version` 有没有前进：`true` = **另一个动作已经生效，这条不要再发**（先 `get_state`）；`false` = 版本没动，屏上可能还是那条动作，可重发（同 `action_id`）。
  - 这两种都是「没进主线程」，所以**都没有 `retry_after_ms`** —— 那个字段只在 3 秒墙钟护栏那条 `ACTION_IN_FLIGHT` 里出现（见 §8.3）。
  - **这三个在 `error.*` 里也有一份**（#120·C11-01）：同一份值镜像进 `error.queue_status` / `error.waited_ms` / `error.version_advanced`，顶层副本保留（老客户端不破）。3 秒墙钟护栏那条 `ACTION_IN_FLIGHT` 的 `retry_after_ms` 同样两处都有（`error.retry_after_ms` + 顶层 `retry_after_ms`）。**查 `a.error.queue_status` 和查顶层都拿得到**；只写一处的那版已经修掉了。

`play_card`：`act-ok-play_card.json`（**旧样本：抓于 `log` 上线之前**，下面这段 JSON 没有 `log`。新 build 的 `applied` 回包在顶层多一个 `log`（与 `state_version` 同级，按 key 取）。`docs/samples/act-ok-*.json` 全部**待重抓** —— 重抓要真发 `act`，没抓之前别把样本当字段全集）

```json
{
  "ok": true,
  "outcome": "applied",
  "state_version": 46,
  "error": null,
  "action": {
    "type": "play_card",
    "payload": {
      "uuid": "2c0a0ca2-b470-4381-a132-0793f2af01d7",
      "target_index": 0
    }
  },
  "ui": "combat",
  "ready": true,
  "actions": [
    {
      "id": "a1",
      "type": "play_card",
      "label": "出牌 爪击 → 第1只(蛇花)",
      "payload": {
        "uuid": "0a19a2f2-05d7-4ed2-962d-b1d6d3c5b3e3",
        "target_index": 0
      }
    },
    {
      "id": "a2",
      "type": "play_card",
      "label": "出牌 防御",
      "payload": {
        "uuid": "57350391-d9e5-4840-b8d4-9e91b6f833a0"
      }
    },
    {
      "id": "a3",
      "type": "play_card",
      "label": "出牌 防御",
      "payload": {
        "uuid": "2591e7c4-05a1-475d-b1c6-33b6763f5f07"
      }
    },
    "... 共 10 项（此处只留前 3 项）"
  ]
}
```

`choose_map_node`：`act-ok-choose_map_node.json`（**旧样本，同样缺 `log`；待重抓**）

```json
{
  "ok": true,
  "outcome": "applied",
  "state_version": 43,
  "error": null,
  "action": {
    "type": "choose_map_node",
    "payload": {
      "x": 1,
      "y": 3,
      "room": "怪物",
      "column": 1,
      "column_label": "左2",
      "row": 4
    }
  },
  "ui": "combat",
  "ready": true,
  "actions": [
    {
      "id": "a1",
      "type": "discard_potion",
      "label": "丢弃药水 瓶中精灵",
      "payload": {
        "slot": 0
      }
    },
    {
      "id": "a2",
      "type": "use_potion",
      "label": "用药水 爆炸药水",
      "payload": {
        "slot": 1
      }
    },
    {
      "id": "a3",
      "type": "discard_potion",
      "label": "丢弃药水 爆炸药水",
      "payload": {
        "slot": 1
      }
    },
    "... 共 5 项（此处只留前 3 项）"
  ]
}
```

本轮抓到回包的动作类型：`cancel_grid`、`choose_map_node`、`choose_option`、`end_turn`、`leave`、`open_chest`、`open_shop`、`play_card`、`proceed`、`smith`、`take_reward`、`use_potion`。

## 5. `legal_actions` 的返回

```json
{
  "ready": true,
  "state_version": 36,
  "screen_type": "NONE",
  "ui": "shop_room",
  "awaiting": "playing",
  "actions": [
    {
      "id": "a1",
      "type": "open_shop",
      "label": "逛商店",
      "payload": {}
    },
    {
      "id": "a2",
      "type": "leave",
      "label": "离开商店",
      "payload": {}
    }
  ]
}
```

| 字段 | 说明 |
|---|---|
| `actions[].id` | 本次调用内的序号，跨调用会变 |
| `actions[].type` | 动作类型，`act` 的 `type` 必须完全一致 |
| `actions[].label` | 中文短语，给 HUD/日志看 |
| `actions[].payload` | 原样回传给 `act`；无参数时 `{}` |
| `blocked_ui` | **只在 `actions:[]` 时出现**：这一帧没有可执行动作，值是**这一拍的 `ui`**（与 `meta.ui` 同源），用来区分「屏上真没有动作」和「你没读到动作」。同一拍一定带 `ready:false` + `error.code:"NOT_READY"`（见下面那段）。**`get_state` 的未就绪回包在顶层也报同一个字段**（#121·C12-04，两处同地址同值） |
| `hints` | 只在「有事要说」时出现：未就绪 / 上次 `act` 被拒 / `ui=char_select` / 本回包投递了新便签正文（`note_history`，只是提醒便签是历史，**不是异常**；`"seen"` 帧没有）**或** hints 自带异常标记（`map_no_reachable`/`suggest_note`/`unknown_ui`/`event_missing`）（逐字段见 §9；`legal_actions` 的那份没有 `beats`/`ms_since_*`） |

**`actions: []` 永远不是「空表就完事」**（issue #120·C11-03）：那一刻回包固定是 `ready:false` + `blocked_ui`（= 这一拍 `ui`）+ `error.code:"NOT_READY"`，`message` 按门闸是否就绪分两种：

- 门闸本来就没就绪 →「ui=X 游戏还没静止（ready=false），这一帧的动作表不可信，稍后重试 legal_actions。」
- 门闸说就绪、但这一帧屏上确实没有可执行动作（正在切屏 / 动作刚起播）→「ui=X 这一帧还没有可执行动作：屏正在切换（或动作刚开始播放），不是「没有出路」，约 1 秒后重试 legal_actions 即可（ready 因此是 false）。」

**`get_state` 同一条规则**：快照的 `meta.ready:true` 但动作表为空时，它会把 `meta.ready` 与顶层 `ready` 一起压成 `false`、加 `meta.blocked_ui`、`awaiting` 从 `playing` 改成 `screen_up`，**`ok` 也变 `false`**、`error` 给同一句 —— 别再拿「`ok:true` + 空表」当正常帧。

## 6. 各界面真实返回

每节：「本界面特有的区块」+「这一拍能做的动作」。`meta` 与 `player/potions/memory` 见前两节，不重复。

### 6.1 ui 词表

`meta.ui` 是**派生值**，判定界面只看它（不要看 `meta.screen_type`）。全量取值来自
`spirelink/state/UiNames.java` + `StateBuilder.deriveUi()`，下表是**完整清单**——不在表里的值不会出现，
出现 `unknown:<SCREEN>` 说明模组还没映射那个画面。

| `ui` | 什么时候出现 | 专属区块 | 样本（§10） |
|---|---|---|---|
| `main_menu` | 主菜单及它的大部分子屏（设置/图鉴/统计/排行榜…都归这里） | 无 | `state-main_menu.json` `actions-main_menu.json` |
| `mode_select` | 主菜单的「模式面板」（`MainMenuScreen.CurScreen=PANEL_MENU`） | 无 | **无样本** |
| `char_select` | 选人界面 | `char_select` | `state-char_select.json` `actions-char_select.json` |
| `tutorial_tip` | 教程弹窗（`CurrentScreen=FTUE`；**人类**自己点顶栏「看牌组/弃牌/消耗/牌堆」也落到这里 —— MCP 自己开的 peek **不算**，那 4 秒 `ui` 保持原屏，见 §3 #115） | 无 | **无样本** |
| `map` | 地图（整幕都能选点） | `map` | `state-map.json` `actions-map.json` |
| `combat` | 战斗房且 `room.phase == COMBAT` | `combat` | `state-combat.json` `actions-combat.json`（2026-11-03 重抓，文件名与内容一致） |
| `combat_reward` | 战斗后的奖励清单（`CurrentScreen=COMBAT_REWARD`） | `rewards` | `state-combat_reward.json` `actions-combat_reward.json` |
| `card_reward` | 选卡（`CARD_REWARD`） | `card_reward` | **无样本**（结构见 §7 的 `card_reward` 一节） |
| `boss_reward` | Boss 遗物三选一（`BOSS_REWARD`） | `boss_reward` | **无样本** |
| `grid` | 选牌网格（锻牌/删牌/变换/事件给牌） | `card_reward` 同构区块 | `state-grid.json` `actions-grid.json` |
| `hand_select` | 手牌选择（弃牌/消耗/置顶） | `card_reward` 同构区块 | **无样本** |
| `choose_one` | 三选一（发现、夜魇等） | 无 | **无样本** |
| `shop` | 商店货架屏（`SHOP`） | `shop` | `state-shop.json` `actions-shop.json` |
| `shop_room` | 站在商店房但货架没开（`room_phase=COMPLETE`）——`ui_group` 也是 `shop` | 无 | `state-shop_room.json` `actions-shop_room.json` |
| `rest` | 营火（`RestRoom`） | 无 | `state-rest.json` `actions-rest.json` |
| `event` | 事件房（`EventRoom`） | `event` | `state-event.json` `actions-event.json` |
| `neow` | 开局祝福（`NeowRoom`） | `event` + `neow` | `state-neow.json` `actions-neow.json` |
| `treasure` | 宝箱房（含第 1/2 幕的 `TreasureRoomBoss`） | 无 | `state-treasure.json` `actions-treasure.json` |
| `boss_cleared` | Boss 已死、地图还没开（`room_type=MonsterRoomBoss` 且 `room_phase=COMPLETE`，issue #42）；此时 `meta.room_cleared=true` | 无 | **无样本** |
| `victory` | 通关结算（`VictoryRoom`，或 `CurrentScreen=VICTORY` 的那一拍 `screen_type` 还是 `NONE`） | `postmortem` | **无样本** |
| `death` | 死亡结算（`CurrentScreen=DEATH`） | `postmortem` | **无样本** |
| `transform` | 变换牌屏（`TRANSFORM`）—— 其实是 `GridCardSelectScreen`（`forTransform`），是 `ui=grid` 的**别名界面** | 无（**不挂** `card_reward`；`hints.grid_*` 也只在 `ui=grid` 时才写） | **无样本**；动作集合 `choose_grid_cards` / `confirm_grid` / `proceed`，**`cancel_grid` 只在原版真的给了 `CancelButton` 时才有**（变形网格是 `canCancel=false`，所以通常没有；见 issue #83 / #96） |
| `door_unlock` | 门锁解锁动画（`DOOR_UNLOCK`） | 无 | **无样本** |
| `credits` | 制作名单（`CREDITS`） | 无 | **无样本** |
| `view` | 纯查看屏（`NO_INTERACT` / `UNLOCK` / `SETTINGS` / `INPUT_SETTINGS`） | 无 | **无样本** |
| `idle` | 房间打完了、屏也关了，且不是 Boss 清场那一拍 | 无 | — |
| `other` | **只在一张快照都没有时**才出现的占位值（例如服务刚起、还没拍过 `get_state`）：`act` 的 `queued` 回包和 `legal_actions` 的 `NOT_READY` 回包都会带它。有快照时这两个回包都报**同一张快照的 `ui`**（#119·C10-02），不再是占位值 | 无 | — |
| `unknown:<SCREEN>` | `screen_type` 有值但模组没映射（不变式 I2），例如 `unknown:SETTINGS` | 无 | — |

> **`ui=char_select` 的动作有前置条件**（issue #108·C7-09）：**还没高亮任何角色时只有 `choose_character`（每个未锁角色一条）+ `back`，没有 `embark`、也没有 `choose_ascension`**；**高亮之后** `embark` 才出现（并排在 `actions[0]`），升天档位也才会出现（而且只列**已解锁、且不等于当前等级**的档）。别在未选人时等 `embark`，也别以为升天档位常驻（顺序与字段见 §7 `char_select`）。

> **`ui=neow` 的 `event.text` 恒为空串**（issue #107）：涅奥的台词走 `InfiniteSpeechBubble`（屏幕上的对话气泡），MCP 不读它；`NeowEvent` 又不是图片事件、从不写共享的 `GenericEventDialog` 那一页。所以 **`event.text` 永远是 `""`** —— 读不到就给空串，**绝不会把上一个事件/上一局别人的台词塞进来**（旧版本会，QA 抓到过 `BigFish` 的「你吃下了香蕉…」）。想知道涅奥在说什么，看 `neow.options[].label`（`选项：<对白原文>`）。

`ui_group` 只做一处归一：`ui=shop_room` 的 `ui_group` 是 `shop`，其余界面 `ui_group == ui`。

> **核对方式**：`curl` 取一屏 `get_state` 看 `meta.ui`/`meta.ui_group`；想知道某个新画面有没有映射，看 `legal_actions` 的 `ui` 是不是 `unknown:…`（同时 `get_state` 的 debug 日志里有「未映射的 screen_type」一行）。
> 词表与 `UiNames.java` 的整表对照：`grep -n 'SCREENS.put\|MENUS.put' spirelink/src/main/java/spirelink/state/UiNames.java`。

### `ui = main_menu`（主菜单）

这个界面没有专属区块（只有 `meta` + 常驻区块）。

> **`continue_run` 点下去之后会先进「载入窗口」**（#121·C12-01 / #122·C12-01）：从点击到 run 真正出现在屏幕上（约 1~5 s，上界 30 s），这一屏报 `meta.ready:false` + `meta.awaiting:"loading"`，`get_state` / `legal_actions` / `act` 全回 `NOT_READY` —— 三个入口的 `message` 都含「存档正在载入（continue_run 已点，战斗/地图还没出来）…不要重发 continue_run；轮询 get_state 直到 ready:true」，`actions` 是 `[]`，`get_state` / `legal_actions` 顶层带 `blocked_ui`（= 那一拍的 `ui`，载入中途可能是 `other`）；`act` 的拒绝按 #121·C12-02 带 `ui`/`ready`/`actions`，**过渡窗口里还带同值的 `blocked_ui` + `awaiting`**（#124·C15-01）。**判据见 §2.1 的 `loading` 行（v3：我们自己的打点 + 状态层 `ui` + 30 s 上界，不看 `loadingSave`）**。**别把「主菜单还画着、按钮还在」当成「没点动」**：那一拍不会再把 `continue_run` 广告成合法动作，只轮询到 `ready:true` 就行。**`embark` 同理**（选人屏 → 淡出 → 第一幕也是同一种载入窗口）。

`meta`（这个界面下的真实值）：

```json
{
  "ready": true,
  "test_mode": false,
  "speed": 1,
  "awaiting": "playing",
  "state_version": 1,
  "in_game": false,
  "seed": null,
  "seed_raw": null,
  "character": null,
  "ascension": 0,
  "act": 0,
  "floor": 0,
  "screen_type": "NONE",
  "room_phase": null,
  "room_type": null,
  "render_crashed": false,
  "ui": "main_menu",
  "ui_group": "main_menu",
  "suggest_include": [],
  "mod_version": "0.1.0",
  "language": "ZHS"
}
```

这一拍能做的动作 —— **按钮组看「存档在不在」，两套互斥**（issue #67）：

**有存档**（`docs/samples/actions-main_menu.json` 就是这一拍）：

- `abandon_run` — 放弃存档（回到新局）
- `continue_run` — 继续上次的一局

**无存档**（刚放弃过 / 从没存过）：这一拍**只给 `open_mode_select`**，**不会**出现 `continue_run`；
`open_mode_select` 点下去进 `ui=mode_select`（`MainMenuScreen.CurScreen=PANEL_MENU`），走「标准/每日/自定」→ 选人。

**弹窗态**：点了 `abandon_run` 会弹确认框，这一拍换成 `confirm_abandon` / `cancel_abandon`（先处理弹窗，别的动作都没有）。

有存档那一拍的真实 `legal_actions`（= `docs/samples/actions-main_menu.json`）：

```json
{
  "ready": true,
  "state_version": 1,
  "screen_type": "NONE",
  "ui": "main_menu",
  "awaiting": "playing",
  "actions": [
    {"id": "a1", "type": "abandon_run", "label": "放弃存档（回到新局）", "payload": {}},
    {"id": "a2", "type": "continue_run", "label": "继续上次的一局", "payload": {}}
  ]
}
```

无存档那一拍的形状（2026-10-31 零点击长跑转录，见 `docs/P1-验收.md` 的 step 01→02）：

```text
[step 01] ui=main_menu  v=2  actions=2  act confirm_abandon  -> applied   # 有存档 + 放弃确认弹窗（2 = confirm/cancel）
[step 02] ui=main_menu  v=3  actions=1  act open_mode_select -> applied   # 存档已删 → 只剩 open_mode_select
```

> **核对方式**：在主菜单读 `legal_actions` —— 有存档时是 `abandon_run` + `continue_run`；删档/放弃之后重读就只剩 `open_mode_select` 一条。
> 上面这段 `meta` 抓于旧版模组，少 `character_source` / `room_cleared` 两个后加的键（主菜单里分别是 `player` 与 `false`，字段定义见 §2）；样本还没重抓，别把这段当权威。

### `ui = map`（地图）

`map`：

```json
{
  "boss_available": false,
  "boss_present": true,
  "boss": {
    "x": -1,
    "y": 15,
    "room": "MonsterRoomBoss"
  },
  "act": 2,
  "floor": 18,
  "rows": 15,
  "current": {
    "x": 0,
    "y": 0
  },
  "from_row_start": 0,
  "from_row": [
    [
      {
        "x": 0,
        "y": 0,
        "symbol": "M",
        "room": "monster",
        "taken": true,
        "edges": [
          [
            0,
            1
          ]
        ]
      },
      {
        "x": 1,
        "y": 0,
        "symbol": "M",
        "room": "monster",
        "edges": [
          [
            2,
            1
          ]
        ]
      },
      {
        "x": 2,
        "y": 0,
        "symbol": "M",
        "room": "monster",
        "edges": []
      },
      "... 共 7 项（此处只留前 3 项）"
    ],
    [
      {
        "x": 0,
        "y": 1,
        "symbol": "M",
        "room": "monster",
        "edges": [
          [
            0,
            2
          ]
        ]
      },
      {
        "x": 1,
        "y": 1,
        "symbol": "?",
        "room": "unknown",
        "edges": []
      },
      {
        "x": 2,
        "y": 1,
        "symbol": "M",
        "room": "monster",
        "edges": [
          [
            2,
            2
          ],
          [
            3,
            2
          ]
        ]
      },
      "... 共 7 项（此处只留前 3 项）"
    ],
    [
      {
        "x": 0,
        "y": 2,
        "symbol": "M",
        "room": "monster",
        "edges": [
          [
            1,
            3
          ]
        ]
      },
      {
        "x": 1,
        "y": 2,
        "symbol": "?",
        "room": "unknown",
        "edges": []
      },
      {
        "x": 2,
        "y": 2,
        "symbol": "M",
        "room": "monster",
        "edges": [
          [
            2,
            3
          ]
        ]
      },
      "... 共 7 项（此处只留前 3 项）"
    ],
    "... 共 15 项（此处只留前 3 项）"
  ],
  "reachable": [
    {
      "x": 0,
      "y": 1
    }
  ]
}
```

`meta`（这个界面下的真实值）：

```json
{
  "ready": true,
  "test_mode": false,
  "speed": 1,
  "awaiting": "playing",
  "state_version": 33,
  "in_game": true,
  "seed": "39350NGPGEMN",
  "seed_raw": 314722147750928300,
  "character": "DEFECT",
  "ascension": 0,
  "act": 2,
  "floor": 18,
  "screen_type": "MAP",
  "room_phase": "NONE",
  "room_type": "MapRoom",
  "render_crashed": false,
  "ui": "map",
  "ui_group": "map",
  "suggest_include": [],
  "mod_version": "0.1.0",
  "language": "ZHS"
}
```

这一拍能做的动作：

- `choose_map_node` — 前往 第2行 · 怪物（左3）　payload `{"x":0,"y":1,"room":"怪物","column":0,"column_label":"左3","row":2}`
- `abandon_mid_run` — 放弃本局（回主菜单）

**`choose_map_node` 的 payload 不止 `{x,y}`**（issue #108·C7-10；文档以前只写 `{x,y}`，实测回包带 6 个字段）：

| 字段 | 含义 |
|---|---|
| `x` / `y` | 地图坐标（`x` = 列号，0 起；`y` = 绝对楼层行，0 起）。**执行器只认这两个**，其余字段只读 |
| `room` | 中文房间类型，按节点的**真实房间类**翻译：`怪物` / `精英` / `事件` / `休息` / `商店` / `宝箱` / `Boss`（房间类取不到时是 `未知`）。注意 `map[]` 节点的 `room` 是**另一套短代码**（`monster`/`unknown`/`event`…），两边的值别混用 |
| `column` | 列号，**等于 `x`** |
| `column_label` | 给人看的位置：`左3` / `中` / `右1`（由 `x` 与整行宽度算出） |
| `row` | 1 起的行号，**等于 `y + 1`**（`label` 里的「第2行」就是它） |

所以**别自己拼坐标**：原样回传 `legal_actions` 给的 payload 即可（`room`/`column_label`/`row` 变了不影响执行，但改 `x`/`y` 会 `ILLEGAL_ACTION「节点不可达」`）。Boss 那一拍没有这些附加字段，payload 就是 `{"x":-1,"y":<地图行数>}`。

**payload 先校验、再判房间（issue #110，别按老文档记）**：

- `x`/`y` 必须是**严格整型**：字符串 `"a"`、小数、或缺 `x`/`y` → `BAD_REQUEST`（`message` 是「缺少 x/y（payload 要写 {"x": <列>, "y": <行>}…）」或「payload.x 必须是整数（收到 … 不是 JSON 数字；字符串 "0" 这种请写成 0）」）。以前 `x` 被读成 `-1`（Boss 哨兵值）后回 `NOT_READY「房间还没结束（phase=…），先 proceed 离开」`——**那个回答已经没有了**：参数问题就是参数问题，不会先被房间阶段判定抢走。
- 负数只有 **`x:-1` 是 Boss 入口**：`x < -1` → `BAD_REQUEST`「payload.x=-5 不合法：地图列从 0 起；Boss 入口才是负数…」；`y < 0` → `BAD_REQUEST`「payload.y=… 不合法：地图行从 0 起…」；`x:-1` 但 `y` 不是行数 → `BAD_REQUEST`「payload.y=… 不是 Boss 行…」。
- 合法但**这一拍没广告**的 payload（例如房间还没结束时点 Boss）仍按**参数级**拒，而且提示里只列**这一拍真能执行**的动作（走 `MapExitPolicy.executableHint`）：有可达节点时它只会说「先 `choose_map_node{x,y}` 走一个能走的节点」，**不会再叫你 `proceed`**（地图有节点时根本不广告 `proceed`）。（「参数级」说的是**不会报 planner/executor 不一致 WARN**，错误码本身仍可能是 `NOT_READY` —— 比如「Boss 图标现在点不了」本质是状态；判断依据是 `message` + 这一拍的动作集合，不是只看 `error.code`。）
- 只有纯状态问题才走类型级：**没有房间 / 地图没打开** → `NOT_READY`；**地图屏还没准备好** → `ILLEGAL_ACTION「地图界面还没准备好」`。参数问题（含合法但这一拍没广告的 payload）一律参数级，不会冒充状态错误。
- 可达节点里没有 `(x,y)` → `ILLEGAL_ACTION「节点 (x,y) 现在不可达；现在能走的节点: (3,2), (5,4)」`，把这一拍能走的坐标一并给你。

**地图上的动作由 `MapExitPolicy` 决定（issue #100；别再按「查看态另有 `proceed`」记）**：

- **有可达节点**：每条可达节点一条 `choose_map_node`（外加常驻的 `abandon_mid_run`），**不广告 `proceed`**；
- **末行清完、只剩 Boss 图标**：只给 `choose_map_node {x:-1,y:<地图行数>}`（label「前往 Boss」）；
- **无节点、无 Boss，但当前房间还没结束**：给 `proceed`（label「关闭地图」，执行器真会 `dungeonMapScreen.close()`）；
- **死角**（无节点、无 Boss、房间已结束）：**只给 `abandon_mid_run`** —— 这一拍发 `proceed` 一定 `ILLEGAL_ACTION`（#100 的软锁就是它以前假借 `proceed` 永远 queued 造成的）。

**放弃之后有 0.8~1.6 s 的「离开过渡期」**（#123·S2，`RunExitWindow`，上界 10 s）：`abandon_mid_run` / `abandon_run` / `confirm_abandon` 的 `applied + settled:false` 之后，旧地牢还在内存里（`mode` 仍是 GAMEPLAY），但那一屏**已经不作数** —— 实时帧 `ui:"other"`、`ready:false`、`blocked_ui:"other"`、`awaiting:"transition"`（`act` 的拒绝也带这后两个键，且 `ui` 取读接口同一拍的实时值；#124·C15-01），三个入口都回 `NOT_READY` +「正在离开本局（放弃/回主菜单过渡中）…旧地牢的 ui 与动作表已经不作数…轮询 get_state 直到 ui=main_menu 且 ready:true」。**别照上一帧的地图/战斗动作表动手**（QA 就是这样拿到 `ILLEGAL_ACTION: ui=main_menu 下不能执行 choose_map_node` 的）。

**`abandon_mid_run` 只在地图与战斗两处广告**（#122·C13-03；战斗里固定是最后一条）：`ui=idle`、奖励屏、事件、篝火、宝箱都**不会**出现它，别去找；地图上的 label 是「放弃本局（回主菜单）」，战斗上的 label 写明「删存档」。

取消地图的动作类型是 **`proceed`，没有 `close_map` 这个 `type`**。

### `ui = combat`（战斗）

专属区块就是 `combat`：手牌、怪物、牌堆。

> **刚进战斗的头几帧不是决策点**（#125·C16-04，`combat_settling`）：怪物还挂着引擎占位意图（`monsters[].intent.id == "DEBUG"`、`unknown:true`），手牌是空的、能量面板还没填。这一拍 `ready:false`、`awaiting:"transition"`、`blocked_ui:"combat"`，`legal_actions` 是 `actions:[]` +「ui=combat 游戏还没静止…」；**等 200~500 ms 再读**，意图滚出来后才 `ready:true`。安全上界 **4 秒**（`StableGate.SETTLING_MAX_MS`）：再多就按常规规则判，不会因为模组怪一直 DEBUG 而永远卡住。

```json
{
  "combat": {
    "round": 1,
    "turn": 1,
    "hand": [
      {
        "uuid": "a707f137-3160-40d8-818f-5a9fc22c662d",
        "id": "Strike_R",
        "name": "打击",
        "text": "造成 6 点伤害。",
        "type": "ATTACK",
        "rarity": "BASIC",
        "cost": 1,
        "upgrades": 0,
        "ethereal": false,
        "exhausts": false,
        "retain": false,
        "target": "ENEMY",
        "has_target": true,
        "is_playable": true,
        "damage": 6,
        "block": 0,
        "magic_number": -1,
        "needs_inspect": false,
        "index": 0,
        "note": null
      }
    ],
    "energy": 3,
    "energy_max": 3,
    "playable_cards": 3,
    "piles": {
      "draw": { "count": 5, "cards": [] },
      "discard": { "count": 0, "cards": [] },
      "exhaust": { "count": 0, "cards": [] },
      "limbo": { "count": 0, "cards": [] }
    },
    "piles_summary": { "draw": 5, "discard": 0, "exhaust": 0 },
    "monsters": [
      {
        "index": 0,
        "id": "FuzzyLouseNormal",
        "name": "虱虫",
        "current_hp": 1,
        "hp": 1,
        "max_hp": 11,
        "block": 0,
        "is_gone": false,
        "half_dead": false,
        "intent": { "id": "ATTACK", "has_damage": true, "damage": 7, "hits": 1, "total_damage": 7, "base_damage": 7 },
        "powers": [
          {
            "id": "Curl Up",
            "name": "蜷身",
            "amount": 7,
            "stack": 7,
            "kind": "buff",
            "term": "蜷身",
            "text": "当受到攻击伤害时，蜷起身子并获得 7 点【格挡】。（每场战斗一次）",
            "keywords": ["格挡"]
          }
        ],
        "note": null
      },
      {
        "index": 1,
        "id": "FuzzyLouseDefensive",
        "name": "虱虫",
        "current_hp": 1,
        "hp": 1,
        "max_hp": 13,
        "block": 0,
        "is_gone": false,
        "half_dead": false,
        "intent": { "id": "DEBUFF", "has_damage": false },
        "powers": [ "… 同上，amount 3" ],
        "note": null
      }
    ],
    "enemies": [ "… 与 monsters 是同一个数组（旧字段，等价）" ]
  }
}
```

战斗区块的六条要点：

- `index` 就是 `target_index` 的下标；**已经死掉/逃跑的怪仍留在数组里**（`is_gone: true`）并且**没有 `intent`** ——
  看到没有 `intent` 的怪，就是它不会再打你了，别自己补一个伤害。
- 只有 `ATTACK*` 意图带 `damage`/`hits`/`total_damage`（只填了 base 值时会带 `damage_is_base: true`）；
  `DEFEND`/`DEBUFF` 这类只带 `id` 与 `has_damage:false`。`intent.id == "DEBUG"` 时带 `unknown:true`（这一帧引擎还没算出来）。
- `piles.*.cards` 默认是**空数组**（只给 `count`）；要看牌面得显式 `include:["draw_pile"]` 等（见 §2，会真的在屏幕上打开那份牌表）。
- `round`/`turn` 同值、`monsters`/`enemies` 同数组：**只加不改**，用哪个都行。
- **`energy` / `energy_max` / `playable_cards`**（#123·C14-05）：`energy` 是当前能量（`EnergyPanel.getCurrentEnergy()`），`energy_max` 是本回合上限，`playable_cards` 是手牌里**现在真能打出**的张数（逐张 `hasEnoughEnergy()`）。以前能量只在 `player` 区块里，`include:["combat"]` 的调用方没有数字可对「为什么 `play_card` 不见了」—— 现在同一对数在 `combat` 里也有。
- **`play_blocked` 恒定存在**（#124·C15-05）：`playable_cards == 0` 且手里有牌时是一句话（原文：「能量不足（energy=0/3）：手牌 3 张这回合都打不出，只能 end_turn 或先用药水；play_card 因此不在 legal_actions 里，不是接口出错。」），**没有阻塞时是 JSON `null`**（不是省略这个键，也不是 `""`）。「键在 = 字段存在」，「值为 `null` = 这回合没有阻塞」，别把两者混起来（QA 见过 `play_blocked: ""` 配 `playable_cards: 10` 的歧义）。**看到字符串别以为接口坏了**：那回合只有 `end_turn`（或用药水）。

`meta`（这一拍的真实值）：

```json
{
  "ready": true,
  "test_mode": true,
  "speed": 1.0,
  "awaiting": "playing",
  "state_version": 151,
  "in_game": true,
  "seed": "4ZB0YVRHMY5A8",
  "seed_raw": -1616770379121713383,
  "character": "IRONCLAD",
  "character_source": "player",
  "ascension": 0,
  "act": 1,
  "floor": 0,
  "screen_type": "NONE",
  "room_phase": "COMBAT",
  "room_type": "MonsterRoom",
  "room_cleared": false,
  "render_crashed": false,
  "ui": "combat",
  "ui_group": "combat",
  "suggest_include": [],
  "mod_version": "0.1.0",
  "language": "ZHS"
}
```

这一拍能做的动作（18 条 = 出牌 16 条 + 结束回合 1 条 + **放弃本局 1 条**；**带目标的牌按每个目标各出一条**）：

- `play_card` — 出牌 打击 → 第1只(虱虫)　payload `{"uuid":"a707f137-3160-40d8-818f-5a9fc22c662d","target_index":0}`
- `play_card` — 出牌 打击 → 第2只(虱虫)　payload `{"uuid":"a707f137-…","target_index":1}`
- `play_card` — 出牌 防御　payload `{"uuid":"d36949a1-4b2a-451e-a4a9-b9918e6708e6"}`
- …（同一张牌换个目标就是另一条）
- `end_turn` — 结束回合　payload `{}`
- `abandon_mid_run` — 放弃本局（删存档并回主菜单）　payload `{}` —— **永远排在最后一条**（#122·C13-03：战斗是这局真可能输死的地方，失控/卡死时的逃生口；放最后是为了「永远取 `actions[0]`」的 naive harness 不会一脚踩进去，标签也写明代价）。**只在能操作的帧出现**：`ready:true` 的那一拍才有动作表；敌人回合/动画中 `ready:false`、`actions:[]`，看不到它。

`play_card` 只认 `uuid`（不认下标）；同一张牌每个目标一条，随便挑一条发给 `act` 即可。

> **核对方式**：`curl` 调 `get_state`（战斗里不用带 include）看 `combat` 区块；`legal_actions` 的条数 = 可打的牌 × 可选目标数 + 2（`end_turn` + `abandon_mid_run`），最后一条一定是 `abandon_mid_run`。
> 注意：`docs/samples/state-combat.json` 与 `actions-combat.json` 是**旧样本，内容其实是 `shop_room`**（见 §10 的注），
> 战斗样式以上面这一拍为准（2026-11-03 现场抓，`state_version=151`）。

### `ui = combat_reward`（战斗奖励清单）

`rewards`：

```json
{
  "count": 1,
  "items": [
    {
      "index": 0,
      "type": "RELIC",
      "text": "玩具扑翼飞机",
      "relic": {
        "id": "Toy Ornithopter",
        "name": "玩具扑翼飞机"
      }
    }
  ]
}
```

`rewards.items[].index` 是**这一拍列表里的下标**，不是稳定 id：领走一条后列表会重排（领走 `index:0`，原来的 `index:1` 就变成 `0`），所以**每领一条都要重新读** `get_state` / `legal_actions` 拿新 index，别复用上一拍的。（对照 `shop`：那里的 `index` 是**稳定槽位号**，买走一件货架不重排。）

**奖励屏的行可能比「房间奖励」多一条**（#120·C11-02 / C11-06）：屏幕列表来自 `CombatRewardScreen.setupItemReward()` —— 先 `new ArrayList<>(room.rewards)`，然后**只在普通房间**（不是 `TreasureRoom` / `RestRoom`、也不是 `noCardsInRewards` 的事件）往**屏幕**列表末尾追加一条 CARD「将一张卡牌加入你的牌组中」。所以 `rewards.count` 可能比房间奖励多 1，**每一行都带 `type`**（`RewardItem.RewardType` 的名字：`GOLD` / `CARD` / `RELIC` / `POTION` / `SAPPHIRE_KEY` / `EMERALD_KEY` …）。**领之前先看 `rewards.items[].type`**：`CARD` 行 `take_reward` 只是打开 `card_reward` 屏（不直接给牌），别以为它和金币/遗物一样点一条就完事。

`take_reward` 的 `index` 是**必填的严格整数**（`slot` 是旧写法别名，二选一必给）：字符串 `"0"`、小数、负数、缺键、越界**一律 `BAD_REQUEST`**，`message` 会带「合法范围 0..N-1（共 N 条）」和「可领取: 0=金币(25 金), 1=遗物(小盒子)」这样的清单（issue #101；以前缺/越界会**静默领走第一条**，可能是你根本没点名的遗物）。

**同样的严格口径现在覆盖所有按序号寻址的动作**（#111 / #113 / C8-01 / C8-02）：`take_card` / `claim_relic` / `buy` / `choose_option` / `choose_match_card` 的 `index` 也是严格整型 —— `1.5`、字符串 `"0"`、负数、缺键、越界**一律 `BAD_REQUEST`**，报文同样带「合法范围 0..N-1（共 N 条/张/个/瓶/项）」+ 候选清单。以前 `getAsInt()` 会把 `1.5` 截成 `1`，**静默拿错牌/买错东西/点错选项**；现在只拒，不猜。逐条见 `card_reward` / `boss_reward` / `shop` / `event` / `match_game` 各小节。

**药水奖励和满腰带**（#112·C8-03 / C8-05）：药水那条 `rewards.items[]` 会多两个键 —— `takeable`（`false` = 现在拿不了）与 `blocked_reason`（腰带满时是 `"药水槽已满，先丢掉一瓶才能拿"`）。腰带满时 `legal_actions` **不会**给这条的 `take_reward`（planner 与 state 用的是同一个判据 `ActionPlanner.potionSlotsFull()`）；硬发 → `POTION_SLOT_FULL`「药水槽满了，拿不了这瓶药水」。**没有 `potion_discard` 屏**：原版领药水只有 `obtainPotion()` 一条路，槽满就是失败，不会跳去选「丢弃哪瓶」（旧文档写的「进丢弃流程」作废）。要拿就先 `discard_potion{slot}` 丢一瓶，再回来 `take_reward` —— **这一屏现在会广告那些 `discard_potion`**（#116a，见本节末尾「腰带满时本屏会广告 `discard_potion`」），所以「先丢一瓶」这一步在本屏就能执行，不再是个死结。

**`rewards.potion_blocked`（screen 级，只在腰带满时出现）**（#123·C14-06）：这时 `legal_actions` 只广告 `discard_potion` + `proceed`，而 `proceed` 的标签是「继续（放弃剩余奖励）」—— 一句话不说，agent 很容易以为「只能放弃」。所以整块 `rewards` 多一个字符串字段，原文：「药水槽已满（3/3）：药水奖励要先用 discard_potion 丢一瓶才能领，丢完再读 legal_actions 就会出现 take_reward；现在直接 proceed 等于放弃这份奖励（含钥匙/遗物）。」它只是解释，`take_reward` 不会因此出现；丢一瓶后重读 `legal_actions` 才出现。

`meta`（这个界面下的真实值）：

```json
{
  "ready": true,
  "test_mode": true,
  "speed": 1,
  "awaiting": "playing",
  "state_version": 9,
  "in_game": true,
  "seed": "39350NGPGEMN",
  "seed_raw": 314722147750928300,
  "character": "DEFECT",
  "ascension": 0,
  "act": 2,
  "floor": 20,
  "screen_type": "COMBAT_REWARD",
  "room_phase": "COMPLETE",
  "room_type": "TreasureRoom",
  "render_crashed": false,
  "ui": "combat_reward",
  "ui_group": "combat_reward",
  "suggest_include": [],
  "mod_version": "0.1.0",
  "language": "ZHS"
}
```

这一拍能做的动作：

- `take_reward` — 领取奖励 玩具扑翼飞机　payload `{"index":0}`
- `proceed` — 继续（放弃剩余奖励）

**腰带满时本屏会广告 `discard_potion`**（#116a）：药水奖励拿不了的那一拍，`legal_actions` 在奖励之外**再给每个真能丢的槽一条 `discard_potion{"slot":N}`**（label「丢弃药水 <名字>」，`slot` 就是 `player.potions` 的下标）—— 以前这一屏根本没有 `discard_potion`，而 `blocked_reason` / `POTION_SLOT_FULL` 都在叫你「先丢一瓶」、指了个这屏执行不了的动作。只有 `potion.canDiscard()` 的槽会被广告（和 `discard_potion` 执行器同一判据，不会广告一条会被拒的）；`use_potion` 不会出现（喝药要玩家回合，奖励屏不是）。丢完再 `take_reward` 就成功。

**`proceed` 出口去哪**（#114，判据在 `BossRewardPolicy`）：

- 普通奖励屏：全领完 label「继续」，否则「继续（放弃剩余奖励）」；执行 = 清空剩余奖励 + 关屏 + 打开地图。
- **Boss 战后的奖励屏（`room_type=MonsterRoomBoss`，第 1/2 幕）不再回地图**：走原版 `ProceedButton` 的 `goToTreasureRoom()` 进 **Boss 宝箱房** —— Boss 遗物只在那口箱子里，地图上没有。label 因此是「继续（进入 Boss 宝箱房（选 Boss 遗物），放弃剩余奖励）」/ 全领完「继续（进入 Boss 宝箱房（选 Boss 遗物））」。
- 第 3 幕（`TheBeyond`）/ 心脏（`TheEnding`）仍按原版走门或真胜利（label 里写「离开本幕」）；A20 双 Boss 走 `goToDoubleBoss`。
- 过场已经在进行中时再发 `proceed` **不幂等也不重启**：#118 起是一条立即的 `NOT_READY`（「上一段 Boss 过场刚开始（已等 Nms / 预算 2000ms）…」），2 秒预算到点/状态没动才重试真过场；**过渡调不通同样明确拒绝**：`NOT_READY` +「Boss 战后的 proceed 必须先把这一局推进到 Boss 遗物房/下一幕，现在做不到（…）；已拒绝，以免丢掉 Boss 遗物或留下只能 abandon_mid_run 的死地图」。

### `ui = treasure`（宝箱房）

这个界面没有专属区块（只有 `meta` + 常驻区块）。

`meta`（这个界面下的真实值）：

```json
{
  "ready": true,
  "test_mode": true,
  "speed": 1,
  "awaiting": "playing",
  "state_version": 8,
  "in_game": true,
  "seed": "39350NGPGEMN",
  "seed_raw": 314722147750928300,
  "character": "DEFECT",
  "ascension": 0,
  "act": 2,
  "floor": 20,
  "screen_type": "NONE",
  "room_phase": "COMPLETE",
  "room_type": "TreasureRoom",
  "render_crashed": false,
  "ui": "treasure",
  "ui_group": "treasure",
  "suggest_include": [],
  "mod_version": "0.1.0",
  "language": "ZHS"
}
```

这一拍能做的动作：

- `open_chest` — 打开宝箱

> **`TreasureRoomBoss`（第 1/2 幕 Boss 宝箱房）也算 `ui=treasure`，但动作集合与出口不同**（#114）：先 `open_chest` 开 Boss 宝箱（`BossChest.open()` 才会调 `BossRelicSelectScreen.open`），领完 Boss 遗物才给 `proceed`「继续（离开本幕）」（执行 = `goToNextDungeon`）。没开箱/没领遗物就 `proceed` → `NOT_READY`「先 open_chest 开 Boss 宝箱并 claim_relic，现在走会丢遗物（…）」。**`proceed` 还有 2 秒过场预算**（#118，`BossRewardPolicy.HAND_OVER_BUDGET_MS=2000`）：`goToNextDungeon()` 真被调起来之后 2 秒内再发 → `NOT_READY`「上一段 Boss 过场刚开始（已等 Nms / 预算 2000ms），房间正在切换；这次 proceed 没有重启过场，请等状态变化后再看 legal_actions」；`isDungeonBeaten` 已置（切幕已交给游戏）→ `NOT_READY`「这一局已经离开 Boss 房、正在切下一幕（已等 Nms）…请轮询 get_state 直到 ui 不再是 treasure」。**这条路径永远不会「回 ok 但什么都没发生」**：旧版用 `AbstractDungeon.waitingOnFadeOut` 当在飞标记，而这个 build 里那个字段只写不读、永不清，于是第一次 act-1/2 过场之后每次 `proceed` 都回 ok 却没跑过场，harness 一路报 `queued` 直到墙钟放弃（#118 的卡死）。预算超时、换了房间、时钟倒退都按「重试真过场」处理。

### `ui = shop_room`（商店房（未开屏））

这个界面没有专属区块（只有 `meta` + 常驻区块）。

`meta`（这个界面下的真实值）：

```json
{
  "ready": true,
  "test_mode": true,
  "speed": 1,
  "awaiting": "playing",
  "state_version": 32,
  "in_game": true,
  "seed": "39350NGPGEMN",
  "seed_raw": 314722147750928300,
  "character": "DEFECT",
  "ascension": 0,
  "act": 1,
  "floor": 20,
  "screen_type": "NONE",
  "room_phase": "COMPLETE",
  "room_type": "ShopRoom",
  "render_crashed": false,
  "ui": "shop_room",
  "ui_group": "shop",
  "suggest_include": [],
  "mod_version": "0.1.0",
  "language": "ZHS"
}
```

这一拍能做的动作：

- `open_shop` — 逛商店
- `leave` — 离开商店

### `ui = shop`（商店（货架））

`shop`：

```json
{
  "open": true,
  "cards": [
    {
      "index": 0,
      "id": "Gash",
      "name": "爪击",
      "price": 50,
      "affordable": true
    },
    {
      "index": 1,
      "id": "Streamline",
      "name": "精简改良",
      "price": 53,
      "affordable": true
    },
    {
      "index": 2,
      "id": "Conserve Battery",
      "name": "充电",
      "price": 45,
      "affordable": true
    },
    "... 共 7 项（此处只留前 3 项）"
  ],
  "relics": [
    {
      "index": 0,
      "id": "CaptainsWheel",
      "name": "舵盘",
      "price": 301,
      "affordable": false
    },
    {
      "index": 1,
      "id": "Pantograph",
      "name": "缩放仪",
      "price": 259,
      "affordable": false
    },
    {
      "index": 2,
      "id": "Membership Card",
      "name": "会员卡",
      "price": 156,
      "affordable": false
    }
  ],
  "potions": [
    {
      "index": 0,
      "id": "DuplicationPotion",
      "name": "复制药水",
      "price": 73,
      "affordable": true
    },
    {
      "index": 1,
      "id": "SteroidPotion",
      "name": "类固醇药水",
      "price": 50,
      "affordable": true
    },
    {
      "index": 2,
      "id": "PowerPotion",
      "name": "能力药水",
      "price": 49,
      "affordable": true
    }
  ],
  "purge": {
    "available": true,
    "cost": 100,
    "affordable": false
  },
  "gold": 87
}
```

`meta`（这个界面下的真实值）：

```json
{
  "ready": true,
  "test_mode": true,
  "speed": 1,
  "awaiting": "playing",
  "state_version": 25,
  "in_game": true,
  "seed": "39350NGPGEMN",
  "seed_raw": 314722147750928300,
  "character": "DEFECT",
  "ascension": 0,
  "act": 1,
  "floor": 20,
  "screen_type": "SHOP",
  "room_phase": "COMPLETE",
  "room_type": "ShopRoom",
  "render_crashed": false,
  "ui": "shop",
  "ui_group": "shop",
  "suggest_include": [],
  "mod_version": "0.1.0",
  "language": "ZHS"
}
```

这一拍能做的动作：

- `buy` — 买卡 爪击（50 金）　payload `{"kind":"card","index":0}`
- `buy` — 买卡 精简改良（53 金）　payload `{"kind":"card","index":1}`
- `buy` — 买卡 充电（45 金）　payload `{"kind":"card","index":2}`
- `buy` — 买卡 蒸汽护壁（49 金）　payload `{"kind":"card","index":3}`
- `buy` — 买卡 雷暴（39 金）　payload `{"kind":"card","index":4}`
- `buy` — 买药水 复制药水（73 金）　payload `{"kind":"potion","index":0}`
- `buy` — 买药水 类固醇药水（50 金）　payload `{"kind":"potion","index":1}`
- `buy` — 买药水 能力药水（49 金）　payload `{"kind":"potion","index":2}`
- …共 9 个

**`buy` 的 `index` 是严格整数**（C8-01 / #111）：`1.5`、字符串 `"0"`、负数、缺键 → `BAD_REQUEST`（以前 `1.5` 会被截成 `1`，买错商品）；`kind` 不在 `card`/`relic`/`potion` 里也在这个码里说明。拒绝报文带该货架的「合法范围 0..N-1（共 N 张/个/瓶）」+ `可买: 0=爪击(Claw)（50 金）, 1=…`（已售出的标「已售出」），越界同理。

**`purge_card` 之后会掉回 `ui=shop_room`**（issue #97·C3-02 实测，别按老文档写成「还在 `ui=shop`」）：

- `purge_card` 只是**打开净化网格**；净化网格是 `forPurge` 的**一击式**网格：`choose_grid_cards` 选一张就**当场关屏并扣钱**（QA 第 6 轮实测：金币 147 → 72），**网格里没有 `confirm_grid` 这一步** —— 别按「choose → confirm 才扣钱」写客户端（`skill/spirelink/references/tools.md` 的网格小节同口径）。
- 网格一关，原版会把商店屏也收掉：那一拍 `screen_type=NONE`、`room_phase=COMPLETE`、`ui=shop_room`，商人还站在那儿但货架是关的。
- 所以**想接着买得再发一次 `open_shop`**（`shop_room` 的 `legal_actions` 就有 `open_shop`）。
- **净化是「每个商店各一次」，不是「一局一次」**（issue #108·C7-06；按原版 `ShopScreen` 字节码核实）：`purgeAvailable` 在进店时（`ShopScreen.init()`）置 `true`，用掉一次后 `ShopRoom.updatePurge()` 置 `false` —— **换一家商店就又有一次**；同一家店里重新 `open_shop` 也不会恢复（`ShopScreen.open()` 只在 Hoarder 模组下强制置 false，不置 true）。用掉之后 `shop.purge.available=false`，硬发 `purge_card` → `ILLEGAL_ACTION`「这一局的重塑服务已经用过了」（**这句文案是旧的错误说法，实际拒绝的是「这家店」**）。
- **价格随本局净化次数上涨，不是固定 100**：`shop.purge.cost` 直接取原版静态 `ShopScreen.actualPurgeCost`。`purgeCost` 开局 75，**每净化一次 +25**（`ShopScreen.purgeCard()` 里 `purgeCost += 25`），进店时把当次值抄进 `actualPurgeCost` —— 所以**本局第一次净化 75、第二次 100、第三次 125** …（跟商店个数无关：第一家不净化，第二家仍报 75）。笑脸面具直接压到 50，送货员 ×0.8、会员卡 ×0.5。§10.2 的 `actions-shop.json` 那一拍是 **100**（前面已经净化过一次），不是「净化固定 100 金」。**客户端按 `cost` + `affordable` 判断，别把 100 写死**。

### `ui = rest`（营火）

这个界面没有专属区块（只有 `meta` + 常驻区块）。

`meta`（这个界面下的真实值）：

```json
{
  "ready": true,
  "test_mode": true,
  "speed": 1,
  "awaiting": "playing",
  "state_version": 7,
  "in_game": true,
  "seed": "39350NGPGEMN",
  "seed_raw": 314722147750928300,
  "character": "DEFECT",
  "ascension": 0,
  "act": 2,
  "floor": 20,
  "screen_type": "NONE",
  "room_phase": "INCOMPLETE",
  "room_type": "RestRoom",
  "render_crashed": false,
  "ui": "rest",
  "ui_group": "rest",
  "suggest_include": [],
  "mod_version": "0.1.0",
  "language": "ZHS"
}
```

这一拍能做的动作：

- `smith` — 锻造（升级一张牌）

**同一张营火屏，`actions` 里有没有 `proceed` 是会变的**（issue #97·C3-09）：`proceed` 只在「继续」按钮真的可见（`proceedVisible()`）时给，而它取决于这一步还剩几个可用选项/营火是否已经选过东西。实测两种情况都出现过：

- 还有可用选项时：`smith`（或 `rest`/`lift`/`toke`）+ **可能**带 `proceed`；
- 已经选过（`campfireUI.somethingSelected` 或选项用完/不可用）时：只给 `proceed`，再发 `rest`/`smith` 会 `ILLEGAL_ACTION`「篝火选项已经选过了」。

所以别背动作清单，**每次都以 `legal_actions` 实际给的为准**。

### `ui = event`（事件）

`event`：

```json
{
  "id": "BigFish",
  "name": "大鱼",
  "body": "当 你 走 过 一 条 长 廊 时 ， 你 看 见 空 中 漂 浮 着 一 根 香 蕉 ， 一 个 甜 甜 圈 ， 和 一 个 盒 子 。 不 … … 仔 细 一 看 ， 它 们 都 是 被 用 绳 子 系 着 ， 从 天 花 板 上 的 几 个 洞 里 悬 挂 下 来 的 。 你 在 接 近 这 几 样 东 西 时 …",
  "options": [
    {
      "index": 0,
      "text": "[香蕉]  #g回复  #g333 #g生命。",
      "disabled": false
    },
    {
      "index": 1,
      "text": "[甜甜圈] #g最大生命值 #g+5 #g。",
      "disabled": false
    },
    {
      "index": 2,
      "text": "[盒子] #g获得一件遗物。 #r被【诅咒】——悔恨。",
      "keywords": [
        "诅咒"
      ],
      "disabled": false
    }
  ],
  "text_typing": false,
  "note": "事件 大鱼：香蕉=回29血 / 甜甜圈=最大生命+5 / 盒子=遗物+悔恨诅咒。满血时选甜甜圈（永久+5上限），残血时香蕉。盒子在真实难度不要（悔恨+诅咒污染牌堆）",
  "note_history": true,
  "note_updated_at": 1793499678
}
```

> ⚠ 上面这份 `docs/samples/state-event.json` 抓于 issue #76 之前：`body` / `options[].text` 还带**逐字空格**和 `#g` 记号，也没有 `text_source`。当前实现的正文是**清洗后**的（`当你走过一条长廊时…`，见本节末尾的清洗说明），字段里多一个 `text_source`。**以字段表为准，别拿样本对当前形状**（issue #94·B2-16 同一类问题）。

`event` 字段（一共 8 个键，全部列在这里）：

| 字段 | 含义 |
|---|---|
| `id` | 事件类简单名（`BigFish`、`GoldShrine`）。**这个就是 `dev_event` 要的 id**（不是显示名） |
| `name` | 本地化显示名（`大鱼`）；查不到时退回 `id` |
| `body` | 当前正文（清洗后，见 issue #76）。`body_unavailable:true` + `body:""` 表示这一拍读不到正文，不是没有事件 |
| `options[]` | `{index, text, disabled, keywords?}`。`text` 同样清洗过；`disabled:true` 的选项点了会被拒 |
| `text_typing` | 正文是否还在逐字打出来。**只是提示**：`true` 时 `ready` 仍可能是 `true`，`choose_option` 照发也能成功（issue #97·C3-04） |
| `text_source` | **正文是从哪个对话框读的**：`image`（`imageEventText`，ImageEvent 那类）/ `room`（`roomEventText`）/ `none`（两个都没可读的 `textDone`，`text_typing` 不可信 —— 这时日志里会打一条 `event.text_typing 无信号源`）。issue #97·C3-07 |
| `note` / `note_state` / `note_history` / `note_updated_at` / `note_key` / `note_pushed` | 你自己给这个事件写的便签（#127 三态）：`note_state:"none"` → `note:null`、其余 `note_*` 都不出现；`"new"` → 本局第一次投递这个版本，`note` 是全文 + `note_history:true`（历史文字，当前数值以同回包字段为准）+ `note_updated_at` + `note_key`（`"event:<事件 id>"`）+ `note_pushed:false`；`"seen"` → 本局投过了，`note:null` 但另外四个键仍在（`note_pushed:true`），要原文用 `note_get` 或 `get_state{force_notes:true}` |

**事件结算后 `event` 区块还会挂在别的界面上**（issue #97·C3-03 实测：`ui=combat_reward`、`ui=map` 都看到过旧 `event`）：`event` 区块的判据是「**当前房间**是 `EventRoom` 且 `room_phase=EVENT`」，跟 `meta.ui` 无关 —— 站在事件房里打开地图/奖励屏，区块照旧在。反过来：**看到 `event` 不代表现在是事件界面**，判界面只认 `meta.ui`；事件真结束了（房间还在、事件对象没了）会退化成一个 `{id, name, resolved:true, options:[]}` 的标记，只在 `ui=event` 时出现。

`meta`（这个界面下的真实值）：

```json
{
  "ready": true,
  "test_mode": true,
  "speed": 1.0,
  "awaiting": "playing",
  "state_version": 65,
  "in_game": true,
  "seed": "4VUVGLVBWKHPG",
  "seed_raw": -1950987540974881185,
  "character": "WATCHER",
  "character_source": "player",
  "ascension": 1,
  "act": 1,
  "floor": 1,
  "screen_type": "NONE",
  "room_phase": "EVENT",
  "room_type": "EventRoom",
  "room_cleared": false,
  "render_crashed": false,
  "game_dead": false,
  "ui": "event",
  "ui_group": "event",
  "suggest_include": [],
  "mod_version": "0.1.0",
  "language": "ZHS"
}
```

这一拍能做的动作：

- `choose_option` — 香蕉 回复 333 生命。　payload `{"index":0}`
- `choose_option` — 甜甜圈 最大生命值 +5 。　payload `{"index":1}`
- `choose_option` — 盒子 获得一件遗物。 被诅咒——悔恨。　payload `{"index":2}`

**`choose_option` 的 `index` 是严格整数**（#113）：`1.5`、字符串、负数、缺键、越界 → `BAD_REQUEST`，报文带「合法范围 0..N-1（共 N 项）」+ `可选项: 0=香蕉 回复 333 生命, 1=…`（`disabled`/没有 hitbox 的选项会标「（锁定，不可点）」，硬发那个下标是 `ILLEGAL_ACTION`）。以前 `1.5` 会被截成 `1`，点了另一个分支 —— 事件分支选错是**改档**的。

**`body` 与 `options[].text` 都是清洗过的文本（issue #76）**：同一个对象、同一份文字，永远是同一串字符——

- 游戏内着色/控制记号（`#y` `#b` `#r` `#g` `#p` `#.`）、`@`、`~`、换行符都已清掉；
- 中文里的**逐字空格**（`当 你 走 过 一 条 长 廊`）已经合回正常词句（`当你走过一条长廊`），只在原文真有空格的地方保留空格；
- 清洗是**幂等**的：把返回的文本再清洗一遍不会变，所以不会出现「同一屏两次调用一次带记号一次不带」的抖动。
- `options[].text` 用同一个清洗器，所以选项文案和 `body` 风格一致；`note` 是你自己写的内容，不经这个清洗。

### `ui = grid`（选牌网格）

这个界面没有专属区块（只有 `meta` + 常驻区块）。

`meta`（这个界面下的真实值）：

```json
{
  "ready": true,
  "test_mode": true,
  "speed": 1,
  "awaiting": "playing",
  "state_version": 131,
  "in_game": true,
  "seed": "39350NGPGEMN",
  "seed_raw": 314722147750928300,
  "character": "DEFECT",
  "ascension": 0,
  "act": 1,
  "floor": 24,
  "screen_type": "GRID",
  "room_phase": "INCOMPLETE",
  "room_type": "RestRoom",
  "render_crashed": false,
  "ui": "grid",
  "ui_group": "grid",
  "suggest_include": [],
  "mod_version": "0.1.0",
  "language": "ZHS"
}
```

这一拍能做的动作：

- `choose_grid_cards` — 打击　payload `{"uuid":"8d533a84-8575-4383-9314-6d9b2ffcb1e4"}`
- `choose_grid_cards` — 防御　payload `{"uuid":"26f12375-036a-443c-9e02-d985f4e6db49"}`
- `choose_grid_cards` — 防御　payload `{"uuid":"2591e7c4-05a1-475d-b1c6-33b6763f5f07"}`
- `choose_grid_cards` — 防御　payload `{"uuid":"21e1921d-d602-4f7c-9c6f-8185db56b709"}`
- `choose_grid_cards` — 防御　payload `{"uuid":"57350391-d9e5-4840-b8d4-9e91b6f833a0"}`
- `choose_grid_cards` — 电击　payload `{"uuid":"55d252a5-e6a6-4dac-8fb5-6b3a8655dd3d"}`
- `choose_grid_cards` — 冷静头脑　payload `{"uuid":"e6167c4b-60af-436d-b9e0-ecc783704a09"}`
- `choose_grid_cards` — 暴雪　payload `{"uuid":"73b5b488-907a-4a5a-963e-2512afcbad54"}`
- …共 17 个

**`cancel_grid` 不是每个网格都有**（issue #96 明确改过语义，别按「网格必有 cancel」写客户端）：

- **有 `CancelButton` 的网格才广告 `cancel_grid`** —— 典型是营火 `smith`、商店净化（原版 `open(..., canCancel=true)`）。
- **确认式网格只给 `choose_grid_cards` / `confirm_grid`**：变形（Transmogrifier）、Designer 的升级/删除、Astrolabe 这类 `canCancel=false` 的网格，`cancel_grid` **不会出现**。
- 为什么这么严：这条路走的是 `closeCurrentScreen()`，而原版事件会把「屏幕关了」当成「玩家选完了」—— 在确认式网格上发 `cancel_grid` 会让事件假装结算（牌一张没变、选项消失）。
- 硬发会得到 `ILLEGAL_ACTION`，文案：「这个选牌网格是确认式的（原版没有取消按钮）：请 choose_grid_cards / confirm_grid（cancel 会被事件当成「已完成」结算）」。
- **一击式网格没有 `confirm_grid`**：营火 `smith` 的升级网格（`forUpgrade`）、商店净化（`forPurge`）这类选一张就关屏 —— `choose_grid_cards` 一选，网格当场关（smith 的升级由营火自己完成；净化则金币当场扣，QA 第 6 轮实测 147→72），`legal_actions` 里**不会出现 `confirm_grid`**。#112·C8-04 实测复核：营火 smith **不是** choose→confirm 两步，别再等确认。
- 唯一的兜底窗口：**一个牌都点不了、确认也用不了**（病态屏）时仍会给一条 `cancel_grid`，那是逃生口，不是常态。


## 7. 结构已知但样本不全的界面

这一节里的界面，**字段结构以代码与真实回包为准，但 `docs/samples/` 里没有（或只有一半）样本**，
所以下面是「结构准、数值示意」。个别界面（`char_select` / `neow`）其实已经有样本了，见下表。

| 小节 | 样本 | 为什么还没有 / 怎么补 |
|---|---|---|
| `boss_cleared` | **无** | 只有「Boss 被打死 → 还没离开那一拍」的窗口（issue #42 新增的 ui；#114 起 `proceed` 进 Boss 宝箱房，不是打开地图）；补：跑到 Boss 战并开 `python scripts/capture_samples.py --watch --seconds 900 --ui boss_cleared` |
| `card_reward` | **无** | 奖励屏停留短，按 `meta.ui` 去重时容易错过；补：`--watch --ui card_reward` |
| `boss_reward` | **无** | 要打赢 Boss 并走到宝箱房开 Boss 遗物屏；补：`--watch --ui boss_reward`（或 test_mode 下 `dev_room{type:"boss_reward"}` 造屏） |
| `char_select` | ✅ `state-char_select.json` `actions-char_select.json` | 已抓（2026-11-02） |
| `neow` | ✅ `state-neow.json` `actions-neow.json` | 已抓（2026-11-02） |
| `match_game` | **无** | 只有 GremlinMatchGame（第一幕 `?` 房随机）才会出现；补：test_mode 下 `dev_event{id:"GremlinMatchGame"}` 造出来再 `--watch` |
| `postmortem` | **无** | 死亡/胜利结算各要发生一次；补：test_mode 下 `dev_room{type:"victory"}` 直接进胜利结算，死亡则真死一次 |

抓完记得回头更新 [§10 样本索引](#10-样本索引)：`python scripts/capture_samples.py --check-index` 会指出漏登记的文件。

### `boss_cleared` — Boss 清场（`ui=boss_cleared`，issue #42）

Boss 被打死、还没点「打开地图」的那一拍：`room_phase=COMPLETE`、`room_type=MonsterRoomBoss`、`screen_type=NONE`，
此时 `ui=boss_cleared` 且 `meta.room_cleared=true`，`legal_actions` 只有一条 `proceed`（动作类型与形状不变；执行器确定性推进，不依赖 vanilla 继续按钮是否可见）。**注意它现在不是「打开地图」**（#114）：`MonsterRoomBoss` + `room_phase=COMPLETE` 会走原版 `ProceedButton` 的 `goToTreasureRoom()`（第 1/2 幕）→ 进 **Boss 宝箱房**，先开箱拿 Boss 遗物；第 3 幕（`TheBeyond`）/心脏（`TheEnding`）才是门/真胜利，A20 双 Boss 走 `goToDoubleBoss`。**label 文案目前仍写「打开地图」**（planner 未同步，已记在 #114）—— 以行为为准，别以为会落在空地图上：过渡调不通会明确 `NOT_READY` 并说明原因。**#118 起这条 hand-over 也走 2 秒预算**（`BossRewardPolicy.HAND_OVER_BUDGET_MS`）：预算内重复 `proceed`、或 `isDungeonBeaten` 已置，都回 `NOT_READY` + 明确原因（「没有重启过场」「正在切下一幕」），**不会再出现「ok 但没动」导致的 `queued` 卡死**；超时/换房/时钟倒退一律重试真过场。
以前这一拍报 `ui=idle`，agent 会以为「什么都没发生」。判定只看房间：普通怪房/精英房走到 `COMPLETE` 仍走原逻辑，
`TreasureRoom` / `TreasureRoomBoss`（Boss 宝箱奖励屏）也保持原有的 `treasure`。

### `card_reward` — 卡牌奖励（`ui=card_reward` / `ui=grid` / `ui=hand_select` 共用）

```json
{
  "card_reward": {
    "count": 3,
    "cards": [
      {
        "uuid": "1b0c…",
        "id": "Strike_R",
        "name": "打击",
        "type": "ATTACK",
        "rarity": "BASIC",
        "cost": 1,
        "upgraded": false,
        "text": "造成6点伤害。",
        "raw_text": "造成!D!点伤害。",
        "damage": 6
      }
    ]
  }
}
```

`text` 里的 `!D!/!B!/!M!` 已替换成真实数值，`raw_text` 保留原文；有对应数值时另给 `damage`/`block`/`magic`。`ui=grid`、`ui=hand_select` 也用这个区块，能否确认看 `hints.grid_can_confirm`。

**`take_card` 的 `index` / `uuid`**（#111 / C7-04 / C8-02）：

- `index` 是 **0 起的严格整数**：`1.5`、字符串 `"1"`、负数、缺键、越界**一律 `BAD_REQUEST`**（以前 `1.5` 会被截成 `1` 并**真的拿走另一张牌**）；
- 拒绝报文带「合法范围 0..N-1（共 N 张）」+ `可拿: 0=打击(Strike), 1=防御(Defend)` 清单；
- 也可以只给 `uuid`（更具体）：给了 `uuid` 就只按它找，找不到会列出**可选 uuid**，不会默默按 `index` 拿另一张；
- `index` 和 `uuid` 都不给 → `BAD_REQUEST`「缺少 index（payload 要写 {"index": <0 起的牌序号>} 或 {"uuid": …}）」。

### `boss_reward` — Boss 遗物三选一

```json
{
  "boss_reward": [
    {
      "index": 0,
      "id": "Sozu",
      "name": "苏生之瓮",
      "tier": "BOSS",
      "text": "…",
      "description": "…"
    }
  ]
}
```

配合 `claim_relic{index}`；不想要用 `skip_relic`。

**`claim_relic` 的 `index` 是严格整数**（C8-01 / #111）：`1.5`、字符串、负数、缺键、越界 → `BAD_REQUEST`，报文带「合法范围 0..N-1（共 N 个）」+ `可选遗物: 0=小盒子(Tiny Chest), 1=…`（旧版只有一句「遗物序号越界: 9」，没有范围也没有候选）。

### `char_select` — 选人

**样本**：`state-char_select.json` + `actions-char_select.json`（已抓，2026-11-02；文件里高亮的是铁甲战士）。

`get_state`（`ui=char_select`，已高亮「静默猎手」）：

```json
{
  "meta": {
    "ready": true,
    "awaiting": "char_select",
    "character": "THE_SILENT",
    "character_source": "char_select"
  },
  "char_select": {
    "options": [
      { "index": 0, "id": "IRONCLAD", "name": "铁甲战士", "selected": false, "locked": false },
      { "index": 1, "id": "THE_SILENT", "name": "静默猎手", "selected": true, "locked": false }
    ],
    "selected": "THE_SILENT",
    "selected_index": 1,
    "selected_name": "静默猎手"
  },
  "hints": {
    "char_selected": "THE_SILENT",
    "next": "embark"
  }
}
```

- `meta.character` 在这一屏**只**以 `char_select.selected` 为准，`meta.character_source` 说明来源：`char_select`（选人界面，或开局前的高亮角色）/ `player`（当前局角色）。**不要**从 `player` 区块推断角色——上一局的 `AbstractDungeon.player` 还挂在内存里，以前这里会报上一局的角色，与 `char_select.selected` 自相矛盾（#49）。
- `meta.awaiting` 在这一屏是 `"char_select"`，不是 `"playing"`；只有开局淡出那一拍才回到 `"transition"`。
- `hints.char_selected` 是已高亮的角色 id（没人被高亮时 `null`），`hints.next` 是这一屏的下一步（`"embark"` / `"choose_character"`）。选人界面是 `hints` 的例外：其它界面 `hints` 只在未就绪、上次被拒、或自带异常标记（`map_no_reachable`/`suggest_note`/`unknown_ui`/`event_missing`）时出现（见 §5、§9），这里即便 `ready=true` 也一直带；不想收可以 `exclude:["hints"]`。

`legal_actions`（`ui=char_select`）：**已经高亮某个角色时 `actions[0]` 就是 `embark`**，已选中的角色本身也留在候选里（`保持 X`，`payload.selected = true`）。**`保持 X` 是幂等 verified**（#122·C13-05）：对已选中的角色再发一次 `choose_character` **不点按钮、不排队**，直接回 `outcome:"applied"`（`ActionResult.verified()`）—— **连发两次都 `applied`**，不会进 `queued`、也不会触发 3 秒 `ACTION_IN_FLIGHT` 护栏（旧实现回 `ok` 但什么都没动 → 被判 `queued`，真机上连续两次「保持 X」会把后续动作堵住约 1.7 s）：

```json
{
  "ready": true,
  "ui": "char_select",
  "awaiting": "char_select",
  "actions": [
    { "id": "a1", "type": "embark", "label": "启程", "payload": {} },
    { "id": "a2", "type": "choose_character", "label": "选择铁甲战士", "payload": { "character": "IRONCLAD" } },
    { "id": "a3", "type": "choose_character", "label": "保持 静默猎手", "payload": { "character": "THE_SILENT", "selected": true } },
    { "id": "a4", "type": "choose_character", "label": "选择故障机器人", "payload": { "character": "DEFECT" } },
    { "id": "a5", "type": "choose_character", "label": "选择观者", "payload": { "character": "WATCHER" } },
    { "id": "a6", "type": "choose_ascension", "label": "升天 1", "payload": { "level": 1 } },
    { "id": "a7", "type": "back", "label": "返回主菜单", "payload": {} }
  ]
}
```

顺序固定为：`embark` → 各角色（已选的写「保持」，被锁的角色不出现）→ 升天档位 → `back`。升天档位只列该角色已解锁的常见档（`0/1/5/10/15/20`，`level:0` 表示关闭升天），永远排在 `embark` 之后——它是个可选微调，不是默认动作。

没有任何角色被高亮时是原来的形状：每个未锁角色一条 `choose_character` + `back`，没有 `embark`、没有升天档位。

所以「永远取 `actions[0]`」的 naive harness 在选人界面会选一次角色，下一拍直接 `embark`，不会在两个角色之间来回横跳（#49）。反过来**连着发两次同一条「保持 X」也不会出事**（幂等 verified，见上）：两次都 `applied`，第二发不占动作队列。

### `neow` — 开局奖励（复用 `event` 区块）

**样本**：`state-neow.json` + `actions-neow.json`（已抓，2026-11-02；这一拍 `actions` 是 `choose_neow_reward{index}` + `proceed`）。

```json
{
  "event": {
    "id": "NeowEvent",
    "name": "涅奥",
    "text": "",
    "options": [
      {
        "index": 0,
        "text": "获得 100 金币",
        "disabled": false
      }
    ],
    "option_count": 4
  }
}
```

`choose_neow_reward{index}` 的 `index` 与 `options[].index` 对应。

**`event.text` 恒为空串**（issue #107）：`"text": ""` 不是「抓漏了」，是唯一正确答案 —— 涅奥把话写在屏幕的对话气泡（`InfiniteSpeechBubble`）里，MCP 不读那玩意儿；`NeowEvent` 也不是 `AbstractImageEvent`，从不往共享的 `GenericEventDialog` 写页面。实现里的三道闸（类族 / 非空标题 / 页主一致）对涅奥只会给出 `""`，读不到就回空串，**永远不会给别人的台词**。对白内容看 `neow.options[].label`。

**`label` 长什么样**：`"选项：" + option.msg`。所以开局第一拍看见 `label:"选项：对话"` 是**正常的** —— 涅奥开场那句对白本身就叫「对话」，它同样以 `choose_neow_reward{index:0}` 的形式出现，点下去只是把对白播完，**不领奖励**（`docs/samples/actions-neow.json` 拍的就是这一屏）。真正的奖励选项（「获得 100 金币」这类）要等对白推完才出现，label 也随之变成 `选项：获得 100 金币`。

**什么时候附带 `proceed`**：可选的 `choose_neow_reward` 只有 1 条时会再给一条 `proceed`「继续」（`actions-neow.json` 的 2 条 = 1 + 1）；多个选项时只有 `choose_neow_reward`。选项全被 `disabled`（死后的 `NEOW_UNLOCK` 屏）时只给 `proceed`「返回主菜单」。

### `match_game` — 小游戏（GremlinMatchGame）

```json
{
  "match_game": {
    "screen": "PLAY",
    "game_done": false,
    "matched": 2,
    "attempts": 3,
    "first_pick": "…",
    "flipping": false,
    "flip_stalled_ms": 1200,
    "cards": [
      {
        "index": 0,
        "id": "…",
        "name": "…",
        "matched": true,
        "is_first_pick": false
      }
    ]
  }
}
```

| 字段 | 出现条件 | 含义 |
|---|---|---|
| `screen` | 恒有（小游戏挂着时） | 字符串：`INTRO` / `RULE_EXPLANATION` / `PLAY` / `COMPLETE` / `CLEAN_UP`。**只有 `PLAY` 接受翻牌** |
| `game_done` | 恒有 | 小游戏是否已经结束 |
| `matched` | 恒有 | 已配好的对数 |
| `cards[]` | 恒有 | `{index,id,name,matched,is_first_pick}` —— `index` 就是 `choose_match_card` 的下标；`is_first_pick` = 那张正等着配对的牌 |
| `first_pick` | 恒有 | 第一张已翻开的牌 cardID，没有时是空串 `""` |
| `flipping` | 恒有 | 正在播翻牌动画就 `true`（此时别抢着翻下一张） |
| `attempts` | **仅 `screen=PLAY`** | 已翻牌次数 |
| `flip_stalled_ms` | **仅 `screen=PLAY`** | 距上次翻牌/配对变化多少毫秒（牌卡住会一直涨） |

**`attempts` / `flip_stalled_ms` 只在 `screen=PLAY` 出现**（issue #119·C10-06）：`INTRO` / `RULE_EXPLANATION` 这些页上棋盘还不接受翻牌，`flip_stalled_ms` 会从进小游戏那一刻一路涨（看着像「卡死了」）、`attempts` 只是残留的 0，所以两份一起被裁掉；`screen`/`cards`/`matched` 仍然给，用它们判断「现在还不能翻」。`get_state.match_game` 与 `event.match_game` 是同一个对象，**两处都裁**。

**PLAY 屏的动作表**（#120·C11-04）：常规是**每张可翻的牌一条 `choose_match_card{index}`**；**棋盘 8 秒没动**（`flip_stalled_ms > 8000`）时会**额外同时给一条** `proceed`「放弃小游戏并离开（会损失奖励）」—— 它和翻牌动作**并存**在 `actions` 里（不是「翻不了才给」，只是给卡住的 agent 一条诚实出口；只是思考中的 agent 不会被逼着放弃）。`game_done` 之后给的是 `proceed`「完成小游戏」。**PLAY 上的 `proceed` 永远等于「放弃/收摊」，会损失小游戏奖励**，别拿它当「退出对话」；INTRO / RULE_EXPLANATION 那些页只有 `event_continue`（推进对话），没有翻牌。

**`choose_match_card` 的 `index` 是严格整数**（#113）：`1.5`、字符串、负数、缺键、越界 → `BAD_REQUEST`，报文带「合法范围 0..N-1（共 N 张）」+ `牌面: 0=打击(Strike), 1=…`（已配对的标「（已配对）」、已翻开待配对的标「（已翻开，待配对）」）。翻一张不存在的牌以前会被静默截断成别的牌。

### `postmortem` — `ui=death` / `ui=victory`

```json
{
  "postmortem": {
    "result": "death",
    "killed_by": "Gremlin Nob",
    "act": 1,
    "floor": 6,
    "hp": 0,
    "deck_size": 18,
    "deck": [
      {"id": "Strike_R", "upgraded": true},
      {"id": "Bash", "upgraded": false}
    ],
    "relics": [
      "BurningBlood"
    ],
    "potions": [],
    "last_combat": {
      "monsters": [
        "Gremlin Nob"
      ],
      "turns": 7
    }
  }
}
```

同一拍 `hints.suggest_note` 会给出「建议记一条什么」。

## 8. 错误码

错误码分三层，**位置不同、处理方式也不同**，先认清在哪一层再动手：

| 层 | 出现在哪 | 长什么样 |
|---|---|---|
| 传输层 | HTTP 状态码 + JSON-RPC `error` | `{"jsonrpc":"2.0","id":7,"error":{"code":-32001,"message":"…"}}`，**没有 `result`** |
| 工具层（**全部 19 个工具**） | `result.content[0].text` 的业务对象 | `error` **一定是对象**：`{"ok":false,"error":{"code":"NOT_READY","message":"…","state_version":N}}`，并带 `result.isError:true`。便签 / 图鉴 / `dev_*` 与 `act` 形状一致（issue #86） |
| `/hud/say`、`/hud/driver`（非 MCP 协议） | 那两个 HTTP 端点的 body | **例外**：不是 JSON-RPC，`error` 是**字符串**：`{"error":"…","status":413}`。别拿它当工具返回（端点语义见 §0.2） |

**注意：工具层失败时 HTTP 仍是 `200`** —— 只看状态码会把所有业务失败当成成功。判断顺序：
HTTP 状态 → JSON-RPC `error` → `result.isError` → 业务 `ok`。

**`act` 的排队诊断也在 `error` 里**（#120·C11-01）：`error.queue_status` / `error.waited_ms` / `error.version_advanced`（单飞队列 BUSY / TIMEOUT 两种回包）与 `error.retry_after_ms`（3 秒墙钟护栏那条 `ACTION_IN_FLIGHT`）都和**回包顶层同值各一份** —— 按 `a.error.queue_status` 取能拿到，老客户端读顶层也不破。除这几个之外 `error` 仍是 `{code, message}`（+ 必要的 `state_version`）。

**`ok:false` 与 `isError:true` 现在是同一件事**（issue #86）：任何工具只要业务对象里 `ok:false`，`result.isError` 就一定是 `true`。以前 `glossary` 查不到词还回 `isError:false`，只看 MCP 标志的客户端会把失败当成功；那个例外已经修掉。

**成功的回包**：`ok:true`、`outcome:"applied"`、`error:null`，并带一句 **`log`**（形状与词表见 §4）；`result.isError` 不出现。`outcome:"queued"` 的 `ok` 也是 `true`，但**没有 `log`**、`actions:[]`，`ui` 是**当前快照的 `meta.ui`**（与 `get_state` 同源；#119·C10-02 之前这里写死 `other`，同一次调用会和 `get_state` 报两个屏）—— 「进了游戏但还没落地」不是失败，别按错误处理。

**`error` 的形状**（issue #86，指工具返回）：永远是 `{code, message}`，`act` / `get_state` / `legal_actions` 可能再多带 `state_version`；此外 `act` 的**排队类**回包会在 `error` 里再带 `queue_status` / `waited_ms` / `version_advanced`（3 秒墙钟护栏那条再带 `retry_after_ms`，与顶层同值，见上 #120·C11-01）；`code` 一定是 `UPPER_SNAKE` 的机器码。顶层 `message` 键**不再单独存在**（旧版便签类工具把 message 放在顶层、`error` 放字符串码，现在统一搬进 `error.message`）。若某个分支给不出机器码，会被归一化成 `code:"INTERNAL"` + 原文当 message。

### 8.1 业务错误码（`act` 与各工具共用）

`ErrorCodes.java` 里 15 个 + 运行时另有 `ACTION_IN_FLIGHT` / `INTERNAL` / `NOTE_WRITE_FAILED` 等，
下表是**全部会从 `act` 出来的**（按 `error.code` 分支，别匹配中文 `message`）；紧接的第二张表是**工具层**（便签 / `card_info` / `glossary` / 全部 `dev_*`）的码 —— `dev_*` 那几条只可能出现在 `test_mode=true` 时：

| code | 谁产生 | 什么时候出现 | agent 该怎么办 |
|---|---|---|---|
| `STALE_VERSION` | `act` | 版本号对不上，**两种情形分开说**（#120·C11-05，错误码不变）：① 你带的**比现场旧**（别人动过/你读的是旧快照）→「state_version N 已过期（当前 M），重新取法律动作。」；② 你带的**比现场新**（手写/猜的版本号，**这个版本还不存在**）→「state_version N 比当前版本（M）更新：这个版本号还不存在，只有 get_state / legal_actions 刚返回的那个才存在。按最近一次读到的 M 重发（错误码仍是 STALE_VERSION）。」 | 重新 `get_state`（或 `legal_actions`）取新版本，用新版本重发（新 `action_id`）。**别猜/别加版本号** —— 只有读工具刚给你的那个才存在 |
| `NOT_READY` | `act` / `get_state` / `legal_actions` / `event_note` / `dev_*` | 游戏这一拍不接受动作：**存档正在载入**（`awaiting:"loading"`）、**正在离开本局**（`awaiting:"transition"`、`ui:"other"`，放弃过渡）、**刚进战斗还在初始化**（`combat_settling`）、动画、敌人回合、主线程忙、渲染崩过、**屏上没有可执行动作**（`ready:false` + `blocked_ui`，`get_state` 连 `ok` 都是 `false`）—— **十一个细分见 §8.2** | 等 200~500 ms 再 `get_state`；**不要**换参数硬重试；空动作那种约 1 秒后重试 `legal_actions` |
| `ACTION_IN_FLIGHT` | `act` | **两个来源**：① 上一个 `act` 回过 `queued`，且 `state_version` 还没推进（3 秒墙钟护栏，**回包带 `retry_after_ms`**）；② **单飞队列 BUSY 且确实有动作在落地**（`ActionInFlight` 里还压着一条没落地的动作，**回包没有 `retry_after_ms`**，只有 `queue_status:"BUSY"` / `waited_ms` / `version_advanced`）—— **#125·C16-02 起，BUSY 但没有任何动作在飞时回的是 `NOT_READY`**（主线程只是被过渡/动画那一帧占着，不是我们上一条动作在落地；message 会改成「主线程正忙着执行别的东西（单飞队列被占：过渡/动画那一帧，不是我们上一条动作在落地）…」）；细则见 §8.3（`retry_after_ms` 只属于①） | **禁止再 `act`**（换 `type`/`action_id` 都不行），只轮询 `get_state`；`NOT_READY` 那支隔 200~500 ms 再读 |
| `ILLEGAL_ACTION` | `act` | 动作类型认识，但这个界面/这一刻不给（没能量、篝火已用过、不是商店…） | 重新 `legal_actions`，**别重试同一条** |
| `UNKNOWN_ACTION_TYPE` | `act` | `type` 根本不在动作集合里（自己造的名字） | 照 `legal_actions` 抄 `type`，别拼 |
| `BAD_REQUEST` | `act` / `note_set` / `note_get` / `card_info` / `dev_*` | 参数不合法：缺 `type`/`state_version`、`uuid` 格式错、便签 `kind` 不在白名单；**动作参数摊平写在 `act` 顶层**（#122·C13-04：只认 `type`/`state_version`/`payload`/`action_id`，报文列出多写的键并给正确形状模板）；**所有按序号寻址的动作**（`take_reward` `take_card` `claim_relic` `buy` `choose_option` `choose_match_card`）的 `index` 一律**严格整型** —— 缺失 / 字符串 `"0"` / 小数 `1.5` / 负数 / 越界都是 `BAD_REQUEST`，`message` 带「合法范围 0..N-1（共 N 条/张/个/瓶/项）」**加候选清单**（`可领取: 0=金币(25 金), 1=遗物(小盒子)` / `可选项: …` / `牌面: …`）（#101 / #111 / #113 / C8-01 / C8-02）；**`choose_map_node` 的 `x`/`y` 同理**（字符串、小数、缺键 → `BAD_REQUEST`，不再回「房间还没结束…先 proceed」，见 §6.2 地图那节 / #110）；`use_potion` 的 `target_index` 越界 | 照 `legal_actions.actions[].payload` 原样抄；便签白名单见 §11；序号按报文里给的合法范围重新挑，别猜 |
| `CARD_NOT_IN_HAND` | `act{play_card}` | 那张牌不在手里（已经打出去了） | 重新 `legal_actions` |
| `CARD_NOT_PLAYABLE` | `act{play_card}` | 状态牌/诅咒，或这一刻不能打 | 换一张或 `end_turn` |
| `NOT_ENOUGH_ENERGY` | `act{play_card}` | 能量不够 | 换便宜的牌或 `end_turn` |
| `TARGET_REQUIRED` | `act{play_card}` / `act{use_potion}` | 这张牌要 `target_index` 但没给；**药水同理**：`target_required:true` 的瓶子不带 `target_index` 就拒（issue #104，`message` 会列出可选目标） | 用 `legal_actions` 里带目标的那条 payload；药水按目标各一条，挑一个 `target_index` |
| `TARGET_INVALID` | `act{play_card}` / `act{use_potion}` | 目标下标越界或怪已死（药水同判据） | 重新 `legal_actions`（按目标各一条，挑活的） |
| `POTION_SLOT_EMPTY` | `act{use_potion,discard_potion}` | 那个格子没药水 | 重读 `player.potions` |
| `POTION_SLOT_FULL` | `act{buy}` / `act{take_reward}` | 腰带满了还买药水（`buy{kind:"potion"}`，文案仍只有「药水槽满了，先喝掉/丢掉一瓶」）；或腰带满时领**药水奖励**（`take_reward`，文案见下）。奖励屏那一条 `rewards.items[]` 里本来就有 `takeable:false` + `blocked_reason`，`legal_actions` 也不会给它的 `take_reward`（#112·C8-05） | **奖励屏那条拒文现在指得动**（#116a）：「药水槽满了，拿不了这瓶药水；先 `discard_potion{"slot":N}` 丢掉一瓶再回来拿（可丢: 0=香蕉, 2=格挡药水）」—— 这一屏同拍就广告着那些 `discard_potion`，直接发即可；`buy` 那条不在奖励屏，先喝/丢一瓶（`discard_potion{slot}`）再去买 |
| `POTION_UNUSABLE` | `act{use_potion}` | 这瓶药现在不能喝（战斗外/要目标） | 换 `discard_potion` 或留着 |
| `POTION_UNDISCARDABLE` | `act{discard_potion}` | 这瓶药不能丢 | 别丢 |
| `HAND_SELECT_OPEN` | `act` | 手牌选择界面开着，别的动作一律拒 | 先 `choose_hand_cards` / `confirm_hand_select` |
| `INTERNAL` | `act` / `get_state` | 模组内部抛异常（日志里有堆栈） | 重试一次；持续出现就把 `mods/spirelink-data/spirelink-debug.log` 附给 issue |

工具层另外还会出现的 code（同样按 `error.code` 分支）：

| code | 哪个工具 | 什么时候 | 怎么办 |
|---|---|---|---|
| `BAD_REQUEST` | `note_set` / `note_get` | `kind` 不在白名单（`event/potion/card/monster/relic/general`）或 `id` 为空 | 照 §11 的白名单传 |
| `GAME_DEAD` | `get_state` / `legal_actions` | **终态信号**：游戏主线程已经死了 —— 崩溃已捕获 **且** 帧计数器停住，再等也不会好（issue #69）。这时 `get_state` 的 `error` 是它，`meta.game_dead=true` | 别重试、别轮询：只能请人**重启游戏**。这期间 `get_state` 仍可读（最后一帧快照），`act` 一律 `NOT_READY` |
| `NOTE_WRITE_FAILED` | `note_set` / `event_note` | 写 `notes.json` 失败（磁盘满/权限） | 别重试刷屏，报告给人 |
| `CARD_NOT_FOUND` | `card_info` / `dev_card` / `dev_use_card` | `uuid`/`id` 找不到那张牌 | 用 `combat.hand[].uuid` 或 `card_info.id` |
| `TERM_NOT_FOUND` | `glossary` | 术语查不到（回包里带 `suggestions`） | 按 suggestions 再查；别凭记忆编 |
| `HAND_FULL` | `dev_card` | 手牌满 10 张（测试模式） | `force:true` |
| `EVENT_NOT_FOUND` | `dev_event` | `id` 为空，或这个类名/全类名不是 `AbstractEvent`（含「类存在但不是事件」） | 用短名（`BigFish`）或全类名；`message` 会带出试过的名字 |
| `ROOM_NOT_FOUND` | `dev_room` / `dev_room{type:"monster"}` | `type` 拼不出房间类（`x` → `XRoom` 找不到） | 用 `monster`/`elite`/`boss`/`rest`/`shop`/`treasure`/`event`/`victory`/`boss_reward`；`message` 里有「试过 XxxRoom」 |
| `POTION_NOT_FOUND` | `dev_potion` | 药水 id/类名/中文名都对不上 | `{list:true}` 先列出全部药水 |
| `RELIC_NOT_FOUND` | `dev_relic` | 遗物 id 对不上（**钥匙 id 不再走这里**：#119·C10-04 起 `RubyKey`/`EmeraldKey`/`SapphireKey` 会被认成「造钥匙宝箱」） | `dev_reward{kind:"relic"}` 或对照遗物 id；想造钥匙用钥匙 id（`Ruby`/`Emerald`/`Sapphire`） |
| `ENCOUNTER_NOT_FOUND` | `dev_encounter` | `ids`/`id` 里有一个是未知遭遇，或构造失败 | `{list:true}` 取全部遭遇 id；这条 issue #82 没列，但代码里真实存在 |
| `CARD_USE_FAILED` | `dev_use_card` | 复制卡牌失败 / `use()` 抛异常且判成 `metadata_only` | 看 `result` + `reason` + `stack`（`reason` 里若写「能量只有 E…绕过了能量检查」是**正常**的 dev 口径，不是失败原因；`energy_before` 带出牌前能量）；这不是卡牌实现的问题就是工具上下文限制 |
| `DEV_TOOL_DISABLED` | 全部 `dev_*` | `test_mode=false`（工具根本没注册，直接调就会撞这条） | 开 `test_mode` 重启，或别用 `dev_*` |

> **核对方式**：`act` 不带 `type` → `BAD_REQUEST`「act 必须带 type」；`act` 带 `state_version:-999` → `STALE_VERSION`；`note_set{kind:"bogus"}` → `BAD_REQUEST`；`event_note` 在非事件房 → `NOT_READY`「当前不在事件房」。四条都能用一条 `curl` 复现。
> 与代码对齐：`grep -o 'String [A-Z_]* = "[A-Z_]*"' spirelink/src/main/java/spirelink/actions/ErrorCodes.java`（15 条）+ `grep -rn 'ACTION_IN_FLIGHT\|"INTERNAL"' spirelink/src/main/java/spirelink/mcp/ToolCatalog.java`。

### 8.2 `NOT_READY` 的十一个细分（看 `message` 的关键片段）

同一个 `NOT_READY` 有很多种完全不同的处境（下表 11 种），处理方式也不同：

| `message` 关键片段 | 谁产生 | 意味着 | agent 该怎么办 |
|---|---|---|---|
| 「正在离开本局（放弃/回主菜单过渡中），<tool> 这一刻没有可执行内容：旧地牢的 ui 与动作表已经不作数，不要照上一帧的广告动手；轮询 get_state 直到 ui=main_menu 且 ready:true（最长约 10 秒）。」 | `get_state` / `legal_actions` / `act`（#123·S2，`<tool>` 是回这句的工具名） | **放弃过渡期**（`abandon_run`/`abandon_mid_run`/`confirm_abandon` 提交后 0.8~1.6 s，`RunExitWindow`，上界 10 s）：旧地牢还在内存里、`mode` 仍是 GAMEPLAY，所以这一拍**既不报旧屏也不广告旧动作** —— 实时帧 `ui:"other"`、`ready:false`、`blocked_ui:"other"`、`awaiting:"transition"`，`act` 一律 `NOT_READY`（**它也带 `blocked_ui`:`other` + `awaiting`:`transition`**，`ui` 取读接口同一拍的实时值；#124·C15-01。缓存兜底帧的 `get_state.meta.ui` 仍是旧快照的屏名 —— 它就是旧快照，但 `ready`/`awaiting`/文案不会骗人） | **只轮询 `get_state`** 到 `ui=main_menu` 且 `ready:true`；别按上一帧的 `map`/`combat` 动作表动手（那正是 QA 拿到 `ILLEGAL_ACTION: ui=main_menu 下不能执行` 的原因） |
| 「存档正在载入（continue_run 已点，战斗/地图还没出来），<tool> 这一刻没有可行动内容：这不是「没点动」，不要重发 continue_run；轮询 get_state 直到 ready:true 或 state_version 变化。」 | `get_state` / `legal_actions` / `act`（#121·C12-01 / #122·C12-01，`<tool>` 是回这句的工具名） | 载入窗口（`continue_run` **或 `embark`** 的点击已交出去、run 屏还没出来，上界 30 s）：动作表不构建，这一拍没有可行动内容，重发只会再点一次按钮。**读操作在这里走 500 ms 短超时**（#123·C14-03，`LOADING_READ_TIMEOUT_MS`；主线程正在建地牢，等满常规预算只会让轮询变慢），所以**每一拍都从缓存快照回一帧**：`ok:false` + `ready:false` + 同一句载入文案 + `error.code:"NOT_READY"`，`meta.awaiting` 被改成 `loading`（缓存里写的是上一拍的 `playing`，不改就是谎话）。`actions:[]`；`get_state` / `legal_actions` 顶层有 `blocked_ui`，`act` 的拒绝带 `ui`/`ready`/`actions`，**并且同样带 `blocked_ui` + `awaiting:"loading"`**（#124·C15-01，三个入口对「在等载入」是同一个答案） | **只轮询 `get_state`**（1~2 s 间隔）到 `ready:true` 或版本变化；别重发 `continue_run`；`awaiting` 此时是 `loading` |
| 「游戏还没进入可读状态（动画未结束）」 | `get_state` | 刚换屏/动画在跑，这一拍没有稳定快照 | 200~500 ms 后再 `get_state` |
| 同一句（`ui`/`blocked_ui` 都是 `combat`） | `get_state` / `legal_actions` | **刚进战斗、还没初始化完**（#125·C16-04，`combat_settling`）：至少一只活怪的 `intent.id` 还是引擎占位 `DEBUG`、手牌空、能量面板没填。`ready:false` + `awaiting:"transition"`（`legal_actions` 的 message 是「ui=combat 游戏还没静止（ready=false）…」）；**4 秒安全上界** `SETTLING_MAX_MS`（坏了/模组怪一直 DEBUG 也不会永远卡） | 等 200~500 ms 再读；**别出牌/别 `end_turn`**（手牌和能量都还没发），也别当卡死 |
| 「不是玩家回合或动画没结束」 | `act` | 敌人回合/过场 | 等；别轮询到爆（间隔 ≥200 ms） |
| 「等了 …ms 主线程仍没有执行这条动作（队列上限 10000ms 已到），这条没有执行」 | `act`（`queue_status:"TIMEOUT"`） | 主线程被占（**动作没执行**），等满了 `COMMAND_TIMEOUT_MS`。回包另带 `waited_ms` / `queue_status` / `version_advanced`（见 §4） | 先看 `version_advanced`：`true` 说明别的动作已生效，**别重发**，先 `get_state`；`false` 才可安全重试（同 `action_id`）。**别和 BUSY 混**：那条是 `ACTION_IN_FLIGHT`，见 §8.3 |
| 「另一个请求正在处理，稍后重试 legal_actions」 | `legal_actions`（**只有它**） | 主线程队列 BUSY —— 读工具被拦，`legal_actions` 没有「动作在飞」语义，所以仍是 `NOT_READY` | 隔一会儿再调；回包 `ready:false`，`ui` 与 `screen_type` 都取自**同一张缓存快照**（描述同一个屏；#119·C10-02 之前 `ui` 写死 `other` 而 `screen_type` 是真屏名，等于把 agent 指向一个不存在的界面）。**`act` 侧的同类处境不是这个码**：单飞队列 BUSY 现在回 `ACTION_IN_FLIGHT`（带 `queue_status:"BUSY"` / `waited_ms` / `version_advanced`，**没有** `retry_after_ms`），见 §8.3 |
| 「游戏渲染已崩溃（…），重启游戏后再继续；`get_state` 仍可读」 | `act`（`meta.render_crashed=true`） | 渲染循环挂了，什么动作都执行不了 | 只能请人**重启游戏**；这期间只读 `get_state` |
| 「ui=X 这一帧还没有可执行动作：屏正在切换（或动作刚开始播放），不是「没有出路」，约 1 秒后重试 legal_actions 即可（ready 因此是 false）」 | `legal_actions` / `get_state`（#120·C11-03） | 门闸说就绪，但这一帧**屏上确实没有可执行动作**；回包 `ready:false` + `blocked_ui`（= 那个 `ui`）；`get_state` 的 `ok` 也是 `false` | **不是死局**：约 1 秒后重试 `legal_actions`；别自己造动作，也别 `act` |
| 「ui=X 游戏还没静止（ready=false），这一帧的动作表不可信，稍后重试 legal_actions」 | `legal_actions` / `get_state`（#120·C11-03） | 门闸本来就没就绪（动画/过场/敌人回合），动作表不可信 | 等 `ready` 变 true（看 `awaiting`），别按空表下结论 |
| 「当前不在事件房」 | `event_note` | 不是等待语义，是**调错地方**了 | 换 `note_set{kind:"event",id:…}`，或等进事件房 |

> **别把 `GAME_DEAD` 当 `NOT_READY` 的一种**：它是**另一个错误码**（终态信号），意思是「主线程已经死了，再等也不会好」（崩溃已捕获 **且** 帧计数停住）。看到 `error.code == "GAME_DEAD"` 或 `meta.game_dead: true` 就停手喊人重启，不要再重试/轮询。

> **核对方式**：战斗中敌人回合时连发 `get_state` 能拿到「游戏还没进入可读状态」那一条（真实回包见 §2）；`act` 在敌人回合发一条合法动作拿到「不是玩家回合或动画没结束」；`legal_actions` 与 `get_state` 并发调用能拿到「另一个请求正在处理」。**载入那条**（#121·C12-01）的核对：主菜单点 `continue_run` 后立刻连发 `get_state`，1~5 s 内应看到 `awaiting:"loading"` + `NOT_READY`「存档正在载入…」，期间 `legal_actions` 不再广告 `continue_run`。
> `render_crashed` 那条要复现渲染崩溃，不现场试——字段与文案见 `ToolCatalog.act()` 里 `CrashGuard.gameCrash()` 分支。

### 8.3 拒绝优先级与 `ACTION_IN_FLIGHT` 细则

**一次 `act` 同时符合多条拒绝条件时，谁先说话**（#121·C12-03）：由 `ToolCatalog.preflightRejectionCode(loading, leavingRun, requested, current, inFlight)` 一个纯函数决定（单测直接钉这张表；`loading` 与 `leavingRun` 都归第一行的 `NOT_READY`），原则是「**谁离执行器越远、谁先说话**」：

| 顺序 | 拒绝码 | 什么时候 | agent 该做什么 |
|---|---|---|---|
| 1 | `NOT_READY` | **存档正在载入**（`awaiting:"loading"`）、**正在离开本局**（`awaiting:"transition"`，放弃过渡）或渲染已崩（后者在预检之前就拦下）：谁都执行不了，重读也读不出新东西 | 只轮询 `get_state`；载入那条**别重发 `continue_run`**，放弃那条等到 `ui=main_menu` |
| 2 | `STALE_VERSION` | 你带的 `state_version` ≠ 当前（旧或未来的）：调用方的快照**不是**当前决策点 | 先重读。屏是否还认识这个动作**不重要** ——「屏也换了」正是版本变了的那个意思，所以这支**永远赢过**屏幕级判断 |
| 3 | `ACTION_IN_FLIGHT` | 版本对、但我们自己上一条动作还在落地（3 秒墙钟护栏 / 单飞队列 BUSY） | 只轮询，别重发 |
| 4 | *（不拦）* → 执行器 | 以上都不成立，交给执行器回 `ILLEGAL_ACTION` / `CARD_NOT_IN_HAND` / `BAD_REQUEST` 之类 | 按具体码处理。执行器的屏幕判断**只有在「版本号已证实是当前」时才有意义** |

所以「先信版本号还是先信屏」不是看运气：版本不对时**一定**先拿到 `STALE_VERSION`（+ 现读的 `actions[]`），不会先吃一个 `ILLEGAL_ACTION`。**任何一条拒绝回包都带 `ui` / `ready` / `actions`**（#121·C12-02，默认 `ready:false`；`STALE_VERSION` 与执行器拒绝会换成新鲜计划，见 §4）；**过渡窗口里还带 `blocked_ui` + `awaiting`，且 `ui` 取读接口同一拍的实时值**（#124·C15-01），三个入口不会各说一个屏。

- **两个来源**（issue #108·C7-02 定码、#125·C16-02 收紧，同一个码、两种处境）：
  1. **3 秒墙钟护栏**：上一个 `act` 回过 `queued`，它还没让 `state_version` 推进。回包里带 **`retry_after_ms`**（护栏还剩多少毫秒放开）。
  2. **单飞队列 BUSY 且真的有动作在飞**：队列被占，而 `ActionInFlight` 里确实压着一条还没落地的动作。回包的 `message` 是「主线程队列里已经有一条动作在排队（单飞队列，不再接收第二条），这条没有执行：从发出到被打回只过了 Nms。」，带 `waited_ms` / `queue_status:"BUSY"` / `version_advanced`，**没有 `retry_after_ms`**（队列占用不是墙钟护栏，没有「还剩多少毫秒」可给）。
  **BUSY 但没有动作在飞 → `NOT_READY`**（#125·C16-02）：主线程只是被过渡/动画那一帧占着，不是我们上一条动作在落地；code 改成 `NOT_READY`，`message` 换成「主线程正忙着执行别的东西（单飞队列被占：过渡/动画那一帧，不是我们上一条动作在落地），这条没有执行：…」。**判据是 `ActionInFlight.blocked(current)` 有没有东西**，不是「队列忙不忙」。
  两种的处置一样：**只轮询**，别重发。**并发两条 `act` 的结论口径（#120·C11-07 + #125）**：同一个 `state_version` 上连发两条，第二条**要么** `ACTION_IN_FLIGHT` + `queue_status:"BUSY"`（第一条真的在落地）**要么** `NOT_READY`（第一条已经落地/没在飞，主线程只是忙）—— 两种都不是 `BAD_REQUEST`，更不会两条都执行；**别把 `NOT_READY` 这里当成新的 bug**。**#112·C8-07 复核（对着 `ToolCatalog.act()` 与 `queueMissPayload()`）**：`retry_after_ms` 由 `ActionInFlight.retryAfterMs()` 写进回包，只有①那条墙钟护栏分支有；②的 `message` 里明说「这是队列被占用，不是 3 秒墙钟护栏，所以回包里没有 retry_after_ms」，单测 `ActQueueMissMessageTest` 也断言了 `payload.has("retry_after_ms") == false`。

- **怎么产生**：`act` 把动作交给游戏后，会**先自己等最多 10 秒**（`ToolCatalog.ACT_STABLE_TIMEOUT_MS = 10_000`）看 `state_version` 动没动；
  10 秒还不落地才回 `outcome:"queued"`，同时把那一版 `state_version` 记成「在飞」，并让 `get_state` 报 `ready:false`。
  所以 **agent 感知的最坏等待 ≈ 10 s + 3 s = 13 s**（动作没落地时别以为卡死，先看 `outcome`）。
- **期间会发生什么**：任何新的 `act`（换 `type`、换 `action_id` 都一样）都被拒，`message` 类似
  「上一个动作还在执行（queued end_turn，已等 1200ms，state_version 151）。墙钟护栏 3000ms 到点就自动解除（还剩 1800ms），期间请轮询 get_state 直到 ready 变 true 或版本推进，不要重发。」
  **护栏这一种**回包里**直接给了 `retry_after_ms`**：还差多少毫秒自动放开（就是「还剩 1800ms」那个数），用它定轮询间隔，别再从文档里猜；**队列 BUSY 那一种没有这个字段**（见上「两个来源」）。
- **什么时候自动解除**：
  1. `state_version` 推进（下一拍 `get_state` 看到新版本）；**或**
  2. 墙钟 **3 秒**到点（`ActionInFlight.TIMEOUT_MS = 3000`，日志里有一行「ACTION_IN_FLIGHT 墙钟超时解除」）。
     这 3 秒是**唯一真源**：`ToolCatalog` 里那个 30 秒常量是只写不读的死代码，已在 issue #54 删除，别再引用「30 秒安全阀」。
     **「真释放」**：到点即解除，不依赖「下一次别的调用顺带把它清掉」—— 到点之后的第一条 `act` 立刻能过（如果还在同一个版本）。
- **正确做法**：只轮询 `get_state`（`legal_actions` 也可以），**别换 id 重试**。版本一推进立刻用新版本发下一条。
- **为什么这么严**：重复发送会把两次点击叠到同一个对话框/动画上，历史上直接冻住渲染循环（`skip_card`、GremlinWheelGame、篝火都踩过）。

> **核对方式**：起一局战斗，对一张动画较慢的牌连发两次 `act`（第一条会 `queued`），第二条就会拿到 `ACTION_IN_FLIGHT`（护栏来源）且回包里有 `retry_after_ms`；等它归零后再发同一条动作会发现保护已解除（日志里搜「墙钟超时解除」）。**队列 BUSY 来源**的核对：并发两条 `act`，被拒那条的 `error.code` 应是 `ACTION_IN_FLIGHT`、`queue_status:"BUSY"`、**没有** `retry_after_ms`。
> 常量核对：`grep -rn 'TIMEOUT_MS' spirelink/src/main/java/spirelink/util/ActionInFlight.java spirelink/src/main/java/spirelink/mcp/ToolCatalog.java` → 只有 `ActionInFlight.TIMEOUT_MS = 3000`，`ToolCatalog` 里已经没有 `IN_FLIGHT_TIMEOUT_MS` / `queuedAt` / `queuedVersion`。

### 8.4 传输层错误（JSON-RPC 与 HTTP 状态）

| `error.code` | HTTP | 什么时候 | 怎么办 |
|---|---|---|---|
| `-32001` | `404` | `Mcp-Session-Id` 不认识（游戏重启过 / 会话被 `DELETE`） | 重新 `initialize` 换会话号 |
| `-32001` | `401` | 配了 `token` 但 `Authorization` 不对或没带 | 加 `Authorization: Bearer <token>`（§12） |
| `-32602` | `200` | `tools/call` 缺 `params.name`，或工具名不存在 | 先 `tools/list` 对名字 |
| `-32601` | `200` | 未知 `method`（只支持 `initialize` / `ping` / `tools/list` / `tools/call` / `notifications/*`） | 用支持的方法 |
| `-32700` | `400` | 请求体不是合法 JSON | 检查 body（必须是单个 JSON 对象，≤1 MB） |
| `-32603` | `500` | 服务端内部错误 | 看 `spirelink-debug.log` |
| `-32600` | **`413`** | 请求体超过 **1048576 字节（1024 KiB）**：读满就拒，这条请求**没有执行** | 缩请求体（分页 / 裁剪样本 / 只发改动字段）**换新的 `id`** 重发；别原样重试——原样重发还是 413。文案里带 `1048576`，`message` 形如「请求体超过上限 1048576 字节（1024 KiB）：已读 N 字节就被拒绝了…这是客户端错误（HTTP 413）」 |

> **核对方式**：`curl -s -H 'Mcp-Session-Id: deadbeef-…' …` → `404 -32001`；`curl -s … -d '{"jsonrpc":"2.0","id":1,"method":"resources/list"}'` → `-32601`；`curl -s … -d '{oops'` → `400 -32700`；超大 body 用 `head -c 1100000 /dev/zero | tr '\0' 'a'` 拼进 `-d` → `413 -32600` + 文案含 `1048576`。
> `413` 是 issue #71 定的：以前这里回 `500 -32603`，客户端会以为是服务端坏了而原样重试同一个 1 MB+ 的 body。

## 9. 三个特殊情形

**`ready: false`**：`get_state` 依然返回**最后一帧稳定快照**（字段齐全，只是不新鲜），`legal_actions` 会带 `hints.last_error`，`act` 一律 `NOT_READY`。等 200~500 ms 再看。

**`hints`**：解释「现在为什么不能动 / 刚才那一下为什么被拒」的一小块（也会用来提醒「便签是历史」）。**什么时候整块出现**（issue #66/#68 + #100 + #124 之后的规则）：

- `ready=false`（在等动画/敌人回合/过场）；**或**
- 上一次 `act` 被拒（`rejectedAtMs > 0`，直到下一次 `applied` 才清）；**或**
- `ui=char_select`（那一屏常驻：`hints.char_selected` / `hints.next` 回答「人已经选好了吗」，issue #49）；**或**
- **这一拍投递了新的便签正文**（`hints.note_history`，#124·C15-02 / #127）：只要有对象这一帧是 `note_state:"new"`（本局第一次投递这个版本，含 `force_notes:true` 那次），就附一句「便签是历史、当前数值以同回包字段为准」。**`"seen"` 的帧没有这句**（正文本来就没重复，解释也不必重复）——所以「这拍没 hints」不代表没便签，看对象的 `note_state` 更准。所以**`ready:true` 也可能有 `hints`**，别只看这一块判断「是不是出事了」；**或**
- **hints 自己带了「异常但 ready」的标记**（issue #100·C6-04）：`map_no_reachable` / `suggest_note` / `unknown_ui` / `event_missing`（键存在且值不是 `null` 才算；显式 `null` 按「没有」处理）。这四种都是「画面是 `ready=true` 的决策点，但光看 `ui` 说不清出了什么事」，所以那几拍 hints 必须跟着一起给 —— 以前它们被 `ready:true` 过滤掉，`ui=map` 无路可走、`ui=victory` 的 `suggest_note` 都只能靠显式 `include:["hints"]` 才看得到。

**`ready:true`、没有未消化错误、hints 里也没有上面那些标记（四个「异常但 ready」标记 + `note_history`）时，`hints` 整个块不出现**。旧版（issue #66/#68 之前）它每一拍都挂 4 个存活探针，结果「有 hints」不再等于「出问题了」；现在存活探针只**装饰已经存在的 hints**，自己绝不造块。

> **例外**：`include:["hints"]` 显式点名时一定回（实测那一拍 15 个键：存活探针 + 场景字段，没有任何 `last_error`）—— 点了名就必须答，否则你分不清「没事」和「模组忽略了我的 include」。

所以：**看到 `hints` 就当有事要看**（先扫 `last_error` / `grid_*` / `game_dead`；**只有 `note_history` 一条不算事**，那是便签历史提醒）；`exclude:["hints"]` 也能真删掉它（不会再被探针复活）。

**这五个诊断字段每拍现读现算（issue #88）**：`act_seq` / `last_error` / `last_error_seq` / `last_error_age_ms` / `last_error_fresh` 都由服务端在**回包那一刻**从计数器重新算，`get_state` 与 `legal_actions` 同拍一致（`last_error_age_ms` 是墙钟差，两次调用会差几毫秒）。以前它们冻结在缓存的快照里 —— 于是重发同一个失败后，`get_state` 还在报上一次的 `act → BAD_REQUEST`（`last_error_age_ms` 卡在 270），看不出其实已经换成了 `STALE_VERSION`。现在不会再出现这种「报了旧错」。

逐字段（2026-11-03 真机 `get_state` 回包核对，`docs/samples/state-event.json` 就是这一拍）。**存活探针**（`frames` `beats` `ms_since_frame` `ms_since_beat` `frames_stalled*` `thread_stalled`）是服务端**每次回包现读现刷**的：`get_state` 和 `legal_actions` 都给实时值（issue #84 以前 `legal_actions` 那份是缓存旧值，已修）；它们只在 `hints` 已经存在时才被写进去：

| 字段 | 出现条件 | 含义 |
|---|---|---|
| `act_seq` / `last_error_seq` | 恒有（有 `hints` 时） | 服务端收到过多少次 `act` / 记过多少次错误。两个数相等只说明「这条错误记在最后一次 `act` 的计数位上」；**它是不是「刚才那次 act 的错」还要看下面 `last_error_fresh` 的三个条件**（#119·C10-05） |
| `last_error` | 本进程记过错误 | 最近一条错误。**两种前缀**：参数级拒绝写 `act → CODE`（如 `act → BAD_REQUEST`，缺字段/类型不对，动作没走到执行器）；动作级拒绝写 `<动作 type> → CODE`（如 `choose_option → STALE_VERSION`、`end_turn → ACTION_IN_FLIGHT`）。读状态失败时也会出现 `get_state → NOT_READY` / `get_state → GAME_DEAD`。**注意 `type` 打错字也算动作级**：`type:"no_such_action"` 拿到的是 `no_such_action → UNKNOWN_ACTION_TYPE`（前缀是你传的那个串，不是 `act`）——别按「前缀一定是 `act`」去匹配（issue #97·C3-05）。它是**历史**，新鲜度看 `last_error_fresh` |
| `last_error_age_ms` | 同上 | 距离那条错误过了多少毫秒（现场 `467`） |
| `last_error_fresh` | 同上 | `true` = **这条错误就是刚才那次 `act` 留下的，而且现在仍然算数**。三个条件同时成立（`ErrorFreshness.isFreshFor`，issue #119·C10-05）：① `last_error_seq == act_seq`；② 它是**动作**的错误，**不是一次读状态失败**（`last_error` 前缀是 `get_state` / `legal_actions` 的一律 `false`）；③ 从记下这条错误之后 **`state_version` 没有再推进**过（`last_error_at >= lastDecisionChangeAt`，同一毫秒算新鲜）。所以排队期间 `get_state` 记的 `NOT_READY` 点不亮它；那条排队动作真落地、世界往前走之后，旧错误立刻变历史。`false` = 陈年旧账。**旧文档「等价于 `last_error_seq == act_seq`」已作废**（那正是一次 `queued` 之后 `last_error_fresh` 连着几十秒为 `true` 的原因） |
| `screen` | 恒有 | 游戏原始的 `AbstractDungeon.screen` 名字（`NONE`/`SHOP`/`GRID`…），和 `meta.screen_type` 同源 |
| `gam_phase` | 恒有 | `AbstractDungeon.actionManager.phase`（`WAITING_ON_USER`/`EXECUTING_ACTIONS`…），`actionManager` 为 `null` 时是字符串 `"null"` |
| `frames` | 恒有 | 渲染循环累计帧数（存活探针） |
| `beats` | **只有 `get_state`** | 动作队列心跳计数；`frames` 涨而 `beats` 不涨，说明是动作线程停了 |
| `ms_since_frame` / `ms_since_beat` | **只有 `get_state`** | 距今多少毫秒没出新帧 / 没心跳（现场都是 `0`） |
| `end_turn_enabled` | 恒有 | 「结束回合」按钮当前可不可按（战斗里最直接的「能不能收手」信号） |
| `monsters_basically_dead` | 恒有 | 怪物基本死光（等结算，别急着出牌） |
| `keywords_unseen` / `needs_inspect` | 恒有 | **目前恒为 `[]`**：占位数组，不是「没有关键词要看」的判断依据 |
| `grid_selected` / `grid_num` / `grid_wanted` | 仅 `ui=grid` | 网格已选几张 / 总张数（`numCards`）/ 判据取到的目标数（读不到 `numCards` 时是 `-1`）。**没有 `grid_num_to_select`**（#112·C8-06）：原版 `GridCardSelectScreen` 只有 `numCards`、没有 `numCardsToSelect` 字段，代码只在字段存在时才写那个键，所以实测永远看不到它 —— 「要选几张」看 `grid_wanted` |
| `grid_can_confirm` / `grid_preview` | 仅 `ui=grid` | 现在能不能确认（判据与 planner 同一套）/ 预览中的牌 id（没有则 `null`） |
| `map_no_reachable` | 仅地图且**无路可走** | `true` = 这一层**既没有能走的节点、Boss 也不可进**（#100 收紧：末行只剩 Boss 图标时 Boss 算入口，不算「无路可走」）。这个标记会让 hints 在 `ready:true` 那拍也保留 |
| `frames_stalled` / `frames_stalled_ms` | 主循环停摆 | 超过 5 秒没新帧才出现（`ms` 是停了多久） |
| `thread_stalled` | 心跳停摆 | 超过 3 秒没心跳才出现 |
| `game_dead` | 判死 | `true` = 主线程已死（崩溃 + 帧停），和 `meta.game_dead` 同源；看到它就喊人重启，别再轮询（issue #69） |
| `potions_full` | **仅 `ui=combat_reward` 且腰带满**（#112·C8-03） | 这一屏上的药水奖励拿不了，先丢一瓶（#116a 起这一屏同拍就广告 `discard_potion{slot}`，直接发即可）。**不是「腰带满就一定有」**：`shop` / `combat` 等别的屏不写它；而且它**不是**上面那四个「ready 帧也保留 hints」的标记 —— 正常一拍（`ready:true`、没有未消化错误）`hints` 整块会被过滤掉，所以默认读不到它。要可靠判断腰带满：看 `rewards.items[].takeable:false` + `blocked_reason`，或 `player.potions`，或显式 `include:["hints"]` |
| `unknown_ui` | `ui=unknown:*` | 模组还没映射这个画面（不变式 I2） |
| `event_missing` | 事件房但拿不到事件 | 房间在、`event` 为空 |
| `match_game` | GremlinMatchGame | 翻牌小游戏进行中 |
| `char_selected` / `next` | 仅 `ui=char_select` | 已选角色（没选则 `null`）/ 下一步该干什么的提示 |
| `suggest_note` | 仅 `postmortem` | 建议记一条什么（写不写由 agent 决定） |
| `note_history` | **只有这一帧真有 `note_state:"new"` 的对象时才出现**（#127）：本局第一次投递某个便签版本（或 `force_notes:true`）那一拍有，之后投 `"seen"` 的帧没有 | 一句话：「note / notes 是历史便签（agent 自己早先写的文字，可能含旧血量等数值），不是当前状态；当前数值一律以同回包的字段为准（怪物看 current_hp，玩家看 player.hp 等）。」**它本身不是异常标记**，只是提醒你别拿便签当实时读数 |

> **`hints.grid_*` 的实际集合就是上面这五个**（`grid_selected` / `grid_num` / `grid_wanted` / `grid_can_confirm` / `grid_preview`，且只在 `ui=grid` 写；`transform` 屏不写）。旧文档列过的 **`grid_num_to_select` 不存在**（#112·C8-06 实测 + 原版类字段核对），别按它写客户端。

`legal_actions` 回包里的 `hints` 也是同一套字段，但**只有在 `get_state` 的同一拍也有 `hints` 时才存在**（它来自同一份快照），而里面的存活探针由服务端在回包前现读现刷（issue #84b），不会给你旧数字。

真实 `hints`（`ui=event`，QA 现场刚吃过一次 `STALE_VERSION`，所以是「有事要说」的那一版；`docs/samples/state-event.json` 同一拍）：

```json
{
  "keywords_unseen": [],
  "needs_inspect": [],
  "act_seq": 48,
  "last_error_seq": 48,
  "screen": "NONE",
  "last_error": "choose_option → STALE_VERSION",
  "last_error_age_ms": 467,
  "last_error_fresh": true,
  "gam_phase": "WAITING_ON_USER",
  "frames": 47594,
  "end_turn_enabled": false,
  "monsters_basically_dead": false,
  "beats": 47594,
  "ms_since_frame": 0,
  "ms_since_beat": 0
}
```

> **核对方式**：`get_state` 前先故意发一条旧版本的 `act`（`state_version` 减 1）拿 `STALE_VERSION`，再 `get_state` → `hints` 里一定有 `last_error` + `last_error_fresh:true` + `last_error_age_ms`（现场 467）；随后成功 `applied` 一次再读，整块会**消失**（`ready:true`、没有未消化错误、也没有 §9 那四个异常标记时不该再有 `hints`）。

**`memory`**：跨局记忆，`{context_notes[], recent_notes[], counts{}, total, run_history[], run_history_count}`；在 `char_select`/`neow`/`main_menu` 还多一个 `briefing`（含 `death_causes` 最近 3 局死因聚合）。

便签类工具（`note_set` / `note_get` / `notes_list` / `event_note`）单独一节：[§11 便签工具族](#11-便签工具族)。

## 10. 样本索引

`docs/samples/` 里 40 个文件，**每个文件 ↔ 产生它的调用/界面**一一对应。索引与目录是否一致，用
`python scripts/capture_samples.py --check-index` 自检（不一致会列出文件名，退出码 2）。

### 10.1 `get_state` 样本（12 个）

| 文件 | 这一份是哪一屏 | 备注 |
|---|---|---|
| `state-main_menu.json` | `ui=main_menu` | 只有 `meta` + 常驻区块；`player` 是 `{}` —— 主菜单没有玩家对象，这是**真实值**，不是抓漏了 |
| `state-char_select.json` | `ui=char_select` | 带 `char_select` 区块（2026-11-02 抓） |
| `state-map.json` | `ui=map` | 带整幕 `map` 区块 |
| `state-combat.json` | `ui=combat` | 2026-11-03 按 issue #48 重抓过，**文件名与内容一致**（`meta.ui=combat`、带 `combat` 区块）。旧版这条写「内容其实是 `shop_room`」，已作废 |
| `state-combat_reward.json` | `ui=combat_reward` | 带 `rewards` 区块 |
| `state-grid.json` | `ui=grid` | 选牌网格 |
| `state-shop.json` | `ui=shop`（货架已开） | 带 `shop` 区块 |
| `state-shop_room.json` | `ui=shop_room`（商人屏没开） | 与 `shop` 的区别见 §6.1 |
| `state-rest.json` | `ui=rest` | 营火 |
| `state-event.json` | `ui=event`（BigFish） | 2026-11-03 QA 现场重抓：`meta` 是完整的 23 键（含 `character_source`/`room_cleared`），`hints` 是「有事要说」的那一版（带 `last_error`），字段逐条见 §9 |
| `state-neow.json` | `ui=neow` | 带 `event` + `neow` 两个区块（2026-11-02 抓） |
| `state-treasure.json` | `ui=treasure` | 宝箱房 |

> **样本的 `meta` 会落后于实现**（issue #79）：`character_source` / `room_cleared` 是后加的键，只有重抓过的样本才有。
> 现在：`state-combat.json` 23 键、`state-event.json` 23 键、`state-char_select.json` 22 键（有 `character_source`、无 `room_cleared`）；**注意新构建的 `meta` 又多了一个常驻键 `game_dead`（issue #69），重抓后是 24 键**，
> **剩下 9 份（main_menu / map / combat_reward / grid / shop / shop_room / rest / treasure / neow）只有 21 键**。
> 缺的那两个键**以 §2 的字段表和现场回包为准，别把样本当权威**；补齐必须在对应那一屏重抓（会动游戏现场）。**一条命令跑完**——从主菜单起一局、正常玩过去，脚本每 2 秒看一眼，撞上名单里的屏就覆盖落盘，全程只调 `get_state`/`legal_actions`，不执行任何动作：
> ```bash
> python scripts/capture_samples.py --watch --ui main_menu,map,combat_reward,grid,shop,shop_room,rest,treasure,neow --force --seconds 1800
> ```
> （**不带 `--watch` 时它只抓当前这一屏**，`--ui` 只是「要不要写这一屏」的过滤器 —— 所以想一次补齐 9 屏只能 `--watch` 边玩边抓，见 §14。）
> 除这两个键外，这 9 份的内容（区块结构、`player`、`event`、动作条数）都仍然有效。

### 10.2 `legal_actions` 样本（12 个）

| 文件 | 这一份是哪一屏 | 动作条数 |
|---|---|---|
| `actions-main_menu.json` | `ui=main_menu`（**有存档**那一版） | 2 = `abandon_run` + `continue_run`（**无存档**时换成 `open_mode_select`，见 §6 `ui=main_menu`） |
| `actions-char_select.json` | `ui=char_select`（已高亮角色，`actions[0]` 是 `embark`） | 11 = `embark` + 4 张角色卡 + 5 档进阶 + `back` |
| `actions-map.json` | `ui=map` | 2 = `choose_map_node` + `abandon_mid_run`（有可达节点时**不广告 `proceed`**；地图动作新语义见 §6.2） |
| `actions-combat.json` | `ui=combat` | 17 = 16 张能打的牌 + `end_turn` |
| `actions-combat_reward.json` | `ui=combat_reward` | 2 = `take_reward` + `proceed` |
| `actions-grid.json` | `ui=grid`（营火 smith 的升级网格） | 17 = 16 张可选牌 + `cancel_grid`（这个网格 `canCancel=true`，所以有 cancel；确认式网格不会有，见 issue #96） |
| `actions-shop.json` | `ui=shop` | 9 = 5 张买得起的卡 + 3 瓶买得起的药水 + `leave`。**这一拍本来就不该有 `purge_card`**：净化要 100 金而当时只有 87 金（`shop.purge={available:true,cost:100,affordable:false}`；**100 是本局第二次净化的价**，首次 75、每次 +25，见 §6.4）；同理买不起的商品不给 `buy`。现场另一拍 11 条是「商品更多 / 净化负担得起」，不是这一份漏抓。**药水腰带满时不会有药水行**（#103）：`buy{kind:"potion"}` 只在有空槽时广告 —— 满腰带那一拍里买得起的药水直接不列（点了必被 `POTION_SLOT_FULL` 拒的动作不该广告），喝掉一瓶后它重新出现 |
| `actions-shop_room.json` | `ui=shop_room` | 2 = `open_shop` + `leave` |
| `actions-rest.json` | `ui=rest` | 1 = `smith`。**不是「只剩 `proceed`」**：这一拍满血（test_mode 999/999）所以不给 `rest`，也没有吉莉娅/和平烟斗所以不给 `lift`/`toke`；**选项用掉一次之后才会只剩 `proceed`** |
| `actions-event.json` | `ui=event`（BigFish） | 3 = 三个 `choose_option`；2026-11-03 现场重抓（带 `hints`） |
| `actions-neow.json` | `ui=neow` | 2 = `choose_neow_reward` + `proceed`。第一条的 label「选项：对话」**不是奖励**——那是涅奥开场那句对白的按钮（`option.msg` 原文就叫「对话」），点它只推进对白；只给一个选项时还会附带 `proceed`「继续」，语义见 §7 `neow` |
| `actions-treasure.json` | `ui=treasure` | 1 = `open_chest`。**`proceed` 不是常驻动作**：只有宝箱开完、奖励领净、`canProceed()` 为真时才进 advertised 集合（QA 第二轮实测那一拍只有 `open_chest`；手动发 `proceed` 能跑通，但那是绕开 planner，issue #92 在定要不要让它也 advertised） |

### 10.3 `act` 样本（14 个）

> ⚠ 这一组 `act-ok-*.json` 都抓于 **`log` 字段上线之前**（回包里没有 `log`）：**全部待重抓**（重抓要真发 `act`，用 `python scripts/capture_samples.py --act`，会真的操作游戏；不能凭空补），字段以 §4 为准。

| 文件 | 动作类型 | `outcome` / 错误码 |
|---|---|---|
| `act-ok-play_card.json` | `play_card` | `applied` |
| `act-ok-end_turn.json` | `end_turn` | `applied` |
| `act-ok-use_potion.json` | `use_potion` | `applied` |
| `act-ok-choose_map_node.json` | `choose_map_node` | `applied` |
| `act-ok-choose_option.json` | `choose_option` | `applied` |
| `act-ok-leave.json` | `leave` | `applied` |
| `act-ok-open_chest.json` | `open_chest` | `applied` |
| `act-ok-open_shop.json` | `open_shop` | `applied` |
| `act-ok-proceed.json` | `proceed` | `applied` |
| `act-ok-smith.json` | `smith` | `applied`（随后进 `ui=grid`） |
| `act-ok-take_reward.json` | `take_reward` | `applied` |
| `act-ok-cancel_grid.json` | `cancel_grid` | `applied` |
| `act-reject.json` | `play_card` | `rejected` + `NOT_READY` |
| `act-bad-request.json` | `no_such_action` | `rejected` + **`UNKNOWN_ACTION_TYPE`**（文件名是历史叫法，不代表错误码是 `BAD_REQUEST`） |

### 10.4 其它（2 个）

| 文件 | 产生它的工具 |
|---|---|
| `card_info.json` | `card_info{uuid}` → 字段见 §13.1 |
| `glossary.json` | `glossary{term:"格挡"}` → 字段见 §13.2 |

### 10.5 还缺的样本

| 缺什么 | 为什么没有 | 怎么补 |
|---|---|---|
| `state-boss_cleared.json` / `actions-boss_cleared.json` | 只存在「Boss 死 → 还没点开地图」那一拍 | `python scripts/capture_samples.py --watch --ui boss_cleared` |
| `state-card_reward.json` / `actions-card_reward.json` | 选卡屏停留短，按 `meta.ui` 去重时容易错过 | `--watch --ui card_reward` |
| `state-boss_reward.json` / `actions-boss_reward.json` | 要打赢 Boss 再开 Boss 遗物屏 | `--watch --ui boss_reward`（或 test_mode 下 `dev_room{type:"boss_reward"}` 造屏） |
| `state-hand_select.json` / `actions-hand_select.json` | 要触发弃牌/消耗类选牌 | `--watch --ui hand_select` |
| `state-death.json` / `state-victory.json`（`postmortem`） | 要真死一次 / 通关一次 | test_mode `dev_room{type:"victory"}` 造胜利；死亡只能真打一局 |
| `state-match_game.json` | 只有 GremlinMatchGame 事件才会出现 | test_mode `dev_event{id:"GremlinMatchGame"}` 之后 `--watch` |
| `actions-mode_select.json` | 主菜单「模式面板」停留短 | `--watch --ui mode_select` |
| `note-*.json`（便签四个工具的回包） | 写便签会改 `mods/spirelink-data/notes.json`，抓样本要单独的运行环境 | 在专用环境按 §11 的 curl 片段跑一遍并落盘；**别拿人正在用的 notes.json 做实验** |

> **核对方式**：`python scripts/capture_samples.py --check-index` → 「文档 N 条 / 目录 M 个 / 不一致 0」；`ls docs/samples/*.json | wc -l` → 40。

## 11. 便签工具族

四个工具，一次只操作**一条**便签（没有批量、没有全量重置 —— 全量重置只在人类界面的便签面板里，issue #47）：

| 工具 | 参数 | 成功回包 | 备注 |
|---|---|---|---|
| `note_set` | `{kind, id, text}` | `{ok, key, text}` | `text` **必填**（issue #59），三种状态：正常字符串 → 写入；`text:""` → **删除**这条（回包 `text: null`）；漏传 / `null` / 不是字符串 → `BAD_REQUEST`，文案明说「要删除请显式传 `text:""`」—— **绝不会再把漏传当成删除** |
| `note_get` | `{kind, id}` | `{ok, key, text}` | 没便签时 `text: null`（**不是**空字符串） |
| `notes_list` | `{kind?, sort?}` | `{ok, notes, count, sort, items[]}` | `sort` 缺省 `recent`（`updated_at` 降序）；`name` 按 `kind:id` 字典序（旧写法 `id` 等价）；`inputSchema` 里已列全 |
| `event_note` | `{text}` | `{ok, id, note}` | 等价 `note_set{kind:"event", id:当前事件}`，**只在事件房可用**。同样三态（必填 / `""` 删除 / 非法 `BAD_REQUEST`），但**「当前不在事件房」的 `NOT_READY` 先判**——不在事件房时别指望拿到 text 校验的报错 |

### 11.1 真实回包

没有便签时的 `notes_list`（2026-11-03 现场抓，当时 `notes.json` 为空）：

```json
{"ok":true,"notes":{},"count":0,"sort":"recent","items":[]}
```

`note_get` 查一条不存在的（注意 `text` 是 `null`，不是 `""`）：

```json
{"ok":true,"key":"event:__issue48_nonexistent__","text":null}
```

`note_set` 给了非法 `kind`（`ok:false`，`error` 是**对象**，`isError:true`）—— issue #86 之后的形状：

```json
{"ok":false,"error":{"code":"BAD_REQUEST","message":"note_set 需要 kind（[event, potion, card, monster, relic, general]）与 id"}}
```

`event_note` 在非事件房：

```json
{"ok":false,"error":{"code":"NOT_READY","message":"当前不在事件房"}}
```

> 这两段是**旧 jar 抓的**，当时是 `{"ok":false,"error":"BAD_REQUEST","message":"…"}`（字符串码 + 顶层 `message`）。issue #86 把 `error` 统一成对象、`message` 搬进 `error.message`，所以现在照上面第二个形状读；`code` 名一个没变。

成功写一条（结构按实现；样本待抓，见 §10.5）：

```json
{"ok":true,"key":"card:Strike_R","text":"前期核心，优先升级"}
```

`notes_list` 有内容时的形状：

```json
{
  "ok": true,
  "notes": {"card:Strike_R": {"text": "前期核心，优先升级", "updated_at": 1793639444}},
  "count": 1,
  "sort": "recent",
  "items": [
    {"key": "card:Strike_R", "kind": "card", "id": "Strike_R", "text": "前期核心，优先升级", "updated_at": 1793639444}
  ]
}
```

- `notes` 是**原始字典** `{key: {text, updated_at}}`；`items` 是**排好序的数组**，`count == items.length`。
  要顺序用 `items`，要按 key 查用 `notes`。
- `updated_at` 单位是**秒**（`System.currentTimeMillis()/1000`），不是毫秒。
- `sort` **已经在 `inputSchema` 里**：`{type:"string", enum:["recent","name"], default:"recent"}`，description 里写明「旧写法 `id` 等价于 `name`」。回包 `sort` 只回显 `recent` / `name` 两个值（传 `id` 也回显 `name`）。
  （`sort` 现在就在 `inputSchema` 里，`tools/list` 能直接自检；回包只回显 `recent` / `name`。）

> **核对方式**：`curl -s -X POST http://127.0.0.1:8731/mcp -H "Content-Type: application/json" -H "Mcp-Session-Id: $SID" -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"notes_list\",\"arguments\":{}}}"`；把 `name` 换成 `note_get` 并给 `{"kind":"event","id":"__nope__"}` 就能看到 `text:null`。

### 11.2 `kind` 白名单与 `id` 规范

`kind` 只有六个值，其它（含旧设计稿里的 `scope:"run"/"lesson"` 写法）一律 `BAD_REQUEST`：

| `kind` | `id` 从哪来 | 例子 | 便签会自动出现在 |
|---|---|---|---|
| `event` | `get_state.event.id`（事件类简单名） | `BigFish` | `event.note` + `event.note_state`（#127；三态见 §11.4） |
| `card` | `card_info.id` / `combat.hand[].id`（cardID） | `Strike_R` | `combat.hand[]/draw_pile[]/discard_pile[]/exhaust_pile[].note` + `.note_state`、`card_info.note` |
| `potion` | `player.potions[].id`（药水类名） | `FirePotion` | **`potions.slots[]` 与 `player.potions[]`**（#125 起可读）的 `note` + `note_state`（#127）；空槽没有 `id`，不带 note 键 |
| `monster` | `combat.monsters[].id` | `FuzzyLouseNormal` | `combat.monsters[].note` + `.note_state`（#127 三态） |
| `relic` | `player.relics[].id` | `BurningBlood` | `player.relics[].note` + `.note_state`（#127 三态） |
| `general` | 自定义 | `run-1` | `memory` 里的便签池 |

`key` 恒为 `kind:id`（`Notes.key()`）。**`note` / `note_state` 每帧都在**（有 `kind:id` 的对象）—— `"none"` 和 `"seen"` 都是 `note:null`，看 `note_state` 区分；别把「值为 `null`」当成「字段缺失」。

> **核对方式**：给一只怪写便签 → 第一拍 `note_state:"new"` + 全文，第二拍 `note_state:"seen"` + `note:null` + `note_pushed:true`；`get_state{force_notes:true}` 又变回 `"new"`。没有便签的怪是 `note_state:"none"` + `note:null`。

> **核对方式**：`note_set{kind:"bogus",id:"x",text:"y"}` → `BAD_REQUEST`，`message` 里直接列出白名单全文。

### 11.3 排序、截断、容量

| 规则 | 细节 |
|---|---|
| 默认排序 | `updated_at` **降序**（新的在前），回包 `sort:"recent"` |
| 按 key 排序 | `sort:"name"`（旧写法 `sort:"id"` 仍接受，等价），按 `kind:id` 字典序；回包 `sort` 字段统一回显 `recent` / `name` |
| 按类过滤 | `notes_list{kind:"card"}` 只列 `card:` 前缀 |
| 单条长度 | **2000 字**上限，超出**静默截断**（不报错、不提示）；`note_set` 回包的 `text` 是截断后的值 |
| 文件上限 | `notes.json` 超过 **256 KB** → 整个文件被忽略并改名成 `notes.json.bad`（便签等于全丢），日志里有 warn |
| 落盘 | 先写 `notes.json.tmp` 再 rename（原子替换）；失败 → `NOTE_WRITE_FAILED` |
| 立刻生效 | `note_set` / `event_note` 会让状态缓存失效，下一次 `get_state` 就能看到新便签 |

> **核对方式**：`note_set` 一条 3000 字的文本，回包 `text` 只有 2000 字；`ls -l mods/spirelink-data/notes.json` 看体积。

### 11.4 自动去重（同一局只推一次）

`get_state` 会**自动**把便签带出来（`memory.recent_notes` / `memory.context_notes` /
`memory.briefing.general_notes` / 对象上的 `note` / `hints.suggest_note`）。

**自动带出来的便签都标了来源是「历史」**（#124·C15-02）：实体（`combat.hand[]`/三堆/`player.relics[]`/`potions.slots[]`/`player.potions[]`/`combat.monsters[]`）与 `event` 上有便签时，带 `note_history:true` + `note_updated_at`（写入时刻 = 便签版本，epoch 秒）+ `note_key`（`kind:id`，可直接喂 `note_get` 复核）。同回包 `hints.note_history` 再给一句总说明：「note / notes 是历史便签（agent 自己早先写的文字，可能含旧血量等数值），不是当前状态；当前数值一律以同回包的字段为准（怪物看 current_hp，玩家看 player.hp 等）。」——**拿便签里的数字当实时值是最常见的误读**，看到 `note_history:true` 就回到 `current_hp` / `hp` / `energy` 这些字段。

**正文只投递一次：`note_state` 说这一帧是哪一种**（#127，取代 #125 的「每帧都带全文」）：

| `note_state` | `note` | 其余 `note_*` | 含义 |
|---|---|---|---|
| `"none"` | `null` | **都不出现** | 这个对象没有便签（键在、值空） |
| `"new"` | **全文** | `note_history:true` + `note_updated_at` + `note_key` + `note_pushed:false` | 本局**第一次**投递这个版本 —— 现在读它 |
| `"seen"` | `null` | 同上四键，但 `note_pushed:true` | 本局**已经投递过**这个版本（正文不再重复，省 token）；要原文用 `note_get{kind,id}` 或下一拍 `get_state{force_notes:true}` |

- **`hints.note_history` 只在这一帧真有 `"new"` 时出现**（#127）：没有新便签的帧不再重复那句提醒，别把「这拍没 hints」当成没有便签。
- **键集合恒定**（#125·S2-16-01）：只要有可解析的 `kind:id`（空药水槽、没有名字的怪**没有**），`note` 和 `note_state` 每帧都在 —— `"none"` / `"seen"` 都是 `note:null`，**看 `note_state` 区分**，别猜。
- **改写便签 = 新版本**（#127）：`note_set` 每次写入都会把 `updated_at` 往前推（同一秒内也 `+1`），新版本会再投递一次全文。**注意投递可能就发生在写入那一拍的重建里**（真实难度实测：`note_set` 之后紧接着读到的已经是 `"seen"`），要确认最新正文就用 `note_get`；`"seen"` 只说明「这一版已经投递过」，不代表正文变了没告诉你。
- **缓存帧也会重新过一遍便签**（#127）：同一个 `state_version` 被重复读取时，返回的是缓存 body 的副本，但便签投递会重新判定 —— 静态画面连读 20 次只有第 1 次带全文，不会每帧重复（旧行为是每帧都带）。
- **`get_state{force_notes:true}`**：这一次响应把本局所有便签按 `"new"` 全量返回（全文 + 四键），之后自动恢复去重（下一拍又变 `"seen"`）。

**`memory` 里的池子仍按版本去重**（`recent_notes` / `context_notes` / `briefing.general_notes` / `suggest_note`）：同一局同一个 `updated_at` 只出现一次，之后省略：

- 省略了多少条：`memory.notes_omitted_this_run` —— 变多说明去重在生效，不是数据丢了。
- **显式查询不受影响**：`note_get` / `notes_list` 永远返回完整内容。
- 新局（`run_id` 变）自动清零，新一局重新推一遍。
- 想强制再要一次：`get_state{force_notes:true}`（调试用，只对这一次响应生效）。

> **核对方式**：连读几拍 `get_state`，看 `memory.notes_omitted_this_run` 是否随拍数增长；再用 `get_state{force_notes:true}` 对比 `memory.recent_notes` 是否重新变全。

### 11.5 存储与「人类侧」边界

- 位置：`mods/spirelink-data/notes.json`，键就是 `kind:id`，值 `{text, updated_at}`。
- 跨进程保留：重启游戏还在。
- **全量重置只有人类能做**：游戏内便签面板的「清空全部」按钮（issue #47），会先备份成
  `notes.json.bak-yyyyMMdd-HHmmss` 再清空；MCP **没有任何**批量删除/重置工具，`note_set` 只能删单条。
- 人类入口：`notes_panel` 配置项（默认开）→ 主菜单左下角「便签（N）」，只读翻看，见 §12.4。

> **核对方式**：`ls mods/spirelink-data/notes.json*`；`cat notes.json` 看键是不是 `kind:id` 形状。

## 12. 鉴权与 test_mode

### 12.1 配置文件在哪、什么时候生效

| 顺序 | 路径 |
|---|---|
| 1（首选） | `%LOCALAPPDATA%\ModTheSpire\SpireLink\config.properties` |
| 2（回退） | `<游戏目录>\mods\spirelink-data\config.properties` |
| 3 | 两个都没有 → 用内置默认值，并**自动生成**一份带注释的模板到首选路径 |

**改完要重启游戏**才生效（模组只在启动时读一次）。文件是 UTF-8。

> **核对方式**：`cat "$LOCALAPPDATA/ModTheSpire/SpireLink/config.properties"`；`ls mods/spirelink-data/config.properties`（回退位置，通常不存在）。

### 12.2 `token`：Bearer 鉴权

- `token=`（空，默认）→ **不鉴权**。服务只监听环回地址（`host` 填别的会被强制改回 `127.0.0.1`）。
- `token=abc123` → **每一个**请求都要带 `Authorization: Bearer abc123`，**包括 `initialize`**。
- 不带或带错 → `HTTP 401` + `-32001`「UNAUTHORIZED: token 不匹配」，同时游戏 HUD 显示「鉴权失败，已拒绝」。

```bash
curl -s -D - -X POST http://127.0.0.1:8731/mcp \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer abc123" \
  -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"x\",\"version\":\"1\"}}}"
```

配了鉴权时 CORS 允许的头里已经包含 `Authorization`（`Access-Control-Allow-Headers`），浏览器端不用额外配。

> **核对方式**：把 `token` 设成 `abc123` 重启游戏后，`curl` 不带 `Authorization` → `401 -32001`；带上 → `200` + `Mcp-session-id`。（本机当前 `token=` 为空，所以本文其它 `curl` 片段都不带这个头。）

### 12.3 `test_mode` 与 `dev_*` 工具（9 + 10）

- 打开方式：`config.properties` 里 `test_mode=true`，或环境变量 `SPIRELINK_TEST_MODE=true`（**环境变量优先**，方便自动化不改用户配置）。默认 **关**。
- 两个效果：① 每局开局 **999 HP**（`TestMode.TEST_HP`，长跑不会被半路打死）；② `tools/list` 多出 10 个 `dev_*`。
- 所以 **`tools/list` 的条数随配置变**：默认 **9**，开了 `test_mode` 是 **19**（9 + 10）。别把 19 写死进客户端。

**9 个常驻**：`get_state` `legal_actions` `act` `card_info` `glossary` `note_set` `note_get` `notes_list` `event_note`。

**10 个 `dev_*`**（只在 `test_mode` 出现；参数取自 `tools/list` 的 `inputSchema`）：

| 工具 | 参数 | 干什么 |
|---|---|---|
| `dev_event` | `{id, act?, force?}` | 直接进指定事件（`id` 是事件类名，如 `BigFish`）。**`act` 只做标注**（#117）：不传 = 跟随当前幕；传了也只用于回显/比对，**永远不改 `act`/`floor`/地图推进状态**（事件类直接实例化，不需要幕号 —— 旧版会写 `actNum=1`，于是第 2 幕上 `dev_event` 一把把 `meta.act` 改成 1）。回包 `{ok, id, class, room, act}`：`act` 是事件实际所在幕（= 当前幕）；传了 `act` 才带 `act_requested`；与当前幕不一致才带 `act_note`（同时写一条 WARN，文案说明「只换房间」）。`force:true` = 跳过忙检查 + 先清空动作队列（从卡死状态自救），默认 `false` |
| `dev_map` | 无 | 导出地图前 3 行的节点/边/父边（诊断「没有可达节点」） |
| `dev_room` | `{type}` | 直接进房间。取值：`monster`（= `fight`，随机遭遇）/`elite`/`boss`/`rest`/`shop`/`treasure`/`event`/`victory`（别名 `true_victory` `winscreen`）/**`boss_reward`**（别名 `bossrelic` `boss_relic`，直接开 Boss 遗物屏）。不在表里的拼成 `XxxRoom` 去找类，找不到 → `ROOM_NOT_FOUND`。回包：普通分支 `{ok,type,class}`（`MonsterRoom` 系另带 `readiness`）；`monster` 分支复用 `dev_encounter` 的回包；**`victory` 分支会把当前房间换成 `VictoryRoom`**，回包带 `room`/`room_phase` 和 **`monsters_dying`/`monsters_settled`**（#109，切屏前会把还在播的死亡动画收尾）—— 见下面「`dev_room` 的 victory 回包与换房」 |
| `dev_encounter` | `{list?, ids?, id?, act?}` | `list:true` 列全部遭遇；`ids:[…]`/`id` 直接开战。**`ids` 是候选列表，一次只开第一个能用的**（issue #108·C7-12）：其余里**认识的**进 `ignored_ids`、**不认识的（不管在第几位）**进 `unknown_ids`（#112·C8-09），任一非空带一句 `notice`；**全都不可用**才 `ENCOUNTER_NOT_FOUND`（`message` 是 `找不到这些遭遇: <id>（{list:true} 列出全部可用 id）`，构造途中炸了也是这个码、文案 `构造遭遇失败: …`）。字段细节见下面「`dev_encounter`：`ids` 是候选列表」 |
| `dev_potion` | `{id, slot?, list?}` | 给药水（类名/游戏 ID/中文名都认）；`list:true` 列全部 |
| `dev_card` | `{id, to?, force?}` | 塞牌（`to:"hand"` 默认 / `"deck"`）；`force` 跳过手牌上限（10 张；不给就 `HAND_FULL`）。返回 `{ok, id, name, uuid, to, force, resolved_by, obtainable}`：`resolved_by` 是 `library`（走 `CardLibrary`，游戏 ID 带空格的卡）/ `reflection`（类名或全限定名）；`obtainable` 只做标记、**从不拦**（老脚本靠它塞不可获得的卡类） |
| `dev_relic` | `{id}` | 直接获得遗物。**也认三把钥匙**（#119·C10-04）：`RubyKey`/`Ruby`/`Red`、`EmeraldKey`/`Emerald`/`Green`、`SapphireKey`/`Sapphire`/`Blue`（大小写、空格、下划线、连字符都不敏感，尾部 `Key` 可省）。`Emerald`/`Sapphire` 不是「给一条遗物」，而是**造出真宝箱形态**：先往 `room.rewards` 加一条真遗物行，再把钥匙行**追加在它后面**、两行互相挂 `relicLink`（与 `AbstractChest.open()` 一致），然后打开奖励屏；回包 `{ok,key,name,rewards,room_rewards,key_index,relic_index,rows,relic,screen_opened}` —— **`rewards` 是屏幕行数**（= 同拍 `get_state.rewards.count`；#120·C11-02 之前报的是房间列表条数，所以出现过回包 2、`get_state` 3 的「多一行」），`room_rewards` 才是房间列表条数，`rows` 是屏幕每一行的 `{index,type,text}`；`key_index`/`relic_index` 都是**屏幕下标**，可直接当 `take_reward{index}`。差的那一行是原版 `CombatRewardScreen.setupItemReward()` 给**普通房间**追加的 CARD 行（`TreasureRoom` 不加，所以宝箱房造出来的钥匙箱不会多这一行）。`RubyKey` 在原版里**没有奖励行**（营火 `Recall` 只置 `Settings.hasRubyKey`），所以只置位并回 `{ok,key,name,rewards:0,ruby_key:true,message}`。普通遗物回包仍是 `{ok,id,name,relics}`；造失败会回滚（本 call 加的行删掉、屏复原） |
| `dev_reward` | `{kind?}` | 造奖励并开奖励屏。合法 `kind` **只有 5 个**：`card`（默认）/ `gold` / `relic` / `potion` / `key`；其它值 → `BAD_REQUEST`「kind 只支持 card / gold / relic / potion / key（收到 "x"）」，**校验在动手之前**，拒绝的调用零副作用。**回包的数字一律按屏幕算**（#120·C11-02）：`rewards` = 屏幕行数（= 同拍 `get_state.rewards.count`）、`room_rewards` = 房间列表条数、`rows` = 屏幕上每行 `{index,type,text}`。`key` 的语义见下面那一小节 |
| `dev_heal` | 无 | 回满血 + 清 debuff（战斗中也有效） |
| `dev_use_card` | `{id, target_index?, force?}` | **走原版出牌路径**（`AbstractPlayer.useCard`：`calculateCardDamage` → `card.use()` → 排队 `UseCardAction` → 能量/手牌收尾），**等同真出牌**：排出的动作由游戏的 `GameActionManager` 在随后几帧执行，效果是真的（以前调完 `card.use()` 就把队列清空，于是「报 played、零效果」，C6-02）。两个 dev 口径：不耗能量（`freeToPlayOnce`）、打完即消失（`purgeOnUse`，临时复制品不进弃牌堆）。`result` 三态 `played`/`blocked_by_game_rule`/`metadata_only`；返回另带 **`effect`**（`queued`/`none`/`dropped`）与 **`queued_actions`**（实测排进队列的动作数；队列读不到时不出现该键），以及 `resolved_by`（`library`/`reflection`）与 **`needs_screen`**（牌把你的屏幕停住了才出现，取值 `grid`/`hand_select`/`card_reward`/`combat_reward`；卡自己跑完就没有这个键）。**战斗外直接 `NOT_READY`**：`GameActionManager.addToBottom()` 只在房间 `phase=COMBAT` 时入队，战斗外「打」出去的牌必定零效果 —— 先 `dev_room{type:"monster"}`。只有 `use()` 抛异常时才清掉半排的队列（`dropped_actions` 记条数）；失败时 `error` 是对象 `{"code":"CARD_USE_FAILED","message":…}`，顶层另带 `stack`（前 5 帧）与 `reason`（`ok:false` ⇒ `isError:true`）。**`reason` 会看能量**（#124·C15-03）：回包带 **`energy_before`**（出牌前能量，读不到时没有这个键），能量不够却强制打出去时 `reason` 明写「cost=N，但本回合能量只有 E：正常路径打不出这张牌，dev_use_card 是强制调用（freeToPlayOnce）绕过了能量检查」——**别把 `played` + 这条 reason 当成「这张牌能打」的证据**；够能量时 reason 是「…（本回合能量 N）按规则可打出」 |

边界：`dev_*` 是**造场景**用的，正式跑图不要依赖；它们的返回字段随时可能变，不要写进客户端的稳定契约。

**拒绝是零副作用的**（issue #64 的验收项）：`dev_*` 在「不是等待输入」的时候（动作还在飞、房间在过场、还有 Effect 抓着旧房间……）会直接拒绝 —— `NOT_READY` + 「现在不能切房间/切事件: <原因>」，或 `BAD_REQUEST` —— 并且**一个字段都不改**：房间对象、怪物池、`nextRoom`、奖励列表都保持调之前的样子，`get_state` 照旧能读到原来那一屏。
失败回滚也是真做的：`dev_room` 进去一半炸了会把**旧房间和旧屏**放回去（再 `get_state` 还是原来的房间）；`dev_reward` 造到一半炸了会把刚加进 `room.rewards` 的条目取出来、屏也复原。

**连续切房有硬冷却**（issue #112·C8-08）：`dev_room` / `dev_event` / `dev_encounter` 共用一个切房守卫（`RoomGuard`）—— 上一次切房后 **2500ms（2.5 秒，`RoomGuard.COOLDOWN_MS`）** 内再切会被拒（QA 第 8 轮在 build16 上量到约 1.2 s，那是当时的常量；以代码里的 `COOLDOWN_MS` 为准，别把 1.2 s 写死）：`error.code` 是 `NOT_READY`，`message` 是「现在不能切房间: 刚切过房间，等特效结束（还需 Nms）」（`dev_event` 的文案是「现在不能切事件: …」）。同一个守卫还会因为过场淡入淡出、动作还在执行、动作队列非空、战斗中非等待输入、开战特效播放中而拒（`message` 会写明是哪一种）。**自动化写法**：连切房间要串行 —— 收到这条就等报文里那个「还需 Nms」（或直接 sleep 3 s）后**重试一次**，别把它当成工具坏了；`dev_event{force:true}` 会跳过整段忙检查（冷却也一起跳过），是另一条逃生路。

#### `dev_reward{kind:"key"}`：钥匙奖励现在**能真领**（issue #60；#116b / #119·C10-04 改口径）

- **`kind:"key"` 现在与 `dev_relic{id:"SapphireKey"}` 完全等价**（同一个 `KeyRewards.fabricate(Key.SAPPHIRE)`）：先加**一条真的遗物行**（`returnRandomRelic(COMMON)` → `room.addRelicToRewards`），再把**蓝宝石钥匙行追加在它后面**（原版 `AbstractRoom.addSapphireKey(link)` 就是 `rewards.add(...)`，不是下标 0），两行互相挂 `relicLink`（`RewardItem` 构造器的行为），最后 `combatRewardScreen.open()`。这才是真宝箱的开箱形态 —— 旧实现只造钥匙行、`relicLink` 指向一条**不在奖励表里**的假遗物行，屏幕上只有钥匙，#116b（领钥匙不能吞掉同屏遗物）根本无从复现。
- 回包：`{ok,kind:"key",key,name,rewards,room_rewards,key_index,relic_index,rows,relic,screen_opened}`（`kind` 由 `dev_reward` 补，其余与 `dev_relic` 的钥匙分支相同）。**数字一律按屏幕算**（#120·C11-02）：`rewards` = 屏幕行数 = 同拍 `get_state.rewards.count`；`room_rewards` = `room.rewards` 条数（排错用，agent 不用它）；`rows` = 屏幕上每行 `{index,type,text}`；`key_index`/`relic_index` 是屏幕下标，可直接当 `take_reward{index}`。普通房间的屏幕列表会被原版 `setupItemReward()` 追加一条 CARD 行（`TreasureRoom` 不加），所以 `rewards` 可能比 `room_rewards` 多 1 —— 那一行不是 dev 工具加的。`relic` 是那条遗物的中文名。造到一半炸了会回滚（本 call 加的行从尾部删掉、屏复原）；Ruby 钥匙没有奖励行，只置 `Settings.hasRubyKey`。
- **`take_reward` 现在是真领取**：执行器复刻 `RewardItem.claimReward()` 的钥匙分支 —— 播 `ObtainKeyEffect(BLUE)`（最后一帧置 `Settings.hasSapphireKey`；特效加不进队列时**直接置位**并 WARN，不会静默丢掉这把钥匙），然后把这行从 `combatRewardScreen.rewards` 和 `room.rewards` 两处一起移除。真宝箱里的绿钥匙（`EMERALD_KEY` → `ObtainKeyEffect(GREEN)` → `hasEmeraldKey`）走同一条路。旧文档「拿不起来是原版行为、`take_reward` 等于无操作」**作废**。
- **不再吞同屏遗物**（**有意偏离**原版 `claimReward()` 的 Linked 互斥）：原版钥匙分支会把 `relicLink.isDone=true; ignoreReward=true`，于是一拿钥匙，同一口箱子里的遗物就从奖励屏和 `player.relics` 一起消失。这里改成把 `relicLink` **双向断开**（`reward.relicLink=null`，对端也置 null），**钥匙照领、遗物照留** —— 那行重新按普通遗物奖励渲染，`take_reward{index}` 能正常领。日志：`take_reward: SAPPHIRE_KEY 已领取；连体奖励保留: RELIC`。
- 钥匙贴图故意不释放（同一次调用里两处列表都移除，理论上还可能再画一帧；真实对局一局最多两把，泄漏有界 —— 测试模式反复造只会多几张，量级同样有界）。
- 非法 `kind` 的 `message` 会把 5 个合法值全列出来，照抄即可。

#### `dev_event` 的 `force`（issue #80 已收口）

`dev_event {id, act?, force?}` —— **`force` 现在是 schema 里声明过的正式参数**（以前实现认、schema 不列，是 issue #80 的遗留矛盾）：

- `force` 缺省 `false`；`true` = **跳过「动作正在执行」的忙检查**，并**先清空动作队列**（`RoomGuard.forceClear()`），专门用来从一个卡死的动作里自救。
- 正常进事件**不需要**它；只在「上一条动作僵住、正常 `dev_event` 被 `NOT_READY` 拒」时才用。
- `dev_room` **没有**对应的 force；它卡住时用 `dev_event{force:true}` 清一次队列再试。

**`id` 必须给类名（CamelCase），不能给显示名**（issue #97·C3-08）：实现是拿 `id` 去 `Class.forName("com.megacrit.cardcrawl.events.<包>." + id)` 试的，所以 `Living Wall` / `Golden Shrine` 这种带空格的本地化名字一律 `EVENT_NOT_FOUND`（message：`找不到事件类: Living Wall`），要写 `LivingWall` / `GoldShrine`。省事的取法：`get_state.event.id` 就是类名，原样传即可；也可以传全限定类名（含 `.` 时按 FQN 解析）。

> **核对方式**：`curl` 拿 `tools/list`，`dev_event.inputSchema.properties` 里应当同时有 `id` / `act` / `force`（`force` 是 boolean）。

#### `dev_room.type`：schema 只列主值，别名照旧可用（issue #81）

`inputSchema` 的 `enum` 是 **8 个主值**：`monster` `elite` `boss` `rest` `shop` `treasure` `event` `victory`。实现另外还认这些**别名**（schema 不列，但传了照样生效）：

| 别名 | 等价于 |
|---|---|
| `fight` | `monster`（同一个「开战」实现，随机遭遇） |
| `true_victory` / `winscreen` | `victory` |
| `boss_reward` / `bossrelic` / `boss_relic` | 不在 enum 里：直接打开 Boss 遗物屏（3 个随机 Boss 遗物），用于验 `claim_relic` |

所以「schema 校验不过」的别名并不等于非法——但**写自动化时用主值**，别依赖没进 enum 的别名。

> **核对方式**：`curl` 拿 `tools/list` 数条数：默认配置是 9，开了 `test_mode` 是 19（本机当前 `test_mode=true`，实测 19）；每条 schema 就是上表参数的另一份写法。

#### `dev_room` 的 `victory` 回包与换房（issue #108·C7-08）

`dev_room{type:"victory"}` 不再只装一块 `VictoryScreen`：它**把当前房间真的换成原版 `VictoryRoom`**（`VictoryRoom.EventType.NONE`，阶段是 `EVENT`），再在它上面装胜利屏。原因：`proceed` 的判断看「当前房间」，以前房间还是上一间（例如 `COMPLETE` 的 Boss 房），`proceed` 会走 Boss 换幕、留在本局，而不是回主菜单。

`victory`（含别名 `true_victory` / `winscreen`）的回包：

```json
{
  "ok": true,
  "type": "victory",
  "class": "VictoryScreen",
  "room": "VictoryRoom",
  "room_phase": "EVENT",
  "monsters_dying": 1,
  "monsters_settled": 1,
  "screen": "VICTORY"
}
```

- `type` **回显你传的那个值**（传 `true_victory` 就回 `true_victory`），`class` / `room` / `room_phase` / `screen` 固定。
- `room` / `room_phase` 是**挂载后**的真实房间类与阶段；随后 `get_state` 里 `meta.room_type` 是 `VictoryRoom`、`meta.room_phase` 是 `EVENT`，`meta.ui` 仍是 `victory`、`postmortem.result` 是胜利。
- **`monsters_dying` / `monsters_settled`（issue #109）**：`monsters_dying` = 被离开的那个房间里**还在播死亡动画**（`isDying=true`）的怪物数；`monsters_settled` = 这次被**收尾**（见下一条）的怪物数。没有就是 `0` —— 两个字段恒在，别按「有才出现」读；`settled` 可能比 `dying` 大（已经把动画播完的尸体会被一并置成死透）。
- **切胜利屏前先把死亡动画收尾**（#109）：`dev_room` 的击杀常常留下 `isDying=true` 的怪（屏幕占着帧、房间不会被 tick，QA 见过 7 秒还是 dying）。实现会先把它们置成「死透」再换屏：`isDying=false`、`isDead=true`、`currentHealth=0`、`deathTimer=0`（只动**已死/正在死/血量已为 0** 的怪，活怪不碰；上限 64 只）。这样换房之后任何读房间的东西（`meta`、`RoomGuard`、回滚逻辑）都不会看到半截死亡。挂载失败时连这一步一起**回滚**（`DeathSettle.revert()`），回包是 `NOT_READY「进入胜利界面失败: …」`，日志里写「怪物死亡动画已回滚 N 只」。
- **胜利屏的怪物组恒为 `null`**（同原版 `new VictoryScreen(null)`）：`VictoryScreen.update()` 会 tick 怪物组，而 `AbstractMonster.updateDeathAnimation()` 在 `deathTimer<0` 时调 `AbstractDungeon.getMonsters()` —— `VictoryRoom.monsters` 是 `null`，于是在那里 NPE（QA 撞过两次：#109 的 Boss 与普通 Cultist）。所以 `monsters_dying` 描述的是**离开的房间**，不是胜利屏的怪。
- **只有 `victory` 分支带 `room` / `room_phase` / `monsters_dying` / `monsters_settled`**：普通房间分支（`elite` / `boss` / `rest` / `shop` / `treasure` / `event` 等）回 `{ok, type, class}`（`MonsterRoom` 系，含精英/Boss，另带 `readiness`）；`monster` 分支**直接复用 `dev_encounter` 的回包**（`encounter` / `monsters` / `readiness` 等）；`boss_reward` 分支回 `{ok, type:"boss_reward", screen, relics}`。别在普通 `dev_room` 回包里找 `room`/`room_phase`。
- 失败整段回滚（旧房间 + 旧屏放回去；#109 之后连**被收尾的死亡动画**一起还原），回 `NOT_READY`；`proceed` 之后如果没回主菜单，日志里会有 `dev_room victory: 挂载后 proceed 仍会走房间分支（C7-08）` 一行。

#### `dev_encounter`：`ids` 是候选列表，一次只开第一个（issue #108·C7-12）

`ids:[…]` **不是「组合遭遇」**：原版一个遭遇就是一整组 `MonsterGroup`（怪物位置都写死），拼不起来。所以：

- **只开第一个能用的 id**（回包的 `encounter` 就是它，值为该 id），**它后面的 id 不再上场**；
- **`ignored_ids` = 原版认识、这次没上场的**（#112·C8-09 收紧）：只收「`knownIds()` 里认识」的尾巴 —— 它们能用，只是这一 call 不开放；
- **`unknown_ids` = 原版不认识的，不看它在第几位**（#112·C8-09）：不在 `knownIds()` 里、或构造不出怪物组、或原版拿 `ApologySlime` 占位糊弄的 id，**都进这里**。旧实现只把「第一个可用 id 之前」的未知 id 放进来，尾巴上的未知 id 被塞进 `ignored_ids`，等于告诉 agent「这个 id 有效，再试一次」——**正好相反**。尾巴只查 `knownIds()`、不去构造怪物组（不产生副作用，与「被拒 = 零副作用」同一条规则）；
- 每个请求的 id 只会落到三者之一：**被用 / `ignored_ids` / `unknown_ids`**，两个列表都按**请求里的顺序**；
- `ignored_ids` / `unknown_ids` 任一非空就带一句 **`notice`**：「一次只开一个遭遇（dev_encounter 取第一个能用的 id，C7-12）：已用 "X"，未使用 [...]，不认识 [...]。要打其余遭遇请再单独调用一次 dev_encounter。」；
- **一个能用的都没有**才回 `ENCOUNTER_NOT_FOUND`（`message` 列出**全部用不上**的 id：不认识 / 构造不出 / 只有 `ApologySlime` 占位）。

回包**恒带 `ignored_ids` / `unknown_ids` 两个数组**（可以为空），`notice` 只在任一非空时出现。举例：

- `ids:["Jaw Worm","Cultist"]` → 开 Jaw Worm，`ignored_ids:["Cultist"]`、`unknown_ids:[]`，`notice` 说 Cultist 没用上、要打请再单独调一次；
- `ids:["Jaw Worm","NotARealEncounter","Cultist"]` → 开 Jaw Worm，**`unknown_ids:["NotARealEncounter"]`、`ignored_ids:["Cultist"]`**（#112·C8-09：未知 id 在中间/末尾也一样进 `unknown_ids`，不再看位置）；
- `ids:["NotARealEncounter","Jaw Worm"]` → 开 Jaw Worm，`unknown_ids:["NotARealEncounter"]`。

不是「静默只开第一只」，也不是「两个一起开」，更不会把不认识的 id 说成「有效但这次没用」。

### 12.4 配置项总表（含 `notes_panel`）

| 键 | 默认 | 说明 |
|---|---|---|
| `host` | `127.0.0.1` | 只允许环回地址；填别的会被强制改回并 warn |
| `port` | `8731` | 被占用时**明确报错**，不会静默换端口 |
| `token` | 空 | 非空 = 要求 `Authorization: Bearer <token>`（§12.2） |
| `debug_log` | `true` | 调试信息写 `mods/spirelink-data/spirelink-debug.log` |
| `hud` | `true` | 游戏左上角的 Agent 行为小面板 |
| `hud_lines` | `4` | 面板保留几行，范围 1–6 |
| `hud_idle_fade` | `true` | agent 闲置时面板自动淡出（8 秒 55%、20 秒 30%） |
| `cursor` | `true` | 游戏内的大肥鱼指针（不碰系统鼠标） |
| `cursor_scale` | `0.5` | 指针缩放，`1.0` = 148px，范围 0.15–2.0 |
| `notes_panel` | `true` | 主菜单左下角「便签（N）」入口（人类只读翻看 + 「清空全部」，issue #46/#47）。**关掉只影响人类入口，不影响 MCP 的便签工具** |
| `test_mode` | `false` | §12.3；环境变量 `SPIRELINK_TEST_MODE` 优先 |
| `speed` | `1.0` | 动作速度倍率，范围 0.5–5.0（环境变量 `SPIRELINK_SPEED` 优先） |
| `gaze_ms` | `2500` | `card_info` 抬牌 / 看牌的注视时长，范围 0–3000 |

> **核对方式**：`grep -n "KEY_" spirelink/src/main/java/spirelink/SpireLinkConfig.java` 对键名；自动生成的模板里每个键都带中文注释（`CONFIG_TEMPLATE`）。

## 13. `card_info` 与 `glossary` 的字段

### 13.1 `card_info`

入参：`{uuid}`（必填）、`upgraded`（可选）。返回（`docs/samples/card_info.json` 是真实回包）：

```json
{
  "ok": true,
  "uuid": "cb079bca-2846-4af5-98f8-9edb3bc331c5",
  "id": "Defend_B",
  "name": "防御",
  "type": "SKILL",
  "cost": 1,
  "rarity": "BASIC",
  "raw_text": "获得 !B! 点格挡。",
  "description": "获得 5 点格挡。",
  "keywords": ["格挡"],
  "shown": true,
  "note": null
}
```

| 字段 | 说明 |
|---|---|
| `id` | cardID，**便签用它**（`note_set{kind:"card", id:…}`） |
| `raw_text` | 原始文本，保留 `!D!/!B!/!M!` 占位符 |
| `description` | 已把占位符替换成真实数值（没有该数值时退回基础值） |
| `keywords` | 这张牌涉及的游戏术语（可以直接喂 `glossary`） |
| `shown` | 是否真的在屏幕上抬起了这张牌 |
| `note` / `note_state` / `note_history` / `note_updated_at` / `note_key` / `note_pushed` | 这张牌的便签（#127 三态）：`"none"` → `note:null` 且无其余键；`"new"` → 全文 + `note_history:true` + `note_updated_at` + `note_key`（`"card:<cardID>"`）+ `note_pushed:false`；`"seen"` → `note:null` + 另外四键（`note_pushed:true`），正文不重复 |

副作用（**不改 `state_version`**）：游戏里把这张牌抬起 `gaze_ms`（默认 2500ms）；牌不在手牌时还会切到牌库界面 4 秒
（牌在手牌时不切 —— 牌库界面会挡住手牌，抬起就看不见了）。这 4 秒 `meta.ui` / `legal_actions` **与 peek 前完全一致**（§3 #115）。`upgraded:true` 时额外弹 3.2 秒升级后的牌面。
错误：缺 `uuid` → `BAD_REQUEST`；没在局内 → `NOT_READY`；uuid 找不到 → `CARD_NOT_FOUND`。

> **核对方式**：从 `combat.hand[].uuid` 取一个真 uuid 传给 `card_info`；把 `uuid` 换成 `"not-a-real-uuid"` → `CARD_NOT_FOUND`。

### 13.2 `glossary`

入参四种写法（`term` 优先于 `terms`，`terms` 优先于 `query`，都没有 = 全列）：

| 入参 | 用途 |
|---|---|
| `{term:"格挡"}` / `{"term":"block"}` | 查一个术语（中文/英文/id 都认） |
| `{terms:["格挡","力量"]}` | 批量，未命中的进 `not_found`（带 `suggestions`） |
| `{query:"格"}` | 前缀/包含模糊搜索 |
| `{list:true}` | 列出全部词条：`{ok, count, results, by_source:{"keyword":N,"power":N}}` |

返回（`docs/samples/glossary.json` 是真实回包）：

```json
{
  "ok": true,
  "query": "格挡",
  "results": [
    {
      "term": "格挡",
      "aliases": ["格挡", "blocks", "BLOCK"],
      "definition": "在下回合开始前，阻挡受到的伤害。",
      "source": "keyword"
    }
  ]
}
```

- **回包的列表字段叫 `results`**（不是旧文档里写的 `terms`）；`term`/`terms`/`query` 三条路径都带 `count`，`list:true` 另外带 `by_source`（只统计真的产出词条的来源）。
- **占位符不再当词条**（issue #61）：本地化里的 `[MISSING_NAME]` / `[MISSING_0]` 这类标记不再作为 `term` 出现；有机器 id 的回退成 id（如 `AccuracyPower`）并标 `name_resolved:false`，没有任何可用名字的干脆不列。所以 `list:true` 的 `count` **小于 171** 是正常的（以前是 171 条、大半是 `[MISSING_*]`）。
- `source`：`keyword`（游戏关键词）或 `power`（能力/状态），引用时最好带上来源。
- 未命中不会静默：`{ok:false, error:{"code":"TERM_NOT_FOUND","message":…}, suggestions:[最近 3 个]}`，且 `result.isError:true`（issue #86 之前这里是 `isError:false` + 字符串码，已修）。
- 回包文本里的已知术语会被标成 `【格挡】` 并附 `keywords` 数组；对某个词拿不准就来查，别猜。

> **核对方式**：`glossary{term:"格挡"}` 与 `glossary{term:"不存在的词"}`（后者 `TERM_NOT_FOUND`）；`glossary{list:true}` 看 `count`。

## 14. 抓样本脚本

`scripts/capture_samples.py`（本次新增，issue #48 的 DoD 之一）—— 按 `meta.ui` 去重抓样本，落盘结构与 §10 完全一致。

```bash
python scripts/capture_samples.py                     # 抓当前这一屏
python scripts/capture_samples.py --ui map,event,rest # 只在名单里的界面才落盘
python scripts/capture_samples.py --watch --seconds 300
python scripts/capture_samples.py --dry-run           # 只打印会写什么
python scripts/capture_samples.py --act               # 额外执行一次动作并抓 act 回包（会真的操作游戏）
python scripts/capture_samples.py --check-index       # 校准 §10 索引，不连游戏
```

规则：

- 文件名就是 `state-<ui>.json` / `actions-<ui>.json`（`ui` 里的非法字符换成 `_`，例如 `unknown:SETTINGS` → `unknown_SETTINGS`）。
- **已存在的文件默认不覆盖** —— 样本是「某个版本的真实返回」，覆盖等于改历史；要覆盖必须 `--force`。
- 写的是**业务对象**（`result.content[0].text` 解析出来的那份），不是 JSON-RPC 外壳，与 `docs/samples/` 现有文件同构。
- `--check-index` 把 §10 表格里的文件名与目录做差集，打印 `missing-in-doc` / `ghost-in-doc`，不一致时退出码 2（可以挂进 CI）。
- 脚本**只读**：默认只调 `get_state` / `legal_actions`；只有显式 `--act` 才会执行动作、才动游戏。

> **核对方式**：`python scripts/capture_samples.py --check-index`；`python scripts/capture_samples.py --dry-run`（会先握手再打印目标路径，不落盘）。

## 15. 契约自检：enum 覆盖到哪了（issue #81）

`tools/list` 的 `inputSchema` 是客户端自检的唯一依据。issue #81 之后**大部分闭集参数已经带 `enum`**，这一节是现状清单 —— 客户端可以照「已声明」那一半做自检，剩下的别指望。

### 15.1 已经带 `enum` 的（可以直接自检）

| 工具.参数 | schema 里的 enum |
|---|---|
| `get_state.include[]` | 20 个区块名（`player` … `draw_pile`，见 §2） |
| `notes_list.sort` | `["recent","name"]`，`default:"recent"` |
| `note_set.kind` / `note_get.kind` / `notes_list.kind` | `["event","potion","card","monster","relic","general"]` |
| `act.type` | 由 `ActionTypes` 现算出来的全部动作类型（与执行器同一份表，schema 不会漂） |
| `dev_room.type` | `["monster","elite","boss","rest","shop","treasure","event","victory"]`（**只列主值**，别名仍可用，见 §12.3） |
| `dev_card.to` | `["hand","deck"]` |
| `dev_reward.kind` | `["card","gold","relic","potion","key"]` |

### 15.2 还没带 `enum` / 范围约束的（别指望 schema 拦）

| 工具.参数 | 现状 | 应当补 | 依据 |
|---|---|---|---|
| `get_state.exclude[]` | `type:"array"`，`items` 没有 `enum` | 与 `include.items.enum` 同一份 20 个名字 | 两者是同一套区块名，`include` 有 `exclude` 没有，纯属漏写 |
| `dev_potion.slot` | `type:"integer"`，没有 `minimum`/`maximum` | `minimum:0`、`maximum:2`（腰带 3 格） | 「0..2」只写在 description 里，越界只能靠报错发现 |
| `act.state_version` | `type:"integer"`，没有 `minimum` | `minimum:1` | 版本从 1 起单调递增；负数只在测 `STALE_VERSION` 时故意用 |

（`dev_encounter.ids[]` 是开放集合——遭遇 id 来自游戏数据，列不完，没有 enum 是合理的。）

> **核对方式**：`curl` 拿 `tools/list`，摊开 `inputSchema.properties` 找 `enum` —— 15.1 的每一行都应当命中；15.2 的三处应当**找不到** enum/范围（`note_set{kind:"bogus"}` 这类非法值仍会得到 `BAD_REQUEST`，但那是运行时校验，不是 schema 自检）。
