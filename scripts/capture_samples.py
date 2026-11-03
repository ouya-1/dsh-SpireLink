#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""按 meta.ui 去重抓 MCP 样本。

一个界面一份 state-<ui>.json（get_state 原始业务体）+ 一份 actions-<ui>.json
（legal_actions 原始业务体），写进 docs/samples/。已经存在的文件默认不覆盖——
样本是「某个版本的真实返回」，覆盖等于悄悄改历史，要覆盖必须显式 --force。

为什么要有这个脚本：docs/MCP-返回格式.md 的每一段 JSON 都声称「从真实游戏里抓下来的」，
但抓法一直只写在 commit message 里（2e82e9e「边跑图边按 meta.ui 去重」），下次补样本得从头写一遍。

用法（游戏在跑、test_mode=true）：

    # 抓当前这一屏（最常用）
    python scripts/capture_samples.py

    # 只抓点名界面：主菜单 → 地图 → 战斗 → 事件 …（不在名单里就跳过）
    python scripts/capture_samples.py --ui map,event,rest

    # 一边玩一边持续抓（每 2 秒看一眼，撞上新的 ui 就落盘，直到 --seconds 到点）
    python scripts/capture_samples.py --watch --seconds 300

    # 只看会写什么，不落盘
    python scripts/capture_samples.py --dry-run

    # 校准 §10 样本索引：docs/samples 目录 ↔ 文档表格一一对应
    python scripts/capture_samples.py --check-index

    # 覆盖已有样本（默认不覆盖）
    python scripts/capture_samples.py --ui combat --force

    # 顺手把一次动作的回包也存下来（会在真机上真的执行动作，默认关闭）
    python scripts/capture_samples.py --ui combat --act

