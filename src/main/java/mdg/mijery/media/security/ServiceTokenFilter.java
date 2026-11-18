package mdg.mijery.media.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Authenticates trusted services (currently mijery-sso and chronicles-api) on
 * {@code /api/**} with a shared token.
 *
 * <p>Deliberately not a user database or JWT scheme: mijery-media has no identities of
 * its own. The token answers "is this call from a service we deployed?", while product
 * scoping stays the caller's job (the SSO checks {@code client_admins} before it ever
 * reaches this service).
 *
 * <p>When {@code mijery.media.service-token} is blank the filter allows the call and
 * warns once at startup. That keeps local development frictionless; production must set
 * the variable. The comparison is constant-time.
 */
@Component
@Slf4j
public class ServiceTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Mijery-Service-Token";

    private final byte[] expectedToken;
    private final boolean enforced;

    public ServiceTokenFilter(@Value("${mijery.media.service-token:}") String serviceToken) {
        this.expectedToken = serviceToken == null
                ? new byte[0]
                : serviceToken.trim().getBytes(StandardCharsets.UTF_8);
        this.enforced = expectedToken.length > 0;
        if (!enforced) {
            log.warn("mijery.media.service-token is empty: /api/** accepts unauthenticated calls. "
                    + "Set MIJERY_MEDIA_SERVICE_TOKEN in production.");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (enforced) {
            String provided = request.getHeader(HEADER);
            boolean matches = provided != null && MessageDigest.isEqual(
                    expectedToken, provided.getBytes(StandardCharsets.UTF_8));
            if (!matches) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.getWriter().write(
                        "{\"success\":false,\"message\":\"Unauthorized service call\"}");
                return;
            }
        }

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("mijery-service", null,
                        List.of(new SimpleGrantedAuthority("ROLE_SERVICE"))));
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
