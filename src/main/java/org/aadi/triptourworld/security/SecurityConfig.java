package org.aadi.triptourworld.security;

import org.aadi.triptourworld.config.Settings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless API security: API-key auth, rate limiting, strict CORS (same-origin unless ALLOWED_ORIGINS is set),
 * Content-Security-Policy and Spring Security's default hardening headers (nosniff, frame deny, HSTS on HTTPS).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, Settings settings) throws Exception {
        // Not registered as beans on purpose: Spring Boot would otherwise add them to the servlet chain a second time.
        RateLimitFilter rateLimit = new RateLimitFilter(settings.rateLimitPerMinute());
        ApiKeyAuthFilter apiKey = new ApiKeyAuthFilter(settings.appApiKey());

        http.csrf(AbstractHttpConfigurer::disable) // stateless, header-based auth: no cookies, so no CSRF surface
                .cors(c -> c.configurationSource(corsSource(settings)))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h.contentSecurityPolicy(csp -> csp.policyDirectives(CSP)))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/health").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .addFilterBefore(rateLimit, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(apiKey, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static CorsConfigurationSource corsSource(Settings settings) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (!settings.allowedOrigins().isEmpty()) {
            CorsConfiguration cfg = new CorsConfiguration();
            cfg.setAllowedOrigins(settings.allowedOrigins());
            cfg.setAllowedMethods(java.util.List.of("GET", "POST", "OPTIONS"));
            cfg.setAllowedHeaders(java.util.List.of("Content-Type", "X-API-Key", "Authorization"));
            cfg.setMaxAge(3600L);
            source.registerCorsConfiguration("/api/**", cfg);
        }
        return source;
    }
}