退出码：0 正常；1 连不上 / 参数错；2 --check-index 发现不一致。
"""
import argparse
import io
import json
import os
import re
import sys
import time

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__))))

from spire_e2e_lib import Mcp  # noqa: E402

DEFAULT_URL = "http://127.0.0.1:8731/mcp"
DEFAULT_OUT = os.path.join("docs", "samples")
DOC = os.path.join("docs", "MCP-返回格式.md")

# ui 名里只有这些字符的才当文件名用；ui=unknown:<Screen> 之类的冒号要换成下划线。
BAD_FILENAME = re.compile(r"[^0-9A-Za-z_.-]+")


def safe_name(ui):
    """unknown:SETTINGS 变成 unknown_SETTINGS；其它 ui 原样保留。"""
    return BAD_FILENAME.sub("_", ui or "unknown")


def tool_body(mcp, name, arguments=None):
    """调工具并返回业务体；工具级错误（isError / ok:false）也原样返回，方便存失败样本。"""
    res = mcp.call("tools/call", {"name": name, "arguments": arguments or {}})
    if res is None:
        raise RuntimeError("空响应：%s" % name)
    if "error" in res:
        raise RuntimeError("JSON-RPC error（%s）: %s"
                           % (name, json.dumps(res["error"], ensure_ascii=False)))
    result = res.get("result") or {}
    content = result.get("content") or []
    if content and content[0].get("type") == "text":
        return json.loads(content[0]["text"])
    # 理论上一路都有 content[0].text；没有就把 result 原样返回（客户端要能自检契约漂移）
    return result


def write_json(path, data, force):
    if os.path.exists(path) and not force:
        return False
    parent = os.path.dirname(path)
    if parent:
        os.makedirs(parent, exist_ok=True)
    tmp = path + ".tmp"
    with io.open(tmp, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(data, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    os.replace(tmp, path)
    return True


def capture_once(mcp, out_dir, wanted, force, dry_run, act):
    """抓一屏；返回 (写了几个文件, ui, ready)。"""
    state = tool_body(mcp, "get_state", {})
    ui = (state.get("meta") or {}).get("ui") or "unknown"
    ready = bool(state.get("ready"))
    if wanted and ui not in wanted:
        print("[skip] ui=%-14s（不在 --ui 列表里）" % ui)
        return 0, ui, ready

    stem = safe_name(ui)
    state_path = os.path.join(out_dir, "state-%s.json" % stem)
    actions_path = os.path.join(out_dir, "actions-%s.json" % stem)
    written = 0

    if dry_run:
        print("[dry] ui=%-14s ready=%-5s -> %s" % (ui, ready, state_path))
        return 0, ui, ready

    if write_json(state_path, state, force):
        print("[ok] %s  （ready=%s，state_version=%s）"
              % (state_path, ready, state.get("state_version")))
        written += 1
    else:
        print("[has] %s  （已存在，未覆盖；要覆盖加 --force）" % state_path)

    actions = tool_body(mcp, "legal_actions", {})
    if write_json(actions_path, actions, force):
        print("[ok] %s  （%d 个动作）" % (actions_path, len(actions.get("actions") or [])))
        written += 1
    else:
        print("[has] %s  （已存在，未覆盖；要覆盖加 --force）" % actions_path)

    if act and ready:
        written += capture_one_action(mcp, out_dir, state, actions, force)
    return written, ui, ready


def capture_one_action(mcp, out_dir, state, actions, force):
    """执行 legal_actions 的第一个动作并落盘回包（会真的操作游戏，必须显式 --act）。"""
    offered = actions.get("actions") or []
    if not offered:
        print("[skip] 这一拍没有动作，不抓 act 样本")
        return 0
    action = offered[0]
    body = tool_body(mcp, "act", {
        "type": action["type"],
        "payload": action.get("payload") or {},
        "state_version": state.get("state_version",
                                   (state.get("meta") or {}).get("state_version")),
        "action_id": "capture-%d" % int(time.time() * 1000),
    })
    name = safe_name(action["type"])
    if body.get("outcome") == "applied":
        path = os.path.join(out_dir, "act-ok-%s.json" % name)
    elif body.get("outcome") == "queued":
        path = os.path.join(out_dir, "act-queued-%s.json" % name)
    else:
        path = os.path.join(out_dir, "act-reject-%s.json" % name)
    if write_json(path, body, force):
        print("[ok] %s  （outcome=%s）" % (path, body.get("outcome")))
        return 1
    print("[has] %s  （已存在，未覆盖）" % path)
    return 0


def check_index(doc_path, out_dir):
    """§10 样本索引 ↔ docs/samples 目录一一对应。返回不一致条数。"""
    with io.open(doc_path, encoding="utf-8") as fh:
        text = fh.read()
    start = text.find("## 10. 样本索引")
    if start < 0:
        print("[fail] %s 里找不到「## 10. 样本索引」" % doc_path)
        return 1
    section = text[start:]
    # 10.5「还缺的样本」列的是**计划中**的文件名，不参与一致性校验
    cut = section.find("### 10.5")
    if cut > 0:
        section = section[:cut]
    indexed = set(re.findall(r"^\| `([A-Za-z0-9_.-]+\.json)`", section, re.M))
    on_disk = set(f for f in os.listdir(out_dir) if f.endswith(".json"))
    missing = sorted(on_disk - indexed)      # 有文件、文档没写
    ghost = sorted(indexed - on_disk)        # 文档写了、文件没有
    for name in missing:
        print("[missing-in-doc] %s  （目录里有，§10 没登记）" % name)
    for name in ghost:
        print("[ghost-in-doc]  %s  （§10 登记了，目录里没有）" % name)
    print("[index] 文档 %d 条 / 目录 %d 个 / 不一致 %d"
          % (len(indexed), len(on_disk), len(missing) + len(ghost)))
    return len(missing) + len(ghost)


def main():
    ap = argparse.ArgumentParser(description="按 meta.ui 去重抓 MCP 样本")
    ap.add_argument("--url", default=DEFAULT_URL)
    ap.add_argument("--out", default=DEFAULT_OUT, help="样本目录（默认 docs/samples）")
    ap.add_argument("--ui", default="", help="只抓这些界面，逗号分隔；留空 = 当前这屏")
    ap.add_argument("--watch", action="store_true", help="持续抓，撞到新 ui 就落盘")
    ap.add_argument("--seconds", type=float, default=120.0, help="--watch 的总时长")
    ap.add_argument("--interval", type=float, default=2.0, help="--watch 的轮询间隔")
    ap.add_argument("--force", action="store_true", help="覆盖已存在的样本")
    ap.add_argument("--dry-run", action="store_true", help="只打印会写什么")
    ap.add_argument("--act", action="store_true",
                    help="额外执行一次动作并抓回包（会真的操作游戏）")
    ap.add_argument("--check-index", action="store_true", help="只校准 §10 索引，不连游戏")
    ap.add_argument("--doc", default=DOC, help="索引所在文档（默认 docs/MCP-返回格式.md）")
    args = ap.parse_args()

    if args.check_index:
        return 2 if check_index(args.doc, args.out) else 0

    wanted = set(x.strip() for x in args.ui.split(",") if x.strip())
    mcp = Mcp(args.url)
    try:
        mcp.open()
    except Exception as exc:
        print("[fail] 连不上 %s：%s" % (args.url, exc))
        return 1
    print("[ok] 已握手 %s（Accept 带 sse；服务端只吃 POST）" % args.url)

    if not args.watch:
        written, ui, ready = capture_once(mcp, args.out, wanted, args.force,
                                          args.dry_run, args.act)
        print("[done] 写了 %d 个文件（ui=%s ready=%s）" % (written, ui, ready))
        return 0

    seen = set()
    deadline = time.time() + args.seconds
    total = 0
    while time.time() < deadline:
        try:
            written, ui, _ = capture_once(mcp, args.out, wanted, args.force,
                                          args.dry_run, args.act)
        except Exception as exc:
            print("[wait] %s" % exc)
            time.sleep(args.interval)
            continue
        total += written
        seen.add(ui)
        if not args.dry_run and written == 0:
            print("[wait] ui=%-14s 已有样本 / 不在名单，继续等" % ui)
        time.sleep(args.interval)
    print("[done] 看了 %d 个界面（%s），写了 %d 个文件"
          % (len(seen), ", ".join(sorted(seen)), total))
    return 0


if __name__ == "__main__":
    sys.exit(main())
