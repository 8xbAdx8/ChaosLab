package com.chaoslab.console;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Set;
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

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain
    ) throws ServletException, IOException {
        if (!Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())) {
            response.setStatus(403);
            response.setContentType("application/json");
            response
                .getWriter()
                .write(
                    "{\"code\":\"CONSOLE_READ_ONLY\",\"message\":\"Writes disabled in console mode\"}"
                );
            return;
        }
        chain.doFilter(request, response);
    }
}
