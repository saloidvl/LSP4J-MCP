package com.saloidvl.lsp4jmcp.client;

import java.util.List;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.ConfigurationItem;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

final class LspSummarizer {

    private LspSummarizer() {
    }

    static String configurationItems(List<ConfigurationItem> items) {
        return items.stream()
            .map(item -> "section=" + item.getSection() + ", scope=" + item.getScopeUri())
            .collect(Collectors.joining("; "));
    }

    static String diagnostics(PublishDiagnosticsParams params, int limit) {
        String entries = params.getDiagnostics().stream()
            .limit(limit)
            .map(LspSummarizer::diagnostic)
            .collect(Collectors.joining("; "));
        String suffix = params.getDiagnostics().size() > limit
            ? "; ...+" + (params.getDiagnostics().size() - limit) + " more"
            : "";
        return "uri=" + params.getUri()
               + ", count=" + params.getDiagnostics().size()
               + (entries.isEmpty() ? "" : ", entries=[" + entries + "]")
               + suffix;
    }

    static String commandArguments(List<Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "[]";
        }
        return arguments.stream()
            .map(LspSummarizer::commandValue)
            .toList()
            .toString();
    }

    static String commandValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Command command) {
            return "Command{title=" + command.getTitle() + ", command=" + command.getCommand() + "}";
        }
        String text = String.valueOf(value);
        return text.length() > 500 ? text.substring(0, 500) + "...<truncated>" : text;
    }

    private static String diagnostic(Diagnostic d) {
        int line = d.getRange() != null ? d.getRange().getStart().getLine() + 1 : -1;
        int column = d.getRange() != null ? d.getRange().getStart().getCharacter() + 1 : -1;
        return "severity=" + d.getSeverity()
               + ", code=" + diagnosticCode(d.getCode())
               + ", line=" + line
               + ", column=" + column
               + ", message=" + d.getMessage();
    }

    private static String diagnosticCode(Either<String, Integer> code) {
        if (code == null) {
            return "null";
        }
        return code.isLeft() ? String.valueOf(code.getLeft()) : String.valueOf(code.getRight());
    }
}
