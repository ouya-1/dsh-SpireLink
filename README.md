# SpireLink —— 让 AI agent 能自己玩《杀戮尖塔》

把游戏局面通过 **MCP（Model Context Protocol）** 暴露给 AI agent：读状态 → 拿合法动作 → 下动作，
全程可被人类在旁边看着（游戏内光标 + 左上角 Agent HUD）。

给 agent 用的操作手册在 [`skill/spirelink`](skill/spirelink)，协议细节在 [`docs/MCP-返回格式.md`](docs/MCP-返回格式.md)，
每个界面能做什么在 [`docs/界面清单.md`](docs/界面清单.md)。

> **非官方项目**：与 MegaCrit、ModTheSpire、BaseMod 均无隶属关系；本仓库不分发游戏本体，也不分发任何第三方 jar（见 [NOTICE.md](NOTICE.md)）。

---

## 一、它是什么

一个 **ModTheSpire mod**。它在游戏进程内部起一个本地 HTTP 服务（默认 `http://127.0.0.1:8731`），
把游戏状态翻译成 JSON，并接受动作指令。

- **读局面**：`get_state` —— 玩家、牌组、遗物、药水、战斗（手牌、能量、每只敌人的意图、牌堆）、
  地图、战斗奖励、卡牌奖励、商店、事件、营火、宝箱、Boss 遗物、各种选择界面。
- **下动作**：`legal_actions` 给出**此刻真正可执行**的动作（每条带 `state_version`），
  `act` 原样执行。版本过期、参数类型错、上一条动作还没落地，都会被明确拒绝，
  并且回包里写清「下一步该做什么」。
- **给 agent 的记忆**：`note_set` / `note_get` / `notes_list` 是跨局保留的便签
  （怪物、卡牌、药水、遗物、事件、通用）；一局结束时 `postmortem` 给出死因摘要。
- **给人看的**：游戏内有个小光标跟着 agent 的动作走，左上角 HUD 显示它正在做什么、写了什么便签。
- **测试模式**（配置里 `test_mode=true`）：额外提供 `dev_*` 工具（跳房间、造遭遇、发牌、加遗物），
  只在测试模式出现，真实难度下不存在。

## 二、环境要求

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| 杀戮尖塔 | 2.3.4（`12-18-2022`） | Steam 版 |
| ModTheSpire | 3.30.3 | Steam 工坊或手动安装 |
| BaseMod | 5.56.0 | Steam 工坊，`sts_version: 12-18-2022` |
| JDK | **8** | 编译目标就是 1.8，不要用 9 以上 |
| Maven | 3.9.x | 只用于构建 |

## 三、安装（只想玩）

1. 拿到 `SpireLink.jar`（自己构建，或下载发布页的附件）；
2. 放进 `<游戏目录>\mods\SpireLink.jar`；
3. 用 ModTheSpire 启动，勾选 **BaseMod** 和 **SpireLink**；
4. 游戏起来后 `curl http://127.0.0.1:8731/health` 返回 200 就说明通了。

配置文件首次运行会自动生成在 `%LOCALAPPDATA%\ModTheSpire\SpireLink\config.properties`：
端口、token、HUD 开关、`test_mode`、倍速都在里面，改完要重启游戏。

## 四、构建

仓库**不包含**必须从你自己的游戏里取的 jar。先把三个文件放进 `lib/`：

```
lib/
  desktop-1.0.jar      <- <游戏目录>/desktop-1.0.jar
  ModTheSpire.jar      <- 工坊 646570/1605060445/ModTheSpire.jar
  BaseMod.jar          <- 工坊 646570/1605833019/BaseMod.jar
```

细节见 [`lib/README.md`](lib/README.md)。然后：

```bat
rem 默认：构建 + 安装到 <仓库上一级>\mods\SpireLink.jar
scripts\build.bat

rem 只构建、不安装（仓库不在游戏目录里时用这个）
scripts\build.bat --no-install

rem 先检查工具链（JDK8 / Maven / 游戏目录）
scripts\build.bat --check
```

