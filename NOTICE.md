# NOTICE

## 非官方项目

SpireLink 是一个**非官方** mod，与 **MegaCrit**（Slay the Spire 的开发商与版权方）、
**ModTheSpire**、**BaseMod** 均无隶属、赞助或背书关系。

## 不随仓库分发的内容

| 内容 | 版权/来源 | 说明 |
| --- | --- | --- |
| Slay the Spire（含 `desktop-1.0.jar`、美术、音频、文本） | © MegaCrit | 需自行购买并安装；本仓库不包含任何游戏文件 |
| ModTheSpire | © 其作者（t-Launcher / kiooeht 等） | 需自行从 Steam 工坊或官方发布处获取 |
| BaseMod | © 其作者 | 同上 |
| 构建产物（`SpireLink.jar`） | 本仓库代码 | 只包含本项目自己的类与自制 UI 素材（`spirelink/ui/cursor/*.png`） |

`lib/` 在 `.gitignore` 里，构建前需要你把上面三个 jar 自己放进去，见 [`lib/README.md`](lib/README.md)。

## 截图

`docs/img/` 里的图片是**功能说明用的游戏截图**，其中的游戏画面、卡牌插画、界面素材版权属 MegaCrit；
本项目对其不作任何授权主张，仅用于说明本 mod 的 HUD / 光标行为。

## 第三方代码

除游戏与两个加载器外，构建期只依赖 Maven Central 上的
[Gson](https://github.com/google/gson)（Apache-2.0）与 [JUnit](https://junit.org/junit4/)（EPL-1.0），
运行时不再引入其它依赖。
