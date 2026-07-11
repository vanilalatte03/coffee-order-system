package com.coffeeorder.global.observability;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
