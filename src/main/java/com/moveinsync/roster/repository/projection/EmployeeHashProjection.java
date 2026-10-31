package com.moveinsync.roster.repository.projection;

public interface EmployeeHashProjection {
    String getEmployeeCode();
    Long getRecordHash();
    Long getSourceVersion();
}
