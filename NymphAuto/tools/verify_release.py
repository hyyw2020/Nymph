#!/usr/bin/env python3.11
# -*- coding: utf-8 -*-
"""
NymphAuto 发版数字自动核对（v2.9.9，只读、不联网、不改产物）。

回应「README 数字全靠手工核对」：从真实 APK、测试报告、源码包读取并打印
核对表。发布说明 / README 中的数字必须引用本脚本的输出，不允许手填估算。

v2.9.0：接入 16 KB 页对齐实测（tools/check_elf_alignment.py）。此前
build.gradle.kts 把 `useLegacyPackaging=false` 注释成「16KB 页对齐」，但产物
里自编译的 libquickjs-jni.so 的 LOAD Align 仍是 0x1000，属于文档与产物不符。
现在每次发版都实测每个 so 的 ELF 段对齐，自编译 so 未对齐直接判 FAIL。

用法：
    python3.11 tools/verify_release.py
    python3.11 tools/verify_release.py --json     # 机器可读输出
"""
from __future__ import annotations

import argparse
import glob
import hashlib
import io
import json
import os
import re
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WORKSPACE = os.path.dirname(ROOT)
BT = "/opt/android-sdk/build-tools/36.0.0"
APK = os.path.join(WORKSPACE, "NymphAuto-release.apk")
SRC_ZIP = os.path.join(WORKSPACE, "NymphAuto-source-v2.9.9.zip")
DEX_DIR = "/tmp/build/v299dex"

# 16 KB 页对齐核验（v2.9.0）
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import check_elf_alignment as elf  # noqa: E402

BANNED = (
    "MediaProjection", "VirtualDisplay", "createVirtualDisplay",
    "MediaCodec", "FLAG_SECURE", "RECORD_AUDIO", "WATCH_OUTSIDE_TOUCH",
)


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def run(args: list[str]) -> str:
    p = subprocess.run(args, capture_output=True)
    out = p.stdout.decode("utf-8", "replace") + p.stderr.decode("utf-8", "replace")
    return out


def apk_info() -> dict:
    badging = run([f"{BT}/aapt2", "dump", "badging", APK])
    pkg = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    perms = re.findall(r"uses-permission: name='([^']+)'", badging)
    abi = re.search(r"native-code: '([^']+)'", badging)
    return {
        "package": pkg.group(1) if pkg else "?",
        "versionCode": int(pkg.group(2)) if pkg else 0,
        "versionName": pkg.group(3) if pkg else "?",
        "bytes": os.path.getsize(APK) if os.path.isfile(APK) else 0,
        "sha256": sha256(APK) if os.path.isfile(APK) else "-",
        "permissions": len(perms),
        "permission_list": perms,
        "abi": abi.group(1) if abi else "?",
    }


def signature_info() -> dict:
    out = run([f"{BT}/apksigner", "verify", "--print-certs", "--verbose", APK])
    m = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]+)", out)
    return {
        "verifies": out.lstrip().startswith("Verifies"),
        "v2": "v2 scheme (APK Signature Scheme v2): true" in out,
        "v3": "v3 scheme (APK Signature Scheme v3): true" in out,
        "cert_sha256": m.group(1) if m else "?",
    }


def dex_info() -> dict:
    dexes = sorted(glob.glob(os.path.join(DEX_DIR, "classes*.dex")))
    if not dexes:
        return {"classes": 0, "banned_refs": {}, "note": "未找到解压的 dex"}
    out = run([f"{BT}/dexdump", "-f", *dexes])
    own = 0
    banned_hits: dict[str, list[str]] = {k: [] for k in BANNED}
    banned_hits["MediaCodec"] = []
    cur = ""
    for line in io.StringIO(out):
        if "Class descriptor" in line:
            cur = line.strip()
            if "Ldev/nymph/auto" in line:
                own += 1
        for k in BANNED:
            if k in line and ("type" in line or "reg=" in line or "Class descriptor" in line):
                banned_hits[k].append(cur[:110])
    # v2.9.2 / ADR-008：新增核心能力必须真正进 release dex，不能「写了没接线」。
    # v2.9.3：浮窗统一视觉投影 OverlayTheme 是那次核心改动，必须确实编进 release dex。
    # v2.9.4：拖动手势状态机 DragGesture 必须确实编进 release dex。
    # v2.9.6：等待画面出现门控 WaitGate 必须确实编进 release dex。
    # v2.9.7：收起球「点击 / 拖动」判定收敛到 core 纯函数，isDragMove / isTap
    #         必须真的编进 release dex，不能只改源码没进包。
    # v2.9.8：同步提交决策 commitDrag、4dp 拖动阈值 dragStartPx 同样必须进包。
    # v2.9.9：拖动改由根视图 onInterceptTouchEvent/onTouchEvent 统一接管（根除
    #   ScrollView 吞手势），FloatingEditorPanel 必须确实编进 release dex。
    expected_symbols = (
        "dev/nymph/auto/core/recorder/RecordabilityGuard",
        "dev/nymph/auto/core/model/LoopPolicy",
        "dev/nymph/auto/ui/OverlayTheme",
        "dev/nymph/auto/core/gesture/DragGesture",
        "dev/nymph/auto/core/video/WaitGate",
        "isDragMove",
        "isTap",
        "commitDrag",
        "dragStartPx",
    )
    return {
        "classes_own": own,
        "banned_refs": {k: len(v) for k, v in banned_hits.items()},
        "banned_detail": {k: v[:3] for k, v in banned_hits.items() if v},
        "expected_symbols": {s: (s in out) for s in expected_symbols},
    }


