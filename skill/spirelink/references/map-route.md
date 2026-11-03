# 地图与路线

## 什么时候有地图数据

- `map` 区块**默认只在 `ui=map` 时给**（约 7 kB，**整幕**）；别的界面想要它得显式 `include:["map"]`（地图生成过就给整幕，没生成给 `null`）。这和 `tools.md` 的默认区块表一致。
- `from_row` 是**整幕所有行**（y=0 起，含走过的行，用 `taken:true` 标出），不是「当前行及以上」的切片；`from_row_start` 只是你所在行的绝对 y。
- 没进地牢时 `map` 为 `null`。

## `map` 区块的真实字段

```json
"map": {
  "act": 1, "floor": 7, "rows": 15,
  "boss_available": false, "boss_present": true,
  "boss": { "x": -1, "y": 15, "room": "MonsterRoomBoss" },
  "from_row": [
    [ {"x": 2, "y": 7, "room": "MonsterRoom", "symbol": "M", "taken": false,
       "edges": [{"x":1,"y":8}, {"x":2,"y":8}], "edge_count": 2}, ... ],
    ...
  ],
  "reachable": [ {"x": 1, "y": 8}, {"x": 2, "y": 8} ]
}
```

> 这份样本抓于旧版：现在 `from_row` 给**整幕所有行**（y=0 起），不是从当前行开始；`from_row_start` 只是你所在行的绝对 y。

| 字段 | 含义 |
|---|---|
| `current` | `{x,y}`：**你现在站的节点**（绝对坐标）；不在图上时为 `null` |
| `from_row[]` | **整幕所有**节点行（从 y=0 起，含走过的行）；每行是节点数组。**坐标一律是绝对 y**：`from_row[0]` 里的节点 `y` 就是它的绝对楼层行，不是相对下标（`from_row_start` 只是你所在行的绝对 y，不是切片起点） |
| 节点 `{x,y,room,symbol,taken,edges[],edge_count,unresolved?}` | `room` 是**地图图标类型**（`MonsterRoom`/`EventRoom`/`EliteRoom`…），整幕都可信——人类开局就能看到整张地图；`symbol` 是人看到的符号；`?` 节点额外 `unresolved:true` **只表示「进去才知道具体是什么事件/商店/宝箱」，不代表图标未知** |
| `reachable[]` | 现在**真的能走**的节点，`choose_map_node` 只接受这里的坐标 |
| `boss_present` / `boss_available` / `boss` | Boss 不是普通节点（它是 `DungeonMap.bossHb` 图标）。末行清完后 `boss_available=true`，此时 `choose_map_node {x:-1}` 表示「打 Boss」 |

## 走一步 `choose_map_node`

- 载荷是 `legal_actions` 给的那一份，**不止 `{x,y}`**（issue #108·C7-10）：普通节点的真实形状是 `{"x":0,"y":1,"room":"怪物","column":0,"column_label":"左3","row":2}` —— `x`/`y` 是地图坐标（`y` 绝对值、0 起），`room` 是中文房间类型（`怪物`/`精英`/`事件`/`休息`/`商店`/`宝箱`/`Boss`），`column` = `x`，`column_label` 是给人看的位置（`左3`/`中`/`右1`），`row` = `y+1`（1 起）。**执行器只认 `x`/`y`**，其余字段只读；`x`/`y` 必须出现在 `reachable` 里，否则 `ILLEGAL_ACTION「节点不可达」` —— **原样回传，别自己拼坐标**。
- **只能往更高层走**（`y` 必须严格大于当前 `y`），同层/下层一律不给、也不接受。
- 末尾一行清完后，`reachable` 为空但 `boss_available=true`，此时用 `choose_map_node {x:-1, y:<rows>}` 进 Boss（**Boss 那一拍没有 room/column/row**，payload 就这两个键）。
- 关闭地图的 type 是 **`proceed`（没有 `close_map`）**：只在「无节点、无 Boss、当前房间还没结束」时给，并且会真的 `dungeonMapScreen.close()`。
- **有可达节点时不会给 `proceed`**（只给 `choose_map_node` + `abandon_mid_run`）；**死角**（无节点、无 Boss、房间已结束）只给 `abandon_mid_run`，这时发 `proceed` 会 `ILLEGAL_ACTION`（issue #100）。

## 没有的东西（别找）

- **没有** `plan_route` 工具，也没有 `planned_route` / `children` / `parent` / `map_here` 这些字段——`get_state` 之前的设计稿里写过，实际未实现，文档以本页为准。
- `lesson_hits`（**还没上线**）里的路线类教训只是笔记，将来上线也不改变上面的字段。

## 房间图标（整幕可见）

- `symbol`：`M` 普通战斗、`E` 精英、`$` 商店、`R` 篝火、`?` 未知、`T` 宝箱、`B` Boss。
- **整幕的图标类型是已知的**，和人类看到的一样：规划时直接读 `from_row[].symbol`（「精英后面紧跟篝火」「宝箱在第 9 层」都是合法判断）。
- 只有 `?` 节点带 `unresolved:true`：**具体内容**（事件/商店/宝箱/战斗）进去才知道，图标本身仍是「未知/问号」。
- 走进去之后 `ui` 变成 `combat` / `event` / `rest` / `shop_room` 等，那时谈得上「里面是什么」。
