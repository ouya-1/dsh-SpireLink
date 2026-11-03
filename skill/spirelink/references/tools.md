# 工具一览

工具清单以服务端 `tools/list` 为准。这里只写「什么时候调、注意什么」，**不抄 schema**——参数以 schema / 报错信息为准。

## 公共约定

- 大部分返回带头：`ok`、`ready`、`state_version`、`error`。`ok: false` 时看 `error.code`。
- `ready: false` 表示游戏还在动（动画、过场），此时只读工具会给最后一帧稳定快照，动作类会拒绝。
- `awaiting` 是闭集 11 个值（`playing` / `char_select` / `dead` / `enemy_turn` / `animating` / `screen_up` / `transition` / `battle_over` / `event_text_typing` / **`loading`（存档正在载入：#121·C12-01 / #122，`continue_run` 或 `embark` 已点、run 屏还没出来）** / `unknown`）：**`ready=true` 不恒为 `playing`**，选人屏是 `char_select`、死亡屏是 `dead`（完整表见 `docs/MCP-返回格式.md` §2.1）。其中 `event_text_typing` 是**历史值**，当前实现不会再返回（打字不压 `ready`，见下面 `event.text_typing`）。
- `state_version` 只比较大小，不要假设一次动作只 +1（一张牌打出去可能跳好几格）。它**只在决策点/关键状态变化时推进**（换屏、血量/能量、手牌、可动作集合……）；事件正文一个字一个字打出来**不**动版本，所以「版本没动」= 这一拍在决策上和上一拍是同一拍（issue #75）。
- 当前 `tools/list` 组成：常驻 10 个 `get_state` `legal_actions` `act` `say` `card_info` `glossary` `note_set` `note_get` `notes_list` `event_note`；`meta.test_mode == true` 时再多 10 个 `dev_*`（`dev_event` `dev_encounter` `dev_room` `dev_map` `dev_card` `dev_relic` `dev_potion` `dev_reward` `dev_heal` `dev_use_card`，见「仅测试模式」）。标题带 ⏳ 的是设计里有、这一版还没注册的工具。

## 逐个说明

### `get_state`

- 每一拍的第一件事；也是 `NOT_READY` 时的兜底（返回最后一帧快照 + `ready: false`）。**未就绪回包的顶层有 `blocked_ui`**（= 这一拍 `ui`，与 `legal_actions` 对称，#121·C12-04）。**存档载入窗口**（`continue_run` **或 `embark`** 交出去、run 屏还没出来；判据 v3 = 我们打点 + 状态层 `ui` 仍是开局前屏 + 30 s 上界，**不看 `loadingSave`**）也走这条，而且**读操作走 500 ms 短超时**（#123·C14-03）：主线程在建造地牢，等满常规预算只会让轮询变慢，所以每一拍都从缓存回一帧 `NOT_READY` +「存档正在载入」+ `awaiting:"loading"`（缓存里的 `awaiting` 会被改成 `loading`）：`ready:false` + `meta.awaiting:"loading"` + `NOT_READY`「存档正在载入（continue_run 已点…）…不要重发 continue_run；轮询 get_state 直到 ready:true」（#121·C12-01）。**快照 `ready:true` 但这一帧屏上没有可执行动作时，`get_state` 会把 `ready` 压成 `false`、`ok` 也变 `false`**，附 `meta.blocked_ui`（= 当前 ui）+ `error.code:"NOT_READY"`（#120·C11-03，见 `docs/MCP-返回格式.md` §5）。
- `include` 是**白名单**（2025-11 起）：给了 `include` 就只回 `meta` + 你点名的区块，其余不返回；不给 `include` 才按界面给默认区块。想减用 `exclude`。
- 目前支持的 `include`：`"deck"` → 追加 `deck: {count, cards:[{uuid,id,name,type,rarity,cost,upgraded,upgraded_name?,upgraded_text?}]}`（选牌/净化/删牌前先看它）。
- **看牌会真的打开屏**：要 `deck` / `discard_pile` / `exhaust_pile` / `draw_pile` 时，指针先走到对应按钮，再把那份牌表翻开。**peek 不挡动作（issue #98）**：你下一条 `act` 会**先关掉 peek 再执行** —— 不必等 4 秒，也不必先 `dismiss_tip`；没人动作时它自己 4 秒后关（关之前会去点「返回」）。**唯一例外**：有 peek 时 `dismiss_tip` **只关 peek、不动底下的屏**（底下可能是你正在做的 `grid` / `hand_select`）。牌表开着时 `meta.ui` 仍报打开前那一屏、`legal_actions` 照旧——**看牌不是决策点**。**四种看牌全按这条走**（#115：`deck`/`discard_pile`/`exhaust_pile`/`draw_pile` 分别开 `MASTER_DECK_VIEW`/`DISCARD_VIEW`/`EXHAUST_VIEW`/`GAME_DECK_VIEW`；旧版只认第一种，另外三种会掉成 `tutorial_tip`、动作缩成 `[dismiss_tip]`）。**商店里 peek 的那 4 秒 `legal_actions` 仍是 `buy`/`purge_card`/`leave`**，不是 `shop_room` 的 `open_shop`/`leave`。只有 `card_reward`/`grid`/`combat`/`shop`/`hand_select` 这五种决策屏会真开 peek。
- `discard_pile` / `exhaust_pile` / `draw_pile` → `{count, cards:[…]}`，和 `deck` 同构；`upgraded_name`/`upgraded_text` 只在「还没升级且可升级」的牌上出现，就是升级后的牌名与规则文本。
- **默认区块按当前界面给**（体积优化后，2025-11 起）：`meta` `player` `potions` `memory` 常驻；`combat` 只在战斗；`event` 只在事件/Neow；`map` **只在 `ui=map`**（约 7 kB，整幕）；`rewards`/`boss_reward`/`card_reward` 只在对应奖励界面；`shop` 只在商店；`char_select`/`neow`/`match_game` 各自界面；`postmortem` 只在 `death`/`victory`。
- **`include` 现在是真白名单**：`get_state{include:["combat"]}` 只回 `meta`+`combat`；`include:["deck"]` 仍附带牌库。
  可点名的区块一共 **20 个**（完整清单 + 出现时机见 `docs/MCP-返回格式.md` §2）：`player` `potions` `memory` `combat` `map` `event` `neow` `rewards` `card_reward` `boss_reward` `shop` `char_select` `match_game` `postmortem` `briefing` `hints` + 四个看牌用的 `deck` `draw_pile` `discard_pile` `exhaust_pile`。
  - `neow`：`ui=neow` 才有内容，别处点名它只回一个空 `neow`。
  - `briefing`：可以**单独**要（issue #85）：`include:["briefing"]` → 顶层 `briefing` 区块；`include:["memory","briefing"]` → 仍只放在 `memory.briefing` 下（同一份只发一次）。
  - `hints`：可以点名要 —— **点名就一定回**（哪怕这一拍没事可解释，内容只有存活探针 + 场景字段，没有任何 `last_error`）；不点名时只有「有事要说」才出现。
  - 名单外的名字**静默忽略**（不报错、不占 key，issue #58a）。
