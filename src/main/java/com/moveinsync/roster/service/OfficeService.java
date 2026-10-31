package com.moveinsync.roster.service;

import com.moveinsync.roster.config.CacheConfig;
import com.moveinsync.roster.repository.OfficeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OfficeService {

    private final OfficeRepository officeRepository;

    @Cacheable(value = CacheConfig.OFFICES_CACHE, key = "#tenantId")
    public Set<String> getValidOfficeCodesForTenant(UUID tenantId) {
        log.debug("Loading valid office codes from DB for tenant: {}", tenantId);
        return officeRepository.findActiveOfficeCodesByTenantId(tenantId);
    }
}
