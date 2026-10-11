import argparse
import hashlib
import json
import pathlib
import tempfile
import zipfile

from androguard.core.apk import APK
from androguard.core.dex import DEX
from loguru import logger


VERSION_NAME = "12.31.0-prod.01"
VERSION_CODE = "312310001"
PACKAGE_NAME = "com.twitter.android"

EXPECTED_FIELDS = {
    "Landroidx/compose/ui/text/b;": {
        "a": "Ljava/lang/String;",
        "g": "Landroidx/compose/ui/text/platform/e;",
        "h": "Ljava/lang/CharSequence;",
        "i": "Landroidx/compose/ui/text/android/m;",
        "l": "I",
    },
    "Landroidx/compose/foundation/text/modifiers/u;": {
        "o": "Landroidx/compose/ui/text/h;",
        "y": "Landroidx/compose/foundation/text/modifiers/k;",
    },
    "Landroidx/compose/foundation/text/modifiers/y;": {
        "o": "Ljava/lang/String;",
        "x": "Landroidx/compose/foundation/text/modifiers/g;",
    },
    "Landroidx/compose/ui/text/h;": {"b": "Ljava/lang/String;"},
}

EXPECTED_METHODS = {
    ("Landroidx/compose/ui/text/b;", "<init>"):
        "(Ljava/lang/String;Landroidx/compose/ui/text/c1;Ljava/util/List;"
        "Ljava/util/List;Landroidx/compose/ui/text/font/i;"
        "Landroidx/compose/ui/unit/c;Z)V",
    ("Landroidx/compose/ui/text/android/m;", "<init>"):
        "(Ljava/lang/CharSequence;Landroid/text/TextPaint;I)V",
    ("Landroidx/compose/foundation/text/modifiers/u;", "u1"):
        "(ZZZZ)V",
    ("Landroidx/compose/foundation/text/modifiers/u;", "b"):
        "(Landroidx/compose/ui/layout/c1;Landroidx/compose/ui/layout/z0;J)"
        "Landroidx/compose/ui/layout/b1;",
    ("Landroidx/compose/foundation/text/modifiers/y;", "b"):
        "(Landroidx/compose/ui/layout/c1;Landroidx/compose/ui/layout/z0;J)"
        "Landroidx/compose/ui/layout/b1;",
    ("Landroidx/compose/ui/unit/a;", "i"): "(J)I",
    ("Landroidx/compose/ui/node/d;", "k"):
        "(Landroidx/compose/ui/node/y;)V",
    ("Landroidx/compose/ui/node/d;", "i"):
        "(Landroidx/compose/ui/node/o;)V",
    ("Landroidx/compose/foundation/text/modifiers/g;", "c"): "()V",
}
EXPECTED_CLASSES = {
    "Landroidx/compose/ui/text/b;",
    "Landroidx/compose/foundation/text/modifiers/u;",
    "Landroidx/compose/foundation/text/modifiers/y;",
    "Landroidx/compose/ui/text/h;",
    "Landroidx/compose/ui/text/platform/e;",
    "Landroidx/compose/ui/text/android/m;",
    "Landroidx/compose/ui/unit/a;",
    "Landroidx/compose/ui/layout/c1;",
    "Landroidx/compose/ui/layout/z0;",
    "Landroidx/compose/ui/node/p0;",
    "Landroidx/compose/ui/layout/b1;",
    "Landroidx/compose/ui/text/c1;",
    "Landroidx/compose/ui/text/font/i;",
    "Landroidx/compose/ui/unit/c;",
    "Landroidx/compose/foundation/text/modifiers/g;",
    "Landroidx/compose/foundation/text/modifiers/k;",
    "Landroidx/compose/ui/node/y;",
    "Landroidx/compose/ui/node/o;",
    "Landroidx/compose/ui/node/d;",
}

INTRINSIC_METHODS = ("B", "Y", "h0", "v0")
INTRINSIC_DESCRIPTOR = (
    "(Landroidx/compose/ui/node/p0;Landroidx/compose/ui/layout/z0;I)I"
)
CALLER_OWNERS = {
    "Lcom/x/urt/items/post/notepost/b;",
    "Landroidx/compose/material3/se;",
    "Landroidx/compose/foundation/text/e1;",
    "Landroidx/compose/foundation/text/modifiers/r;",
    "Landroidx/compose/foundation/text/modifiers/v;",
    "Landroidx/compose/ui/text/b0;",
    "Landroidx/compose/foundation/text/modifiers/u;",
    "Landroidx/compose/foundation/text/modifiers/y;",
}


