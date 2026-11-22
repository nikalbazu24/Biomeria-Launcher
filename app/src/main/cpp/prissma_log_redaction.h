#pragma once

#include <string>

inline std::string redactMinecraftToken(std::string line, const std::string& token) {
    if (token.empty() || token == "0") return line;
    const std::string replacement = "[token oculto]";
    for (std::size_t offset = 0; (offset = line.find(token, offset)) != std::string::npos;
            offset += replacement.size()) {
        line.replace(offset, token.size(), replacement);
    }
    return line;
}
