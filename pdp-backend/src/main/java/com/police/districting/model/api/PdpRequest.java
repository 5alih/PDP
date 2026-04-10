package com.police.districting.model.api;

import lombok.Data;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Data
public class PdpRequest {
    @NotNull
    @Min(1)
    private Integer numDistricts;  // number of districts (p)
    
    @NotNull
    @Min(1)
    private Integer year;          // year of crime data
    
    @Min(1)
    private Integer maxRecords;    // optional limit on crime records
}