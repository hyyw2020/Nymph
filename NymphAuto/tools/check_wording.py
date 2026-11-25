#!/usr/bin/env python3.11
# -*- coding: utf-8 -*-
"""
NymphAuto 表述规范化静态门禁（v2.9.16 引入，v2.9.17 分类化，只读、不联网、不改产物）。

约束：仓库文本中不得以用户反馈原话、口语化俚语、拟人或情绪化措辞描述问题，
一律以专业术语陈述结论。本脚本把该约束落成每次发版自动执行的断言。

## 判定口径

禁用的是四类表述，按类别独立维护，便于评审与审计：
  1. 问题原文：用户反馈原话及其片段；
  2. 原文引用标注：把用户原话标注为输入的引用措辞；
  3. 口语化标题：带引号的口语化章节标题；
  4. 俚语拟人情绪化：口语化俚语 / 拟人 / 情绪化措辞 / 拼接瑕疵。

**不禁用**已被项目作为技术现象简称沿用的工程术语，也不禁用中性专业词汇。
v2.9.17 移除两条中性词误伤条目，避免规范化反过来损失工程表述的准确性。
匹配一律使用精确短语，不使用模糊通配。

## 分层与例外机制（v2.9.17 / D4）

严格性不变：任何命中仍判 FAIL。新增两点以消除措辞冻结：
  - [RULES] 按类别组织，新增或调整措辞规则时可定位到明确类别；
  - [ALLOWLIST] 显式豁免表，默认空。仅允许对「俚语拟人情绪化」与
    「口语化标题」两类、且仅限产品内引导文案所在文件，经评审后放行。
    [NON_WAIVABLE] 两类（问题原文、原文引用标注）永不可豁免，纪律底线不变。

设计原则：**可理解性由信息架构承担，不由放宽措辞承担**。界面侧通过分层、
对照与即时展开降低理解成本，本门禁只负责专业表述纪律，两者不冲突。

## 扫描范围

/workspace 下随交付发布的文本（NymphAuto 源码树、根下发布说明与分析文档、
publish 脚本、nymph-download 下载页）。明确排除：
  - bgi-ref/：第三方参考资料，非本仓库产物，源码包亦不包含；
  - .codebuddy/、.git/、build/、.gradle/、.kotlin/ 等项目数据与构建产物；
  - 二进制（.apk/.zip/.mp4/.so/... 由扩展名白名单排除）；
  - 本脚本自身（内含禁用词字面量，用于定义规则）。

用法：
    python3.11 tools/check_wording.py            # 人读输出，命中则退出码 1
    python3.11 tools/check_wording.py --json     # 机器可读
"""
from __future__ import annotations

import argparse
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WORKSPACE = os.path.dirname(ROOT)
SELF = os.path.abspath(__file__)

SKIP_DIRS = {".git", "build", ".gradle", ".kotlin", ".idea", "node_modules",
             ".codebuddy", "bgi-ref", "__pycache__", "models", "jniLibs"}
TEXT_EXT = {".kt", ".kts", ".java", ".md", ".txt", ".json", ".xml", ".gradle",
            ".pro", ".cfg", ".yaml", ".yml", ".toml", ".html", ".css", ".js",
            ".py", ".sh", ".log"}

