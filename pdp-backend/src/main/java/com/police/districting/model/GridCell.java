package com.police.districting.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GridCell {
    private int x;
    private int y;
    private int crimeCount;
    private double riskScore;
    private double streetLength;
}