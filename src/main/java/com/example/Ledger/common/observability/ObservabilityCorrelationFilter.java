package com.example.Ledger.common.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ObservabilityCorrelationFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_ORGANIZATION_ID = "organizationId";
    public static final String MDC_USER_ID = "userId";

    private static final Pattern SAFE_CORRELATION_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String traceId = resolveTraceId(request);
        MDC.put(MDC_TRACE_ID, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_ID);
            MDC.remove(MDC_ORGANIZATION_ID);
            MDC.remove(MDC_USER_ID);
        }
    }

    private String resolveTraceId(HttpServletRequest request) {
        String candidate = request.getHeader(TRACE_ID_HEADER);
        if (!StringUtils.hasText(candidate)) {
            candidate = request.getHeader(CORRELATION_ID_HEADER);
        }
        if (StringUtils.hasText(candidate)) {
            String trimmed = candidate.trim();
            if (SAFE_CORRELATION_ID.matcher(trimmed).matches()) {
                return trimmed;
            }
        }
        return UUID.randomUUID().toString();
    }
}
