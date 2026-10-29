package com.chatapp.messages.security;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

import com.chatapp.messages.client.AuthClient;
import com.chatapp.messages.client.AuthResult;
import com.chatapp.messages.exception.ErrorResponse;
import com.chatapp.messages.exception.ErrorResponses;
import com.chatapp.messages.logging.RequestIds;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** NOT a @Component: constructed in SecurityConfig so it runs once, inside the security chain. Never logs the token. */
public class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BearerTokenAuthenticationFilter.class);
    /** RFC 6750 b64token after a case-insensitive "Bearer" and one or more spaces. Empty or spaced tokens do not match. */
    private static final Pattern BEARER = Pattern.compile("^Bearer +([A-Za-z0-9._~+/-]+=*) *$", Pattern.CASE_INSENSITIVE);
    private static final int MAX_HEADER_LENGTH = 4096;

    private final AuthClient authClient;
    private final AuthenticationEntryPoint entryPoint;
    private final ObjectMapper mapper;

    public BearerTokenAuthenticationFilter(AuthClient authClient, AuthenticationEntryPoint entryPoint, ObjectMapper mapper) {
        this.authClient = authClient;
        this.entryPoint = entryPoint;
        this.mapper = mapper;
    }

    /** Keep in step with SecurityConfig.PUBLIC_PATHS: public paths never trigger a ValidateToken call. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/actuator/health") || path.startsWith("/actuator/health/")
                || path.equals("/ws") || path.startsWith("/ws/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null) {
            chain.doFilter(request, response);   // no credentials: the authorization rule answers 401 TOKEN_MISSING
            return;
        }
        Matcher m = BEARER.matcher(header);
        if (header.length() > MAX_HEADER_LENGTH || !m.matches()) {
            entryPoint.commence(request, response, new TokenRejectedException("Authorization header must be 'Bearer <token>'"));
            return;
        }

        switch (authClient.verify(m.group(1))) {
            case AuthResult.Valid v -> {
                AuthenticatedUser user = new AuthenticatedUser(v.userId(), v.roles());
                var authorities = v.roles().stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
                SecurityContext context = SecurityContextHolder.getContextHolderStrategy().createEmptyContext();
                // credentials = the token, kept for forwarding (Step 25). Redacted toString; never logged.
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, new BearerToken(m.group(1)), authorities));
                SecurityContextHolder.getContextHolderStrategy().setContext(context);
                log.debug("authenticated userId={}", v.userId());
                chain.doFilter(request, response);
            }
            case AuthResult.Invalid i ->
                    entryPoint.commence(request, response, new TokenRejectedException("Token is invalid or expired"));
            case AuthResult.Unavailable u -> {
                log.warn("rejecting request {} with 503: auth-service gave no answer ({})", RequestIds.get(request, response), u.code());
                ErrorResponses.write(request, response, mapper, HttpStatus.SERVICE_UNAVAILABLE,
                        ErrorResponse.AUTH_UNAVAILABLE, "Authentication service unavailable");
            }
        }
    }
}
