package com.coffeeorder.domain.idempotency.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 키 순서가 달라도 같은 JSON 요청으로 취급하기 위한 정규화된 멱등성 입력값.
 *
 * <p>객체 필드는 재귀적으로 정렬하지만 배열 순서는 보존한다. 따라서 배열의 순서 차이는 서로 다른 요청으로 간주한다.
 */
public record CanonicalPayload(String json) {

    public CanonicalPayload {
        json = CanonicalJson.normalize(json);
    }

    public static CanonicalPayload fromJson(String json) {
        return new CanonicalPayload(json);
    }

    /** DB에 저장할 64자리 SHA-256 요청 해시를 계산한다. */
    public String sha256() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
