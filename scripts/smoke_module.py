import argparse
import json
import pathlib
import subprocess
import xml.etree.ElementTree as ET


def smoke_module(serial, output):
    output.mkdir(parents=True, exist_ok=True)

    def adb(*args):
        return subprocess.run(["adb", "-s", serial, *args], check=True,
                              text=True, capture_output=True).stdout

    adb("install", "-r", "app/build/outputs/apk/debug/app-debug.apk")
    launch = adb("shell", "am", "start", "-W", "-n", "dev.furihook/.MainActivity")
    if "Status: ok" not in launch:
        raise AssertionError("模块 Activity 启动失败")
    adb("shell", "uiautomator", "dump", "/sdcard/furihook-module-ui.xml")
    tree_path = output / "module-ui.xml"
    adb("pull", "/sdcard/furihook-module-ui.xml", str(tree_path))
    tree = ET.parse(tree_path)
    texts = [node.attrib.get("text", "") for node in tree.iter("node")]
    for expected in ("FuriHook 0.2.0", "阶段二 · TextView 本地振假名", "测试方法"):
        if expected not in texts:
            raise AssertionError(f"模块界面缺少内容：{expected}")
    report = {
        "serial": serial,
        "apiLevel": adb("shell", "getprop", "ro.build.version.sdk").strip(),
        "moduleInstalled": True,
        "moduleActivityStarted": True,
        "moduleUiAssertionsPassed": True,
        "lsposedInstalled": False,
        "hookExecutionVerified": False,
    }
    (output / "module-smoke.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    smoke_module(args.serial, args.output)
