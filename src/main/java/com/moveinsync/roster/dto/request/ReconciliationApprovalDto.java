package com.moveinsync.roster.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReconciliationApprovalDto {

    @NotBlank(message = "Action must be APPROVE or REJECT")
    private String action; // APPROVE or REJECT

    private String operatorNotes;
}
