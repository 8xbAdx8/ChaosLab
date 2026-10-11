package com.chaoslab.console.security;

import com.chaoslab.console.ConsoleReadOnlyFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/** Single trusted workspace, two fixed roles, session authentication; no execution permissions. */
@Configuration(proxyBeanMethods = false)
public class ConsoleSecurityConfiguration {

    public static final String SESSION_COOKIE = "CHAOSLAB_SESSION";

    public static boolean localHistory(Environment env) {
        return env.matchesProfiles("history-demo");
    }

    public static String path(HttpServletRequest request) {
        return request
            .getRequestURI()
            .substring(request.getContextPath().length());
    }

    private static boolean read(HttpServletRequest r) {
        return Set.of("GET", "HEAD").contains(r.getMethod());
    }

    private static boolean loopback(HttpServletRequest r) {
        return Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(
            r.getRemoteAddr()
        );
    }

    public static void safeFailure(
        HttpServletResponse response,
        int status,
        String code
    ) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"code\":\"" + code + "\"}");
    }

    @Bean
    PasswordEncoder consolePasswordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    InMemoryUserDetailsManager consoleUsers(Environment env) {
        var users = new ArrayList<UserDetails>();
        if (!localHistory(env)) {
            if (
                !env.getProperty(
                    "server.servlet.session.cookie.secure",
                    Boolean.class,
                    true
                ) &&
                !Set.of("127.0.0.1", "::1", "localhost").contains(
                    env.getProperty("server.address", "")
                )
            ) throw new IllegalStateException(
                "INSECURE_SESSION_REQUIRES_LOOPBACK"
            );
            for (String role : List.of("viewer", "admin")) {
                String name = env.getProperty(
                    "chaoslab.security." + role + ".username",
                    ""
                );
                String hash = env.getProperty(
                    "chaoslab.security." + role + ".password-hash",
                    ""
                );
                if (name.isEmpty() && hash.isEmpty()) continue;
                if (
                    !name.matches("[A-Za-z0-9._-]{1,64}") ||
                    !hash.matches(
                        "\\$2[aby]\\$(10|11|12|13|14)\\$[./A-Za-z0-9]{53}"
                    )
                ) throw new IllegalStateException(
                    "CONSOLE_CREDENTIAL_CONFIGURATION_INVALID"
                );
                if (
                    users
                        .stream()
                        .anyMatch(u -> u.getUsername().equalsIgnoreCase(name))
                ) throw new IllegalStateException(
                    "CONSOLE_CREDENTIAL_CONFIGURATION_INVALID"
                );
                users.add(
                    User.withUsername(name)
                        .password(hash)
                        .roles(role.toUpperCase(java.util.Locale.ROOT))
                        .build()
                );
            }
        }
        // Empty configuration denies every login. No generated/default password and no public bypass.
        return new InMemoryUserDetailsManager(users);
    }

    @Bean
    @Order(100)
    SecurityFilterChain consoleSecurity(HttpSecurity http, Environment env)
        throws Exception {
        boolean history = localHistory(env);
        http.httpBasic(b -> b.disable())
            .rememberMe(r -> r.disable())
            .requestCache(c -> c.disable())
            .sessionManagement(s ->
                s
                    .sessionCreationPolicy(
                        history
                            ? SessionCreationPolicy.STATELESS
                            : SessionCreationPolicy.IF_REQUIRED
                    )
                    .sessionFixation(f -> f.changeSessionId())
            )
            .exceptionHandling(e ->
                e
                    .authenticationEntryPoint((r, s, x) ->
                        safeFailure(s, 401, "AUTHENTICATION_REQUIRED")
                    )
                    .accessDeniedHandler((r, s, x) ->
                        safeFailure(s, 403, "ACCESS_DENIED")
                    )
            );
        if (history) {
            // Explicitly not a logged-in VIEWER/ADMIN. Only loopback immutable archive and identity.
            http.authorizeHttpRequests(a ->
                a
                    .requestMatchers(
                        r ->
                            loopback(r) &&
                            (read(r) || "OPTIONS".equals(r.getMethod())) &&
                            Set.of(
                                "/api/v1/auth/me",
                                "/api/v1/console/history/m1-core",
                                "/actuator/health"
                            ).contains(path(r))
                    )
                    .permitAll()
                    .anyRequest()
                    .denyAll()
            )
                .formLogin(f -> f.disable())
                .logout(l -> l.disable());
        } else {
            http.authorizeHttpRequests(a ->
                a
                    .requestMatchers(
                        r ->
                            read(r) &&
                            Set.of(
                                "/api/v1/auth/me",
                                "/api/v1/auth/csrf",
                                "/actuator/health"
                            ).contains(path(r))
                    )
                    .permitAll()
                    .requestMatchers(
                        r -> read(r) && "/api/v1/console/access".equals(path(r))
                    )
                    .hasRole("ADMIN")
                    .requestMatchers(
                        r ->
                            read(r) && ConsoleReadOnlyFilter.isReadPath(path(r))
                    )
                    .hasAnyRole("VIEWER", "ADMIN")
                    .requestMatchers(
                        r ->
                            "OPTIONS".equals(r.getMethod()) &&
                            ConsoleReadOnlyFilter.isReadPath(path(r))
                    )
                    .hasAnyRole("VIEWER", "ADMIN")
                    .anyRequest()
                    .denyAll()
            )
                .formLogin(f ->
                    f
                        .loginPage("/login")
                        .loginProcessingUrl("/api/v1/auth/login")
                        .successHandler((r, s, x) -> {
                            s.setContentType("application/json");
                            s.setHeader("Cache-Control", "no-store");
                            s.getWriter().write("{\"authenticated\":true}");
                        })
                        .failureHandler((r, s, x) ->
                            safeFailure(s, 401, "LOGIN_FAILED")
                        )
                )
                .logout(l ->
                    l
                        .logoutUrl("/api/v1/auth/logout")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies(SESSION_COOKIE)
                        .logoutSuccessHandler((r, s, x) -> s.setStatus(204))
                );
        }
        // Default HttpSession CSRF repository + XOR masking retained, including login/logout.
        return http.build();
    }
}
