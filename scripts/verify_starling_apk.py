#!/usr/bin/env python3
"""Check that BIOMERIA embeds the pinned Starling ARM64 runtime without a second APK."""

import sys
from zipfile import ZipFile


ENGINE_FILES = (
    "lib/arm64-v8a/libhorizon_android.so",
    "lib/arm64-v8a/libhorizon_core.so",
    "lib/arm64-v8a/libstarling_java.so",
    "lib/arm64-v8a/libstarling_javac.so",
    "assets/prepare-kit.zip",
    "assets/lowbudget.zip",
    "assets/StarlingVisuals.zip",
    "assets/starling/licenses/LICENSE",
    "assets/starling/licenses/THIRD_PARTY_NOTICES.md",
)


def verify(apk, upstream):
    with ZipFile(apk) as package, ZipFile(upstream) as engine:
        files = package.namelist()
        expected = {name for name in engine.namelist() if name.startswith(("lib/arm64-v8a/", "assets/"))}
        for name in expected | set(ENGINE_FILES):
            if files.count(name) != 1:
                raise ValueError(f"Missing or duplicate embedded Starling file: {name}")
        for name in expected:
            with engine.open(name) as original, package.open(name) as bundled:
                for expected_chunk in iter(lambda: original.read(65536), b""):
                    if bundled.read(len(expected_chunk)) != expected_chunk:
                        raise ValueError(f"Embedded Starling file differs from the pinned build: {name}")
                if bundled.read(1):
                    raise ValueError(f"Embedded Starling file has unexpected trailing bytes: {name}")
        if any(name.lower().endswith(".apk") for name in files):
            raise ValueError("BIOMERIA unexpectedly contains a nested APK")


if __name__ == "__main__":
    verify(*sys.argv[1:])
    print("Starling ARM64 runtime verified inside BIOMERIA")
