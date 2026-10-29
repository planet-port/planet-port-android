#!/usr/bin/env python3
"""vivo adb 安装自动点击器（OriginOS 安装确认弹窗无官方关闭开关的兜底）。

用法: vivo-install.py <serial> <apk路径>
原理: adb install 在后台跑，轮询 uiautomator dump 当前弹窗，
      依次点掉 vivo 安装链路的确认层：
      勾协议 CheckBox →「安装」→「继续安装」→「仍要安装」→「完成」。
      每轮只点一个目标（同一弹窗多目标连点易误触），直至 install 进程退出。
"""
import re
import subprocess
import sys
import time

SERIAL, APK = sys.argv[1], sys.argv[2]
# 按链路顺序排列，同屏出现多个时点链路最靠前的
BUTTONS = ["安装", "继续安装", "仍要安装", "完成"]
DEV = ["adb", "-s", SERIAL]


def sh(*args, timeout=15):
    return subprocess.run(DEV + list(args), capture_output=True, text=True, timeout=timeout)


def dump():
    sh("shell", "uiautomator", "dump", "/sdcard/ui.xml", timeout=10)
    return sh("shell", "cat", "/sdcard/ui.xml", timeout=10).stdout


def bounds_center(b):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    if x2 <= x1 or y2 <= y1:
        return None
    return (x1 + x2) // 2, (y1 + y2) // 2


def node_attr(node, name):
    m = re.search(rf'{name}="([^"]*)"', node)
    return m.group(1) if m else None


def find_target(xml):
    """返回本屏应点的坐标：未勾选 CheckBox 优先（不勾选直接点安装无效），
    否则按链路顺序找第一个在屏按钮。点文本/控件节点中心即可——按钮本体
    未标 clickable 时父容器接管点击，同样生效。"""
    for node in re.findall(r"<node[^>]*/>", xml):
        if "CheckBox" in (node_attr(node, "class") or "") and node_attr(node, "checked") == "false":
            c = bounds_center(node_attr(node, "bounds") or "")
            if c:
                return c
    for want in BUTTONS:
        for node in re.findall(r"<node[^>]*/>", xml):
            if node_attr(node, "text") == want:
                c = bounds_center(node_attr(node, "bounds") or "")
                if c:
                    return c
    return None


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
        target = find_target(xml)
        if target and time.time() - last_action > 0.8:
            sh("shell", "input", "tap", str(target[0]), str(target[1]))
            last_action = time.time()
        time.sleep(1.2)
    if install.poll() is None:
        install.kill()
        print("TIMEOUT: install 未在 300s 内完成")
        sys.exit(1)
    out = install.communicate(timeout=60)[0].strip()
    print(out)
    if install.returncode == 0:
        # 装完自动启动 app（装机即验，省一步手动点开）
        sh("shell", "am", "start", "-n", "cn.imgozi.a.planetport/cn.imgozi.planetport.ui.MainActivity")
    sys.exit(install.returncode)


if __name__ == "__main__":
    main()
