package com.police.districting.model.api;

import lombok.Data;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Data
public class SaRequest {
    @NotNull @Min(1)
    private Integer numDistricts;
    
    @NotNull @Min(1)
    private Integer year;
    
    @Min(1)
    private Integer maxRecords = 50000;   // default
    
    // SA parameters with defaults
    private Double initialTemperature = 100.0;
    private Double coolingRate = 0.95;
    private Integer iterationsPerTemp = 1000;
    private Integer maxIterations = 100000;
}