- **`exclude`** 从默认集合里减（`exclude:["map","memory"]`），取值只认上面那 20 个名字。空区块/null 区块不再占 key。
- `include`/`exclude` 的三条硬规则：**① 名单外的名字静默忽略**（不报错、不占 key）；**② `meta` 永不可排除**（`exclude:["meta"]` 无效——`ready`/`state_version` 是版本闸的唯一依据）；**③ `exclude` 压过 `include`**（两边都写了就是「不要它」，不会退化成空占位）。
- `exclude:["hints"]` 现在**真能删干净**（旧版会被存活探针补回 4 个字段，issue #66/#68 已修）。
- `memory.briefing` 只在**开局、换幕、回主菜单/选人/死亡/胜利**时出现一次（其余调用 `memory` 只有 `recent_notes`/`counts`/`run_history`）。
- `hints` **只在「有事要说」时整块出现**：`ready=false`、上一次 `act` 被拒（直到下一次 `applied` 才停）、`ui=char_select`（那一屏恒有，`hints.char_selected`/`next` 回答「人已经选好了吗」，issue #49），或 **hints 自带异常标记**（`map_no_reachable` / `suggest_note` / `unknown_ui` / `event_missing` —— 这四种在 `ready:true` 的拍也要给，issue #100·C6-04）。**`ready:true`、没有未消化错误、也没有这四个标记时，`hints` 整个块不存在**（不再挂 4 个存活探针）——所以「看到 hints」就意味**有事要看**：先扫 `last_error` / `grid_*` / `game_dead` / `map_no_reachable`。
- `legal_actions.hints` 的字段和 `get_state` 一样（它来自同一份快照），里面的存活探针在回包前**现读现刷**（issue #84b 修掉了「发缓存旧数字」）；`get_state` 里没有 hints 时它也不会凭空出现。
- `map[]` 节点精简为 `{x, y, symbol, room, taken?, edges:[[x,y]]}`：`symbol` 是地图图标（M/?/E/R/$/T/B），`room` 是短代码（monster/elite/**unknown**/event/rest/shop/treasure/boss；`unknown` 就是地图上的 `?` 节点，进去可能是事件/怪物/商店/宝箱，别当成「一定是事件」），`taken` 只有走过才出现；`row/column/column_label/room_zh/edge_count/edges_in/empty/unresolved` 都已删除（可由 x/y/edges/symbol 推导，中文用 `UiNames.roomZh()` 现查）。
- **自动返回的笔记按局去重**：同一局内同一条笔记的同一个版本（`updated_at` 相同）只在第一次自动出现（`memory.recent_notes` / `memory.context_notes` / `memory.briefing.general_notes` / 对象上的 `note` / `hints.suggest_note`），之后省略，数量看 `memory.notes_omitted_this_run`；`note_get`/`notes_list` 永远完整，新局清零，`get_state{force_notes:true}` 可强制再要一次。
- `player.potions`：`[{slot, id, name, can_use, can_discard, description}]`（只列真药水，空格子不在里面；腰带全貌看顶层 `potions`）。
- `player.orbs`（故障机器人）：每格一项 `{index, empty, id?, name?, passive?, evoke?}`——**空格也占一个 index 并标 `empty:true`**，槽位数看 `player.orb_slots`，已有几颗看 `orbs_filled`，下一次激发的是 `orb_evoke_next`（最左边那颗非空球，-1 = 没有球），`player.focus` 是聚焦值。`passive`/`evoke` 已经把这层的聚焦算进去了，别再自己加一次。
- `boss_reward`（`ui=boss_reward`）：`[{index, id, name, tier, text, description}]`，配合 `claim_relic{index}`；不想要就 `skip_relic`。
- `neow`（`ui=neow`）：`{options:[{index,label}], option_count}`，选项用 **`choose_neow_reward{index}`**（**不是** `choose_option` —— 只有普通事件房才用 `choose_option`）。`label` 是「选项：<对白原文>」，首拍常见「选项：对话」（涅奥开场对白），点了不领奖励。
- `char_select`（`ui=char_select`）：`{options[{index,id,name,selected,locked}], selected, selected_index, selected_name}`；此时 `meta.character` 就是高亮角色的 `chosenClass`，不用从 label 猜。
- `ui=event` 但事件已结算（只剩 proceed）时，`event` 是 `{id:"", name:"", resolved:true, options:[]}`，不是 `null`。
- `event.body` / `event.options[].text` 都是**清洗过的文本**（issue #76）：`#y/#b/#r/#g/#p/#.`、`@`、`~`、换行都去掉了，中文的逐字空格（`当 你 走 过`）也合回正常词句；同一份文字永远给同一串字符（清洗幂等），不会这次带记号下次不带。
- `event.text_typing` **只是提示、不挡动作**（issue #97·C3-04）：正文还在逐字打时 `ready` 仍是 `true`、`awaiting` 仍是 `playing`，`choose_option` 这时发出去会成功；别等 `text_typing` 变 `false`。（`awaiting=event_text_typing` 是历史值，现在的实现不会返回。）
- `event.text_source`：正文是从哪个对话框读的 —— `image`（`imageEventText`）/ `room`（`roomEventText`）/ `none`（两个都没有可读的 `textDone`，这时 `text_typing` 不可信，日志里有 `event.text_typing 无信号源`）（issue #97·C3-07）。
- **`event` 区块挂在什么界面上与 `meta.ui` 无关**（issue #97·C3-03）：判据是「当前房间是 `EventRoom` 且 `room_phase=EVENT`」，所以在事件房里打开地图/奖励屏时，`ui=map`、`ui=combat_reward` 也可能带旧 `event`。**看到 `event` 不代表在事件里，判界面只认 `meta.ui`**。
- `ui=neow` 时 `event` 不再是 `null`：`{id:"NeowEvent", name, text, options[{index,text,disabled}], option_count}`（`choose_neow_reward` 的 `index` 与 `options[].index` 对应）。**`event.text` 恒为空串**（#107）：涅奥的台词在屏幕对话气泡（`InfiniteSpeechBubble`）里，MCP 不读它，也不许拿共享图片对话框那一页顶替 —— 读不到就给 `""`，**绝不会是别人的台词**（旧版会给上一个事件的正文）。想知道涅奥在说什么看 `neow.options[].label`。
- `memory`（跨局记忆，**每局都看**）：`{context_notes[{kind,id,text}], recent_notes[…], counts{kind:条数}, total, run_history[…], run_history_count}`；在 `char_select`/`neow`/`main_menu` 还带 `briefing{recent_runs[…], general_notes[], notes_total, notes_by_kind{}, death_causes[{killed_by,count,last_run}]}`——`death_causes` 是**最近 3 局**的死因聚合（同一敌人死几次一眼可见），`notes_total`/`notes_by_kind` 是「我积累了多少」。`run_history` 持久化在 `mods/spirelink-data/history.json`，每局结束追加一条，并且**每次 `get_state` 都从文件状态现读**（死的那一拍就能看到这条记录，下一局开局 `briefing.recent_runs` 一定有「上一局怎么死的」）。
- `player.powers` / `combat.enemies[].powers` 每项：`{id, name, kind, amount, stack, text}` —— `kind` 是 `buff`/`debuff`（直接读 `AbstractPower.type`，不用自己猜正负），`stack` 与 `amount` 同值，`text` 已清掉 `#y/#b/#r/#g/#p/#.` 这类游戏内着色记号；列表按 **buff 在前、debuff 在后** 排序。
- `powers[].amount` 允许负数（力量被扣到 -1 就是 -1，不会变成 null）；`card_reward.cards[].index` 一定有值（0/1/2），`take_card{index}` 直接用它。
- `postmortem`（`ui=death`/`victory`）：`{result, killed_by, act, floor, hp, deck_size, deck[], relics[], potions[], last_combat{monsters[], turns}}`；同时 `hints.suggest_note` 给出「建议记一条什么」，写不写由 agent 决定。
  - `card_reward`：`ui=card_reward` 时 `{count, cards:[{uuid,id,name,type,rarity,cost,upgraded,text,raw_text,damage?,block?,magic?}]}`；`text` 里的 `!D!/!B!/!M!` 已经替换成真实数字（没有该数值时退回基础值），`raw_text` 保留原文。**卡牌奖励永远有 `skip_card`**（含 Boss 奖励）；
  - `shop`：`ui=shop`/`shop_room` 时 `{open, gold, cards[{index,id,name,price,affordable}], relics[…], potions[…], purge{available,cost,affordable}}`；`index` 是**稳定槽位号**——买走一件后货架不重排，所以「买 index 4」始终指同一件；
  - `match_game`：GremlinMatchGame 时给 `screen`/`matched`/`cards[]`（见 `event` 行）；
  - **不在对应界面时是什么形状**（issue #94·B2-07 实测，别按直觉猜）：
    - **不写 `include`**：这些区块**根本不出现在回包里**（被过滤掉了）—— 既不是 `null` 也不是 `{}`，`payload["combat"]` 直接 KeyError；
    - **显式 `include` 点了名**：这一拍没内容就给 `{}`（空对象）—— 意思是「你要的这个区块，这一拍是空的」，**不是 `null`**（13 个点名实测里 9 个是 `{}`）；
    - 三个例外：`map` 在地图还没生成（`AbstractDungeon.map` 为 null/空）时给 `null`，生成过就给整幕；`char_select` 只要画面对象还在就给真数据（`options`/`selected`/`selected_index`），哪怕当前不在选人屏；`deck`/`draw_pile`/`discard_pile`/`exhaust_pile` 点了名就真给你（会开屏 4 秒）。
    - 判「现在是什么界面」永远只看 `meta.ui`：区块在不在、是不是 `{}`，都不代表界面。
- `meta` 关键字段（**24 个常驻键 + 几个条件键**，完整表在 `docs/MCP-返回格式.md` §2）：`ready` `ui` `ui_group` `state_version` `in_game` `character` **`character_source`** `ascension` `act` `floor` `room_type` `room_phase` **`room_cleared`** `screen_type` `render_crashed`（渲染崩过则为 true 且**不会自己变回 false**，此时 `act` 一律 `NOT_READY`）**`game_dead`** `test_mode` `mod_version` `language`。
  - `character_source`：`character` 是从哪儿读到的 —— `player` 或 `char_select`。选人界面 `player` 还指着上一局，那一屏改读画面，值不对劲先看这个（issue #49）。
  - `room_cleared`：只在 Boss 清场那拍为 true（同 `ui=boss_cleared` 的判据，issue #42），其余界面一律 false。
  - 条件键（有才出现，别把 23 写死）：`in_flight`（有动作在飞，`ready` 被压成 false）、`blocked_ui`（屏上没有可执行动作时 = 当前 `ui`；`get_state` 未就绪回包的**顶层**也有同名同值字段，#121·C12-04）、`crash`（崩过一次的摘要）、**`dead_for_ms` / `dead_hint`（只在 `game_dead:true` 时）**。
  - **`game_dead` 是终态信号**（issue #69）：崩溃被捕获 **且** 帧计数停住 = 再等也没用，`get_state`/`legal_actions` 这回 `error.code=GAME_DEAD`。看到它就喊人重启，别轮询。`render_crashed` 只表示「崩过」；`game_dead` 才表示「救不回来」。
  - `ascension` 在 `ui=char_select` 那一屏是**屏幕上已设的等级**（没开进阶模式就是 0），不是 `AbstractDungeon.ascensionLevel`——后者要到点「启程」才写；所以刚 `choose_ascension` 完就能用它复核（issue #77）。
  - `ui` 值跨版本固定：进商店但还没开屏 = `shop_room`，商人屏打开 = `shop`；两者 `ui_group` 都是 `shop`。
  - `combat` 字段**只加不改**：`round` 与 `turn` 并存（同一个数），`monsters` 与 `enemies` 并存（同一个数组），`piles` 保持原结构、另给 `piles_summary:{draw,discard,exhaust}`。推荐用 `turn`/`enemies`/`piles_summary`，旧字段继续有效。
  - 血量别名：`player.hp` = `player.current_hp`，`enemies[].hp` = `enemies[].current_hp`；两者同值，随便用哪个。
  - `intent`：`{id, has_damage?, damage?, hits?, total_damage?, damage_is_base?, base_damage?, unknown?}`。**`is_gone:true`（已死/已逃）的怪没有 `intent` 字段**——别把它当成「下一回合还要打你」；怪仍留在 `enemies` 数组里（`index` 就是 `target_index` 的下标，不能删）。**只有 `ATTACK*` 意图才有 `has_damage`/`damage`**（`DEFEND`/`ESCAPE`/`BUFF` 不带，避免出现「防御意图 + 10 伤害」这种陈旧数字）；`unknown:true`（`DEBUG` 意图）时连 `has_damage` 都不给——这一帧引擎还没算出意图，别据此判断安全。
  - `player.energy` / `energy_max` **只在战斗房里有数值**，其它界面是显式 `null`，并多一个 **`energy_note`**：「非战斗态：能量只在战斗房里有意义（原版能量面板会保留上一战的数字，照抄会像「你还有 3 费」）；进战斗后 energy / energy_max 会回来。」（#124·C15-04）看到 `null` 别当漏字段，也别拿上一战的数字推算。
