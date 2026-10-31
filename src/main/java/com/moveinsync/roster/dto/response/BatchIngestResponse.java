package com.moveinsync.roster.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchIngestResponse {
    private String tenantCode;
    private int totalReceived;
    private int appliedCount;
    private int staleCount;
    private int duplicateCount;
    private int rejectedCount;

    @Builder.Default
    private List<RecordResultDto> results = new ArrayList<>();
}
