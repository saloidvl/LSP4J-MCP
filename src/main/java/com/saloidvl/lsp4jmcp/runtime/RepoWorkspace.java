package com.saloidvl.lsp4jmcp.runtime;

import java.io.IOException;
import java.nio.file.Path;

public record RepoWorkspace(Path canonicalPath, String repoId) {

    public static RepoWorkspace fromPath(Path path) throws IOException {
        Path canonical = path.toRealPath();
        String repoId = "%08x".formatted(canonical.toString().hashCode());
        return new RepoWorkspace(canonical, repoId);
    }
}
