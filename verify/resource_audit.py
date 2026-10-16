#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
静态资源一致性审计（无需 Android SDK）。

校验四件事：
1. Java 代码里引用的 R.id / R.string / R.color / R.drawable / R.layout / R.xml / R.style 是否都已定义；
2. findViewById 使用的 id 是否存在于布局；
3. getString(R.string.X, ...) 的实参个数是否与字符串资源中的占位符最大序号一致；
4. AndroidManifest 中声明的类是否都有对应源文件。

用法：python verify/resource_audit.py
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA_DIR = os.path.join(ROOT, "app", "src", "main", "java")
RES_DIR = os.path.join(ROOT, "app", "src", "main", "res")
MANIFEST = os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml")

problems = []
notes = []


def read(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def collect_java_files():
    out = []
    for base, _, files in os.walk(JAVA_DIR):
        for name in files:
            if name.endswith(".java"):
                out.append(os.path.join(base, name))
    return sorted(out)


def parse_res_definitions():
    """返回 {type: set(names)}"""
    defs = {"id": set(), "string": set(), "color": set(),
            "drawable": set(), "layout": set(), "xml": set(), "style": set()}

    for base, _, files in os.walk(RES_DIR):
        folder = os.path.basename(base)
        for name in files:
            path = os.path.join(base, name)
            stem, ext = os.path.splitext(name)
            if not ext == ".xml":
                continue
            content = read(path)
            if folder == "layout":
                defs["layout"].add(stem)
            elif folder == "drawable":
                defs["drawable"].add(stem)
            elif folder == "xml":
                defs["xml"].add(stem)
            elif folder.startswith("values"):
                for m in re.finditer(r'<(string|color|style)\s+name="([^"]+)"', content):
                    kind, res_name = m.group(1), m.group(2)
                    defs[kind].add(res_name)
                # values 里也可能定义 drawable 形状
                for m in re.finditer(r'<item[^>]*type="drawable"[^>]*name="([^"]+)"', content):
                    defs["drawable"].add(m.group(1))
            # 任意资源文件中的 id 声明
            for m in re.finditer(r'@\+id/([A-Za-z0-9_]+)', content):
                defs["id"].add(m.group(1))
    return defs


def audit_java_references(defs):
    ref_re = re.compile(r'\bR\.(id|string|color|drawable|layout|xml|style|mipmap|array)\.([A-Za-z0-9_]+)')
    total = 0
    for path in collect_java_files():
        content = read(path)
        rel = os.path.relpath(path, ROOT)
        for m in ref_re.finditer(content):
            kind, name = m.group(1), m.group(2)
            total += 1
            bucket = defs.get(kind)
            if bucket is None:
                problems.append("%s: 引用了未被审计的资源类型 R.%s.%s" % (rel, kind, name))
            elif name not in bucket:
                problems.append("%s: 引用了不存在的资源 R.%s.%s" % (rel, kind, name))
    notes.append("Java 中资源引用总数: %d" % total)


def audit_layout_ids(defs):
    """findViewById(R.id.x) 的 id 必须来自布局"""
    total = 0
    for path in collect_java_files():
        content = read(path)
        rel = os.path.relpath(path, ROOT)
        for m in re.finditer(r'findViewById\(R\.id\.([A-Za-z0-9_]+)\)', content):
            total += 1
            if m.group(1) not in defs["id"]:
                problems.append("%s: findViewById 使用了未声明的 id: %s" % (rel, m.group(1)))
    notes.append("findViewById 调用数: %d" % total)


def audit_format_args():
    """getString(R.string.X, a, b, ...) 的实参个数 == 资源占位符最大序号"""
    strings = {}
    path = os.path.join(RES_DIR, "values", "strings.xml")
    for m in re.finditer(r'<string\s+name="([^"]+)"[^>]*>(.*?)</string>', read(path), re.S):
        strings[m.group(1)] = m.group(2)

    # 1) 只有在「带参数格式化」的字符串里，裸 % 才是错误（必须写成 %%）
    #    纯展示用字符串（如 note_range 的 15%–60%）保留单个 % 才是正确的，
    #    因为 Resources.getString(id) 不做 String.format，%% 会原样显示。
    used_with_args = set()
    call_re = re.compile(r'getString\(\s*R\.string\.([A-Za-z0-9_]+)((?:[^()]|\([^()]*\))*)\)')
    calls = []
    for path_ in collect_java_files():
        content = read(path_)
        rel = os.path.relpath(path_, ROOT)
        for m in call_re.finditer(content):
            name, args_blob = m.group(1), m.group(2)
            if name not in strings:
                continue
            stripped = args_blob.strip()
            if stripped.startswith(","):
                stripped = stripped[1:]
            args = []
            depth = 0
            cur = ""
            for ch in stripped:
                if ch == '(':
                    depth += 1
                elif ch == ')':
                    depth -= 1
                if ch == ',' and depth == 0:
                    args.append(cur)
                    cur = ""
                else:
                    cur += ch
            if cur.strip():
                args.append(cur)
            provided = len([a for a in args if a.strip()])
            calls.append((rel, name, provided))

    for name, value in strings.items():
        has_placeholder = bool(re.search(r'%\d+\$', value))
        needs_escape = has_placeholder or any(n == name and c > 0 for _, n, c in calls)
        if needs_escape:
            tmp = re.sub(r'%%', '', value)
            tmp = re.sub(r'%\d+\$', '', tmp)
            if '%' in tmp:
                problems.append("strings.xml: %s 参与格式化但存在未转义的 %%（应写成 %%%%）" % name)
        if needs_escape:
            used_with_args.add(name)

    # 2) 调用点实参个数
    total = 0
    for rel, name, provided in calls:
        idx = [int(x) for x in re.findall(r'%(\d+)\$', strings[name])]
        if not idx:
            continue
        total += 1
        expected = max(idx)
        if provided != expected:
            problems.append("%s: getString(R.string.%s) 传入 %d 个参数，资源需要 %d 个"
                            % (rel, name, provided, expected))

    # 3) 带占位符的字符串不得被布局直接用 @string 引用（会原样显示 %1$d）
    for base, _, files in os.walk(RES_DIR):
        for fname in files:
            if not fname.endswith(".xml"):
                continue
            fpath = os.path.join(base, fname)
            body = read(fpath)
            for m in re.finditer(r'@string/([A-Za-z0-9_]+)', body):
                target = m.group(1)
                if target in strings and re.search(r'%\d+\$', strings[target]):
                    problems.append("%s: 直接引用了需要参数的字符串 @string/%s"
                                    % (os.path.relpath(fpath, ROOT), target))
    notes.append("带格式参数的 getString 调用数: %d" % total)


def audit_manifest():
    content = read(MANIFEST)
    pkg = "com.autoswipe.ball"
    total = 0
    for m in re.finditer(r'android:name="\.([A-Za-z0-9_.]+)"', content):
        total += 1
        rel_path = os.path.join(JAVA_DIR, *pkg.split("."), *m.group(1).split(".")) + ".java"
        if not os.path.isfile(rel_path):
            problems.append("AndroidManifest.xml: 找不到类 %s.%s" % (pkg, m.group(1)))
    for m in re.finditer(r'android:(?:icon|label|theme|resource)="@(\w+)/([A-Za-z0-9_]+)"', content):
        kind, name = m.group(1), m.group(2)
        if kind in ("drawable", "xml", "style", "string", "color"):
            if name not in DEFS.get(kind, set()):
                problems.append("AndroidManifest.xml: 引用不存在的资源 @%s/%s" % (kind, name))
    notes.append("Manifest 中组件/资源引用检查项: %d" % total)


DEFS = {}

if __name__ == "__main__":
    DEFS = parse_res_definitions()
    print("资源定义统计:")
    for kind in ("layout", "drawable", "xml", "id", "string", "color", "style"):
        print("  %-9s %d 项" % (kind, len(DEFS[kind])))
    audit_java_references(DEFS)
    audit_layout_ids(DEFS)
    audit_format_args()
    audit_manifest()

    print()
    for n in notes:
        print("· " + n)
    print()
    if problems:
        print("发现问题 %d 项:" % len(problems))
        for p in problems:
            print("  [FAIL] " + p)
        sys.exit(1)
    print("结果: ALL PASS（Java 资源引用 / 布局 id / 格式化参数 / Manifest 类与资源全部一致）")
