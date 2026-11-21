"""Check the Android ARM64 libraries for Minecraft Java 1.21.4."""
import argparse
import hashlib
import io
from pathlib import Path
import re
import struct
import subprocess
import sys
import tempfile
from zipfile import BadZipFile, ZipFile

NATIVE_ROOT = "lib/arm64-v8a"
ASSETS = "assets/lwjgl/3.3.3"
PAIRS = {
    "lwjgl": ("liblwjgl.so", "org/lwjgl"),
    "lwjgl-opengl": ("liblwjgl_opengl.so", "org/lwjgl/opengl"),
    "lwjgl-stb": ("liblwjgl_stb.so", "org/lwjgl/stb"),
    "lwjgl-tinyfd": ("liblwjgl_tinyfd.so", "org/lwjgl/tinyfd"),
}
# The JVM uses the system allocator in place of jemalloc.
REQUIRED_NATIVES = {
    "libprissma_jvm_bridge.so", "libjnidispatch.so", "libglfw.so",
    "libopenal.so", "libfreetype.so", "libmobileglues.so",
    *(library for library, _ in PAIRS.values()),
}
GLFW_SYMBOLS = {
    "glfwInit", "glfwCreateWindow", "glfwMakeContextCurrent", "glfwSwapBuffers",
    "glfwPollEvents", "glfwGetProcAddress", "glfwTerminate",
    "prissmaGlfwSetSurface", "prissmaGlfwSetSize", "prissmaGlfwInput",
    "prissmaGlfwHasPresentedFrame", "prissmaGlfwCursorCaptured",
}
RENDERER_SYMBOLS = {"eglBindAPI", "eglCreateContext", "eglCreateWindowSurface", "eglSwapBuffers", "glXGetProcAddress"}
DESKTOP_DEPENDENCY = re.compile(
    r"^(?:ld-linux[^/]*|lib(?:X11|Xrandr|Xcursor|Xi|Xinerama|wayland[^/]*|xkbcommon)"
    r"\.so.*|.+\.so\.\d+(?:\.\d+)*)$"
)


def is_arm64_shared_elf(binary):
    return (
        len(binary) >= 64
        and binary[:7] == b"\x7fELF\x02\x01\x01"
        and struct.unpack_from("<HH", binary, 16) == (3, 183)
    )


def check_jar_pair(apk, module, library, package, binary):
    asset = f"{ASSETS}/{module}-3.3.3.jar"
    resource = f"META-INF/linux/arm64/{package}/{library}.sha1"
    try:
        with ZipFile(io.BytesIO(apk.read(asset))) as jar:
            expected = jar.read(resource).decode("ascii").strip().lower()
            if module == "lwjgl" and b"pojavexec" in jar.read("org/lwjgl/system/Library.class"):
                return f"{asset}: Library.class todavía intenta cargar pojavexec."
    except (KeyError, BadZipFile, UnicodeError) as error:
        return f"{asset}: faltan componentes del JAR o el SHA-1 de su nativo ({error})."
    if expected != hashlib.sha1(binary).hexdigest():
        return f"{module}: el SHA-1 de {library} no coincide con el JAR del APK."
    return None


def native_details(binary, directory, library):
    path = directory / library
    path.write_bytes(binary)
    output = subprocess.run(
        ["readelf", "--wide", "--dynamic", "--dyn-syms", str(path)],
        check=True, capture_output=True, text=True,
    ).stdout
    dependencies = re.findall(r"\(NEEDED\).*?\[([^\]]+)\]", output)
    exports = set()
    for line in output.splitlines():
        fields = line.split()
        if (
            len(fields) >= 8 and fields[0].rstrip(":").isdigit()
            and fields[4] in {"GLOBAL", "WEAK"}
            and fields[5] in {"DEFAULT", "PROTECTED"} and fields[6] != "UND"
        ):
            exports.add(fields[7].split("@", 1)[0])
    return dependencies, exports


def validate_apk(apk_path):
    problems = []
    with ZipFile(apk_path) as apk, tempfile.TemporaryDirectory() as workdir:
        names = set(apk.namelist())
        directory = Path(workdir)
        for library in sorted(REQUIRED_NATIVES):
            name = f"{NATIVE_ROOT}/{library}"
            if name not in names:
                problems.append(f"Falta {name}.")
                continue
            binary = apk.read(name)
            if not is_arm64_shared_elf(binary):
                problems.append(f"{name}: se esperaba una biblioteca ELF AArch64 de 64 bits.")
                continue
            try:
                dependencies, exports = native_details(binary, directory, library)
            except subprocess.CalledProcessError as error:
                problems.append(f"{name}: readelf no pudo analizar el binario ({error.stderr.strip()}).")
                continue
            desktop = sorted(dep for dep in dependencies if DESKTOP_DEPENDENCY.fullmatch(dep))
            if desktop:
                problems.append(f"{name}: dependencias de escritorio: {', '.join(desktop)}.")
            if library == "libglfw.so":
                missing = sorted(GLFW_SYMBOLS - exports)
                if missing:
                    problems.append(f"{name}: símbolos GLFW ausentes: {', '.join(missing)}.")
            if library == "libmobileglues.so":
                missing = sorted(RENDERER_SYMBOLS - exports)
                if missing:
                    problems.append(f"{name}: símbolos EGL/GL ausentes: {', '.join(missing)}.")

        for module, (library, package) in PAIRS.items():
            name = f"{NATIVE_ROOT}/{library}"
            if name in names:
                problem = check_jar_pair(apk, module, library, package, apk.read(name))
                if problem:
                    problems.append(problem)
    return problems


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    try:
        problems = validate_apk(args.apk)
    except (BadZipFile, OSError, RuntimeError) as error:
        parser.exit(2, f"No se pudo verificar el APK: {error}\n")
    for problem in problems:
        print(f"ERROR: {problem}", file=sys.stderr)
    if not problems:
        print("APK verificado: módulos de Minecraft Java 1.21.4 para Android ARM64.")
    return int(bool(problems))


if __name__ == "__main__":
    sys.exit(main())
