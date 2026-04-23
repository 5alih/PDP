package com.police.districting.model.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class PdpRequest {

    @NotNull
    @Min(1)
    private Integer numDistricts;

    @NotNull
    @Min(2001)
    private Integer year;

    @Min(1)
    private Integer maxRecords;

    @Min(2)
    private Integer gridSize;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private Double lambda;

    @Min(100)
    private Long timeLimitMillis;
}