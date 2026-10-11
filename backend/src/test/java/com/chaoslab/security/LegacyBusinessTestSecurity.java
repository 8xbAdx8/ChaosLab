package com.chaoslab.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** TEST CLASSPATH ONLY. Isolates existing business/Fake API tests from the new auth contract.
 * Auth tests explicitly select auth-test, never this profile. Cannot ship in a production JAR. */
@Configuration(proxyBeanMethods = false)
@Profile("legacy-api-test")
public class LegacyBusinessTestSecurity {

    @Bean
    @Order(-100)
    SecurityFilterChain legacyBusinessFixtureChain(HttpSecurity http)
        throws Exception {
        return http
            .securityMatcher("/api/**", "/actuator/**", "/error", "/")
            .csrf(c -> c.disable())
            .authorizeHttpRequests(a -> a.anyRequest().permitAll())
            .build();
    }
}
