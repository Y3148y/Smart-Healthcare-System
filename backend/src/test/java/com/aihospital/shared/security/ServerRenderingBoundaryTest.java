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
        for (var handler : mappings.getHandlerMethods().values()) {
            if (!handler.getBeanType().getName().startsWith("com.aihospital.")) continue;
            var type = handler.getMethod().getReturnType();
            assertFalse(ModelAndView.class.isAssignableFrom(type));
            assertNotEquals("org.springframework.web.servlet.view.FragmentsRendering", type.getName());
        }
    }
    @Test void stageSseUsesTheExplicitJsonConversationControllerNotAViewFragmentHandler() {
        var streams = mappings.getHandlerMethods().values().stream()
                .filter(handler -> SseEmitter.class.isAssignableFrom(handler.getMethod().getReturnType())).toList();
        assertEquals(1, streams.size());
        assertEquals(com.aihospital.triage.api.TriageStreamController.class, streams.get(0).getBeanType());
    }
}
