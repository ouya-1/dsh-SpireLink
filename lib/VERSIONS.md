# lib/ 里的 jar 与版本基线

> 本文件只记录**版本与来源**；jar 本身在 `.gitignore` 里，需要你从自己的游戏安装和 Steam 工坊里拷进来，
> 放法见 [README.md](README.md)。

## 一、运行时四件套

| 组件 | 版本 | 版本从哪看出来的 |
| --- | --- | --- |
| 杀戮尖塔 | 2.3.4（构建日期 2022-12-18） | 项目基线；与 BaseMod 声明的 `sts_version: 12-18-2022` 一致 |
| 游戏自带 JRE | 1.8.0_144 | `<游戏目录>/jre/release` 里的 `JAVA_VERSION` |
| ModTheSpire | 3.30.3 | `ModTheSpire.jar` 内 `META-INF/version.prop` 的 `version` |
| BaseMod | 5.56.0 | `BaseMod.jar` 内 `ModTheSpire.json` 的 `version`、`sts_version`、`mts_version` |

## 二、lib/ 里应有的三个文件

| 文件名 | 从哪来 |
| --- | --- |
| `desktop-1.0.jar` | 游戏安装目录根下 |
| `ModTheSpire.jar` | Steam 工坊 `646570/1605060445`，或手动安装到游戏目录 |
| `BaseMod.jar` | Steam 工坊 `646570/1605833019` |

## 三、换版本会怎样

`pom.xml` 用 system scope 指这三个 jar，只作编译期依赖。换游戏版本时最容易坏的是
**混淆后的字段名和方法签名**（本项目的补丁直接贴着 `AbstractDungeon`、`AbstractPlayer`、
`CombatRewardScreen` 这些类写），所以升级前先跑 `mvn test`，再把真实流程走一遍。

## 四、构建工具链

| 组件 | 要求 |
| --- | --- |
| JDK | 8（`maven.compiler.source/target = 1.8`） |
| Maven | 3.9.x |

`scripts/build.bat` 会优先找仓库内的 `tools\jdk8` 和 `tools\maven`（可选的便携布局），
找不到就用 `JAVA_HOME` 和 PATH 上的 `mvn`。