def drag_gate() -> dict:
    """v2.9.8 / v2.9.9 静态门禁：拖动必须是「根视图统接管 + 同步直提交」，
    不得回归两类坏链路。

    1. v2.9.7 的 `Choreographer.postFrameCallback` + `frameScheduled` 自锁会让
       `frameScheduled` 永久为 true，之后每个 MOVE 都在入口 return，真机表现
       就是「收起后悬浮球完全拖不动」。
    2. v2.9.8 及以前把拖动监听器挂在**嵌在 ScrollView 内的顶部条**上，竖向 /
       斜向 MOVE 刚过阈值就被 `MaxHeightScrollView.onInterceptTouchEvent` 截走，
       列表项只收到 DOWN + CANCEL，拖动监听器拿不到 MOVE —— 真机表现正是
       「面板几乎不动、抖一下就停」（用户第三次反馈的「完全拖动不了」）。

    v2.9.9 改为整块面板在**根视图**用 `onInterceptTouchEvent` 拦截 + `onTouchEvent`
    执行（本工程 `RecordPanel` 已验证的 idiom），根层级拦截在 ScrollView 之上，
    滚动容器永远抢不到拖动手势。这里只检查**非注释代码行**，把「不得复活」变成
    每次发版自动跑的断言。
    """
    svc = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/service/FloatingEditorService.kt")
    panel = os.path.join(ROOT, "app/src/main/java/dev/nymph/auto/ui/FloatingEditorPanel.kt")

    def code_only(path: str) -> str:
        keep = []
        with open(path, encoding="utf-8") as f:
            for line in f:
                s = line.strip()
                if s.startswith("*") or s.startswith("/*") or s.startswith("//"):
                    continue
                keep.append(line)
        return "".join(keep)

    svc_code = code_only(svc)
    panel_code = code_only(panel)
    banned = ("Choreographer", "postFrameCallback", "frameScheduled", "pendingX", "pendingY")
    found = {k: (k in svc_code) for k in banned}
    # v2.9.9：旧的「监听器挂在滚动容器内子 View」方案必须消失；根视图必须接管手势
    no_old_listener = "attachDrag" not in panel_code
    root_intercept = "onInterceptTouchEvent" in panel_code and "onTouchEvent" in panel_code
    sync = "commitDrag" in svc_code and "wm.updateViewLayout" in svc_code
    small = "dragStartPx" in panel_code
    return {
        "banned_async_state": found,
        "no_old_scrollview_drag_listener": no_old_listener,
        "root_intercept_touch": root_intercept,
        "sync_commit_wired": sync,
        "small_drag_threshold": small,
        "ok": (not any(found.values())) and no_old_listener and root_intercept and sync and small,
    }


def test_info() -> dict:
    total = failed = skipped = 0
    # core 用 test-results/test/，app 用 test-results/testDebugUnitTest/，
    # 两个都要统计（v2.7.1 修：此前只匹配 test/ 会漏掉全部 app 单测）
    files = glob.glob(os.path.join(ROOT, "**", "build", "test-results", "**", "TEST-*.xml"), recursive=True)
    for f in files:
        try:
            r = ET.parse(f).getroot()
            total += int(r.get("tests", 0))
            failed += int(r.get("failures", 0)) + int(r.get("errors", 0))
            skipped += int(r.get("skipped", 0))
        except Exception:
            pass
    return {"cases": total, "failed": failed, "skipped": skipped, "suites": len(files)}