- `hints` 关键字段（逐条出现条件见 `docs/MCP-返回格式.md` §9）：`grid_wanted`/`grid_can_confirm`/`grid_preview`（**仅 `ui=grid`**，判据与 planner 一致：确认屏已弹 / `isJustForConfirming` / 确认按钮可用 / 已选够）、`last_error` + `last_error_fresh`（**三条件**：`last_error_seq == act_seq` + **是动作错误**（`get_state`/`legal_actions` 的读失败不算）+ 记下之后 `state_version` 没再推进；#119·C10-05）+ **`last_error_age_ms`** + `act_seq`/`last_error_seq`、`potions_full`（**只在 `ui=combat_reward` 且腰带满时写**，而且正常拍整块 hints 会被过滤掉 —— 要它得 `include:["hints"]`，日常判断腰带满看 `rewards.items[].takeable` / `player.potions`）、`event_missing`、**`map_no_reachable`（仅地图：无节点且 Boss 也不可进；这种 ready 帧 hints 会保留）**、`match_game`、`frames` 与 `frames_stalled`+`frames_stalled_ms`（主循环停摆时看这个）、**`note_history`（只有这一帧真有 `note_state:"new"` 的对象时才出现的一句提醒：便签是历史、当前数值以同回包字段为准；#127 —— `"seen"` 帧没有，它出现不代表出事）**。
- 这五个诊断字段**每拍现读现算**（issue #88），`get_state` 与 `legal_actions` 同拍一致（`last_error_age_ms` 有毫秒级墙钟差）。`last_error` 的两种前缀：参数级拒绝 `act → CODE`（如 `act → BAD_REQUEST`），动作级拒绝 `<动作 type> → CODE`（如 `choose_option → STALE_VERSION`、`end_turn → ACTION_IN_FLIGHT`），读状态失败另有 `get_state → NOT_READY` / `get_state → GAME_DEAD`。
- 另外这几组也在这个块里：**存活探针** `frames` / `beats` / `ms_since_frame` / `ms_since_beat`（`frames` 涨而 `beats` 不涨 = 动作线程停了；停摆超过阈值再加 `frames_stalled` / `frames_stalled_ms` / `thread_stalled`）、**场景** `screen` / `gam_phase` / `end_turn_enabled` / `monsters_basically_dead`、**判死** `game_dead`、**占位** `keywords_unseen` / `needs_inspect`（目前恒为 `[]`，别据此判断有没有关键词要看）、`map_no_reachable`、`unknown_ui`、`char_selected`/`next`（`ui=char_select`）、`suggest_note`（`postmortem`）。
- `meta.ui` 是**派生**界面名，判定用它，不要用 `meta.screen_type`（那是原始调试值）。

### `legal_actions`

- 无参数。返回 `ready`、`state_version`、`ui`、`actions[]`。
- 每个动作有 `id`（只在当前 `state_version` 内有效）、`type`、`label`（人话）、`payload`。
- `actions: []` **永远带 `ready:false` + `blocked_ui`（= 这一拍 ui）+ `error.code:"NOT_READY"`**（#120·C11-03；载入窗口那句是「存档正在载入…」，#121·C12-01）：`message` 两种（门闸没就绪 / 屏在切换、约 1 秒后重试）。等下一拍，不要自己造动作；也别把空表当「没有出路」。

### `act`

- 入参：`type` + `state_version`（**必填**）、`payload`、`action_id`。`action_id` 是**可选**但强烈建议的幂等键（同 id 只执行一次；不传不报错，但重复提交就失去保护）。
- 返回 `outcome`：`applied`（成功并回到稳定状态，带 `log`）/ `queued`（已进游戏但没等到稳定；`actions` 是空数组、`ui` 取当前快照，与同一拍 `get_state` 同源，#119·C10-02）/ `rejected`（没执行，带错误码）。
- **`applied` 也可能是「已提交、世界还在动」**（#123·C14-01/02/03）：`continue_run` / `embark` / 放弃类（`abandon_run` `abandon_mid_run` `confirm_abandon`）回的是 `applied` + **`settled:false`**（目前只有这些回包带 `settled`，恒 `false`）—— 点击真交出去了，但世界在过渡：`ready:false`、`actions:[]`、`awaiting` 是 `loading`/`transition`，`log` 写「已提交…不要重发；轮询 get_state 直到…」。**不要重发、不要当失败**；**回包耗时 0.55~0.70 s**（一次主线程执行 + 一帧过渡，不是 ~0.1 s 的「立刻回」），验收写成「**≤1 s 且 `outcome` 不是 `queued`**」。它和 `queued` 不同：`queued` 还没落地、会锁 3 秒 `ACTION_IN_FLIGHT`，`settled:false` 是已落地、等载入/退出窗口结束（见 `references/tools.md` 与 `docs/MCP-返回格式.md` §8.2）。
- `log` 只是一句话，**别指望所有动作都有数字**（issue #108·C7-11）：只有 9 种动作会尝试拼差值（`play_card` `end_turn` `use_potion` `discard_potion` `choose_hand_cards` `confirm_hand_select` `cancel_hand_select` `take_reward` `proceed`），维度只有「能量 / 手牌 / 格挡 / HP / 回合 / 怪物 HP」且**变了才写**（怪物 HP 归零写 `击杀`）；其中 `take_reward` / `proceed` 通常就只剩 `领取奖励` / `继续`。其余动作（`open_shop` / `buy` / `purge_card` / `take_card` / `skip_card` / `open_chest` / `choose_map_node` …）只有 HUD 那句（`看看商店`、`买了件东西`、`走向 第3行 · 精英（左2）`），**没有数字**。**金币变化永远不在 `log` 里**（看 `player.gold`）；完整对照见 `docs/MCP-返回格式.md` §4。
- `queued` 之后禁止再 `act` 新动作：任何新动作都会被 `ACTION_IN_FLIGHT` 拒（换 id 也不行），只能轮询 `get_state` 等 `state_version` 推进；要重发同一动作只能带同一个 `action_id`。**`settled:false` 之后同样不许发动作**（载入/退出窗口里 `act` 一律 `NOT_READY`），只能轮询。
- 护栏看的是 `state_version`：推进即解除；**3 秒**仍无推进会**真释放**（`ActionInFlight.TIMEOUT_MS`，到点即解除，不靠别的调用顺带清）并写 warn 日志；**护栏那种** `ACTION_IN_FLIGHT` 回包里的 **`retry_after_ms`** 就是「还剩多少毫秒放开」（单飞队列 BUSY 那种没有，见下文 `ACTION_IN_FLIGHT` 的两个来源）。旧文档写的「30 秒」是过期数字。
- `act` 交出去后**先等最多 10 秒**（`ACT_STABLE_TIMEOUT_MS`）看版本动没动，才决定回 `applied` 还是 `queued`：所以最坏感知 ≈ 10 + 3 = 13 秒，这个窗口里重发只会换来 `ACTION_IN_FLIGHT`。
- 不要在返回里期待完整状态；下一拍自己 `get_state`。
- `act` 回包里**没有** `gaze_cleared` 字段（旧文档提过，实现里没有）；看牌状态只影响 `card_info` 的 `shown`。

### `say`（HUD 思考块）

`say { text, ttl_ms? }` → `{shown, chars, ttl_ms}`。

- 把一行字推到游戏左上角那块淡蓝紫的思考块。**只影响显示**，不改任何游戏状态，不消耗 state_version，也不参与幂等。
- 一次只显示最新一条，新的覆盖旧的；`ttl_ms` 默认 12000，到期自己消失，想留着就再推。
- `shown:false` = 这次没画上去（没屏/被限流），换一句再推。
- 走 MCP 的等同物是 `POST /hud/say`（非 MCP 端点，body 同样是 `{text, ttlMs}`）。

### `card_info`（内部调试，agent 不需要调用）

> 抬牌是 mod 的自动副作用（配置项 `gaze_ms`，默认 2500ms）：agent 想了解一张牌就调 `card_info`，画面会自己抬起它，agent 不需要知道动画存在。

- 入参：`{ "uuid": "..." }`（只用 uuid）
- 返回：`uuid` / `id`（cardID，便签用）/ `name` / `type` / `cost` / `rarity` / `description` / `keywords` / `shown` / `note`
- 副作用：把那张牌抬起 `gaze_ms`；**不改** `state_version`
- 找不到牌返回 `CARD_NOT_FOUND`
- 原 0.9 设计里的 `inspect`（kind/duration_ms/clear）**不再实现**，能力已并入本工具


### `plan_route`（未实现，P2）

