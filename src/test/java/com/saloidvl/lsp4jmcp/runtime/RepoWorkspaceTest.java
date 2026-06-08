package com.saloidvl.lsp4jmcp.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RepoWorkspaceTest {

    @Test
    void fromPath_normalizesRealPathAndKeepsStableRepoId(@TempDir Path tempDir) throws IOException {
        Path repo = Files.createDirectories(tempDir.resolve("repo"));

        RepoWorkspace workspace = RepoWorkspace.fromPath(repo);

        assertThat(workspace.canonicalPath()).isEqualTo(repo.toRealPath());
        assertThat(workspace.repoId()).matches("[0-9a-f]{8,}");
    }

    @Test
    void fromPath_returnsSameRepoIdForTheSameRealPath(@TempDir Path tempDir) throws IOException {
        Path repo = Files.createDirectories(tempDir.resolve("repo"));

        RepoWorkspace left = RepoWorkspace.fromPath(repo);
        RepoWorkspace right = RepoWorkspace.fromPath(repo.toRealPath());

        assertThat(left.repoId()).isEqualTo(right.repoId());
        assertThat(left.canonicalPath()).isEqualTo(right.canonicalPath());
    }
}
