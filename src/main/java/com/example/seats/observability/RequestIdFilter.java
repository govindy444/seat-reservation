package com.example.seats.observability;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs before Spring Security so even 401s carry a request id. Accepts a caller-supplied X-Request-Id
 * (so a client or the burst script can correlate) if it looks sane, otherwise generates one.
 * Emits one access-log line per request; actuator traffic is skipped to keep the log about real work.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "request_id";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Logger access = LoggerFactory.getLogger("access");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String incoming = req.getHeader(HEADER);
        String requestId = incoming != null && SAFE.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        MDC.put(MDC_KEY, requestId);
        res.setHeader(HEADER, requestId);
        long start = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            if (!req.getRequestURI().startsWith("/actuator")) {
                access.atInfo().setMessage("request")
                        .addKeyValue("method", req.getMethod())
                        .addKeyValue("path", req.getRequestURI())
                        .addKeyValue("status", res.getStatus())
                        .addKeyValue("duration_ms", (System.nanoTime() - start) / 1_000_000)
                        .log();
            }
            MDC.remove(MDC_KEY);
        }
    }
}