- 任意稳定状态都能调，不消耗回合、不改 `ui`。
- 载荷 `{ state_version, nodes: [{x,y}, ...] }`；`{ "clear": true }` 擦掉路线。
- 画一半是合法的，不要求一路画到 Boss。详见 `references/map-route.md`。

### `compendium`（未实现，P2）

- `{ kind, id, ascension }`，`kind` 取 `card` `relic` `potion` `monster` `event`；省略 `ascension` 用当前局。
- 一次最多 8 个：`{ queries: [{ kind, id }, ...] }`，给选牌/选遗物时省往返。
- 它是**图鉴**，不是这一手的现场。（**本工具是 P2 设计稿，还没注册进 `tools/list`，现在调不到。**）将来上线后「查不到」按现行错误码口径走 `TERM_NOT_FOUND`（`glossary` 现在就用这个码），旧设计稿写的 `UNKNOWN_ID` 不会出现 —— 别为它写分支。也不要拿模型记忆补。

### `glossary`（已实现）

游戏内百科全书。返回文本里的已知术语会被 `【】` 标出（`获得【格挡】5 点`），并同时给 `keywords:["格挡"]` 这样的机器可读字段；对某个词拿不准就查它，别猜。

- `glossary{term:"格挡"}` / `{"term":"block"}` / `{"term":"Dexterity"}` → `{ok:true, results:[{term, aliases, definition, source:"keyword"|"power"}]}`
- `glossary{terms:["格挡","力量","易伤"]}` → 批量，未命中的进 `not_found`（带 suggestions）
- `glossary{query:"格"}` → 前缀/包含模糊搜索
- `glossary{list:true}` → `{ok, count, results, by_source:{keyword:N,power:N}}`。字段名是 **`results`**（旧写法 `terms` 不存在了）；**`count` 小于 171 是正常的**——占位符（`[MISSING_NAME]`/`[MISSING_0]`）不再当词条，有机器 id 的回退成 id 并标 `name_resolved:false`（issue #61）
- 未命中：`{ok:false, error:{"code":"TERM_NOT_FOUND","message":…}, suggestions:[最近 3 个]}`，不会静默空返回；`isError` 这时是 `true`（issue #86）

标记覆盖：`combat.hand[].text`、`card_info.text`、`card_reward.cards[].text`、`event.body`、`event.options[].text`、`relics[].text`、`player.potions[].description`、`powers[].text`（powers 另有 `term` 字段）。

- `{ ids: [...] }`，最多 8 个，例如 `Vulnerable`、`Weak`、`primer.ascension`。
- 返回 `title`、`text`、`source`（`game` = 游戏本地化，`primer` = 手写规则）。引用时区分来源。

### `note_set` / `note_get` / `notes_list` / `event_note`（已实现；`note_retract` 未实现）

- 写：`{ kind, id, text }`；`kind` 只能是 `event` / `potion` / `card` / `monster` / `relic` / `general`，其它值（**包括旧设计稿的 `scope:"run"`**）一律 `BAD_REQUEST`。
- 读：`note_get { kind, id }` → `{ok, key, text}`（没有这条就是 `text: null`）；列表：`notes_list { kind?, sort? }`（`recent` 默认 / `name`）。
- 删单条：`note_set { kind, id, text: "" }`；`event_note { text }` 是事件房快捷写。**没有** `note_retract`（`ToolCatalog` 只有常量），也没有批量删除/重置。
- 便签存 `notes.json`、**跨局保留**、单条 2000 字静默截断；完整字段与边界见 `docs/MCP-返回格式.md` §11，agent 侧怎么用见 `references/memory.md`。
- **人的可视反馈（全中文，每行 ≤28 字）**：
  - 笔记行（浅青色描边，9 秒淡出，60 秒同 key 去重，日志标签 `HUD 笔记行:`）：`记下：大颚虫会加力量，先杀它` / `翻了下笔记：共 10 条，最近记的是「开局先攒格挡」` / `上次记过：地精大块头：怕技能牌`；
  - 动作行（普通行，进入滚动列表）：`打出手牌《打击》` / `结束这一回合` / `喝下药水《火焰药水》` / `走向 第3行 · 精英（左2）` / `继续前进` / `收下遗物` / `打开宝箱` / `拿走奖励` / `不拿这张牌`；
  - 查看行：`看了看场面（第 289 拍）` / `又确认了一遍能做什么`；
  - 全部只给人看，不影响任何返回值。
- **牌库 peek**：`get_state{include:["deck"]}` 或 `card_info` 在 `ui ∈ {card_reward, grid, combat, shop, hand_select}` 时会**真的把游戏画面切到牌库界面**（`MasterDeckViewScreen`）4 秒，让旁边的人看到 agent 在看什么。peek 是只读的：期间 `get_state`/`legal_actions` 仍报告原界面的 `ui` 与全部动作；**任何 `act` 会先强制关掉 peek 再执行**（所以 4 秒只是「没人打扰时的自关时间」，不是你要等的冷却；`dismiss_tip` 例外，它关的就是 peek 本身，见 issue #98）。日志：`peek_deck: open (from=<ui>…)` / `peek_deck: close (auto-close after 4s / forced-close by act:<type>)`。

### `run_log`（未实现，P2；`ToolCatalog` 只有常量，不在 `tools/list`）

- 设计稿：默认返回本局摘要（`run_id`、种子、路径、每层摘要、便签、已交卷教训 id）。现在想看「上一局怎么死的」只能用 `memory.briefing.recent_runs` / `memory.run_history`。
- `{ include_history: true }` 只给最近 5 局的死亡摘要，不含完整牌表。
- 想复盘「上一局怎么死的」用它，不要翻 `log_tail`。

### `submit_lesson`（未实现，P2；只有常量，不在 `tools/list`）

- 设计稿：死亡/结算画面的交卷 `{ state_version, lessons: [...] }`，最多 3 条，允许空列表。**现在死亡屏只有 `proceed`**，别等交卷。
- 交卷后 `awaiting` 从 `write_lesson` 变 `null`（**设计值**：当前实现里死亡屏的 `awaiting` 是 `"dead"`，`write_lesson` 还没出现）。也可以不交卷直接 `proceed`，但 `run_log` 会记 `lesson_skipped: true`。

## `ui` 与允许的动作

