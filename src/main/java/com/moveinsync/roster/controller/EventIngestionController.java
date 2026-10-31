package com.moveinsync.roster.controller;

import com.moveinsync.roster.dto.request.BatchIngestRequestDto;
import com.moveinsync.roster.dto.request.EmployeeEventDto;
import com.moveinsync.roster.dto.response.ApiResponse;
import com.moveinsync.roster.dto.response.BatchIngestResponse;
import com.moveinsync.roster.dto.response.RecordResultDto;
import com.moveinsync.roster.service.EventIngestionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventIngestionController {

    private final EventIngestionService eventIngestionService;

    @PostMapping("/ingest")
    @PreAuthorize("hasAnyAuthority('ROLE_OPERATOR', 'ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<BatchIngestResponse>> ingestBatch(
            @Valid @RequestBody BatchIngestRequestDto request) {
        BatchIngestResponse response = eventIngestionService.ingestBatch(request);
        return ResponseEntity.ok(ApiResponse.ok("Batch ingestion processed with per-record validation", response));
    }

    @PostMapping("/ingest/single")
    @PreAuthorize("hasAnyAuthority('ROLE_OPERATOR', 'ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<RecordResultDto>> ingestSingle(
            @Valid @RequestBody EmployeeEventDto request) {
        RecordResultDto response = eventIngestionService.ingestSingle(request);
        return ResponseEntity.ok(ApiResponse.ok("Single event processed", response));
    }
}
