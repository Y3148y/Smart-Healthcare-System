package com.aihospital.triage.infrastructure.llm;

import com.aihospital.shared.security.ApiCredentialCheck;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class ApiCredentialCheckTest {
    @Test void controlCharactersCannotMasqueradeAsConfiguredKeys() {
        assertFalse(ApiCredentialCheck.usable(null));
        for (String input : new String[]{"", " ", "\u0016", "sk-test\n", "sk-test ", "中文"})
            assertFalse(ApiCredentialCheck.usable(input));
        assertTrue(ApiCredentialCheck.usable("synthetic-test-credential"));
    }
    @Test void explicitLiveModeRejectsInvalidCredentialWithoutEchoingIt() {
        var model = new OptionalNarrationModel();
        ReflectionTestUtils.setField(model, "mode", "openai-compatible");
        ReflectionTestUtils.setField(model, "apiKey", "\u0016");
        ReflectionTestUtils.setField(model, "model", "synthetic-model");
        assertFalse(model.runtimeStatus().configured());
        var error = assertThrows(IllegalStateException.class, model::validateCredential);
        assertFalse(error.getMessage().contains("\u0016"));
    }
    @Test void demoDoesNotRequireExternalCredentials() {
        var model = new OptionalNarrationModel();
        ReflectionTestUtils.setField(model, "mode", "demo");
        assertDoesNotThrow(model::validateCredential);
    }
}