| `ui` | 实际会拿到的 `type` | 备注 |
|---|---|---|
| `combat` | `play_card` `use_potion` `discard_potion` `end_turn`，**最后一条恒为 `abandon_mid_run`**「放弃本局（删存档并回主菜单）」 | `combat.energy` / `combat.energy_max` / `combat.playable_cards`（#123·C14-05，手牌里现在真能打出的张数）；**`combat.play_blocked` 恒定存在**（#124·C15-05）：有阻塞时是一句话「能量不足（energy=0/3）：手牌 3 张这回合都打不出…play_card 因此不在 legal_actions 里，不是接口出错」，没阻塞时是 `null`（不是省略键、也不是 `""`）——看到字符串只该 `end_turn`/用药水。 **刚进战斗头几帧**（#125·C16-04，`combat_settling`）：`ready:false` + `awaiting:"transition"` + `blocked_ui:"combat"`（怪还挂着 `Intent.DEBUG` 占位、手牌空、能量没填），`legal_actions` 是 `actions:[]` +「ui=combat 游戏还没静止…」；等 200~500 ms 再读，**4 秒安全上界**（模组怪一直 DEBUG 也不会永远卡）。 出牌前 mod 会自动抬牌，不用额外调用。**放弃本局永远排最后**（#122·C13-03）：naive「取 `actions[0]`」不会踩到它；只在能操作帧出现，选了就删存档回主菜单，别当普通出口 |
| `tutorial_tip` | `dismiss_tip` | 教程弹窗；**人类自己点顶栏看牌组/弃牌/消耗/牌堆也落到这里**。MCP 自己开的 peek 不算（那 4 秒 `ui` 保持原屏、动作不变，#115） |
| `view` | `proceed`（兜底出口） | 玩家自己开的界面 / 过场 / 设置；先退出，不出牌 |
| `combat_reward` | `take_reward` `proceed`；**腰带满时另有每个可丢槽的 `discard_potion{slot}`**（#116a） | 奖励 kind 见 `references/combat.md`。普通房间：`proceed` 恒在、可放弃剩余奖励，**一次 `proceed` 同时关屏并打开地图**（不会卡在 `ui=idle`）。**Boss 战后的那屏不一样**（#114）：`proceed` 走 `goToTreasureRoom` 进 **Boss 宝箱房**，label「继续（进入 Boss 宝箱房（选 Boss 遗物），放弃剩余奖励）」；第 3 幕/心脏走门或真胜利。**钥匙奖励现在真领取**、且不吞同屏遗物（#116b） |
| `idle`（房间已打完、屏已关） | `proceed`「打开地图」 | 一次到位：执行器直接 `dungeonMapScreen.open`，不需要点两次 |
| `boss_cleared`（Boss 死、地图还没开，issue #42） | `proceed` | `room_type=MonsterRoomBoss` 且 `room_phase=COMPLETE` 那一拍；`meta.room_cleared=true`。以前报 `idle`，agent 会以为什么都没发生。**#114 起它进 Boss 宝箱房**（`goToTreasureRoom`，第 1/2 幕），不是打开地图 —— label 文案仍写「打开地图」，按行为走；过渡调不通会明确 `NOT_READY`；**过场进行中重复发也是 `NOT_READY`（2 秒预算 + 明确原因，不是幂等 ok）**（#118） |
| `card_reward` | `take_card` `skip_card` | `skip_card` 恒在 |
| `boss_reward` | `claim_relic` `skip_relic` | 三选一 + **跳过**（`skip_relic` 调原版 `noPick()`，等于屏上的 skip 按钮）；不拿就点跳过，不会锁死 |
| `map` | 有可达节点：**只有 `choose_map_node`**（+ 常驻 `abandon_mid_run`，**不广告 `proceed`**）；末行只剩 Boss：`choose_map_node{x:-1,y:<行数>}`；无节点无 Boss 但房间没结束：`proceed`「关闭地图」；死角：只有 `abandon_mid_run` | `choose_map_node` 只能选 `reachable` 里的坐标。取消地图的 type 是 **`proceed` 不是 `close_map`**，且只在「无节点、无 Boss、当前房间没结束」时给；死角发 `proceed` → `ILLEGAL_ACTION`（issue #100 的软锁就是它以前假借 proceed 一直 queued）。`hints.map_no_reachable` 只在**无节点且 Boss 也不可进**时为 `true`。**payload 类型错误（`x`/`y` 是字符串/小数/缺键）→ `BAD_REQUEST`**（#110：参数先校验再判房间，不再回「房间还没结束…先 proceed」；拒绝提示只列这一拍真能执行的动作） |
| `rest` | `rest` `smith` `lift` `toke`，外加**可能有也可能没有**的 `proceed` | 一个篝火只能用一次选项。`proceed` 只在「继续」按钮真可见时给（取决于剩余可用选项/是否已选过），所以同一屏两次读到的 `actions` 可能一个有 `proceed` 一个没有 —— 以 `legal_actions` 为准（issue #97·C3-09） |
| `treasure`（第 1/2 幕 Boss 宝箱房，`TreasureRoomBoss`） | `open_chest` `proceed` | **Boss 遗物在这个宝箱里**：先 `open_chest`（会开 `boss_reward`），领完才给 `proceed`「继续（离开本幕）」（`goToNextDungeon`）；没领就 `proceed` 会被拒（防止丢遗物）。**Boss 战后的奖励屏 `proceed` 进的就是这间房**（#114）。**`proceed` 走 `goToNextDungeon` 且带 2 秒过场预算**（#118）：预算内重发 → `NOT_READY`「上一段 Boss 过场刚开始（已等 Nms / 预算 2000ms）…请等状态变化后再看 legal_actions」；`isDungeonBeaten` 已置 → `NOT_READY`「正在切下一幕…轮询 `get_state` 直到 `ui` 不再是 treasure」；**绝不会「回 ok 但没动」→ `queued` 卡死** |
| `shop_room` | `open_shop` `leave` | 原版要点商人一下才开商店，所以要先 `open_shop`；开好后 `ui` 变 `shop` |
| `shop` | `buy {kind,index}` `purge_card` `leave` | `kind` 是 `card`/`relic`/`potion`；钱不够/已售出会明确 reject。`purge_card` 只**打开净化网格**（净化网格是 `forPurge` 的**一击式**：`choose_grid_cards` 选一张就关屏并扣钱，**没有 `confirm_grid`**）；**净化完掉回 `ui=shop_room`**（`screen=NONE`、`room_phase=COMPLETE`），想接着买得**再发一次 `open_shop`**（issue #97·C3-02，旧文档写「仍是 ui=shop」是错的）。**净化是「每家商店各一次」，不是一局一次**（issue #108·C7-06）：用掉后这一家 `purge.available=false`，换一家店又有一次；`purge.cost` 随本局已净化次数上涨（首次 75、每次 +25 → 75/100/125…；笑脸面具压到 50、送货员 ×0.8、会员卡 ×0.5），**按 `cost` 判断，别把 100 写死** |
| `event` | `choose_option` `event_continue` `proceed` | 无选项时给 `event_continue` 推进 |
| `treasure`（普通宝箱房 `TreasureRoom`） | `open_chest`（**实测那一拍只有它**）；`proceed` 仅当宝箱开完、奖励领净、`canProceed()` 为真才出现 | 开箱/上钥匙按真实按钮。别把 `proceed` 当随时可用（QA 第二轮实测没出现；手动发能过但那是绕开 planner，issue #92） |
| `grid` | `choose_grid_cards` `confirm_grid` `cancel_grid`（**条件出现**） | `cancel_grid` 只在原版**真有 CancelButton** 的网格给（营火 smith、商店净化）；**确认式网格（Transmogrifier 变形、Designer 升级/删除、Astrolabe）只给 choose/confirm**（issue #96：那条 cancel 会被事件当成「已选完」，牌一张不变就结算）。硬发 → `ILLEGAL_ACTION`。单击即生效的网格（涅奥移除/转化、瓶装、商店净化）选一张就自己关屏，不出现 `confirm_grid` |
| `event`（GremlinMatchGame 翻牌配对） | `choose_match_card {index}`，外加 `proceed`（**PLAY 上 = 放弃，会损失奖励**） | `get_state.match_game` 给出 `screen`（字符串：INTRO/RULE_EXPLANATION/PLAY/COMPLETE/CLEAN_UP）、`game_done`、`matched`、`cards[{index,id,name,matched,is_first_pick}]`、`first_pick`（空串=没有）、`flipping`；**`attempts` / `flip_stalled_ms` 只在 `screen=PLAY` 出现**（#119·C10-06：非 PLAY 页上 `flip_stalled_ms` 会从进小游戏起一路涨，像卡死），`get_state.match_game` 与 `event.match_game` 两处都裁。**`proceed` 的出现时机（#120·C11-04）**：PLAY 上棋盘 8 秒没动时会**和翻牌动作并存**多给一条「放弃小游戏并离开（会损失奖励）」；`game_done` 后是「完成小游戏」。选了就等于放弃奖励 |
| `hand_select` | `choose_hand_cards` `confirm_hand_select` `cancel_hand_select`（**条件出现**） | 三态（issue #74 / #102，planner 与执行器同源 `HandSelectPolicy`）：**还有牌能选** → 每张未选中的牌一条 `choose_hand_cards{uuid}`；**点确认真能关屏**（`canPickZero` 且没选牌 / `anyNumber` / `upTo` / 已选够 N 张）→ `confirm_hand_select`；**`cancel_hand_select` 只在「一张没选、点同一个按钮就能关屏」时给**（`canPickZero`/`anyNumber`/`upTo` 这类屏；原版这块屏只有一个按钮，cancel = 空手点它）。**必选屏（恰好 N 张、`canPickZero=false`）没有 cancel**，硬发 → `ILLEGAL_ACTION`「这个选择必须选够 N 张」；**已选牌时也不给**（那一下等于确认，硬发 → `ILLEGAL_ACTION`「取消等于确认」）。连 choose/confirm 都给不出的病态屏才兜底给一条 cancel |
| `neow` | `choose_neow_reward` `proceed` | 首拍 `label` 常是「选项：对话」—— 那是涅奥开场对白的按钮（`option.msg` 就叫「对话」），点了只推进对白、**不领奖励**；真奖励选项随后才出现。只给 1 个选项时会附带 `proceed`「继续」 |
| `main_menu` | **有存档**：`continue_run` `abandon_run`；**无存档**：`open_mode_select`；**弹窗**：`confirm_abandon` `cancel_abandon`（**没有** `proceed`，也**没有** `back`）；**载入窗口**（`continue_run` 点下去到 run 出来之前）：**没有动作**，`legal_actions` 回 `NOT_READY` + `awaiting:"loading"`（#121·C12-01，只轮询、别重发 `continue_run`） | 按钮组按存档二选一（issue #67）：**有存档的那一拍没有 `open_mode_select`**，**无存档的那一拍也没有 `continue_run`**（放弃本局后立刻消失，要走 `open_mode_select` → `mode_select` → 选人才起得来）；有弹窗先处理弹窗。**主菜单不下发 `proceed`**（issue #70：以前发 `proceed` 会崩成 `INTERNAL`/NPE，现在即使硬发也只会得到 `ILLEGAL_ACTION`；`back` 是 `mode_select` 的动作，主菜单没有） |
| `mode_select` | `choose_mode` `back` | |
| `char_select` | `choose_character` `choose_ascension` `embark` `back` | **`保持 X` 是幂等 verified**（#122·C13-05）：对已选中的角色再发一次 `choose_character` 不点按钮、不排队，直接 `applied` —— 连发两次都 `applied`，不会 `queued`、也不触发 3 秒 `ACTION_IN_FLIGHT`（旧实现回 `ok` 没动 → 判 `queued`，真机堵过 1.7 s）。**前置条件**（issue #108·C7-09）：**没人被高亮时只有 `choose_character` + `back`**（没有 `embark`，也没有升天档；`hints.next` 会说下一步是 `choose_character`）；高亮之后 `embark` 排 `actions[0]`，升天档才出现。`choose_ascension` 只在该角色已解锁升天时出现，梯子是 0/1/5/10/15/20（**当前等级那一档不列**） |
| `map` 与 `combat`（**只有这两处**：#122·C13-03 起战斗屏也会广告，战斗里固定最后一条；`ui=idle` 等其它屏**不广告**） | `abandon_mid_run` | 放弃本局：删存档 + 回主菜单。**原版是暂停菜单里那个 Abandon Run**，agent 判断这局没救了才用。战斗那条标签写明「删存档」；地图那条是「放弃本局（回主菜单）」 |
| `death` / `victory`（没有 `run_over` 这个 ui） | `proceed` | `ui=victory` 只看「是否在 `VictoryRoom`」（赢的那一拍 `screen_type` 还是 `NONE` 也算）；`proceed` 就是「回主菜单」，`VictoryScreen` 没生成时会现建一个。`submit_lesson` 尚未上线（⏳） |
| `transform` | `choose_grid_cards` / `confirm_grid` / `proceed`（**没有 `cancel_grid`**：变形网格 `canCancel=false`） | **按 grid 规划**（issue #83 已修）：原版「变形选牌」用的就是 `GridCardSelectScreen`（`forTransform=true`），只是偶尔被报成 `TRANSFORM` —— 两者是同一块屏的别名。流程与选牌网格相同：`choose_grid_cards` 选够 → `confirm_grid`；`proceed` 只是**常驻出口**（grid 一个动作都给不出时才出现），不是唯一动作 |
| `choose_one` / `credits` / `door_unlock` / `idle` / `other` | 看 `legal_actions` 给什么 | 没有就别动 |

