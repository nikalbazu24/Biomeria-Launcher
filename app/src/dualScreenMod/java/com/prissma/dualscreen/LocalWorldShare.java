package com.prissma.dualscreen;

import java.lang.reflect.Method;

final class LocalWorldShare {
    private LocalWorldShare() {}

    static int open(Object server) throws ReflectiveOperationException {
        if (server == null) return 0;
        ClassLoader loader = server.getClass().getClassLoader();
        Class<?> serverType = loader.loadClass("net.minecraft.server.MinecraftServer");
        Method isPublished = serverType.getMethod("method_3860");
        if ((boolean) isPublished.invoke(server)) return (int) serverType.getMethod("method_3756").invoke(server);
        Object mode = serverType.getMethod("method_3790").invoke(server);
        Method findPort = loader.loadClass("net.minecraft.class_3521").getMethod("method_15302");
        Method publish = serverType.getMethod("method_3763", loader.loadClass("net.minecraft.class_1934"), boolean.class, int.class);
        for (int i = 0; i < 3; i++) {
            int port = (int) findPort.invoke(null);
            if (port > 0 && port <= 65535 && (boolean) publish.invoke(server, mode, false, port)) return port;
        }
        return -1;
    }
}
