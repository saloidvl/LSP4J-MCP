package com.saloidvl.lsp4jmcp.client;

import java.io.Writer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class DebugLogWriter extends Writer {
    private static final Logger LOG = LoggerFactory.getLogger(DebugLogWriter.class);

    private final String prefix;
    private final StringBuilder buffer = new StringBuilder();

    DebugLogWriter(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public void write(char[] cbuf, int off, int len) {
        for (int i = off; i < off + len; i++) {
            char ch = cbuf[i];
            if (ch == '\n') {
                flushBuffer();
            } else if (ch != '\r') {
                buffer.append(ch);
            }
        }
    }

    @Override
    public void flush() {
        flushBuffer();
    }

    @Override
    public void close() {
        flushBuffer();
    }

    private void flushBuffer() {
        if (buffer.isEmpty()) {
            return;
        }
        LOG.debug("{}: {}", prefix, buffer);
        buffer.setLength(0);
    }
}
