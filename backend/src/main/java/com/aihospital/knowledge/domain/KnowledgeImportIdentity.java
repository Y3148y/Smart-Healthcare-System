package com.aihospital.knowledge.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stable client key and server-side binding for structured imports. */
public final class KnowledgeImportIdentity {
    private KnowledgeImportIdentity() {}

    public static String key(String title, String body) {
        return "sha256:" + sha256(String.valueOf(title) + "\0" + String.valueOf(body));
    }

    public static String requestHash(String title, String body, KnowledgeMetadata metadata, ObjectMapper json) {
        try {
            return sha256(String.valueOf(title) + "\0" + String.valueOf(body) + "\0"
                    + json.writeValueAsString(metadata));
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("知识资料元数据无法规范化", invalid);
        }
    }

    public static boolean validKey(String key, String title, String body) {
        return key != null && key.matches("sha256:[a-f0-9]{64}") && key.equals(key(title, body));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
