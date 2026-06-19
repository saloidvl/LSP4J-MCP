package com.saloidvl.lsp4jmcp.client;

import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.LanguageServer;

public interface JdtlsLanguageServer extends LanguageServer {
    @JsonRequest("java/buildWorkspace")
    CompletableFuture<BuildWorkspaceStatus> buildWorkspace(Either<Boolean, boolean[]> forceReBuild);
}
