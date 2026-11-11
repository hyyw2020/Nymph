#!/usr/bin/env python3.11
# -*- coding: utf-8 -*-
"""
NymphAuto 发版前 16 KB 页对齐核验（只读、不联网、不改产物）。

## 为什么需要它

v2.8.1 及以前的 build.gradle.kts 把 `useLegacyPackaging = false` 注释成
「16KB 页大小对齐」，README 也沿用此说法；但实测解包 v2.8.1 的产物后发现：
自编译的 `libquickjs-jni.so` 与 TFLite 预编译的 `libtask_vision_jni.so` 的
ELF LOAD 段 Align 仍为 0x1000(4KB)，只有其余 4 个第三方 so 是 0x4000(16KB)。
即**文档与产物不符**，且无人发现——因为没有任何一道自动核验。

本脚本把「16 KB 兼容」从注释变成可重复的实测：解包 APK，逐个 so 用
`llvm-readelf -l` 读所有 LOAD 段的 Align，只有全部 >= 0x4000 才算对齐。

## 判定的诚实边界

- 自编译 so（`libquickjs-jni.so`）由本工程 CMake 控制，必须对齐，否则 FAIL。
- 预编译第三方 so 不由本工程编译，若未对齐会如实列为 LEGACY 并给出名字，
  不冒充已修复、也不偷偷判 PASS；是否需要换运行时由人决定。
- `zipalign -c -P 16` 只查 ZIP 条目对齐，与 ELF 段对齐是两回事，单独查。

用法：
    python3.11 tools/check_elf_alignment.py [APK 路径]
    python3.11 tools/check_elf_alignment.py --json
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import re
import subprocess
import sys
import zipfile

WORKSPACE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_APK = os.path.join(os.path.dirname(WORKSPACE), "NymphAuto-release.apk")
NDK_ROOT = "/opt/android-sdk/ndk/27.0.12077973"
READELF = f"{NDK_ROOT}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
ZIPALIGN = "/opt/android-sdk/build-tools/36.0.0/zipalign"

PAGE_16K = 0x4000
# 本工程源码现场编译、CMake 可控制的 so；这些必须 16KB 对齐。
OWN_LIBS = {"libquickjs-jni.so"}


def find_readelf() -> str:
    if os.path.isfile(READELF):
        return READELF
    hits = glob.glob("/opt/android-sdk/ndk/*/toolchains/llvm/prebuilt/*/bin/llvm-readelf")
    return hits[0] if hits else "llvm-readelf"


def load_aligns(so_path: str, readelf: str) -> list[int]:
    """返回该 so 所有 LOAD 段的 Align（十六进制转 int）。"""
    p = subprocess.run([readelf, "-l", so_path], capture_output=True)
    out = p.stdout.decode("utf-8", "replace") + p.stderr.decode("utf-8", "replace")
    aligns: list[int] = []
    in_load = False
    for line in out.splitlines():
        s = line.strip()
        if s.startswith("Type") and "Align" in s:
            in_load = True
            continue
        if in_load:
            if s.startswith("LOAD"):
                m = re.search(r"\s(0x[0-9a-fA-F]+)\s*$", line)
                if m:
                    aligns.append(int(m.group(1), 16))
            elif s.startswith(("GNU_STACK", "NOTE", "TLS", "DYNAMIC", "GNU_RELRO", "GNU_EH_FRAME")):
                in_load = False
    return aligns


def check(apk: str) -> dict:
    result: dict = {
        "apk": apk,
        "libs": [],
        "own_aligned": True,
        "third_party_unaligned": [],
        "zipalign_16k": None,
        "error": None,
    }
    if not os.path.isfile(apk):
        result["error"] = f"APK 不存在: {apk}"
        return result
    readelf = find_readelf()
    tmp = "/tmp/build/elfcheck_v290"
    os.makedirs(tmp, exist_ok=True)
    with zipfile.ZipFile(apk) as z:
        for name in z.namelist():
            if name.startswith("lib/") and name.endswith(".so"):
                z.extract(name, tmp)
    sos = sorted(glob.glob(os.path.join(tmp, "lib", "*", "*.so")))
    for so in sos:
        base = os.path.basename(so)
        aligns = load_aligns(so, readelf)
        worst = min(aligns) if aligns else 0
        aligned = bool(aligns) and worst >= PAGE_16K
        own = base in OWN_LIBS
        entry = {
            "name": base,
            "abi": os.path.basename(os.path.dirname(so)),
            "load_aligns": [hex(a) for a in aligns],
            "min_align": hex(worst),
            "aligned_16k": aligned,
            "own": own,
        }
        result["libs"].append(entry)
        if own and not aligned:
            result["own_aligned"] = False
        if not own and not aligned:
            result["third_party_unaligned"].append(base)
    if os.path.isfile(ZIPALIGN):
        p = subprocess.run([ZIPALIGN, "-c", "-P", "16", "-v", "4", apk], capture_output=True)
        result["zipalign_16k"] = p.returncode == 0
    return result


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("apk", nargs="?", default=DEFAULT_APK)
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args()
    data = check(a.apk)
    if a.json:
        print(json.dumps(data, ensure_ascii=False, indent=2))
        return 0
    print("| so | ABI | 最小 LOAD Align | 16KB | 归属 |\n|---|---|---|---|---|")
    for e in data["libs"]:
        print(f"| `{e['name']}` | {e['abi']} | `{e['min_align']}` | "
              f"{'✅' if e['aligned_16k'] else '❌'} | "
              f"{'本工程编译' if e['own'] else '第三方预编译'} |")
    print(f"\nZIP 条目 16KB 对齐(zipalign -P 16): {data['zipalign_16k']}")
    print(f"自编译 so 全部 16KB 对齐: {data['own_aligned']}")
    if data["third_party_unaligned"]:
        print(f"第三方预编译 so 未对齐(不由本工程 CMake 控制，如实列出): "
              f"{', '.join(data['third_party_unaligned'])}")
    if data["error"]:
        print("错误:", data["error"])
    ok = data["own_aligned"] and not data["error"]
    print("\n结论:", "PASS ✅（自编译产物已 16KB 对齐）" if ok else "FAIL ❌")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
