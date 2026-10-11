package com.chaoslab.console.security;

import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ConsoleIdentityController {

    private final Environment env;

    public ConsoleIdentityController(Environment env) {
        this.env = env;
    }

    public record Identity(
        String mode,
        boolean authenticated,
        String username,
        List<String> roles,
        boolean readOnly,
        boolean realExecutionAllowed
    ) {}

    public record Csrf(String headerName, String token) {}

    public record Access(
        List<String> capabilities,
        boolean realExecutionAllowed
    ) {}

    @GetMapping("/api/v1/auth/me")
    public ResponseEntity<Identity> me(Authentication auth) {
        boolean history = ConsoleSecurityConfiguration.localHistory(env);
        boolean authenticated =
            !history &&
            auth != null &&
            auth.isAuthenticated() &&
            !(auth instanceof AnonymousAuthenticationToken);
        List<String> roles = authenticated
            ? auth
                  .getAuthorities()
                  .stream()
                  .map(a -> a.getAuthority())
                  .filter(
                      r -> r.equals("ROLE_VIEWER") || r.equals("ROLE_ADMIN")
                  )
                  .map(r -> r.substring(5))
                  .sorted()
                  .toList()
            : List.of();
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(
                new Identity(
                    history ? "LOCAL_HISTORY" : "SESSION",
                    authenticated,
                    authenticated ? auth.getName() : null,
                    roles,
                    true,
                    false
                )
            );
    }

    @GetMapping("/api/v1/auth/csrf")
    public ResponseEntity<Csrf> csrf(CsrfToken token) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(new Csrf(token.getHeaderName(), token.getToken()));
    }

    @GetMapping("/api/v1/console/access")
    public Access access() {
        return new Access(
            List.of("CONSOLE_READ", "ADMIN_PERMISSION_READ"),
            false
        );
    }
}
