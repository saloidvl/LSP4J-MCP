package com.saloidvl.lsp4jmcp.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class LombokSupportTest {

    @TempDir
    Path tempDir;

    @Test
    void usesLombok_returnsTrueWhenJavaFileHasLombokImport() throws IOException {
        Path src = Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(
            src.resolve("MyEntity.java"),
            "package com.example;\nimport lombok.Data;\n@Data\npublic class MyEntity { private String name; }");

        assertThat(LombokSupport.usesLombok(tempDir)).isTrue();
    }

    @Test
    void usesLombok_returnsFalseWhenNoLombokImport() throws IOException {
        Path src = Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(
            src.resolve("Foo.java"),
            "package com.example;\nimport java.util.List;\npublic class Foo {}");

        assertThat(LombokSupport.usesLombok(tempDir)).isFalse();
    }

    @Test
    void usesLombok_returnsFalseForEmptyWorkspace() {
        assertThat(LombokSupport.usesLombok(tempDir)).isFalse();
    }

    @Test
    void usesLombok_excludesTargetDirectory() throws IOException {
        Path target = Files.createDirectories(tempDir.resolve("target").resolve("generated-sources"));
        Files.writeString(
            target.resolve("Generated.java"),
            "import lombok.Data;\n@Data\npublic class Generated {}");

        assertThat(LombokSupport.usesLombok(tempDir)).isFalse();
    }

    @Test
    void usesLombok_excludesBuildDirectory() throws IOException {
        Path build = Files.createDirectories(tempDir.resolve("build").resolve("classes"));
        Files.writeString(
            build.resolve("Built.java"),
            "import lombok.Value;\npublic class Built {}");

        assertThat(LombokSupport.usesLombok(tempDir)).isFalse();
    }

    @Test
    void usesLombok_excludesDotGradleDirectory() throws IOException {
        Path gradle = Files.createDirectories(tempDir.resolve(".gradle"));
        Files.writeString(
            gradle.resolve("Cached.java"),
            "import lombok.Builder;\npublic class Cached {}");

        assertThat(LombokSupport.usesLombok(tempDir)).isFalse();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void usesLombok_continuesWhenSubdirectoryIsUnreadable() throws IOException {
        Path src = Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(src.resolve("Safe.java"), "public class Safe {}");
        Path restricted = Files.createDirectories(tempDir.resolve("restricted"));
        restricted.toFile().setExecutable(false);

        try {
            assertThat(LombokSupport.usesLombok(tempDir)).isFalse();
        } finally {
            restricted.toFile().setExecutable(true);
        }
    }

    @Test
    void findJar_returnsJarFromEnvVarWhenFileExists() throws IOException {
        Path jar = Files.createFile(tempDir.resolve("lombok-1.18.34.jar"));

        Optional<Path> result = LombokSupport.findJar(jar.toString(), tempDir.toString());

        assertThat(result).contains(jar);
    }

    @Test
    void findJar_returnsEmptyAndDoesNotSearchFurtherWhenEnvSetButFileMissing() {
        Optional<Path> result = LombokSupport.findJar("/nonexistent/lombok.jar", tempDir.toString());

        assertThat(result).isEmpty();
    }

    @Test
    void findJar_findsMavenJarWhenEnvNotSet() throws IOException {
        Path mavenDir = Files.createDirectories(
            tempDir.resolve(".m2/repository/org/projectlombok/lombok/1.18.30"));
        Path jar = Files.createFile(mavenDir.resolve("lombok-1.18.30.jar"));

        Optional<Path> result = LombokSupport.findJar(null, tempDir.toString());

        assertThat(result).contains(jar);
    }

    @Test
    void findJar_returnsEmptyWhenNothingFound() {
        Optional<Path> result = LombokSupport.findJar(null, tempDir.toString());

        assertThat(result).isEmpty();
    }

    @Test
    void findJar_prefersMavenOverGradle() throws IOException {
        Path mavenJar = Files.createFile(Files.createDirectories(
                tempDir.resolve(".m2/repository/org/projectlombok/lombok/1.18.30"))
            .resolve("lombok-1.18.30.jar"));
        Files.createFile(Files.createDirectories(
                tempDir.resolve(".gradle/caches/modules-2/files-2.1/org.projectlombok/lombok/1.18.32/abc123"))
            .resolve("lombok-1.18.32.jar"));

        Optional<Path> result = LombokSupport.findJar(null, tempDir.toString());

        assertThat(result).contains(mavenJar);
    }

    @Test
    void findJar_picksLatestSemanticVersionFromMaven() throws IOException {
        Files.createFile(Files.createDirectories(
                tempDir.resolve(".m2/repository/org/projectlombok/lombok/1.18.9"))
            .resolve("lombok-1.18.9.jar"));
        Path newer = Files.createFile(Files.createDirectories(
                tempDir.resolve(".m2/repository/org/projectlombok/lombok/1.18.30"))
            .resolve("lombok-1.18.30.jar"));

        Optional<Path> result = LombokSupport.findJar(null, tempDir.toString());

        assertThat(result).contains(newer);
    }

    @Test
    void parseVersion_parsesStandardVersion() {
        assertThat(LombokSupport.parseVersion("lombok-1.18.30.jar")).containsExactly(1, 18, 30);
    }

    @Test
    void parseVersion_treatsOlderVersionCorrectly() {
        List<Integer> old = LombokSupport.parseVersion("lombok-1.18.9.jar");
        List<Integer> newer = LombokSupport.parseVersion("lombok-1.18.30.jar");
        int cmp = 0;
        for (int i = 0; i < Math.max(old.size(), newer.size()); i++) {
            int a = i < old.size() ? old.get(i) : 0;
            int b = i < newer.size() ? newer.get(i) : 0;
            if (a != b) {
                cmp = Integer.compare(a, b);
                break;
            }
        }
        assertThat(cmp).isLessThan(0);
    }

    @Test
    void detectAndFind_returnsEmptyWhenWorkspaceHasNoLombok() throws IOException {
        Files.writeString(
            Files.createDirectories(tempDir.resolve("src")).resolve("Plain.java"),
            "public class Plain {}");

        Optional<Path> result = LombokSupport.detectAndFind(tempDir);

        assertThat(result).isEmpty();
    }
}
