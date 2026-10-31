package com.moveinsync.roster.auth.service;

import com.moveinsync.roster.domain.entity.Tenant;
import com.moveinsync.roster.domain.entity.User;
import com.moveinsync.roster.dto.request.LoginRequestDto;
import com.moveinsync.roster.dto.response.LoginResponseDto;
import com.moveinsync.roster.exception.BusinessException;
import com.moveinsync.roster.exception.TenantNotFoundException;
import com.moveinsync.roster.repository.TenantRepository;
import com.moveinsync.roster.repository.UserRepository;
import com.moveinsync.roster.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;

    public LoginResponseDto login(LoginRequestDto request) {
        Tenant tenant = tenantRepository.findByTenantCode(request.getTenantCode().trim())
                .orElseThrow(() -> new TenantNotFoundException(request.getTenantCode()));

        if (!tenant.isActive()) {
            throw new BusinessException("Tenant account is deactivated", HttpStatus.FORBIDDEN, "TENANT_INACTIVE");
        }

        User user = userRepository.findByTenantIdAndUsername(tenant.getId(), request.getUsername().trim())
                .orElseThrow(() -> new BusinessException("Invalid username or password", HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS"));

        if (!user.isEnabled()) {
            throw new BusinessException("User account is disabled", HttpStatus.FORBIDDEN, "USER_DISABLED");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException("Invalid username or password", HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
        }

        String token = tokenProvider.generateToken(
                user.getId(),
                user.getUsername(),
                tenant.getId(),
                tenant.getTenantCode(),
                user.getRole().name()
        );

        return LoginResponseDto.builder()
                .token(token)
                .tokenType("Bearer")
                .username(user.getUsername())
                .role(user.getRole().name())
                .tenantId(tenant.getId())
                .tenantCode(tenant.getTenantCode())
                .expiresAt(Instant.now().plus(24, ChronoUnit.HOURS))
                .build();
    }
}
