#!/usr/bin/env python3
"""vivo adb 安装自动点击器（OriginOS 安装确认弹窗无官方关闭开关的兜底）。

用法: vivo-install.py <serial> <apk路径>
原理: adb install 在后台跑，轮询 uiautomator dump 当前弹窗，
      依次点掉 vivo 安装链路的四层确认：
      勾协议 CheckBox →「安装」→「继续安装」→「仍要安装」→「完成/打开」。
      每轮只点一个目标（同一弹窗多目标连点易误触），直至 install 进程退出。
"""
import re
import subprocess
import sys
import time

SERIAL, APK = sys.argv[1], sys.argv[2]
# 按链路顺序排列，同屏出现多个时点链路最靠前的
BUTTONS = ["安装", "继续安装", "仍要安装", "完成", "打开"]
DEV = ["adb", "-s", SERIAL]


def sh(*args, timeout=15):
    return subprocess.run(DEV + list(args), capture_output=True, text=True, timeout=timeout)


def dump():
    sh("shell", "uiautomator", "dump", "/sdcard/ui.xml", timeout=10)
    r = sh("shell", "cat", "/sdcard/ui.xml", timeout=10)
    return r.stdout


def bounds_center(b):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    if x2 <= x1 or y2 <= y1:
        return None
    return (x1 + x2) // 2, (y1 + y2) // 2


def parse(xml):
    """返回 (未勾选CheckBox中心, 本屏应点的按钮中心)。"""
    checkbox = None
    for node in re.findall(r"<node[^>]*/>", xml):
        cls = re.search(r'class="([^"]*)"', node)
        checked = re.search(r'checked="([^"]*)"', node)
        b = re.search(r'bounds="([^"]*)"', node)
        if cls and "CheckBox" in cls.group(1) and checked and checked.group(1) == "false":
            c = bounds_center(b.group(1)) if b else None
            if c:
                checkbox = c
                break
    button = None
    for want in BUTTONS:
        for node in re.findall(r"<node[^>]*/>", xml):
            t = re.search(r'text="([^"]*)"', node)
            clickable = re.search(r'clickable="([^"]*)"', node)
            b = re.search(r'bounds="([^"]*)"', node)
            if t and t.group(1) == want and b:
                # 按钮本体不可点时点其中心同样有效（父容器接管点击）
                c = bounds_center(b.group(1))
                if c and (not clickable or clickable.group(1) == "true" or want in ("安装", "继续安装")):
                    button = c
                    break
        if button:
            break
    return checkbox, button


def main():
    install = subprocess.Popen(
        DEV + ["install", "-r", APK], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True
    )
    deadline = time.time() + 300
    last_action = 0.0
    while install.poll() is None and time.time() < deadline:
        try:
            xml = dump()
        except Exception:
            time.sleep(1)
            continue
        checkbox, button = parse(xml)
        # 勾选框优先：不勾选直接点安装无效
        target = checkbox or button
        if target and time.time() - last_action > 0.8:
            sh("shell", "input", "tap", str(target[0]), str(target[1]))
            last_action = time.time()
        time.sleep(1.2)
    out = install.communicate(timeout=60)[0] if install.poll() is not None else ""
    if install.poll() is None:
        install.kill()
        print("TIMEOUT: install 未在 300s 内完成")
        sys.exit(1)
    print(out.strip())
    sys.exit(install.returncode)


if __name__ == "__main__":
    main()
