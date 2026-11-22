package com.prissma.dualscreen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Binds a room request to the one integrated Minecraft server that opened it. */
final class InternetWorldLease {
    private String request;
    private Object server;
    private int port;

    void update(Path directory, Object integratedServer, boolean worldReady) {
        try {
            Path marker = directory.resolve("internet-open");
            if (Files.size(marker) != 36) return;
            String token = Files.readString(marker, StandardCharsets.US_ASCII);
            if (!UUID.fromString(token).toString().equals(token)) return;
            if (!token.equals(request)) {
                request = token;
                server = worldReady ? integratedServer : null;
                port = open();
            } else if (port > 0 && server != integratedServer) {
                // Dimension changes keep the same integrated server. Another save must get a new request.
                server = null;
                port = 0;
            }
            GamePanelBridge.writeAtomically(directory.resolve("internet-world"),
                    (token + " " + port).getBytes(StandardCharsets.US_ASCII));
        } catch (IOException | IllegalArgumentException ignored) {
            // The Android owner may stop or replace the request between reads.
        }
    }

    private int open() {
        try {
            return LocalWorldShare.open(server);
        } catch (ReflectiveOperationException | LinkageError error) {
            System.err.println("[PRISSMA] No se pudo preparar el mundo para internet: " + error);
            return -1;
        }
    }

    void clear() {
        request = null;
        server = null;
        port = 0;
    }
}
