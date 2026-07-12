package com.coffeeorder.domain.idempotency.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record CanonicalPayload(String json) {

    public CanonicalPayload {
        json = CanonicalJson.normalize(json);
    }

    public static CanonicalPayload fromJson(String json) {
        return new CanonicalPayload(json);
    }

    public String sha256() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
