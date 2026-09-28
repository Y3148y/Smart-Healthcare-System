package com.aihospital.shared.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small dependency-free HS256 JWT issuer for the demo accounts. */
public class JwtService {
    private static final String SECRET = "ai-hospital-demo-signing-key-change-before-production";
    private static final Pattern SUBJECT = Pattern.compile("\\\"sub\\\":\\\"([^\\\"]+)\\\"");
    private static final Pattern ROLE = Pattern.compile("\\\"role\\\":\\\"([^\\\"]+)\\\"");
    private static final Pattern EXPIRES_AT = Pattern.compile("\\\"exp\\\":(\\d+)");

    public record Claims(String subject, String role, long expiresAt) {}

    public String issue(String user, String role) {
        String header = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = b64("{\"sub\":\"" + user + "\",\"role\":\"" + role + "\",\"exp\":" + (Instant.now().getEpochSecond()+28800) + "}");
        return header + "." + payload + "." + sign(header + "." + payload);
    }

    public Claims verifyBearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) throw new IllegalArgumentException("缺少认证令牌");
        String token = authorization.substring(7).trim();
        String[] parts = token.split("\\.");
        if (parts.length != 3) throw new IllegalArgumentException("认证令牌格式错误");
        byte[] expected = sign(parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = parts[2].getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, actual)) throw new IllegalArgumentException("认证令牌签名无效");
        try {
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            String subject = capture(SUBJECT, payload);
            String role = capture(ROLE, payload);
            long expiresAt = Long.parseLong(capture(EXPIRES_AT, payload));
            if (expiresAt <= Instant.now().getEpochSecond()) throw new IllegalArgumentException("认证令牌已过期");
            return new Claims(subject, role, expiresAt);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("认证令牌内容无效", ex);
        }
    }

    private String capture(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        if (!matcher.find()) throw new IllegalArgumentException("认证令牌内容无效");
        return matcher.group(1);
    }
    private String sign(String content) { try { Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256")); return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); } }
    private String b64(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
}
