package com.aihospital.triage.api;

import com.aihospital.shared.model.Models.SendMessage;
import com.aihospital.shared.model.Models.TriageResult;
import com.aihospital.shared.security.RoleGuard;
import com.aihospital.triage.application.TriageConversationService;
import com.aihospital.triage.domain.TriageRecords.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/triage")
public class TriageController {
    private static final Pattern NUMERIC_HTML_ENTITY = Pattern.compile("&#(x[0-9a-fA-F]+|\\d+);");
    private final TriageConversationService conversations;
    private final RoleGuard guard;
    public TriageController(TriageConversationService conversations, RoleGuard guard) {
        this.conversations = conversations; this.guard = guard;
    }
    @GetMapping("/sessions") public List<Session> sessions(@RequestHeader(value = "Authorization", required = false) String auth) {
        return conversations.sessions(guard.require(auth, "PATIENT").subject());
    }
    @PostMapping("/sessions") public Conversation create(@RequestBody Eligibility eligibility,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return conversations.create(guard.require(auth, "PATIENT").subject(), eligibility);
    }
    @GetMapping("/sessions/{id}") public Conversation conversation(@PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return conversations.conversation(id, guard.require(auth, "PATIENT").subject());
    }
    @PostMapping("/sessions/{id}/turns") public Conversation turn(@PathVariable String id, @RequestBody SendMessage body,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return conversations.send(id, guard.require(auth, "PATIENT").subject(), decodeNumericHtmlEntities(body.content()));
    }
    @GetMapping("/timeline") public List<TimelineEvent> timeline(@RequestHeader(value = "Authorization", required = false) String auth) {
        return conversations.timeline(guard.require(auth, "PATIENT").subject());
    }
    @GetMapping("/sessions/{id}/result") public TriageResult result(@PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return conversations.latestResult(id, guard.require(auth, "PATIENT").subject());
    }
    @PostMapping("/sessions/{id}/human-review") public HumanReview humanReview(@PathVariable String id,
            @RequestBody(required = false) HumanReviewRequest body,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return conversations.requestHumanReview(id, guard.require(auth, "PATIENT").subject(),
                body == null ? "" : body.reason());
    }
    private record HumanReviewRequest(String reason) {}
    private String decodeNumericHtmlEntities(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        Matcher matcher = NUMERIC_HTML_ENTITY.matcher(raw);
        StringBuffer decoded = new StringBuffer();
        while (matcher.find()) {
            String token = matcher.group(1);
            String replacement = matcher.group();
            try {
                boolean hex = token.startsWith("x") || token.startsWith("X");
                int codePoint = Integer.parseInt(hex ? token.substring(1) : token, hex ? 16 : 10);
                if (Character.isValidCodePoint(codePoint)) replacement = new String(Character.toChars(codePoint));
            } catch (NumberFormatException ignored) { }
            matcher.appendReplacement(decoded, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(decoded);
        return decoded.toString();
    }
}