要目标的牌才带 `target_index`（`has_target == true`）；没有目标的牌带上会被拒。地图载荷**不止 `{x,y}`**（issue #108·C7-10）：普通节点还带 `room`（中文房间）/ `column`（= `x`）/ `column_label`（`左3`/`中`/`右1`）/ `row`（= `y+1`），执行器只认 `x`/`y`；`{x,y}` 必须是 `reachable` 里的坐标。Boss 那一拍是 `{x:-1,y:<行数>}`。`x`/`y` 必须是**严格整型**：字符串、小数、缺键 → `BAD_REQUEST`（#110；以前会被读成 `-1` = Boss 哨兵值，再回一句「房间还没结束…先 proceed」，那个回答已经删掉）。

## 动作类型要点

只写行为和坑；参数形状以 `legal_actions` 给的 `payload` 为准，不要自己拼。

- **所有按序号寻址的动作**（`take_reward` `take_card` `claim_relic` `buy` `choose_option` `choose_match_card`）的 `index` 一律**严格整型**（#111 / #113 / C8-01 / C8-02）：JSON 字符串 `"0"`、小数 `1.5`、负数、缺键、越界都是 `BAD_REQUEST`，报文带「合法范围 0..N-1（共 N …）」+ 候选清单（`可领取: …` / `可拿: …` / `可选遗物: …` / `可买: …` / `可选项: …` / `牌面: …`）。**别把 `1.5` 当 `1`**：以前会被静默截断成 1，拿错牌/买错货/点错事件分支。
- `choose_map_node` 的 `x`/`y` 同理（#110）：字符串、小数、缺键 → `BAD_REQUEST`；不再回「房间还没结束…先 proceed」，提示只列这一拍真能执行的动作。

### 事件：`choose_option` / `event_continue`

- `choose_option{index}` 的 `index` 就是选项序号（`get_state.event.options[]` 的下标），**它代表事件分支本身**：mod 直接调用事件的 `buttonEffect(index)`（原版点下去真正执行的处理器），返回 `outcome=applied` 表示这个分支已经执行，后面的对话框/动画会自己走。
- 少数自绘按钮的事件（如 `GremlinWheelGame`）需要额外补 `buttonPressed`，mod 已处理，agent 不用管。
- 没有选项可点时 mod 给 `event_continue`（推进打字/对话），不要在那边等 `ready=false` 空转。

### 开局难度：`choose_ascension{level}`

- 原版只有在选人界面用两个小箭头改升天，而且**那个值只在点「启程」时才写进 `AbstractDungeon.ascensionLevel`**（`isAscensionMode ? ascensionLevel : 0`，见 `CharacterSelectScreen` 的确认逻辑）。所以要在 `embark` **之前**设。
- `level=0` 表示关闭升天（普通难度）；`1..20` 是升天等级。**没解锁的等级会被拒绝**并告诉你当前上限（"这个角色只解锁到升天 N"），不会静默夹到上限。
- 设完可以用 `meta.ascension` 复核（进局后还应该等于设的值；实测设 1 → 进局 `meta.ascension=1`）。

### 中途放弃：`abandon_mid_run`

- 等价于原版暂停菜单里的 Abandon Run（`ConfirmPopup` 的 `ABANDON_MID_RUN` 分支）：淡出音乐 → 清事件 → 关当前屏 → `CardCrawlGame.startOver()`，并**删掉存档**（`SaveAndContinue.deleteSave` + 2 秒后二次清理，防止淡出期间又写回一份）。回主菜单后 `continue_run` 不会再出现。
- **提交后的 0.8~1.6 s 是「离开过渡期」**（#123·S2，`RunExitWindow`，上界 10 s）：`abandon_mid_run`/`abandon_run`/`confirm_abandon` 回 `applied + settled:false` 之后，实时帧 `ui:"other"`、`ready:false`、`blocked_ui:"other"`、`awaiting:"transition"`，三个入口都回 `NOT_READY` +「正在离开本局…旧地牢的 ui 与动作表已经不作数…轮询 get_state 直到 ui=main_menu 且 ready:true」。**`act` 的拒绝也带 `blocked_ui` + `awaiting`**（#124·C15-01，`ui` 取读接口同一拍的实时值，不再是缓存快照里的旧屏名）。**别照上一帧的地图/战斗动作表动手**。
- **广告位置：地图 + 战斗**（#122·C13-03）。地图上恒在（有可达节点时也挂）；战斗屏上**固定最后一条**、标签「放弃本局（删存档并回主菜单）」，只在能操作的帧出现 —— 放最后是为了「永远取 `actions[0]`」的 harness 不会一脚踩进去。`ui=idle` / 奖励屏 / 事件 / 篝火 / 宝箱 **不广告**它（避免每个界面都挂一个「认输」按钮），但**执行器在任何局内界面都接受**它。

### 选牌网格：`choose_grid_cards` / `confirm_grid` / `cancel_grid`

- `choose_grid_cards{uuid}` 会**直接生效**：牌进 `selectedCards`，并且在「一次点击就该关屏」的网格里（`isJustForConfirming` / `forPurge` / `forTransform` / `forClarity` / 选满 `grid_wanted` / 升级网格）**当场关闭网格**，随后 `ui` 变化、`state_version` 推进。
- 升级网格（篝火 `smith`）：选一张 → 同时写 `upgradePreviewCard` 并关屏，营火自己完成升级；不需要再点确认。
- 多选网格（`grid_wanted > 1`）：选够之前 `ui` 仍是 `grid`，可以继续 `choose_grid_cards`；选够后给 `confirm_grid`。
- 判断能不能确认看 `hints.grid_can_confirm`（与 planner 同判据：已选 `grid_selected > 0` / 有预览 / 确认屏已弹 / 确认按钮可用）。
- **`cancel_grid` 只在有 CancelButton 的网格出现**（issue #96），且一旦出现就立即生效（不排队）。确认式网格（变形 / Designer / Astrolabe）没有它 —— 走完的方式是选牌（变形这类选一张就自己关屏；选够但没关时再 `confirm_grid`）；硬发 `cancel_grid` 会 `ILLEGAL_ACTION`，因为原版事件会把「屏关了」理解成「选完了」，结果是牌没变、事件却结算了。

### 篝火：`rest` / `smith` / `lift` / `toke`

- **一个篝火只允许用一个选项**。用掉之后 `ui=rest` 只给 `proceed`；再 `act rest/smith` 会返回 `ILLEGAL_ACTION`「篝火选项已经选过了」。
- 锻造（`smith`）会进 `ui=grid`，在那里按网格规则选一张（升级网格一选就关屏，不用再确认，见上面的网格小节）。
- 缺对应遗物时，挖（`toke`）/ 举（`lift`）/ 回忆等选项不会出现在列表里。

### 奖励：`take_reward`

- **领之前先看 `rewards.items[].type`**（#120·C11-02 / C11-06）：奖励屏的屏幕列表来自 `CombatRewardScreen.setupItemReward()` —— **普通房间**会在 `room.rewards` 拷贝后**追加一条 CARD 行**「将一张卡牌加入你的牌组中」（`TreasureRoom` / `RestRoom` / `noCardsInRewards` 的事件不加）。所以 `rewards.count` 可能比房间奖励多 1；`CARD` 行 `take_reward` 只是打开 `card_reward` 屏（不直接给牌）。
- 领一条用 `take_reward{index}`（`index` **必填**、严格 JSON 整数，`slot` 是旧别名：字符串 `"0"`、小数、负数、缺键、越界都是 `BAD_REQUEST`，报文带「合法范围 0..N-1」+「可领取: 0=…, 1=…」清单，issue #101）。mod 以 `reward.isDone=true` 判定领取（CommunicationMod 做法），同一条领过再点不会重复发。
- **`index` 是「这一拍」的列表下标，每领一条就会重排**：领走 `index:0` 之后，原来的 `index:1` 变成 `0`。所以每领一条都要重新 `get_state` / `legal_actions` 拿新 index，**别把上一拍的 index 记下来接着用**。（对照 `shop`：那里的 `index` 是**稳定槽位号**，买走一件后货架不重排。）
- `combat_reward` 永远带 `proceed`，想跳过剩下的奖励直接 `proceed` 即可。**例外（#114）**：Boss 战后的那屏 `proceed` 不是回地图，而是进 Boss 宝箱房选 Boss 遗物（label 写明「进入 Boss 宝箱房（选 Boss 遗物）」）；第 3 幕/心脏走门或真胜利。
- **腰带满时 `rewards` 整块多一个 `potion_blocked`**（#123·C14-06，screen 级字符串，逐条 `takeable:false` 之外的整体解释）：「药水槽已满（3/3）：药水奖励要先用 discard_potion 丢一瓶才能领，丢完再读 legal_actions 就会出现 take_reward；现在直接 proceed 等于放弃这份奖励（含钥匙/遗物）。」看到它就按字面做：`discard_potion` → 重读 `legal_actions` → `take_reward`，别直接 `proceed`。
- **药水奖励在腰带满时拿不了**（#112·C8-03/C8-05）：那条 `rewards.items[]` 带 `takeable:false` + `blocked_reason`，`legal_actions` 不给它的 `take_reward`；硬发 → `POTION_SLOT_FULL`「药水槽满了，拿不了这瓶药水；先 `discard_potion{"slot":N}` 丢掉一瓶再回来拿（可丢: 0=香蕉, …）」。**这一屏同时广告那些 `discard_potion`**（#116a，每个 `canDiscard()` 的槽一条），直接发它就行，不用换屏。**没有 `potion_discard` 屏**（旧文档写的「进丢弃流程」作废）。

