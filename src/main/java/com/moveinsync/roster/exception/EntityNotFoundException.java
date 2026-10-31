package com.moveinsync.roster.exception;

import org.springframework.http.HttpStatus;

public class EntityNotFoundException extends BusinessException {

    public EntityNotFoundException(String entityName, String identifier) {
        super(String.format("%s with identifier '%s' was not found", entityName, identifier),
              HttpStatus.NOT_FOUND,
              "ENTITY_NOT_FOUND");
    }
}
