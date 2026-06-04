package com.saloidvl.lsp4jmcp.testsupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class MockitoCompatibilityTest {

    @Mock
    private List<String> values;

    @Test
    void mockitoExtension_canCreateMocks() {
        assertThat(values).isNotNull();
    }
}
