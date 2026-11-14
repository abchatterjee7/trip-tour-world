package org.aadi.triptourworld.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Header-based API key auth for /api/** ({@code X-API-Key: ...} or {@code Authorization: Bearer ...}).
 * When APP_API_KEY is empty the API is open (local dev) and every caller is treated as "dev".
 */
public final class ApiKeyAuthFilter extends OncePerRequestFilter {
    private final byte[] expected;

    public ApiKeyAuthFilter(String apiKey) {
        this.expected = apiKey == null || apiKey.isBlank() ? null : apiKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        return !p.startsWith("/api/") || p.equals("/api/health") || "OPTIONS".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (expected == null) {
            authenticate("dev");
            chain.doFilter(req, res);
            return;
        }
        String provided = req.getHeader("X-API-Key");
        if (provided == null) {
            String auth = req.getHeader("Authorization");
            if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
                provided = auth.substring(7).strip();
            }
        }
        boolean ok = provided != null
                && MessageDigest.isEqual(expected, provided.getBytes(StandardCharsets.UTF_8)); // constant-time
        if (!ok) {
            res.setStatus(401);
            res.setHeader("WWW-Authenticate", "ApiKey");
            res.setContentType("application/json");
            res.getWriter().write("{\"success\":false,\"error\":\"Missing or invalid API key.\"}");
            return;
        }
        authenticate("api-client");
        chain.doFilter(req, res);
    }

    private static void authenticate(String principal) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_API"))));
    }
}
