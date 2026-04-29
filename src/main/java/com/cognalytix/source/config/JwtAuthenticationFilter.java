package com.cognalytix.source.config;

import com.cognalytix.source.domain.user.User;
import com.cognalytix.source.domain.user.UserRepository;
import com.cognalytix.source.security.AuthUserPrincipal;
import com.cognalytix.source.service.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** Use Spring Security matchers so paths agree with SecurityFilterChain (context path, etc.). */
    private static final List<RequestMatcher> PUBLIC_URLS = List.of(
            PathPatternRequestMatcher.pathPattern("/"),
            PathPatternRequestMatcher.pathPattern("/favicon.ico"),
            PathPatternRequestMatcher.pathPattern("/api/auth/**"),
            PathPatternRequestMatcher.pathPattern("/login"),
            PathPatternRequestMatcher.pathPattern("/error"),
            PathPatternRequestMatcher.pathPattern("/actuator/health"),
            PathPatternRequestMatcher.pathPattern("/actuator/health/**")
    );

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return PUBLIC_URLS.stream().anyMatch(m -> m.matches(request));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(7).trim();
        try {
            Claims claims = jwtService.parseAccessToken(token);
            UUID userId = UUID.fromString(claims.getSubject());
            User user = userRepository.findById(userId).orElse(null);
            if (user == null || !user.isActive()) {
                unauthorized(response);
                return;
            }
            AuthUserPrincipal principal = new AuthUserPrincipal(user);
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (JwtException | IllegalArgumentException ex) {
            unauthorized(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Unauthorized\"}");
    }
}