## 仅测试模式（`dev_*`）

只有 `meta.test_mode == true` 时这些工具才进 `tools/list`；正式对局不出现，也不要用。

| 工具 | 干什么 | 注意 |
|---|---|---|
| `dev_event {id, act?, force?}` | 强制切到指定事件房。**`id` 必须是类名（CamelCase）**：`BigFish` / `LivingWall` / `GoldShrine`，或全限定类名；**显示名不行**（`Living Wall` / `Golden Shrine` → `EVENT_NOT_FOUND`「找不到事件类: …」，实现是按类名 `Class.forName` 找的）。省事取法：`get_state.event.id` 原样传（issue #97·C3-08）。**`act` 只做标注**（#117）：不传 = 跟随当前幕；传了也只回显/比对，**永远不改 `act`/`floor`/地图推进状态**。回包 `{ok,id,class,room,act}`；传了才带 `act_requested`，与当前幕不一致才带 `act_note` | `force:true` = 跳过忙检查 + **先清空动作队列**（`RoomGuard.forceClear()`），专门从卡死状态自救；默认 false，正常进事件不需要（issue #80 已把这个参数补进 schema） |
| `dev_encounter` | `{list:true}` 列出全部遭遇 id（含 act/常量名）；`{ids:["Jaw Worm"]}`（或 `{id:"Jaw Worm"}`）直接开这场遭遇战，进入后 `ui=combat`、`combat.enemies` 有 id/name/hp/max_hp/intent；`dev_room{type:"monster"}` 复用同一段代码（随机遭遇）。**`ids` 是候选列表，一次只开第一个能用的**（issue #108·C7-12）：回包 `encounter` = 用的那个 id，其余里**认识的**进 `ignored_ids`、**不认识的（不管在第几位）**进 `unknown_ids`（#112·C8-09：旧版把尾巴上的未知 id 塞进 `ignored_ids`，等于骗你说它有效），任一非空带 `notice`；全都不可用才 `ENCOUNTER_NOT_FOUND` |
| `dev_potion` | `{list:true}` 列出全部药水（id/类名/本地化名）；`{id:"FirePotion"}` / `{id:"Fire Potion"}` / `{id:"火焰药水"}` 三种写法等价 |
| `dev_room {type}` | 直接进入指定房间。schema 的 `enum` 是 **8 个主值**：`monster`/`elite`/`boss`/`rest`/`shop`/`treasure`/`event`/`victory`；实现另外认别名（schema 不列但照样生效）：`fight`→`monster`、`true_victory`/`winscreen`→`victory`、`boss_reward`/`bossrelic`/`boss_relic`→ 直接开 Boss 遗物屏（3 个随机 Boss 遗物，用于验 `claim_relic`）。**`victory` 分支会把当前房间换成 `VictoryRoom`**（issue #108·C7-08），回包 `{ok,type,class:"VictoryScreen",room:"VictoryRoom",room_phase:"EVENT",screen:"VICTORY"}`；随后 `get_state` 的 `meta.room_type=VictoryRoom`、`meta.room_phase=EVENT` —— 这样 `proceed` 才会回主菜单，而不是走 Boss 换幕；回包另带 **`monsters_dying`**（收尾前还在死亡动画里的怪数）与 **`monsters_settled`**（这次收尾了几只，#109：切胜利屏前会先把 `isDying` 的怪置成死透，失败连这一步一起回滚；胜利屏的怪物组恒为 `null`） | 写自动化用主值；别名能用但别依赖。**连续切房有 2.5 s 硬冷却**（`RoomGuard.COOLDOWN_MS=2500`，#112·C8-08）：刚切过房间再切 → `NOT_READY`「现在不能切房间: 刚切过房间，等特效结束（还需 Nms）」（`dev_event` 是「现在不能切事件: …」）；`dev_room`/`dev_event`/`dev_encounter` 共用这个守卫，等报文里那个 Nms（或直接 sleep 3 s）后**重试一次** |
| `dev_map` | 导出地图前 3 行的节点/边/父边 | 诊断「没有可达节点」用 |
| `dev_card {id, to?, force?}` / `dev_relic` / `dev_potion` / `dev_reward` | 给牌 / 给遗物 / 给药水 / 造奖励。**回包里的 `rewards` 是屏幕行数**（= 同拍 `get_state.rewards.count`，#120·C11-02；`room_rewards` 才是房间列表条数，`rows` 给每行 `{index,type,text}`）—— 普通房间会多一条原版追加的 CARD 行，不是 dev 工具加的。`dev_card` 的卡牌 ID 三种写法都行（类名 `AThousandCuts` / 游戏 ID `"A Thousand Cuts"` / 全限定名），`to:"hand"`（默认）或 `"deck"`；响应带 `resolved_by`（`library`/`reflection`）与 `obtainable`。**`dev_relic` 也认三把钥匙**（#119·C10-04）：`RubyKey`/`Ruby`/`Red`、`EmeraldKey`/`Emerald`/`Green`、`SapphireKey`/`Sapphire`/`Blue`（大小写/空格/下划线/连字符不敏感，尾部 `Key` 可省）—— `Emerald`/`Sapphire` 会造**真宝箱形态**（一条真遗物行 + 追加其后的钥匙行，两行互挂 `relicLink`，再开奖励屏），回包 `{ok,key,name,rewards,key_index,relic_index,relic,screen_opened}`；`RubyKey` 原版没有奖励行，只置 `Settings.hasRubyKey`。**`dev_reward{kind:"key"}` 与 `dev_relic{id:"SapphireKey"}` 完全等价**（回包另带 `kind`） | 手牌满 10 张会返回 `HAND_FULL`，长测要塞第 11 张得 `force:true` |
| `dev_heal` | 回满血并清 debuff（战斗中也有效），返回 `hp_before/hp_after/debuffs_removed` | 测试模式专用，长扫描每个事件前调一次 |
| `dev_use_card {id, target_index?, force?}` | 回包带 **`energy_before`**（出牌前能量）与 `reason`：能量不够时 reason 明写「正常路径打不出这张牌，dev_use_card 是强制调用（freeToPlayOnce）绕过了能量检查」（#124·C15-03）——**别把 `played` 当「这张牌能打」的证据**。**走原版出牌路径 `AbstractPlayer.useCard`，等同真出牌**：效果由游戏的 `GameActionManager` 在随后几帧真的结算（掉血/格挡/能力都会变），不是只调一下 `card.use()`。两个 dev 口径：不耗能量（`freeToPlayOnce`）、打完即消失（`purgeOnUse`，临时复制品不进弃牌堆）。返回 `result` 三态 + `reason`：`played`（正常返回且按规则可打出）/ `blocked_by_game_rule`（`cost=-2`，诅咒/状态牌，仍跑了出牌路径）/ `metadata_only`（`use()` 抛异常，工具上下文限制）；`ok` 只对前两种为 `true`，另带 **`effect`**（`queued`/`none`/`dropped`）、**`queued_actions`**（这步实测排进动作队列几条）、`needs_screen`（`grid`/`hand_select`）、`resolved_by` | **战斗外直接 `NOT_READY`**（动作队列只在 `COMBAT` 房间执行，战斗外打出的牌必定零效果）：先 `dev_room{type:"monster"}` 进战斗；只有 `use()` 抛异常时才清掉半排的队列（`dropped_actions` 记条数）。卡牌覆盖快扫的判据：443 张每条都要有 `result`（`tools/tmp/cards_report.json`），`metadata_only` 必须带原因 |

`hints.last_error` 只在 `last_error_fresh == true` 时代表「刚才那次 act 的错、而且现在还算数」，否则是历史。`true` 要三个条件同时成立（#119·C10-05）：`last_error_seq == act_seq`、是**动作**的错误（`get_state`/`legal_actions` 的读失败不算）、记下之后 `state_version` 没再推进 —— 所以排队期间的 `get_state → NOT_READY` 点不亮它，动作真落地后旧错误立刻变历史。`act_seq`/`last_error_seq` 可自行比较，但**相等不等于新鲜**。`meta.render_crashed: true` 表示游戏渲染已经崩过（此时 `get_state` 仍可读，`act` 一律 `NOT_READY`，需要重启游戏）。滞留动作超过 5 秒，`RoomGuard` 会自动清一次队列并写日志。恢复顺序：`get_state` 看 `ui` 与 `hints.last_error` → 还是不动就 `dev_event {id:"BigFish", force:true}` 清队列 → 再 `get_state`。

**`meta.game_dead: true` 就别再走恢复流程了**：它表示主线程已经死了（崩溃被捕获 + 帧计数停住），这时的 `get_state`/`legal_actions` 会回 `error.code = GAME_DEAD`。唯一出路是**请人重启游戏**；`render_crashed` 只说明「崩过」，`game_dead` 才说明「救不回来」。

**传输层的两件小事**：① 请求体超过 **1048576 字节**会被直接回 **HTTP 413 + `-32600`**（文案带 `1048576`），这条请求**没执行**，缩小 body 换新 `id` 重发；② `act` 把动作交出去后会**先等最多 10 秒**看版本动没动，才决定回 `applied` 还是 `queued`，所以「动作没落地」的最坏等待 ≈ 10 s + 3 s 护栏 ≈ 13 秒——别在这个窗口里重发。

