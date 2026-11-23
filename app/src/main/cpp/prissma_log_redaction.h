#pragma once

#include <cstddef>
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

/**
 * JNI's NewStringUTF only accepts Modified UTF-8: no NUL bytes, no 4-byte sequences and no broken
 * sequences. Game and mod output can contain any of those (emoji, binary noise), and with CheckJNI
 * an invalid line aborts the whole process. Every invalid or unsupported sequence becomes '?', and
 * the result is cut at a character boundary so it never exceeds maxBytes.
 */
inline std::string toJniSafeLogLine(const std::string& line, std::size_t maxBytes = 4000) {
    std::string out;
    out.reserve(line.size() < maxBytes ? line.size() : maxBytes);
    const auto* bytes = reinterpret_cast<const unsigned char*>(line.data());
    const std::size_t size = line.size();
    auto continuation = [&](std::size_t at) { return at < size && (bytes[at] & 0xC0) == 0x80; };
    std::size_t i = 0;
    while (i < size) {
        const unsigned char lead = bytes[i];
        std::size_t length = 0;
        if (lead >= 0x01 && lead <= 0x7F) {
            length = 1;
        } else if (lead >= 0xC2 && lead <= 0xDF && continuation(i + 1)) {
            length = 2;
        } else if (lead >= 0xE0 && lead <= 0xEF && continuation(i + 1) && continuation(i + 2)) {
            const unsigned char second = bytes[i + 1];
            const bool overlong = lead == 0xE0 && second < 0xA0;
            const bool surrogate = lead == 0xED && second >= 0xA0;
            if (!overlong && !surrogate) length = 3;
        }
        if (length == 0) {
            // Skip a whole 4-byte sequence at once so one emoji becomes a single '?'.
            std::size_t skip = 1;
            if (lead >= 0xF0 && lead <= 0xF4) {
                while (skip < 4 && continuation(i + skip)) ++skip;
            }
            if (out.size() + 1 > maxBytes) break;
            out.push_back('?');
            i += skip;
            continue;
        }
        if (out.size() + length > maxBytes) break;
        out.append(line, i, length);
        i += length;
    }
    return out;
}
