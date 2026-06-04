package com.saloidvl.lsp4jmcp.runtime;

import java.nio.file.Path;

public final class SocketPaths {
    private static final String CACHE_DIR_NAME = ".cache";
    private static final String APP_DIR_NAME = "lsp4j-mcp";

    private SocketPaths() {
    }

    public static Path supervisorSocketDirectory() {
        return Path.of(System.getProperty("user.home"), CACHE_DIR_NAME, APP_DIR_NAME);
    }

    public static Path supervisorSocketPath() {
        return supervisorSocketDirectory().resolve("supervisor-" + BuildInfo.version() + ".sock");
    }
}
