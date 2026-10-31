package com.moveinsync.roster.controller;

import com.moveinsync.roster.domain.enums.EmployeeStatus;
import com.moveinsync.roster.dto.request.TransportProfileUpdateDto;
import com.moveinsync.roster.dto.response.ApiResponse;
import com.moveinsync.roster.dto.response.EmployeeResponseDto;
import com.moveinsync.roster.dto.response.PageResponse;
import com.moveinsync.roster.service.EmployeeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/employees")
@RequiredArgsConstructor
public class EmployeeController {

    private final EmployeeService employeeService;

    @GetMapping("/{employeeCode}")
    @PreAuthorize("hasAnyAuthority('ROLE_EMPLOYEE', 'ROLE_TRANSPORT_ADMIN', 'ROLE_OPERATOR')")
    public ResponseEntity<ApiResponse<EmployeeResponseDto>> getEmployee(@PathVariable String employeeCode) {
        EmployeeResponseDto response = employeeService.getEmployee(employeeCode);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLE_TRANSPORT_ADMIN', 'ROLE_OPERATOR')")
    public ResponseEntity<ApiResponse<PageResponse<EmployeeResponseDto>>> listEmployees(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) EmployeeStatus status) {
        PageResponse<EmployeeResponseDto> response = employeeService.listEmployees(page, size, status);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PutMapping("/{employeeCode}/transport")
    @PreAuthorize("hasAuthority('ROLE_TRANSPORT_ADMIN')")
    public ResponseEntity<ApiResponse<EmployeeResponseDto>> updateTransportProfile(
            @PathVariable String employeeCode,
            @Valid @RequestBody TransportProfileUpdateDto request) {
        EmployeeResponseDto response = employeeService.updateTransportProfile(employeeCode, request);
        return ResponseEntity.ok(ApiResponse.ok("Transport profile updated successfully", response));
    }
}
