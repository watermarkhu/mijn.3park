#!/usr/bin/env python3
"""Fail CI when Acode's app/module.toml and the Gradle files drift apart.

Compares the source of truth (app/module.toml) against app/build.gradle.kts:
  - android namespace / applicationId
  - compileSdk, minSdk, targetSdk, versionCode, versionName
  - languageLevel JAVA_8  <->  JavaVersion.VERSION_1_8 (jvmTarget defaults to
    targetCompatibility under AGP built-in Kotlin, so it isn't set explicitly)
  - every module.toml implementation/testImplementation entry present in Gradle
    with the same version (entries without a version, e.g. "kotlin-stdlib",
    only require the artifact to be present)
  - manifest path exists, build types don't enable minification

Stdlib only (tomllib needs Python >= 3.11, as on ubuntu-latest runners).
Exits 0 when in sync, 1 with a list of mismatches otherwise.
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOML_PATH = ROOT / "app" / "module.toml"
GRADLE_PATH = ROOT / "app" / "build.gradle.kts"

try:
    import tomllib
except ImportError:  # pragma: no cover
    print("ERROR: Python >= 3.11 required (tomllib missing)")
    sys.exit(2)


def fail(errors: list[str]) -> int:
    print(f"{len(errors)} sync error(s) between {TOML_PATH} and {GRADLE_PATH}:")
    for e in errors:
        print(f"  - {e}")
    print("Fix: update app/build.gradle.kts to match app/module.toml (source of truth).")
    return 1


def main() -> int:
    errors: list[str] = []
    try:
        module = tomllib.loads(TOML_PATH.read_text())
    except FileNotFoundError:
        print(f"ERROR: {TOML_PATH} not found")
        return 2
    try:
        gradle = GRADLE_PATH.read_text()
    except FileNotFoundError:
        print(f"ERROR: {GRADLE_PATH} not found")
        return 2

    android = module.get("android", {})

    # --- scalar fields ---
    for field, pattern in [
        ("namespace", r'namespace\s*=\s*"([^"]+)"'),
        ("compileSdk", r"compileSdk\s*=\s*(\d+)"),
        ("minSdk", r"minSdk\s*=\s*(\d+)"),
        ("targetSdk", r"targetSdk\s*=\s*(\d+)"),
        ("versionCode", r"versionCode\s*=\s*(\d+)"),
        ("versionName", r'versionName\s*=\s*"([^"]+)"'),
    ]:
        expected = android.get(field)
        m = re.search(pattern, gradle)
        if expected is None:
            errors.append(f"module.toml is missing [android] {field}")
        elif not m:
            errors.append(f"app/build.gradle.kts is missing {field} (expected {expected!r})")
        elif str(expected) != m.group(1):
            errors.append(
                f"{field} mismatch: module.toml={expected!r} vs build.gradle.kts={m.group(1)!r}"
            )

    # applicationId must track the namespace (single-app repo).
    m = re.search(r'applicationId\s*=\s*"([^"]+)"', gradle)
    if m and android.get("namespace") and m.group(1) != android["namespace"]:
        errors.append(
            f"applicationId mismatch: module.toml namespace={android['namespace']!r} "
            f"vs build.gradle.kts applicationId={m.group(1)!r}"
        )

    # --- language level ---
    # Built-in Kotlin (AGP 9+) derives the Kotlin jvmTarget from
    # compileOptions.targetCompatibility, so only the Java level is checked.
    if module.get("module", {}).get("languageLevel") == "JAVA_8":
        if "JavaVersion.VERSION_1_8" not in gradle:
            errors.append('languageLevel JAVA_8 requires JavaVersion.VERSION_1_8 in compileOptions')

    # --- dependencies ---
    module_deps = module.get("dependencies", {})
    for scope in ("implementation", "testImplementation"):
        gradle_deps = set(re.findall(rf'{scope}\("([^"]+)"\)', gradle))
        for entry in module_deps.get(scope, []):
            parts = entry.split(":")
            if len(parts) == 1:  # bare artifact, e.g. "kotlin-stdlib": version unpinned
                if not any(entry in dep for dep in gradle_deps):
                    errors.append(
                        f"{scope} dependency {entry!r} from module.toml missing in build.gradle.kts"
                    )
            elif len(parts) == 3:  # group:name:version must match exactly
                if entry not in gradle_deps:
                    errors.append(
                        f"{scope} dependency {entry!r} from module.toml missing or version-drifted "
                        f"in build.gradle.kts (has {sorted(d for d in gradle_deps if parts[1] in d) or 'nothing'})"
                    )
            else:
                errors.append(f"unparseable dependency entry {entry!r} in module.toml")

        for dep in sorted(gradle_deps):
            dep_parts = dep.split(":")
            dep_key = ":".join(dep_parts[:2]) if len(dep_parts) >= 3 else dep
            dep_artifact = dep_parts[1] if len(dep_parts) >= 3 else dep
            known = False
            for e in module_deps.get(scope, []):
                e_parts = e.split(":")
                if len(e_parts) == 1 and e == dep_artifact:
                    known = True
                elif len(e_parts) == 3 and ":".join(e_parts[:2]) == dep_key:
                    known = True
            if not known:
                errors.append(
                    f"{scope} dependency {dep!r} in build.gradle.kts has no counterpart in module.toml"
                )

    # --- manifest ---
    manifest = android.get("manifest")
    if manifest and not (ROOT / "app" / manifest).is_file():
        errors.append(f"manifest {manifest!r} from module.toml does not exist under app/")

    # --- build types must stay non-minified (module.toml: minifyEnabled = false) ---
    for bt in android.get("buildTypes", []):
        if bt.get("minifyEnabled") is False and "isMinifyEnabled = true" in gradle:
            errors.append(f"build type {bt.get('name')!r}: module.toml minifyEnabled=false but Gradle enables it")
            break
    else:
        if any(bt.get("minifyEnabled") is False for bt in android.get("buildTypes", [])):
            if "isMinifyEnabled = false" not in gradle:
                errors.append("module.toml disables minifyEnabled but build.gradle.kts never sets isMinifyEnabled = false")

    if errors:
        return fail(errors)
    print(f"OK: {TOML_PATH.name} and {GRADLE_PATH.name} are in sync.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
