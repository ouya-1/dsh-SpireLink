# 战斗里怎么读

原则：**数字用模组算好的，别自己推**。要看牌时 mod 会自动抬牌（`gaze_ms`，默认 700ms），不需要额外调用工具。下面只列容易读错的点。

## 牌（手牌 / 牌堆 / 牌组 / 商店 / 奖励共用一套结构）

| 字段 | 怎么用 |
|---|---|
| `uuid` | 这一张实例的身份证。出牌只认它，不认手牌序号（序号每拍都可能变） |
| `id` | 牌的种类（`Strike_R`）。写便签、查图鉴、挂教训标签用它 |
| `name` / `text` | 当前游戏语言的显示名和说明，`text` 里的数字已经替换过动态量 |
| `cost` | 原样显示。`X` 是 X 费，`-2` 是这张牌本来就不能打；不要自己归一化成 0 |
| `damage` / `block` / `magic_number` | **已经算过力量、虚弱、易伤之后的值**，直接用，不要再加一遍 |
| `target` | 原始目标枚举（`SELF` `NONE` `ENEMY` `SELF_AND_ENEMY` `ALL_ENEMY` `ALL`） |
| `has_target` | 要不要给 `target_index` 的唯一依据（`target == ENEMY` 时为真） |
| `is_playable` / `unplayable_reason` | 不能打时看原因：`energy` `target` `unplayable` `ethereal_blocked` `other` |
| `upgrades` / `ethereal` / `exhausts` / `retain` | 静态标记，用于判断「打完会不会消失」 |
| `needs_inspect` | 这一局还没看过、又不是基础打击/防御。只是提示「新东西」，不影响能不能打 |

- 手牌顺序等于画面从左到右；但**不要用序号出牌**，用 `uuid`。
- 牌堆给 `count` 和 `cards`；只想知数量就不要展开 `cards`。
- 图鉴里的「抽象牌」不做场面修正，另给 `base_text` / `upgraded_text` 和 `values: "base"`——那是图鉴，不是这一手。

## 能量与「打不出牌」

| 字段 | 怎么用 |
|---|---|
| `combat.energy` / `combat.energy_max` | 当前能量 / 本回合上限（#123·C14-05）。和 `player.energy`/`energy_max` 同源；`get_state{include:["combat"]}` 时也有这对数字 |
| `combat.playable_cards` | 手牌里**现在真能打出**的张数（逐张 `hasEnoughEnergy()`）。`0` 且手里有牌 = 这回合只能 `end_turn` 或先用药水 |
| `combat.play_blocked` | **恒定存在**（#124·C15-05）：有阻塞时是一句解释「能量不足（energy=0/3）：手牌 3 张这回合都打不出，只能 end_turn 或先用药水；play_card 因此不在 legal_actions 里，不是接口出错。」；没阻塞时是 `null`（不是省略键、也不是 `""`）。`null` = 这回合没有阻塞，别当缺字段 |

`play_card` 突然不见了，先看 `play_blocked` / `playable_cards`，别以为接口坏了。

## 敌人

| 字段 | 注意 |
|---|---|
| `index` | 数组下标，**死了也不压缩**。所以不要把「下标 1」理解成「第二只活着的怪」 |
| `is_gone` | 死了/走了，不能再当目标；合法动作里不会再列它 |
| `half_dead` | 双体怪被拆掉一半的情况，别当成满血 |
| `current_hp` / `max_hp` / `block` | 当前血量含伤害结算后的结果，格挡是这一手的 |
| `intent` | 只有 `{ id, damage, hits, base_damage }` 四个字段，**没有 name**：游戏没有意图的本地化文案（界面画图标），要人话就自己按 `id` 映射 |
| `powers` | `id` `name` `amount` `text`；没层数的能力没有 `amount` |

意图 `id` 的坑：

- `ATTACK` / `ATTACK_BUFF` / `ATTACK_DEBUFF` / `ATTACK_DEFEND` / `DEFEND` / `DEFEND_BUFF` / `DEFEND_DEBUFF` / `BUFF` / `DEBUFF` / `STRONG_DEBUFF` / `ESCAPE` / `SLEEP` / `STUN` / `MAGIC` 是真实值；`MAGIC` 不是攻击，别按伤害算。
- `NONE` = 游戏明说这一手没意图；`UNKNOWN` = 模组没认出来。两个不是一回事，别当同义词。
- `damage` 是这一手的真实伤害（含力量/虚弱修正），`base_damage` 是没修正的基数；`hits` 是段数。
- 意图只说**这一手**，不是招式表。以后会怎么打去 `compendium` 查怪物条目，并且要带上进阶档位。

## 药水

| 字段 | 注意 |
|---|---|
| `slot` | 皮带槽位，空槽也占位（`empty: true`），下标不会漂 |
| `id` | 药水种类（`FirePotion`），来自游戏药水对象的 ID |
| `can_use` / `can_discard` | 现在能不能用 / 能不能丢 |
| `target_required` | 为真时必须带 `target_index`（药水只有「要敌人 / 不要」两档） |
| `is_thrown` | 是不是投掷类；只影响表现，不影响动作 |
| `text` | 游戏自己的描述（神圣树皮这类全局修正已经算进去） |

整个 `player.potions` 是 `{ slot_count, slots: [...] }`，`slots` 长度恒等于 `slot_count`：

- 空槽只有两个字段：`{ "slot": 2, "empty": true }`。
- `slots` 里**不会出现 `null`**；如果你看到 `potions: null` 或 `slots` 里有 `null`，那是模组 bug——记下 `state_version` 和当时 `ui` 报给 mod 侧，不要自己补空对象。

## 自己这边

- `player`：血、格挡、能量、金币、钥匙、姿态（`stance`）、充能球（`orbs`）、能力（`powers`）。
- `player.relics` 是**便利副本**，权威来源是独立的 `relics` 区块；计数、触发、教训标签都以 `relics` 为准。
- 遗物 `counter == -1` 时没有计数器；`counter_meaning` 只有图鉴登记过的才给。

## 奖励屏的 kind

`gold` `stolen_gold` `relic` `potion` `card` `emerald_key` `sapphire_key`，一共 7 种。没有 `ruby_key`、也没有 `relic_linked`，不要自己造。

## `needs_inspect` 怎么用

- 牌 / 药水 / 遗物 / 怪的 `needs_inspect: true` 只表示「这一局第一次见」，不是要你调工具：出牌/拿牌前 mod 自己会抬牌并把它从 `hints.needs_inspect` 里去掉。
- 真正要看内容就读字段（`name` / `text` / `damage` / `intent`）或查图鉴；不要为了「确认」去开官方界面。
- 地图节点：直接看 `map_here` / `include: ["map"]` 给的节点与连线，语义靠 `plan_route` 的路线对照。

## 读到「不确定」时怎么办

- 字段没有 / 值是 `null`：就是没有，不要猜。
- 图鉴和现场冲突：以现场（`get_state` / `legal_actions`）为准。
- 数值和卡面文字对不上：信 `damage` / `block` / `text`（模组算过的），不要自己重算。