# 按类别维护的禁用规则。键即类别名，会随命中明细与 --json 输出，供审计。
RULES: dict[str, tuple[str, ...]] = {
    # 1) 用户反馈原话及片段
    "问题原文": (
        "这两个圈出来的还是拖动不了", "我不能自由移动", "还是这么大", "不满意",
        "圈出来的", "圈出", "拖不动", "拖不了", "差评", "体感", "人话", "误杀",
        "看不到", "藏在", "一点都不",
        "第一批多弹窗脚本一定踩，必须现在加", "我就按步骤触发点击逻辑",
        "我要的是 release 的发布", "但是我要的是 release", "完全由我判断",
        "我是在横屏模式下", "我在横屏模式下拾取", "我也不想看",
        "只能拾点，不能框区域", "一边操作拾取坐标，一边可以操作指针外的任意区域",
    ),
    # 2) 把用户原话标注为输入的引用措辞
    "原文引用标注": (
        "用户诉求原文", "用户「唯一最优方案」", "用户给出「唯一最优方案」",
        "用户末尾明确强调", "用户明确要求", "用户明确要消除", "用户授权「",
        "按既有约定「", "回应用户负向评估", "回应用户反馈「", "回应的范式转变",
        "用户说的", "反馈「", "用户反馈「", "观测输入「",
    ),
    # 3) 带引号的口语化章节标题
    "口语化标题": (
        "修「一直在抽搐」", "修「界面不美观」", "界面不美观",
    ),
    # 4) 俚语 / 拟人 / 情绪化 / 原文标注 / 拼接瑕疵
    #    v2.9.17 移除「如实」「诚实」两条中性词：二者不含口语化、拟人或
    #    情绪化属性，列在禁用表内会误伤工程文档的常规表述。
    "俚语拟人情绪化": (
        "讽刺的是", "最具讽刺意味", "恰好在它点名要防的场景里失效",
        "照单全收", "推倒地基", "推倒等于", "不推倒地基", "用户第一诉求",
        "用户诉求（原文）", "甚至可以一边", "一句话：不要", "这个场景真的出现在屏幕上了",
        "面板几乎不动", "抖一下就停", "执行clean", "诚实告知", "假装",
        "狂点", "打爆", "无主窗口", "根本不读", "根本不再派发", "根本不知道",
        "本可跑却被误判拦截", "未硬凑为 bug",
    ),
}

# 兼容既有调用点：展平后的全部禁用模式。
BANNED: tuple[str, ...] = tuple(p for group in RULES.values() for p in group)

# 显式豁免表，默认空，需经评审后登记。键为相对 WORKSPACE 的文件路径。
# 仅对产品内引导文案开放，且 [NON_WAIVABLE] 类别不受豁免影响。
ALLOWLIST: dict[str, tuple[str, ...]] = {}
NON_WAIVABLE = frozenset({"问题原文", "原文引用标注"})


def _waived(rel_path: str, pattern: str) -> bool:
    return pattern in ALLOWLIST.get(rel_path, ())


def check(root: str = WORKSPACE) -> dict:
    """扫描交付文本，返回命中明细；无任何命中时 ok=True。"""
    hits: list[dict] = []
    waived: list[dict] = []
    for dp, dns, fns in os.walk(root):
        dns[:] = [d for d in dns if d not in SKIP_DIRS]
        for fn in fns:
            if os.path.splitext(fn)[1].lower() not in TEXT_EXT:
                continue
            p = os.path.join(dp, fn)
            if os.path.abspath(p) == SELF:
                continue
            try:
                lines = open(p, encoding="utf-8", errors="replace").read().splitlines()
            except OSError:
                continue
            for lineno, line in enumerate(lines, 1):
                matched = False
                for category, patterns in RULES.items():
                    for pat in patterns:
                        if pat not in line:
                            continue
                        entry = {
                            "file": os.path.relpath(p, root),
                            "line": lineno,
                            "pattern": pat,
                            "category": category,
                            "text": line.strip()[:160],
                        }
                        if category not in NON_WAIVABLE and _waived(entry["file"], pat):
                            waived.append(entry)
                        else:
                            hits.append(entry)
                        matched = True
                        break  # 同一行只报首个命中，避免重复计数
                    if matched:
                        break
    return {
        "patterns": len(BANNED),
        "categories": {k: len(v) for k, v in RULES.items()},
        "hits": hits,
        "count": len(hits),
        "waived": waived,
        "ok": not hits,
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args()
    d = check()
    if a.json:
        print(json.dumps(d, ensure_ascii=False, indent=2))
    else:
        cats = " / ".join("%s %d 条" % (k, v) for k, v in d["categories"].items())
        print("表述规范化门禁：%d 条禁用模式（%s）· 命中 %d 处 · 豁免 %d 处 · %s"
              % (d["patterns"], cats, d["count"], len(d["waived"]),
                 "PASS" if d["ok"] else "FAIL"))
        for h in d["hits"][:60]:
            print("  %s:%d  [%s/%s]  %s" % (h["file"], h["line"], h["category"],
                                            h["pattern"], h["text"]))
        if len(d["hits"]) > 60:
            print("  ... 另有 %d 处" % (len(d["hits"]) - 60))
    return 0 if d["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
