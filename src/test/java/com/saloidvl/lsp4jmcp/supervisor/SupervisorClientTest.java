package com.saloidvl.lsp4jmcp.supervisor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SupervisorClientTest {

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void detachedCommand_runsCommandAsLeaderOfNewProcessGroup() throws Exception {
        Process process = new ProcessBuilder(SupervisorClient.detachedCommand(
                List.of("/bin/sh", "-c", "echo started; exec sleep 30")))
            .redirectErrorStream(true)
            .start();
        try {
            BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream()));
            assertThat(output.readLine()).isEqualTo("started");

            long childGroup = processGroup(process.pid());
            long ownGroup = processGroup(ProcessHandle.current().pid());

            assertThat(childGroup).isEqualTo(process.pid());
            assertThat(childGroup).isNotEqualTo(ownGroup);
        } finally {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private static long processGroup(long pid) throws Exception {
        Process ps = new ProcessBuilder("ps", "-o", "pgid=", "-p", Long.toString(pid)).start();
        String value = new String(ps.getInputStream().readAllBytes()).trim();
        assertThat(ps.waitFor(5, TimeUnit.SECONDS)).isTrue();
        return Long.parseLong(value);
    }
}
