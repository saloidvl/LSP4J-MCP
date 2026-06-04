package com.saloidvl.lsp4jmcp.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class BuildInfo {

    private static final String VERSION = loadVersion();

    private BuildInfo() {
    }

    public static String version() {
        return VERSION;
    }

    private static String loadVersion() {
        try (InputStream input = BuildInfo.class.getClassLoader().getResourceAsStream("build-info.properties")) {
            if (input == null) {
                return "unknown";
            }
            Properties props = new Properties();
            props.load(input);
            return props.getProperty("project.version", "unknown");
        } catch (IOException e) {
            return "unknown";
        }
    }
}