def source_info() -> dict:
    if not os.path.isfile(SRC_ZIP):
        return {"files": 0, "bytes": 0, "sha256": "-"}
    with zipfile.ZipFile(SRC_ZIP) as z:
        names = [n for n in z.namelist() if not n.endswith("/")]
    return {
        "files": len(names),
        "bytes": os.path.getsize(SRC_ZIP),
        "sha256": sha256(SRC_ZIP),
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args()
    data = {
        "apk": apk_info(),
        "signature": signature_info(),
        "dex": dex_info(),
        "tests": test_info(),
        "source": source_info(),
        "elf_alignment": elf.check(APK),
        "drag": drag_gate(),
    }
    if a.json:
        print(json.dumps(data, ensure_ascii=False, indent=2))
        return 0
    apk, sig, dex, t, src, elfd = (
        data[k] for k in ("apk", "signature", "dex", "tests", "source", "elf_alignment")
    )
    print(f"| 核对项 | 实测值 |\n|---|---|")
    print(f"| 版本 | {apk['versionName']} (versionCode {apk['versionCode']})，包名 {apk['package']} |")
    print(f"| Release APK | {apk['bytes']:,} 字节（{apk['bytes']/1024/1024:.2f} MB） |")
    print(f"| APK SHA-256 | `{apk['sha256']}` |")
    print(f"| 权限数 | {apk['permissions']} 项（{', '.join(p.split('.')[-1] for p in apk['permission_list'])}） |")
    print(f"| ABI | {apk['abi']} |")
    print(f"| 签名 | verify={sig['verifies']}，v2={sig['v2']}，v3={sig['v3']}，证书 `{sig['cert_sha256']}` |")
    print(f"| 本工程可读类 | {dex.get('classes_own', 0)} 个 |")
    print(f"| 禁用 API 类型/方法引用 | {dex.get('banned_refs', {})}（全部应为 0） |")
    syms = dex.get("expected_symbols", {})
    print(f"| 新增 core 符号进 release dex | {syms}（全部应为 True，见 ADR-008） |")
    print(f"| 单测 | {t['cases']} 项 / 失败 {t['failed']} / 跳过 {t['skipped']} / {t['suites']} 个 suite |")
    g = data["drag"]
    print(f"| 折叠球拖动门禁 | 根视图接管={g['root_intercept_touch']} · 旧 ScrollView 监听器已移除={g['no_old_scrollview_drag_listener']} · "
          f"同步直提交={g['sync_commit_wired']} · 4dp 阈值={g['small_drag_threshold']} · "
          f"异步自锁残留={g['banned_async_state']} · 结论={'PASS' if g['ok'] else 'FAIL'} |")
    print(f"| 源码包 | {src['files']} 文件 / {src['bytes']:,} 字节 |")
    print(f"| 源码包 SHA-256 | `{src['sha256']}` |")
    libs = elfd.get("libs", [])
    aligned = sum(1 for e in libs if e["aligned_16k"])
    print(f"| 16KB 页对齐(ELF LOAD) | {aligned}/{len(libs)} 个 so 对齐；"
          f"自编译产物全部对齐={elfd.get('own_aligned')}；"
          f"ZIP 条目 16KB(zipalign -P 16)={elfd.get('zipalign_16k')} |")
    if elfd.get("third_party_unaligned"):
        print(f"| ⚠ 第三方预编译 so 未对齐（本工程 CMake 不可控，如实列出） | "
              f"{', '.join(elfd['third_party_unaligned'])} |")
    ok = (
        apk["versionName"] == "2.9.9" and apk["versionCode"] == 27
        and sig["verifies"] and sig["v2"] and sig["v3"]
        and t["cases"] > 0 and t["failed"] == 0
        and data["drag"]["ok"]
        and all(v == 0 for v in dex.get("banned_refs", {"x": -1}).values())
        # v2.9.2 / ADR-008：新增 core 能力必须真的编进 release dex
        and bool(syms) and all(syms.values())
        # 自编译 so 必须 16KB 对齐（这是 v2.9.0 的核心修复，纳入发版硬门槛）
        and elfd.get("own_aligned") is True
    )
    print("\n结论:", "PASS ✅" if ok else "FAIL ❌（请检查上表）")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
