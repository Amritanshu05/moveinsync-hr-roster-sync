package com.moveinsync.roster.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponse {
    private int status;
    private String errorCode;
    private String message;
    private String path;

    @Builder.Default
    private Instant timestamp = Instant.now();

    @Builder.Default
    private List<RecordErrorDetail> errors = new ArrayList<>();
}
