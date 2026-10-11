package com.chaoslab.console;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Opt-in server boundary for the console profile; UI visibility is not authorization. */
@Component
@ConditionalOnProperty(
    name = "chaoslab.console.read-only",
    havingValue = "true"
)
public class ConsoleReadOnlyFilter extends OncePerRequestFilter {

    private static final Set<String> READ_PATHS = Set.of(
        "/api/v1/console/overview",
        "/api/v1/console/experiments",
        "/api/v1/console/executions",
        "/api/v1/console/audit-logs",
        "/api/v1/console/reports",
        "/api/v1/console/history/m1-core",
        "/api/v1/targets",
        "/api/v1/scenarios",
        "/actuator/health"
    );
    private static final String UUID_PATH =
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern DETAIL_PATH = Pattern.compile(
        "/api/v1/console/(experiments|executions|reports)/" + UUID_PATH
    );
    private static final Pattern EVIDENCE_PATH = Pattern.compile(
        "/api/v1/console/executions/" + UUID_PATH + "/evidence"
    );

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain
    ) throws ServletException, IOException {
        // Match the raw URI relative to the servlet context, not a normalized prefix.
        // Encodings, matrix parameters, duplicate separators and extra suffixes fail closed.
        String path = request
            .getRequestURI()
            .substring(request.getContextPath().length());
        boolean allowedPath =
            READ_PATHS.contains(path) ||
            DETAIL_PATH.matcher(path).matches() ||
            EVIDENCE_PATH.matcher(path).matches();
        if (!allowedPath) {
            deny(response);
            return;
        }
        if ("OPTIONS".equals(request.getMethod())) {
            // Same-origin UI needs no CORS. Do not let MVC OPTIONS advertise mutation
            // methods on mixed read/write controllers, or implicitly enable cross-origin reads.
            if (
                request.getHeader("Origin") != null ||
                request.getHeader("Access-Control-Request-Method") != null ||
                request.getHeader("Access-Control-Request-Headers") != null
            ) {
                deny(response);
                return;
            }
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            response.setHeader("Allow", "GET, HEAD, OPTIONS");
            return;
        }
        if (!Set.of("GET", "HEAD").contains(request.getMethod())) {
            deny(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private static void deny(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response
            .getWriter()
            .write(
                "{\"code\":\"CONSOLE_READ_ONLY\",\"message\":\"Request not allowed in console mode\"}"
            );
    }
}