## 错误码

全部错误码以 `docs/MCP-返回格式.md` §8 为准（那里按「谁产生 / 什么时候 / 怎么办」逐条列了，并与 `ErrorCodes.java` 对齐）。

**拒绝优先级**（#121·C12-03，一个纯函数 `preflightRejectionCode(loading, leavingRun, requested, current, inFlight)` 决定，原则「谁离执行器越远、谁先说话」）：`NOT_READY`（存档载入 / **正在离开本局** / 渲染崩过）→ `STALE_VERSION` → `ACTION_IN_FLIGHT` → 交给执行器（`ILLEGAL_ACTION` 等）。**所有 `act` 拒绝都带 `ui`/`ready`/`actions`**；**过渡窗口里（载入/放弃）还带 `blocked_ui` + `awaiting`**（#124·C15-01），`ui` 取读接口同一拍的实时值（过渡期就是 `other`），三个入口不会各说一个屏。所以版本不对时**永远先拿到 `STALE_VERSION`**，不会先吃屏幕级拒绝。**所有拒绝回包都带 `ui` / `ready` / `actions`**（#121·C12-02，默认 `ready:false`；`STALE_VERSION` 与执行器拒绝会换成新鲜计划）。

`act` 会给出的：`STALE_VERSION` `NOT_READY` `ILLEGAL_ACTION` `UNKNOWN_ACTION_TYPE` `BAD_REQUEST` `CARD_NOT_IN_HAND` `CARD_NOT_PLAYABLE` `NOT_ENOUGH_ENERGY` `TARGET_REQUIRED` `TARGET_INVALID` `POTION_SLOT_EMPTY` `POTION_SLOT_FULL` `POTION_UNUSABLE` `POTION_UNDISCARDABLE` `HAND_SELECT_OPEN` `ACTION_IN_FLIGHT` `INTERNAL`。

工具层另有：`BAD_REQUEST`（便签 `kind` 非法、`text` 漏传/非字符串）、`NOTE_WRITE_FAILED`、`CARD_NOT_FOUND`（`card_info`）、`TERM_NOT_FOUND`（`glossary`）、`HAND_FULL`（`dev_card`）、`EVENT_NOT_FOUND` / `ROOM_NOT_FOUND` / `POTION_NOT_FOUND` / `RELIC_NOT_FOUND` / `ENCOUNTER_NOT_FOUND` / `CARD_USE_FAILED`（`dev_*`）、`DEV_TOOL_DISABLED`（`test_mode` 没开）。

**`error` 的形状（issue #86）**：所有工具失败时 `error` 都是对象 `{"code":"…","message":"…"}`（`act` / `get_state` / `legal_actions` 可能再多带 `state_version`；`act` 的排队诊断 `queue_status` / `waited_ms` / `version_advanced` / `retry_after_ms` 在 **`error.*` 与回包顶层各有一份**，#120·C11-01）；顶层**没有**单独的 `message` 键，`code` 一定是 `UPPER_SNAKE` 机器码。并且 **`ok:false` ⇒ `result.isError:true`**（原来 `glossary` 是例外，已修）。

`/hud/*` 是**例外**：只有两个端点 —— `POST /hud/say`（把一行字推到游戏左上角的思考块）与 `GET /hud/driver`（谁在开车：哪个 DSH 会话在调这个游戏）。它们不是 MCP 协议，body 是 `{"error":"字符串","status":N}`，别按工具返回解析；方法用错回 `405`（带 `Allow`），名字不对（`/hud/nope`、`/hud`）回 `404`，token 不对回 `401`。

`GAME_DEAD` 是**终态信号**（`get_state` / `legal_actions` 回）：游戏主线程已经死了，重试/轮询都没用，只能请人重启。

> 旧文档里的 `UNKNOWN_ID` `CANNOT_DRAW` `VALIDATION` `NOTE_LIMIT` `UNAUTHORIZED` 是设计稿里的码，**当前实现不会返回**（`UNAUTHORIZED` 只以 HTTP 401 + `-32001` 的形式出现在传输层，不是 `act` 的错误码）。

`ACTION_IN_FLIGHT`：**两个来源**（issue #108·C7-02 定码、#125·C16-02 收紧）—— ① 上一个 `act` 返回过 `queued`、`state_version` 还没推进（**3 秒墙钟护栏**）：回包里带 **`retry_after_ms`**（还差多少毫秒护栏放开）；② **单飞队列 BUSY 且 `ActionInFlight` 里确实压着一条没落地的动作**：回包带 `queue_status:"BUSY"` / `waited_ms` / `version_advanced`，但**没有 `retry_after_ms`**。**BUSY 但没有任何动作在飞 → 回的是 `NOT_READY`**（#125·C16-02：主线程只是被过渡/动画那一帧占着，message 会写「主线程正忙着执行别的东西…不是我们上一条动作在落地」）—— 这支隔 200~500 ms 再读即可。两种都是**不要再发动作**，只轮询 `get_state`；版本一推进自动解除，墙钟 3 秒到点也会真释放（不依赖别的调用顺带清）。重复发送会把两次点击叠到同一个对话框上，可能冻结游戏。**结论口径（#120·C11-07 + #125）**：同一个 `state_version` 上**并发连发两条 `act`**，第二条**要么 `ACTION_IN_FLIGHT` + `queue_status:"BUSY"`（第一条真在落地）要么 `NOT_READY`（队列忙但没有动作在飞）**，两种都不是 `BAD_REQUEST`，也不会两条都执行 —— 别把 `NOT_READY` 这支当新 bug 记。诊断字段在 `error.*` 与顶层各一份（#120·C11-01）。

## 记忆 / 便签（notes）

agent 可以对遇到的对象写便签，下次遇到同一对象时 `get_state` 里会自动带出 `note`。

### kind 与 id 规范
| kind | id 取自 | 例子 |
|---|---|---|
| `event` | 事件类简单名（`get_state.event.id`） | `BigFish` |
| `card` | `card.cardID`（`card_info.id`、`combat.hand[].id`） | `Strike_R` |
| `potion` | 药水类名（`player.potions[].id`） | `FirePotion` |
| `monster` | `combat.monsters[].id` | `JawWorm` |
| `relic` | `player.relics[].id` | `BurningBlood` |
| `general` | 自定义 | `run-1` |

### 工具
- `note_set {kind,id,text}` → `{ok,key,text}`。`text` **必填**，三态：正常字符串 = 写入；`text:""` = 删除；**漏传 / `null` / 非字符串 = `BAD_REQUEST`**（文案直接告诉你「要删除请显式传 text:""」——漏传**不会**再被当成删除，issue #59）
- `note_get {kind,id}` → `{ok,key,text}`（没有便签时 text=null）
- `notes_list {kind?, sort?}` → `{ok, notes:{"card:Strike_R":{text,updated_at}}, count, sort, items:[{key,kind,id,text,updated_at}]}`（`sort` 在 `inputSchema` 里：`enum=["recent","name"]`，默认 `recent`；旧写法 `sort:"id"` 等价于 `name`，回包只回显 `recent`/`name`）
- `event_note {text}` → 等价 `note_set{kind:"event", id:当前事件}`，只在事件房可用。`text` 同样三态，但**「当前不在事件房」的 `NOT_READY` 先判**（不在事件房时拿不到 text 校验报错）

示例：
```
note_set {"kind":"card","id":"Strike_R","text":"前期核心，优先升级"}
note_set {"kind":"monster","id":"JawWorm","text":"会加力量，先清小怪"}
event_note {"text":"这次选煎烤"}
```

### 出现在哪里
- `get_state.event.note`、`player.relics[].note`、`combat.hand[]/draw_pile[]/discard_pile[]/exhaust_pile[].note`、`combat.monsters[].note`、**`potions.slots[].note` 与 `player.potions[].note`**（#125 起可读）、`card_info.note`
- **正文只投递一次，`note_state` 说三态**（#127，取代 #125 的「每帧全文」）：只要有 `kind:id`，`note` + `note_state` 每帧都在 —— `"none"` = 没便签（`note:null`，其余 `note_*` 不出现）；`"new"` = 本局第一次投递这个版本（`note` 是**全文** + `note_history:true` + `note_updated_at` + `note_key` + `note_pushed:false`）；`"seen"` = 本局投过了（`note:null` + 另外四个键，`note_pushed:true`）。同回包 `hints.note_history` 那句**只在有 `"new"` 的帧出现**（`"seen"` 帧没有）。**「字段消失」仍不表示推过了**，看 `note_state`。
- 空药水槽 / 没有名字的怪没有 `id`，所以不带 note 键（不是漏字段）。
- **`memory.recent_notes` / `context_notes` / `briefing.general_notes` / `suggest_note` 仍按版本去重**：同一局同一个 `updated_at` 只出现一次，`notes_omitted_this_run` 会涨；`force_notes:true` 可再要一次。
- **改写便签 = 新版本**（#127）：`note_set` 每次写入都把 `note_updated_at` 往前推（同一秒内也 `+1`），所以下一帧该对象必然是 `"new"` 并重新带全文。
- `get_state{force_notes:true}`：这一次响应按 `"new"` 全量返回（全文 + 四键），之后自动恢复去重（下一拍又 `"seen"`）。
- 写完便签立刻生效：`note_set` 会让状态缓存失效，下一次 `get_state` 就能看到
- 存储：`mods/spirelink-data/notes.json`，单条 ≤ 2000 字，跨进程保留

