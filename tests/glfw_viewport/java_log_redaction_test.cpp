#include "prissma_log_redaction.h"

#include <cassert>

int main() {
    const std::string token = "sample.header.signature";
    assert(redactMinecraftToken("[log] " + token + ", " + token, token)
            == "[log] [token oculto], [token oculto]");
    assert(redactMinecraftToken("Minecraft 1.20.1", "0") == "Minecraft 1.20.1");
    assert(redactMinecraftToken("", token).empty());
    assert(redactMinecraftToken("sin datos de sesión", {}) == "sin datos de sesión");
}
