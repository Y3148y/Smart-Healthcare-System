package com.aihospital.shared.diagnostics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Local, one-shot consent for a named synthetic session. No HTTP activation or patient-facing output. */
public final class SyntheticReviewDiagnostics implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Map<String,SyntheticReviewDiagnostics> ACTIVE = new ConcurrentHashMap<>();
    private static final SyntheticReviewDiagnostics NONE = new SyntheticReviewDiagnostics(null,null,null,null,null);
    private final Path root;
    private final String userMessageId, sessionId, requestId, traceId;
    private boolean written;
    private SyntheticReviewDiagnostics(Path root,String userMessageId,String sessionId,String requestId,String traceId) {
        this.root=root;this.userMessageId=userMessageId;this.sessionId=sessionId;this.requestId=requestId;this.traceId=traceId;
    }
    private static Path directory() {
        Path project=Path.of("").toAbsolutePath().normalize();
        if(project.getFileName()!=null&&project.getFileName().toString().equals("backend"))project=project.getParent();
        if(!Files.isRegularFile(project.resolve("backend/pom.xml")))return null;
        return project.resolve("reports/rag/synthetic-review-diagnostic");
    }
    public static SyntheticReviewDiagnostics open(String sessionId,String patient,String requestId,
            String userMessageId,Supplier<List<String>> patientStatements) {
        return open(directory(),sessionId,patient,requestId,userMessageId,patientStatements,Instant.now());
    }
    static SyntheticReviewDiagnostics open(Path root,String sessionId,String patient,String requestId,
            String userMessageId,Supplier<List<String>> patientStatements,Instant now) {
        try {
            if(root==null||!safeDirectory(root)||!"lisi".equals(patient)||!uuid(sessionId)||!uuid(requestId)||!uuid(userMessageId))return NONE;
            Path permit=root.resolve("permit.json");
            if(Files.isSymbolicLink(root)||Files.isSymbolicLink(permit)||!Files.isRegularFile(permit,LinkOption.NOFOLLOW_LINKS)
                    ||Files.size(permit)>8192)return NONE;
            byte[] permitBytes;
            try(var input=Files.newInputStream(permit,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
                permitBytes=input.readNBytes(8193);
            }
            if(permitBytes.length>8192)return NONE;
            var p=JSON.readTree(permitBytes);
            if(p==null||!p.isObject()||p.size()!=6||!p.path("schemaVersion").isInt()||p.path("schemaVersion").asInt()!=1
                    ||!p.path("syntheticOnly").isBoolean()||!p.path("syntheticOnly").asBoolean()
                    ||!sessionId.equals(p.path("sessionId").asText())||!p.path("turns").isArray()||p.path("turns").size()!=2)return NONE;
            Instant expiry=Instant.parse(p.path("expiresAt").asText());
            Instant issued=Instant.parse(p.path("issuedAt").asText());
            if(issued.isAfter(now)||!expiry.isAfter(now)||!expiry.isAfter(issued)||expiry.isAfter(issued.plusSeconds(3600)))return NONE;
            var ids=new HashSet<String>();JsonNode selected=null;int position=0;
            for(var turn:p.path("turns")) {
                position++;
                if(!turn.isObject()||turn.size()!=2||!uuid(turn.path("requestId").asText())
                        ||!ids.add(turn.path("requestId").asText())||!turn.path("patientHashes").isArray()
                        ||turn.path("patientHashes").size()!=position)return NONE;
                for(var hash:turn.path("patientHashes"))if(!hash.isTextual()||!hash.asText().matches("[0-9a-f]{64}"))return NONE;
                if(requestId.equals(turn.path("requestId").asText()))selected=turn;
            }
            if(selected==null)return NONE;
            List<String> statements=patientStatements.get();
            if(statements==null||statements.size()!=selected.path("patientHashes").size())return NONE;
            for(int i=0;i<statements.size();i++)if(statements.get(i)==null||statements.get(i).length()>2000
                    ||!sha256(statements.get(i)).equals(selected.path("patientHashes").get(i).asText()))return NONE;
            var metadata=TurnTraceContext.metadata();
            if(metadata==null||!userMessageId.equals(metadata.userMessageId()))return NONE;
            // Claim before generation. Failed calls also consume their permit; no repeated sampling.
            Files.writeString(root.resolve(requestId+".claimed"),"SYNTHETIC_ONLY\n",StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            var scope=new SyntheticReviewDiagnostics(root,userMessageId,sessionId,requestId,metadata.traceId());
            return ACTIVE.putIfAbsent(userMessageId,scope)==null?scope:NONE;
        } catch(Exception unavailable) {return NONE;}
    }
    public static boolean enabled() {return current()!=null;}
    private static SyntheticReviewDiagnostics current() {
        var metadata=TurnTraceContext.metadata();
        if(metadata==null)return null;
        var scope=ACTIVE.get(metadata.userMessageId());
        return scope!=null&&scope.traceId.equals(metadata.traceId())?scope:null;
    }
    public static void record(JsonNode draft,JsonNode references,JsonNode decision,JsonNode outcome) {
        if(outcome==null||!Set.of("REJECTED","UNAVAILABLE").contains(outcome.path("status").asText()))return;
        var scope=current();if(scope==null)return;
        scope.write(draft,references,decision,outcome);
    }
    private synchronized void write(JsonNode draft,JsonNode references,JsonNode decision,JsonNode outcome) {
        if(written||ACTIVE.get(userMessageId)!=this)return;
        written=true;
        try {
            var packet=JSON.createObjectNode();packet.put("schemaVersion",1);packet.put("kind","REJECTED_SYNTHETIC_DRAFT_DIAGNOSTIC");
            packet.put("sessionId",sessionId);packet.put("requestId",requestId);packet.put("traceId",traceId);
            packet.set("draft",draft);packet.set("references",references);packet.set("decision",decision);packet.set("outcome",outcome);
            packet.putArray("finalAdoptedReferenceIds");
            byte[] bytes=JSON.writeValueAsBytes(packet);
            if(bytes.length>65536||!safeDirectory(root))return;
            Files.write(root.resolve(requestId+".json"),bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
        } catch(Exception unavailable) { /* Diagnostic I/O must never alter the patient result. */ }
    }
    public static String sha256(String text) {
        try {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static boolean uuid(String value) {return value!=null&&value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");}
    private static boolean safeDirectory(Path root) throws java.io.IOException {
        Path absolute=root.toAbsolutePath().normalize();
        for(Path current=absolute;current!=null;current=current.getParent())if(Files.isSymbolicLink(current))return false;
        return Files.isDirectory(absolute,LinkOption.NOFOLLOW_LINKS)&&absolute.equals(absolute.toRealPath());
    }
    @Override public void close() {if(userMessageId!=null)ACTIVE.remove(userMessageId,this);}
}