def normalize_descriptor(descriptor):
    return descriptor.replace(" ", "")


def method_records(dex_files):
    records = {}
    call_sites = []
    for dex_name, dex in dex_files:
        for definition in dex.get_classes():
            owner = definition.get_name()
            for method in definition.get_methods():
                descriptor = normalize_descriptor(method.get_descriptor())
                key = (owner, method.get_name())
                if owner in EXPECTED_FIELDS or (owner, method.get_name()) in EXPECTED_METHODS \
                        or (owner in (
                            "Landroidx/compose/foundation/text/modifiers/u;",
                            "Landroidx/compose/foundation/text/modifiers/y;")
                            and method.get_name() in INTRINSIC_METHODS):
                    records.setdefault(key, []).append({
                        "dex": dex_name,
                        "descriptor": descriptor,
                        "access": method.get_access_flags_string(),
                    })
                if owner not in CALLER_OWNERS:
                    continue
                code = method.get_code()
                if code is None:
                    continue
                for instruction in code.get_bc().get_instructions():
                    if not instruction.get_name().startswith("invoke-"):
                        continue
                    for operand in instruction.get_operands():
                        if (isinstance(operand, tuple) and len(operand) == 3
                                and isinstance(operand[2], str)
                                and "->" in operand[2]):
                            call_sites.append({
                                "caller": owner + "->" + method.get_name() + descriptor,
                                "opcode": instruction.get_name(),
                                "target": operand[2],
                            })
                            break
    return records, call_sites


