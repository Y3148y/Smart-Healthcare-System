package com.aihospital.shared.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.view.xslt.XsltView;
import static org.junit.jupiter.api.Assertions.*;

/** Current feature-use observations only; does not suppress Spring version advisories. */
@SpringBootTest
class ServerRenderingBoundaryTest {
    @Autowired WebApplicationContext context;
    @Autowired RequestMappingHandlerMapping mappings;

    @Test void noXsltViewsOrFragmentRenderingControllerResultsAreRegistered() {
        assertTrue(context.getBeansOfType(XsltView.class).isEmpty());
        for (var entry : mappings.getHandlerMethods().entrySet()) {
            var handler = entry.getValue();
            if (!handler.getBeanType().getName().startsWith("com.aihospital.")) continue;
            var type = handler.getMethod().getReturnType();
            assertFalse(ModelAndView.class.isAssignableFrom(type));
            assertNotEquals("org.springframework.web.servlet.view.FragmentsRendering", type.getName());
            var paths = entry.getKey().getPathPatternsCondition();
            if (paths != null) for (var path : paths.getPatterns()) {
                assertNotEquals("/**", path.getPatternString(), "catch-all view mappings are not allowed");
                assertNotEquals("/{*path}", path.getPatternString(), "catch-all view mappings are not allowed");
            }
        }
    }
    @Test void stageSseUsesTheExplicitJsonConversationControllerNotAViewFragmentHandler() {
        var streams = mappings.getHandlerMethods().values().stream()
                .filter(handler -> SseEmitter.class.isAssignableFrom(handler.getMethod().getReturnType())).toList();
        assertEquals(1, streams.size());
        assertEquals(com.aihospital.triage.api.TriageStreamController.class, streams.get(0).getBeanType());
    }
}
