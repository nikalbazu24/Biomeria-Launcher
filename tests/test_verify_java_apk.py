import hashlib
import io
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

from scripts.verify_java_apk import (
    ASSETS,
    GLFW_SYMBOLS,
    NATIVE_ROOT,
    PAIRS,
    REQUIRED_NATIVES,
    RENDERER_SYMBOLS,
    LTW_SYMBOLS,
    check_jar_pair,
    is_arm64_shared_elf,
    validate_apk,
)


def elf(machine=183, file_type=3):
    header = bytearray(64)
    header[:7] = b"\x7fELF\x02\x01\x01"
    struct.pack_into("<HH", header, 16, file_type, machine)
    return bytes(header)


def jar(library, package, native, loader=b"loader"):
    data = io.BytesIO()
    with ZipFile(data, "w") as archive:
        archive.writestr(
            f"META-INF/linux/arm64/{package}/{library}.sha1",
            hashlib.sha1(native).hexdigest(),
        )
        if library == "liblwjgl.so":
            archive.writestr("org/lwjgl/system/Library.class", loader)
    return data.getvalue()


class VerifyJavaApkTest(unittest.TestCase):
    def test_rejects_wrong_architecture_and_file_type(self):
        self.assertTrue(is_arm64_shared_elf(elf()))
        self.assertFalse(is_arm64_shared_elf(elf(machine=62)))
        self.assertFalse(is_arm64_shared_elf(elf(file_type=2)))
        self.assertFalse(is_arm64_shared_elf(b"\x7fELF"))

    def test_reports_mismatched_jar_and_so(self):
        data = io.BytesIO()
        with ZipFile(data, "w") as apk:
            apk.writestr(
                f"{ASSETS}/lwjgl-3.3.3.jar",
                jar("liblwjgl.so", "org/lwjgl", b"another build"),
            )
        with ZipFile(data) as apk:
            error = check_jar_pair(apk, "lwjgl", "liblwjgl.so", "org/lwjgl", elf())
        self.assertIn("no coincide", error)

    def test_reports_absent_glfw(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "missing-glfw.apk"
            with ZipFile(path, "w"):
                pass
            self.assertIn(f"Falta {NATIVE_ROOT}/libglfw.so.", validate_apk(path))

    def test_rejects_the_amethyst_bootstrap_without_its_launcher(self):
        data = io.BytesIO()
        with ZipFile(data, "w") as apk:
            apk.writestr(
                f"{ASSETS}/lwjgl-3.3.3.jar",
                jar("liblwjgl.so", "org/lwjgl", elf(), loader=b"pojavexec"),
            )
        with ZipFile(data) as apk:
            error = check_jar_pair(apk, "lwjgl", "liblwjgl.so", "org/lwjgl", elf())
        self.assertIn("todavía intenta cargar pojavexec", error)

    def test_reports_desktop_dependencies_and_missing_glfw_exports(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "desktop-glfw.apk"
            with ZipFile(path, "w") as apk:
                apk.writestr(f"{NATIVE_ROOT}/libglfw.so", elf())
            with patch(
                "scripts.verify_java_apk.native_details",
                return_value=(["libc.so.6", "libX11.so.6"], {"glfwInit"}),
            ):
                problems = validate_apk(path)
            self.assertTrue(any("libX11.so.6" in problem for problem in problems))
            self.assertTrue(any("glfwCreateWindow" in problem for problem in problems))

    def test_accepts_complete_matched_package(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "complete.apk"
            with ZipFile(path, "w") as apk:
                for library in REQUIRED_NATIVES:
                    apk.writestr(f"{NATIVE_ROOT}/{library}", elf())
                for module, (library, package) in PAIRS.items():
                    apk.writestr(f"{ASSETS}/{module}-3.3.3.jar", jar(library, package, elf()))
            with patch(
                "scripts.verify_java_apk.native_details",
                return_value=(["libc.so", "libEGL.so"], GLFW_SYMBOLS | RENDERER_SYMBOLS | LTW_SYMBOLS),
            ):
                self.assertEqual([], validate_apk(path))


if __name__ == "__main__":
    unittest.main()
