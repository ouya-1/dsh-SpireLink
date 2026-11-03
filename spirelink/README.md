# SpireLink

Slay the Spire 模组：在游戏进程内跑一个 MCP（Model Context Protocol）服务，
把局面按设计文档第 7 节的区块结构暴露给外部 Agent。P0 只实现一个工具 `get_state`
（`meta` / `player` / `combat`）。

## 基座来源（MIT）

本工程的补丁写法和「主线程队列」思路参考了
[MCPTheSpire](https://github.com/ifree/MCPTheSpire)（作者 ifree，MIT 许可证）：

- 参考仓库保留在 `vendor/MCPTheSpire`（已 gitignore，不参与构建）。
- 代码按本项目设计文档第 16 节的包名与职责重写，未直接拷贝其实现。
- 原项目依赖的 ModTheSpire / BaseMod / Java 8 组合保持不变。

## 运行

1. `scripts/build.bat` 生成 `..\\mods\\SpireLink.jar`。
2. ModTheSpire 模组列表里启用 SpireLink，启动游戏。
3. 配置与数据目录：`<游戏目录>/mods/spirelink-data/`
   - `config.properties`：`host` / `port` / `token` / `debug_log`（UTF-8）
   - `spirelink.log`：每个稳定状态一行 `version=<n> ui=<x> hp=<cur/max> hand_uuids=<...>`
   - `spirelink-debug.log`：`debug_log=true` 时的调试日志
4. MCP 端点：`http://127.0.0.1:8731/mcp`（只用环回地址；`token` 非空时要求
   `Authorization: Bearer <token>`）。

## 许可证

MIT。见 `LICENSE`（沿用上游 MIT 文本）。
