package com.police.districting.model.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DistrictingResponse {
    private List<List<int[]>> districts; // list of districts, each district is list of [x,y] grid cells
    private double objectiveValue;
    private List<Double> workloads;
    private int totalCells;
    private int gridSize;
    private double minLat;
    private double maxLat;
    private double minLon;
    private double maxLon;
}