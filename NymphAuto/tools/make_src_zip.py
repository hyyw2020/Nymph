# -*- coding: utf-8 -*-
"""生成 NymphAuto 干净源码包（v3.0.0）。

从工作树打包可审阅源码，排除构建产物 / 二进制资源 / 敏感文件：
- build/ .gradle/ .kotlin/ .idea/ *.iml
- app/src/main/assets/models（ONNX 等二进制，体积大且不属源码审阅范围）
- jniLibs、*.so（原生库二进制）
- 任何 .apk/.jks/.keystore/.p12 及含 token/凭据的文件
"""
import os, zipfile

ROOT = '/workspace/NymphAuto'
OUT = '/workspace/NymphAuto-source-v3.0.0.zip'

SKIP_DIRS = {'.git', 'build', '.gradle', '.kotlin', '.idea', 'node_modules',
             'jniLibs', 'models', '.codebuddy'}
SKIP_EXT = {'.apk', '.jks', '.keystore', '.p12', '.pfx', '.so', '.onnx',
            '.bin', '.zip', '.idsig', '.class'}
SENSITIVE_NAMES = ('gitee_token', 'github_token', 'sign_', 'sign_env',
                   'git-credentials', 'credentials', 'token')

def keep(full: str, name: str) -> bool:
    if name in SENSITIVE_NAMES or any(s in name for s in SENSITIVE_NAMES):
        return False
    ext = os.path.splitext(name)[1].lower()
    if ext in SKIP_EXT:
        return False
    # 允许源码 / 文档 / 配置类
    return ext in {'.kt', '.kts', '.java', '.md', '.txt', '.json', '.xml',
                   '.gradle', '.pro', '.cfg', '.yaml', '.yml', '.toml',
                   '.png', '.jpg', '.svg', '.webp', '.html', '.css', '.js'}

files = []
for dirpath, dirnames, filenames in os.walk(ROOT):
    dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS
                   and not any(s in d for s in SENSITIVE_NAMES)]
    # assets/models 仅排除其二进制，但保留目录与说明文件
    if os.path.basename(dirpath) == 'models' and 'assets' in dirpath.split(os.sep):
        filenames[:] = [f for f in filenames
                        if os.path.splitext(f)[1].lower() not in {'.onnx', '.bin'}]
    for fn in filenames:
        if keep(os.path.join(dirpath, fn), fn):
            files.append(os.path.join(dirpath, fn))

files.sort()
with zipfile.ZipFile(OUT, 'w', zipfile.ZIP_DEFLATED) as z:
    for f in files:
        rel = os.path.relpath(f, ROOT)
        z.write(f, 'NymphAuto/' + rel)

with zipfile.ZipFile(OUT) as z:
    ns = [n for n in z.namelist() if not n.endswith('/')]
    import hashlib
    h = hashlib.sha256()
    with open(OUT, 'rb') as f:
        for c in iter(lambda: f.read(1 << 20), b''):
            h.update(c)
    print('源码包文件数', len(ns), '字节', os.path.getsize(OUT), 'SHA', h.hexdigest())
