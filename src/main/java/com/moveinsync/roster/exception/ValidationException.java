package com.moveinsync.roster.exception;

import com.moveinsync.roster.dto.response.RecordErrorDetail;
import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.List;

@Getter
public class ValidationException extends BusinessException {

    private final List<RecordErrorDetail> validationErrors;

    public ValidationException(String message, List<RecordErrorDetail> validationErrors) {
        super(message, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        this.validationErrors = validationErrors;
    }
}
