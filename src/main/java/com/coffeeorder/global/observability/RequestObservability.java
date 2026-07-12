package com.coffeeorder.global.observability;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Controller와 예외 처리기가 request attribute로 공통 관측 컨텍스트를 전달하는 도우미.
 *
 * <p>오류 응답 본문에서는 안정 오류 코드만 추출해 Filter의 요청 로그와 메트릭 tag에 사용한다.
 */
public final class RequestObservability {

    static final String RESULT_CODE_ATTRIBUTE =
            RequestObservability.class.getName() + ".resultCode";
    static final String USER_ID_ATTRIBUTE = RequestObservability.class.getName() + ".userId";
    static final String OPERATION_ATTRIBUTE = RequestObservability.class.getName() + ".operation";

    private static final Pattern ERROR_CODE_PATTERN =
            Pattern.compile("\\\"code\\\"\\s*:\\s*\\\"([A-Z0-9_]+)\\\"");

    private RequestObservability() {}

    public static void operation(HttpServletRequest request, String operation) {
        request.setAttribute(OPERATION_ATTRIBUTE, operation);
    }

    public static void user(HttpServletRequest request, long userId) {
        request.setAttribute(USER_ID_ATTRIBUTE, Long.toString(userId));
    }

    public static void result(HttpServletRequest request, String resultCode) {
        request.setAttribute(RESULT_CODE_ATTRIBUTE, resultCode);
    }

    public static void resultFromResponse(HttpServletRequest request, int status, String body) {
        result(request, resultCode(status, body));
    }

    /** 성공은 공통 성공 코드로, 오류는 JSON의 안정 오류 코드로 정규화한다. */
    public static String resultCode(int status, String body) {
        if (status < 400) {
            return "SUCCESS";
        }
        Matcher matcher = ERROR_CODE_PATTERN.matcher(body == null ? "" : body);
        return matcher.find() ? matcher.group(1) : "UNKNOWN_ERROR";
    }

    static String attribute(HttpServletRequest request, String name, String fallback) {
        Object value = request.getAttribute(name);
        return value == null ? fallback : value.toString();
    }
}
