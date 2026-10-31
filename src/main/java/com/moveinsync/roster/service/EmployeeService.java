package com.moveinsync.roster.service;

import com.moveinsync.roster.config.CacheConfig;
import com.moveinsync.roster.domain.entity.Employee;
import com.moveinsync.roster.domain.enums.EmployeeStatus;
import com.moveinsync.roster.dto.request.TransportProfileUpdateDto;
import com.moveinsync.roster.dto.response.EmployeeResponseDto;
import com.moveinsync.roster.dto.response.PageResponse;
import com.moveinsync.roster.exception.EntityNotFoundException;
import com.moveinsync.roster.mapper.EmployeeMapper;
import com.moveinsync.roster.repository.EmployeeRepository;
import com.moveinsync.roster.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final EmployeeMapper employeeMapper;

    @Transactional(readOnly = true)
    @Cacheable(value = CacheConfig.EMPLOYEE_CACHE, key = "T(com.moveinsync.roster.security.TenantContext).getTenantId() + ':' + #employeeCode")
    public EmployeeResponseDto getEmployee(String employeeCode) {
        UUID tenantId = TenantContext.getTenantId();
        Employee employee = employeeRepository.findByTenantIdAndEmployeeCode(tenantId, employeeCode.trim())
                .orElseThrow(() -> new EntityNotFoundException("Employee", employeeCode));

        return employeeMapper.toResponseDto(employee);
    }

    @Transactional(readOnly = true)
    public PageResponse<EmployeeResponseDto> listEmployees(int page, int size, EmployeeStatus status) {
        UUID tenantId = TenantContext.getTenantId();
        Pageable pageable = PageRequest.of(page, size, Sort.by("employeeCode").ascending());

        Page<Employee> employeePage;
        if (status != null) {
            employeePage = employeeRepository.findAll((root, query, cb) -> cb.and(
                    cb.equal(root.get("tenantId"), tenantId),
                    cb.equal(root.get("status"), status)
            ), pageable);
        } else {
            employeePage = employeeRepository.findAll((root, query, cb) ->
                    cb.equal(root.get("tenantId"), tenantId), pageable);
        }

        return PageResponse.<EmployeeResponseDto>builder()
                .content(employeePage.getContent().stream().map(employeeMapper::toResponseDto).toList())
                .pageNumber(employeePage.getNumber())
                .pageSize(employeePage.getSize())
                .totalElements(employeePage.getTotalElements())
                .totalPages(employeePage.getTotalPages())
                .last(employeePage.isLast())
                .build();
    }

    @Transactional
    @CacheEvict(value = CacheConfig.EMPLOYEE_CACHE, key = "T(com.moveinsync.roster.security.TenantContext).getTenantId() + ':' + #employeeCode")
    public EmployeeResponseDto updateTransportProfile(String employeeCode, TransportProfileUpdateDto dto) {
        UUID tenantId = TenantContext.getTenantId();
        Employee employee = employeeRepository.findForUpdateByTenantIdAndEmployeeCode(tenantId, employeeCode.trim())
                .orElseThrow(() -> new EntityNotFoundException("Employee", employeeCode));

        // Transport Admin Field Ownership: modifies pickupPointId, specialAssistance, transportNotes
        employee.setPickupPointId(dto.getPickupPointId().trim());
        employee.setSpecialAssistance(dto.getSpecialAssistance() != null && dto.getSpecialAssistance());
        employee.setTransportNotes(dto.getTransportNotes() != null ? dto.getTransportNotes().trim() : null);

        Employee updated = employeeRepository.save(employee);
        log.info("Transport Admin updated transport profile for employee: {}", employeeCode);

        return employeeMapper.toResponseDto(updated);
    }
}
