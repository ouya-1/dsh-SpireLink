# SpireLink skill 包（安装说明）

这是 **Harness 侧的操作技能**：教模型怎么用 SpireLink 的 MCP 工具打《杀戮尖塔》。模组本体在仓库根，不在这里。skill 只讲「怎么操作」，游戏知识（卡牌、关键词、怪物招式）由模组的 `compendium` / `glossary` 工具提供。对应的设计文档是仓库根的 `STS-Agent-Mod-设计方案.md`（v1.1，Harness 侧合同见附录 B）。

## 目录结构

```text
skill/spirelink/
  SKILL.md                 主文件：什么时候用、硬规则、每一拍、常见错误速查
  references/tools.md      工具清单、动作类型要点（事件/网格/篝火/奖励）、ui 与允许动作对照
  references/combat.md     战斗读法：哪些字段可信、药水/奖励结构
  references/map-route.md  地图、路线校验、未知节点
  references/memory.md     本局便签、跨局教训、死亡交卷
  references/recovery.md   错误码处理矩阵与恢复剧本
  README.md                本文件
```

## 装到哪

DSH 会扫描固定的 skill 根目录，**只认一层深度**：`<root>/<name>/SKILL.md`（或 `<root>/<name>.md`）。嵌套更深的目录不会被发现。

装的时候直接用仓库脚本，别手抄：

```bat
scripts\install-skill.bat            :: 用户级目录连结（默认，改仓库即生效）
scripts\install-skill.bat --project  :: 装到 <项目根>\.dsh\skills，只对该仓库生效
scripts\install-skill.bat --copy     :: 拷贝一份，不跟随仓库改动
scripts\install-skill.bat --dry-run  :: 只打印将要做什么
```

| 优先级 | 名字 | 路径 |
|---|---|---|
| 100 | project-dsh | `<项目根>/.dsh/skills` |
| 200 | project-agents | `<项目根>/.agents/skills` |
| 300 | custom | 配置里的 `customSkillDirs` |
| 400 | user-dsh | `<dshHome>/skills`（本机 `<dshHome>\skills`） |
| 500 | user-agents | `<agentsHome>/skills` |

数字小的优先。安装 = 把 `spirelink` 整个目录拷进其中一个根目录：

```text
# 项目级（推荐：只对这个仓库生效）
<项目根>/.dsh/skills/spirelink/SKILL.md

# 用户级（所有会话都能用）
<dshHome>\skills\spirelink\SKILL.md
```

注意事项：

- 目录名、frontmatter 的 `name` 都必须是 `spirelink`（小写 kebab-case）；两者不一致时以 `name` 作为调用名。
- `disable-model-invocation: true` 不要删：删了它模型就会在平时看到并自动加载这个 skill（详见下一节）。
- `references/` 里的文件不会被单独发现，它们是 `SKILL.md` 用相对路径引用的资料，模型需要时会自己读。
- 整体拷目录，不要只拷 `SKILL.md`——正文会引用 references。

## 加载策略：只由用户显式调用

`SKILL.md` 的 frontmatter 里有 `disable-model-invocation: true`。效果：

- **不会出现在模型平时的 skill 目录里**（`available_skills` 里没有 `spirelink`），也不会因为「用户提到尖塔」被自动带进上下文；
- 模型自己调 `skill` 工具会被拒：`skill "spirelink" is not available for model invocation`；
- 只有用户在会话里显式调用（如 `/spirelink`）才把正文注入进来。

这个档位是有意选的：打一局是有状态、要人盯着的活，自动加载会在闲聊里被无关上下文带进来，真要对局时又不一定触发。

## 怎么验证

1. **关键反证**：会话的 `available_skills` 里**不应出现** `spirelink`。出现了说明 `disable-model-invocation` 没生效。
2. **模型调不动**：`skill({ "name": "spirelink" })` 应报 `not available for model invocation`。
3. **用户能加载**：在会话里显式调用 `/spirelink`，正文立刻出现在上下文里，并带 `resourceBase` 目录。
4. **加载后行为对**：此时对模型说「打一局」，它应先 `get_state` 而不是凭印象编效果。

排查：

- 目录里没有 → 检查是不是多套了一层（`skills/spirelink/spirelink/SKILL.md` 不会被发现）、frontmatter 是否缺 `name` 或 `description`、名字是否 kebab-case。
- 目录里有但加载失败 → 检查 `SKILL.md` 的 YAML 头（`---` 开头、两行键值、`---` 结束）、文件是否 UTF-8。
- 内容没更新 → skill 目录会被监听，但**已加载进会话的正文不会自动刷新**；新会话最稳。

## 和模组版本怎么对齐

- skill **不写死模组版本**，也不会替模组宣布能力。
- 第一拍 `get_state` 之后，读 `meta.mod_version`：
  - 字段存在且与本包的配套版本一致 → 正常继续；
  - 字段缺失 → 模组比 skill 旧（`mod_version` 是 v1.0 起才有的字段），提示用户更新模组；
  - 字段比本包配套版本新 → 提示用户更新 skill（工具/字段可能有增补）。
- 配套版本号在打包发布时写进本文件的「发布记录」，仓库里不保证实时。

## 发布记录

| skill 包版本 | 配套模组 | 备注 |
|---|---|---|
| 1.0.0 | 与模组同版本发布（`meta.mod_version` 主次版本一致） | 首次随模组发布；与设计文档 v1.1 对齐 |

## 更新与卸载

- 更新：整目录覆盖。新会话生效；已加载的旧正文不会自动刷新。
- 卸载：删掉整个 `spirelink` 目录，重启会话。
- 改 `name` 等于改调用名，会让 Harness 的工具缓存和脚本里的引用失效，不要随手改。

## 边界

- 这里不放：卡牌/遗物/药水/怪物/事件数据、关键词释义、进阶规则——都在模组的 `compendium` / `glossary` 里。
- 这里不放：打法策略（该不该打精英、该拿哪张牌）。skill 只保证「操作正确」，不保证「赢得漂亮」。
- 这里不放：工具 schema。`tools/list` 才是权威，工具增删以它为准。
