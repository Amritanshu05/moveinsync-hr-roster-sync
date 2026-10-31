package com.moveinsync.roster.security;

import com.moveinsync.roster.domain.entity.Tenant;
import com.moveinsync.roster.repository.TenantRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class TenantFilter extends OncePerRequestFilter {

    private final TenantRepository tenantRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader("X-Correlation-ID");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().substring(0, 8);
        }
        MDC.put("traceId", traceId);

        String tenantHeader = request.getHeader("X-Tenant-ID");
        if (tenantHeader != null && !tenantHeader.isBlank()) {
            resolveAndSetTenant(tenantHeader.trim());
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            MDC.remove("traceId");
            MDC.remove("tenantId");
            MDC.remove("employeeId");
        }
    }

    private void resolveAndSetTenant(String tenantHeader) {
        try {
            // Check if passed as UUID
            UUID tenantId = UUID.fromString(tenantHeader);
            Optional<Tenant> tenantOpt = tenantRepository.findById(tenantId);
            if (tenantOpt.isPresent() && tenantOpt.get().isActive()) {
                TenantContext.setTenant(tenantOpt.get().getId(), tenantOpt.get().getTenantCode());
                MDC.put("tenantId", tenantOpt.get().getTenantCode());
                return;
            }
        } catch (IllegalArgumentException ignored) {
            // Passed as code string
        }

        Optional<Tenant> tenantOpt = tenantRepository.findByTenantCode(tenantHeader);
        if (tenantOpt.isPresent() && tenantOpt.get().isActive()) {
            TenantContext.setTenant(tenantOpt.get().getId(), tenantOpt.get().getTenantCode());
            MDC.put("tenantId", tenantOpt.get().getTenantCode());
        }
    }
}
