package com.moveinsync.roster.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.roster.dto.response.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String jwt = getJwtFromRequest(request);

        if (StringUtils.hasText(jwt)) {
            if (tokenProvider.validateToken(jwt)) {
                String username = tokenProvider.getUsernameFromToken(jwt);
                UUID tenantId = tokenProvider.getTenantIdFromToken(jwt);
                String tenantCode = tokenProvider.getTenantCodeFromToken(jwt);
                String role = tokenProvider.getRoleFromToken(jwt);
                UUID userId = tokenProvider.getUserIdFromToken(jwt);

                // Multi-Tenant Isolation Enforcement:
                // If the request contains X-Tenant-ID, assert that it matches the authenticated JWT tenant.
                String tenantHeader = request.getHeader("X-Tenant-ID");
                if (StringUtils.hasText(tenantHeader)) {
                    String trimmedHeader = tenantHeader.trim();
                    boolean matchesCode = trimmedHeader.equalsIgnoreCase(tenantCode);
                    boolean matchesId = trimmedHeader.equalsIgnoreCase(tenantId != null ? tenantId.toString() : "");

                    if (!matchesCode && !matchesId) {
                        log.warn("Security Alert: Tenant mismatch! User '{}' from tenant '{}' ({}) attempted access to tenant '{}'",
                                username, tenantCode, tenantId, trimmedHeader);
                        sendForbiddenResponse(response, "Tenant ID in header does not match authenticated user tenant", "TENANT_MISMATCH");
                        return;
                    }
                }

                // The authenticated JWT is the authoritative source for tenant identity
                TenantContext.setTenant(tenantId, tenantCode);
                MDC.put("tenantId", tenantCode);

                UserPrincipal principal = new UserPrincipal(
                        userId != null ? userId : UUID.randomUUID(),
                        tenantId,
                        username,
                        null,
                        List.of(new SimpleGrantedAuthority(role)),
                        true
                );

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }

        filterChain.doFilter(request, response);
    }

    private void sendForbiddenResponse(HttpServletResponse response, String message, String errorCode) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiResponse<?> apiResponse = ApiResponse.error(message, errorCode);
        response.getWriter().write(objectMapper.writeValueAsString(apiResponse));
    }

    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
