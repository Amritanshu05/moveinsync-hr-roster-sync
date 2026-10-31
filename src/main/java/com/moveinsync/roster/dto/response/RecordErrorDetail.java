package com.moveinsync.roster.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecordErrorDetail {
    private String field;
    private String errorCode;
    private String message;
}
