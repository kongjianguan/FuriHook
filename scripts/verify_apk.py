import argparse
import hashlib
import json
import pathlib
import subprocess
import zipfile

from androguard.core.dex import DEX
from loguru import logger


def inspect_apk(apk, analyzer, output):
    logger.disable("androguard")
    expected = {
        "META-INF/xposed/java_init.list": "dev.furihook.hook.FuriHookModule\n",
        "META-INF/xposed/scope.list": "dev.furihook.testapp\n",
        "META-INF/xposed/module.prop": (
            "minApiVersion=102\ntargetApiVersion=102\nstaticScope=false\n"
            "exceptionMode=protective\nautoHotReload=false\n"
        ),
    }
    with zipfile.ZipFile(apk) as archive:
        for name, value in expected.items():
            if archive.read(name).decode("utf-8") != value:
                raise AssertionError(f"模块元数据错误：{name}")
        if "assets/xposed_init" in archive.namelist():
            raise AssertionError("APK 包含旧版入口")
        classes = set()
        for name in archive.namelist():
            if name.startswith("classes") and name.endswith(".dex") and "/" not in name:
                classes.update(definition.get_name() for definition in DEX(archive.read(name)).get_classes())
        if "Ldev/furihook/hook/FuriHookModule;" not in classes:
            raise AssertionError("APK 缺少模块入口类")
        if any(name.startswith(("Lio/github/libxposed/api/", "Lde/robv/android/xposed/"))
               for name in classes):
            raise AssertionError("APK 不得打包 Xposed API 实现")

    def analyze(*args):
        return subprocess.run([str(analyzer), *args, str(apk)], check=True,
                              text=True, capture_output=True).stdout.strip()

    application_id = analyze("manifest", "application-id")
    minimum_sdk = analyze("manifest", "min-sdk")
    target_sdk = analyze("manifest", "target-sdk")
    permissions = analyze("manifest", "permissions")
    if (application_id, minimum_sdk, target_sdk) != ("dev.furihook", "28", "36"):
        raise AssertionError("Android 包配置不符合要求")
    if "android.permission.INTERNET" in permissions:
        raise AssertionError("模块不得申请网络权限")

    packages = analyze("dex", "packages", "--defined-only")

    output.mkdir(parents=True, exist_ok=True)
    (output / "dex-packages.txt").write_text(packages + "\n", encoding="utf-8")
    (output / "defined-classes.json").write_text(
        json.dumps(sorted(classes), indent=2) + "\n", encoding="utf-8")
    with apk.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    report = {
        "apk": str(apk),
        "sha256": digest,
        "applicationId": application_id,
        "minSdk": int(minimum_sdk),
        "targetSdk": int(target_sdk),
        "metadata": expected,
        "bundledApiImplementation": False,
        "definedClassCount": len(classes),
        "legacyEntry": False,
        "hookExecutionVerified": False,
    }
    (output / "apk-verification.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=pathlib.Path, required=True)
    parser.add_argument("--apkanalyzer", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    inspect_apk(args.apk, args.apkanalyzer, args.output)
