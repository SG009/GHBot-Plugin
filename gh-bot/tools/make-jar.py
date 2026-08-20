#!/usr/bin/env python3
"""Build GHBot-<version>.jar from compiled classes + resources (Gradle-free).

The dev sandbox cannot reach services.gradle.org / repo.papermc.io, so Gradle
can't run. This replicates the `jar` task: it packages the already-compiled
classes and the src/main/resources tree (with the `${version}` placeholder in
plugin.yml expanded) into build/libs/GHBot-<version>.jar.

Usage: python3 tools/make-jar.py <classes-dir> <resources-dir> <version> <out.jar>
"""
import os
import sys
import zipfile


def build(classes_dir, resources_dir, version, out_path):
    os.makedirs(os.path.dirname(out_path) or ".", exist_ok=True)
    count = 0
    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as z:
        for root, _, files in os.walk(classes_dir):
            for f in files:
                if not f.endswith(".class"):
                    continue
                p = os.path.join(root, f)
                z.write(p, os.path.relpath(p, classes_dir))
                count += 1
        for root, _, files in os.walk(resources_dir):
            for f in files:
                p = os.path.join(root, f)
                arc = os.path.relpath(p, resources_dir)
                data = open(p, "rb").read()
                if f == "plugin.yml":
                    data = data.replace(b"${version}", version.encode("utf-8"))
                z.writestr(arc, data)
    return count


if __name__ == "__main__":
    n = build(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4])
    print(f"[make-jar] packaged {n} classes -> {sys.argv[4]}")
