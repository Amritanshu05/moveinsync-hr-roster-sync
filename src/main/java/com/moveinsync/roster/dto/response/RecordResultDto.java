package com.moveinsync.roster.dto.response;

import com.moveinsync.roster.domain.enums.ProcessingOutcome;
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
public class RecordResultDto {
    private String employeeCode;
    private ProcessingOutcome outcome;
    private Long appliedVersion;

    @Builder.Default
    private List<RecordErrorDetail> errors = new ArrayList<>();
}
