package com.saloidvl.lsp4jmcp.client;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LombokSupport {
    private static final Logger LOG = LoggerFactory.getLogger(LombokSupport.class);

    private LombokSupport() {
    }

    public static boolean usesLombok(Path workspace) {
        boolean[] found = {false};
        try {
            Files.walkFileTree(
                workspace, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                        if (name.equals("target") || name.equals("build") || name.equals(".gradle")) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (!file.toString().endsWith(".java")) {
                            return FileVisitResult.CONTINUE;
                        }
                        try {
                            for (String line : Files.readAllLines(file)) {
                                if (line.startsWith("import lombok.")) {
                                    found[0] = true;
                                    return FileVisitResult.TERMINATE;
                                }
                            }
                        } catch (IOException e) {
                            LOG.debug("Skipping unreadable file during Lombok scan: {}", file);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        LOG.debug("Skipping inaccessible path during Lombok scan: {}", file);
                        return FileVisitResult.CONTINUE;
                    }
                });
        } catch (IOException e) {
            LOG.debug("Lombok workspace scan failed: {}", e.getMessage());
        }
        return found[0];
    }

    public static Optional<Path> findJar() {
        return findJar(System.getenv("LOMBOK_JAR"), System.getProperty("user.home"));
    }

    static Optional<Path> findJar(String lombokJarEnv, String userHome) {
        if (lombokJarEnv != null && !lombokJarEnv.isBlank()) {
            Path path = Path.of(lombokJarEnv);
            if (Files.exists(path)) {
                return Optional.of(path);
            }
            LOG.warn("LOMBOK_JAR env points to non-existent file: {}", path);
            return Optional.empty();
        }

        Optional<Path> maven = findLatestJar(
            Path.of(userHome, ".m2", "repository", "org", "projectlombok", "lombok"));
        if (maven.isPresent()) {
            return maven;
        }

        return findLatestJar(
            Path.of(
                userHome, ".gradle", "caches", "modules-2", "files-2.1",
                "org.projectlombok", "lombok"));
    }

    private static Optional<Path> findLatestJar(Path baseDir) {
        if (!Files.isDirectory(baseDir)) {
            return Optional.empty();
        }
        try (var stream = Files.walk(baseDir, 3)) {
            return stream
                .filter(path -> path.getFileName().toString().matches("lombok-[\\d.]+\\.jar"))
                .max((a, b) -> compareVersions(
                    parseVersion(a.getFileName().toString()),
                    parseVersion(b.getFileName().toString())));
        } catch (IOException e) {
            LOG.debug("Failed to search {} for lombok.jar: {}", baseDir, e.getMessage());
            return Optional.empty();
        }
    }

    static List<Integer> parseVersion(String filename) {
        String version = filename.replaceFirst("^lombok-", "").replaceFirst("\\.jar$", "");
        return Arrays.stream(version.split("\\."))
            .map(segment -> {
                try {
                    return Integer.parseInt(segment);
                } catch (NumberFormatException e) {
                    return 0;
                }
            })
            .collect(Collectors.toList());
    }

    private static int compareVersions(List<Integer> left, List<Integer> right) {
        int length = Math.max(left.size(), right.size());
        for (int i = 0; i < length; i++) {
            int leftValue = i < left.size() ? left.get(i) : 0;
            int rightValue = i < right.size() ? right.get(i) : 0;
            if (leftValue != rightValue) {
                return Integer.compare(leftValue, rightValue);
            }
        }
        return 0;
    }

    public static Optional<Path> detectAndFind(Path workspace) {
        if (!usesLombok(workspace)) {
            return Optional.empty();
        }
        Optional<Path> jar = findJar();
        if (jar.isEmpty()) {
            LOG.warn("Lombok detected in workspace but lombok.jar not found on this system. "
                     + "Lombok-generated code (constructors, getters, etc.) may produce false JDTLS "
                     + "diagnostics. Set LOMBOK_JAR env var to the path of lombok.jar to fix this.");
        }
        LOG.info("Lombok jar path: {}", jar.orElse(null));
        return jar;
    }
}
