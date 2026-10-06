package com.aihospital.shared.security;

/** Credentials must be usable in an HTTP header; never include their value in diagnostics. */
public final class ApiCredentialCheck {
    private ApiCredentialCheck() {}
    public static boolean usable(String value) {
        return value != null && !value.isEmpty() && value.chars().allMatch(c -> c >= 33 && c <= 126);
    }
}
