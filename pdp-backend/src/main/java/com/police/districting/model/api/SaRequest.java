package com.police.districting.model.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SaRequest {

    @NotNull
    @Min(1)
    private Integer numDistricts;

    @NotNull
    @Min(2001)
    private Integer year;

    @Min(1)
    private Integer maxRecords = 50000;

    @Min(2)
    private Integer gridSize = 20;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private Double lambda = 0.5;

    @Min(100)
    private Long timeLimitMillis = 60000L;

    @DecimalMin(value = "0.0001")
    private Double initialTemperature = 100.0;

    @DecimalMin(value = "0.0001")
    @DecimalMax(value = "0.9999")
    private Double coolingRate = 0.95;

    @Min(1)
    private Integer iterationsPerTemp = 200;

    @Min(1)
    private Integer maxIterations = 5000;
}