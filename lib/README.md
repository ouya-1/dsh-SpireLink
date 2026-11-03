# lib/ —— 需要你自己准备的 jar

`lib/*` 被 `.gitignore` 排除，仓库里不带任何 jar。构建前把下面三个文件放进来（文件名必须一致，`pom.xml` 用的是 system scope 绝对路径）：

```
lib/
  desktop-1.0.jar     <- <游戏目录>/desktop-1.0.jar
  ModTheSpire.jar     <- <Steam>\steamapps\workshop\content\646570\1605060445\ModTheSpire.jar
  BaseMod.jar         <- <Steam>\steamapps\workshop\content\646570\1605833019\BaseMod.jar
```

版本基线（换版本可能编译不过，主要风险在混淆后的字段名）：

| 组件 | 版本 |
| --- | --- |
| Slay the Spire | 2.3.4（`12-18-2022`，即 `desktop-1.0.jar` 对应版本） |
| ModTheSpire | 3.30.3 |
| BaseMod | 5.56.0（`sts_version: 12-18-2022`） |

> 这三个 jar 的版权归各自作者，本仓库不分发（见 [NOTICE.md](../NOTICE.md)）。
> `VERSIONS.md` 里记录了这些版本是怎么核对出来的。