脚本优先用仓库内的 `tools\jdk8` 和 `tools\maven`（如果你按这个布局放了），
否则回退到 `JAVA_HOME` 和 PATH 上的 `mvn`。游戏目录默认取仓库的上一级，也可以显式指定：

```bat
set SPIRELINK_GAME=D:\Steam\steamapps\common\SlayTheSpire
scripts\build.bat
```

跑单测：`cd spirelink` 然后 `mvn test`（约 980 条，全部离线，不需要游戏进程）。

## 五、接 MCP

端点是 `http://127.0.0.1:8731/mcp`（JSON-RPC over HTTP，Streamable HTTP）。
先 `initialize`，之后每个请求都要带响应头里的 `Mcp-Session-Id`：

```bash
curl -s -X POST http://127.0.0.1:8731/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"me","version":"0"}}}'
```

除了 `/mcp`，还有三个**非 MCP 协议**的旁路端点：
`GET /health`（探活）、`POST /hud/say`（往 HUD 推一行字）、`GET /hud/driver`（谁在开车）。

工具的准确形状以 `tools/list` 和 [`docs/MCP-返回格式.md`](docs/MCP-返回格式.md) 为准，
真机回包样本在 [`docs/samples/`](docs/samples)。

## 六、给 agent 用

仓库里带了一份操作手册（技能包），装到你的 agent 环境就行：

```bat
scripts\install-skill.bat             rem 装到 %USERPROFILE%\.dsh\skills\spirelink
scripts\install-skill.bat --project   rem 或者装到本仓库的 .dsh\skills\
```

手册入口是 [`skill/spirelink/SKILL.md`](skill/spirelink/SKILL.md)，另有三份参考：
`references/tools.md`（每个工具的边界）、`references/recovery.md`（被拒了怎么办）、
`references/map-route.md` 与 `references/combat.md`（路线与战斗）。

一个最小的 agent 循环长这样：

```
get_state            —— 看 meta.ui / ready / hints
legal_actions        —— 拿这一拍真正能做的事，连同 state_version
act{type, payload, state_version, action_id}
    applied  —— 成了，继续
    queued   —— 已经进游戏，只轮询 get_state，不要重发
    rejected —— STALE_VERSION 就重读状态；ILLEGAL_ACTION 就换个动作；
                NOT_READY 就等一拍（屏在切、动画没走完、或正在载入）
```

## 七、文档

| 文件 | 内容 |
| --- | --- |
| [docs/安装与使用-给大肥鱼.md](docs/安装与使用-给大肥鱼.md) | **给 agent 看的从零上手手册**：装、启动、连 MCP、一局的循环、踩坑表 |
| [docs/MCP-返回格式.md](docs/MCP-返回格式.md) | 每个区块的字段、每个错误码的含义与下一步、拒绝的优先级 |
| [docs/界面清单.md](docs/界面清单.md) | 每个 `ui` 下该做什么、已知边界 |
| [docs/大肥鱼指针.md](docs/大肥鱼指针.md) | 游戏内光标的设计（给人看的那部分） |
| [docs/samples/](docs/samples) | 真机抓回来的回包样本 |

## 八、已知边界

- 只在 Windows + Steam 版上验证过（脚本都是 `.bat` 和 PowerShell）；
- 同一时刻只能有一个客户端在开车，用 `GET /hud/driver` 可以查是谁；
- `test_mode` 会显著改变局面（999 生命、`dev_*` 可作弊），做真实难度验证时务必关掉；
- 少数界面（死亡、胜利，以及个别罕见选择器）自动化覆盖较少，见 `docs/界面清单.md` 的「已知边界」。

## 九、许可证

MIT，见 [LICENSE](LICENSE)。游戏与第三方组件的版权归各自作者，见 [NOTICE.md](NOTICE.md)。
