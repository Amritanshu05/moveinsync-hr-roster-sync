package com.moveinsync.roster.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReconciliationErrorResponseDto {

    private String employeeCode;
    private Integer rowNumber;
    private String errorType;
    private String field;
    private String errorCode;
    private String message;
}
