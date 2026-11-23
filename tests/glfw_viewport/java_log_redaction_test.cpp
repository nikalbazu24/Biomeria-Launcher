#include "biomeria_log_redaction.h"

#include <cassert>

int main() {
    const std::string token = "sample.header.signature";
    assert(redactMinecraftToken("[log] " + token + ", " + token, token)
            == "[log] [token oculto], [token oculto]");
    assert(redactMinecraftToken("Minecraft 1.20.1", "0") == "Minecraft 1.20.1");
    assert(redactMinecraftToken("", token).empty());
    assert(redactMinecraftToken("sin datos de sesión", {}) == "sin datos de sesión");

    // Spanish accents (2-byte) and other 3-byte characters pass through untouched.
    assert(toJniSafeLogLine("Cargando mundo: Montaña ☀") == "Cargando mundo: Montaña ☀");
    // An emoji (4-byte UTF-8) is not valid Modified UTF-8 and becomes a single '?'.
    assert(toJniSafeLogLine("listo \xF0\x9F\x8E\xAE!") == "listo ?!");
    // Embedded NUL, stray continuation bytes and a truncated sequence are replaced.
    assert(toJniSafeLogLine(std::string("a\0b", 3)) == "a?b");
    assert(toJniSafeLogLine("x\x80y") == "x?y");
    assert(toJniSafeLogLine("fin \xC3") == "fin ?");
    // UTF-16 surrogates encoded as UTF-8 and overlong forms are rejected.
    assert(toJniSafeLogLine("\xED\xA0\x80") == "???");
    assert(toJniSafeLogLine("\xE0\x80\x80") == "???");
    // The length cap never cuts a character in half.
    assert(toJniSafeLogLine("abñ", 3) == "ab");
    assert(toJniSafeLogLine(std::string(5000, 'z')).size() == 4000);
}
