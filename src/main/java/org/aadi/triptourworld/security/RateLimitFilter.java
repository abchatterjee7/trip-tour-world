package org.aadi.triptourworld.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Per-client throttling for /api/**: strict bucket for writes (they cost LLM tokens), looser for polling reads. */
public final class RateLimitFilter extends OncePerRequestFilter {
    private final RateLimiter writes;
    private final RateLimiter reads;

    public RateLimitFilter(int writesPerMinute) {
        this.writes = new RateLimiter(writesPerMinute, 60_000);
        this.reads = new RateLimiter(writesPerMinute * 12, 60_000);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        return !p.startsWith("/api/") || p.equals("/api/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        boolean read = "GET".equals(req.getMethod()) || "HEAD".equals(req.getMethod());
        String key = clientIp(req);
        if (!(read ? reads : writes).allow(key, System.currentTimeMillis())) {
            res.setStatus(429);
            res.setHeader("Retry-After", "30");
            res.setContentType("application/json");
            res.getWriter().write("{\"success\":false,\"error\":\"Too many requests. Please slow down.\"}");
            return;
        }
        chain.doFilter(req, res);
    }

    /** Behind Render/Hostinger proxies the real client is the first X-Forwarded-For entry. */
    static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].strip();
        }
        return req.getRemoteAddr();
    }
}
