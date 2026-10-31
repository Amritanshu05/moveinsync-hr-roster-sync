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
public class TransportProfileUpdateDto {

    @NotBlank(message = "Pickup point ID is required")
    private String pickupPointId;

    @Builder.Default
    private Boolean specialAssistance = false;

    private String transportNotes;
}
