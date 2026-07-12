package com.coffeeorder.global.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private static final Logger log = LoggerFactory.getLogger(TraceIdFilter.class);
    private static final String TRACE_ID_MDC_KEY = "traceId";

    private final OperationalMetrics metrics;

    public TraceIdFilter(ObjectProvider<OperationalMetrics> metrics) {
        this.metrics = metrics.getIfAvailable(OperationalMetrics::fallback);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = UUID.randomUUID().toString();
        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        MDC.put(TRACE_ID_MDC_KEY, traceId);
        long startedAt = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            String endpoint =
                    RequestObservability.attribute(
                            request, HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "unmatched");
            String resultCode =
                    RequestObservability.attribute(
                            request,
                            RequestObservability.RESULT_CODE_ATTRIBUTE,
                            response.getStatus() < 400 ? "SUCCESS" : "UNKNOWN_ERROR");
            String error = Boolean.toString(response.getStatus() >= 400);
            metrics.increment(
                    "coffee.api.requests",
                    "method",
                    request.getMethod(),
                    "endpoint",
                    endpoint,
                    "result_code",
                    resultCode,
                    "error",
                    error);
            metrics.record(
                    "coffee.api.request.duration",
                    Duration.ofNanos(System.nanoTime() - startedAt),
                    "method",
                    request.getMethod(),
                    "endpoint",
                    endpoint,
                    "result_code",
                    resultCode);
            log.info(
                    "request_completed method={} endpoint={} status={} resultCode={} traceId={} userId={} operation={}",
                    request.getMethod(),
                    endpoint,
                    response.getStatus(),
                    resultCode,
                    traceId,
                    RequestObservability.attribute(
                            request, RequestObservability.USER_ID_ATTRIBUTE, "none"),
                    RequestObservability.attribute(
                            request, RequestObservability.OPERATION_ATTRIBUTE, "none"));
            MDC.remove(TRACE_ID_MDC_KEY);
        }
    }

    public static String getTraceId(HttpServletRequest request) {
        Object traceId = request.getAttribute(TRACE_ID_ATTRIBUTE);
        return traceId == null ? "unknown" : traceId.toString();
    }

    public static String currentTraceId() {
        String traceId = MDC.get(TRACE_ID_MDC_KEY);
        return traceId == null ? "unknown" : traceId;
    }
}