def verify(apks_path, output_path):
    logger.disable("androguard")
    with apks_path.open("rb") as stream:
        apks_sha = hashlib.file_digest(stream, "sha256").hexdigest()

    with tempfile.TemporaryDirectory(prefix="furihook-x-contract-",
                                     dir=apks_path.resolve().parent) as temporary:
        base_path = pathlib.Path(temporary) / "base.apk"
        with zipfile.ZipFile(apks_path) as bundle:
            names = set(bundle.namelist())
            if "base.apk" not in names:
                raise AssertionError("APKS缺少base.apk")
            base_path.write_bytes(bundle.read("base.apk"))
            split_names = sorted(name for name in names if name.endswith(".apk"))

        base = APK(str(base_path))
        actual_version = {
            "packageName": base.get_package(),
            "versionName": base.get_androidversion_name(),
            "versionCode": base.get_androidversion_code(),
        }
        if actual_version != {
                "packageName": PACKAGE_NAME,
                "versionName": VERSION_NAME,
                "versionCode": VERSION_CODE,
        }:
            raise AssertionError("X安装包版本或包名与预期不符：" + str(actual_version))

        with zipfile.ZipFile(base_path) as apk:
            dex_names = sorted(name for name in apk.namelist()
                               if name.startswith("classes") and name.endswith(".dex"))
            if not dex_names:
                raise AssertionError("base.apk缺少DEX")
            dex_files = [(name, DEX(apk.read(name))) for name in dex_names]

    all_classes = {}
    for dex_name, dex in dex_files:
        for definition in dex.get_classes():
            all_classes[definition.get_name()] = {
                "dex": dex_name,
                "fields": {field.get_name(): {
                    "descriptor": field.get_descriptor(),
                    "access": field.get_access_flags_string(),
                } for field in definition.get_fields()},
            }

    missing_classes = EXPECTED_CLASSES.difference(all_classes)
    if missing_classes:
        raise AssertionError("缺少反射类：" + ", ".join(sorted(missing_classes)))

    for owner, fields in EXPECTED_FIELDS.items():
        if owner not in all_classes:
            raise AssertionError("缺少反射类：" + owner)
        actual = all_classes[owner]["fields"]
        for name, descriptor in fields.items():
            if name not in actual or actual[name]["descriptor"] != descriptor:
                raise AssertionError("字段描述符不符：" + owner + "->" + name)

    methods, call_sites = method_records(dex_files)
    for (owner, name), descriptor in EXPECTED_METHODS.items():
        candidates = methods.get((owner, name), [])
        if not any(item["descriptor"] == descriptor for item in candidates):
            raise AssertionError("方法描述符不符：" + owner + "->" + name + descriptor)
        if (owner, name) in (
                ("Landroidx/compose/ui/unit/a;", "i"),
                ("Landroidx/compose/ui/node/d;", "k"),
                ("Landroidx/compose/ui/node/d;", "i")):
            actual = next(item for item in candidates if item["descriptor"] == descriptor)
            if "static" not in actual["access"].split():
                raise AssertionError("静态方法标志不符：" + owner + "->" + name)
    for owner in (
            "Landroidx/compose/foundation/text/modifiers/u;",
            "Landroidx/compose/foundation/text/modifiers/y;"):
        for name in INTRINSIC_METHODS:
            candidates = methods.get((owner, name), [])
            if not any(item["descriptor"] == INTRINSIC_DESCRIPTOR for item in candidates):
                raise AssertionError("intrinsic描述符不符：" + owner + "->" + name)

    reflected = {
        "classes": sorted(EXPECTED_CLASSES),
        "fields": [
            {"owner": owner, "name": name, "descriptor": descriptor,
             "access": all_classes[owner]["fields"][name]["access"]}
            for owner, fields in EXPECTED_FIELDS.items()
            for name, descriptor in fields.items()
        ],
        "methods": [
            {"owner": owner, "name": name, "descriptor": descriptor,
             "access": next(item["access"] for item in methods[(owner, name)]
                            if item["descriptor"] == descriptor)}
            for (owner, name), descriptor in EXPECTED_METHODS.items()
        ] + [
            {"owner": owner, "name": name, "descriptor": INTRINSIC_DESCRIPTOR,
             "access": next(item["access"] for item in methods[(owner, name)]
                            if item["descriptor"] == INTRINSIC_DESCRIPTOR)}
            for owner in (
                "Landroidx/compose/foundation/text/modifiers/u;",
                "Landroidx/compose/foundation/text/modifiers/y;")
            for name in INTRINSIC_METHODS
        ],
    }
    business_chain = [
        site for site in call_sites
        if site["caller"].startswith("Lcom/x/urt/items/post/notepost/b;->a(")
        and site["target"].startswith("Landroidx/compose/material3/se;->c(")
    ]
    material_to_foundation = [
        site for site in call_sites
        if site["caller"].startswith("Landroidx/compose/material3/se;->c(")
        and site["target"].startswith("Landroidx/compose/foundation/text/e1;->c(")
    ]
    foundation_to_nodes = [
        site for site in call_sites
        if site["caller"].startswith("Landroidx/compose/foundation/text/e1;->c(")
        and site["target"].startswith((
            "Landroidx/compose/foundation/text/modifiers/r;-><init>",
            "Landroidx/compose/foundation/text/modifiers/v;-><init>",
        ))
    ]
    paragraph_creation = [
        site for site in call_sites
        if site["caller"].startswith("Landroidx/compose/ui/text/b0;-><init>(")
        and site["target"].startswith("Landroidx/compose/ui/text/b;-><init>(")
    ]
    if not business_chain or not material_to_foundation or not foundation_to_nodes:
        raise AssertionError(
            "缺少X正文到Compose Foundation文本节点的调用链证据："
            + json.dumps({"business": len(business_chain),
                          "material": len(material_to_foundation),
                          "foundation": len(foundation_to_nodes),
                          "seTargets": [site["target"] for site in call_sites
                                       if site["caller"].startswith(
                                           "Landroidx/compose/material3/se;->c(")
                                       and "foundation" in site["target"]][:12]}))
    if not paragraph_creation:
        raise AssertionError("缺少多段落文本创建ParagraphIntrinsics的调用证据")
    evidence = business_chain + material_to_foundation + foundation_to_nodes + paragraph_creation

    report = {
        "apks": str(apks_path),
        "apksSha256": apks_sha,
        "version": actual_version,
        "apkSplits": split_names,
        "baseDexFiles": dex_names,
        "reflectionContract": reflected,
        "callEvidence": {
            "xPostBodyToMaterialText": business_chain,
            "materialTextToFoundationText": material_to_foundation,
            "foundationTextToModifierNodes": foundation_to_nodes,
            "multiParagraphIntrinsicsCreation": paragraph_creation,
        },
        "staticContractVerified": True,
        "runtimeHookVerified": False,
    }
    rendered = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if output_path is not None:
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(rendered, encoding="utf-8")
    print(rendered, end="")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--apks", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path)
    arguments = parser.parse_args()
    verify(arguments.apks, arguments.output)
