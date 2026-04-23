package com.police.districting.model.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DistrictingResponse {

    // list of districts, each district is list of [x,y] cells
    private List<List<int[]>> districts;

    private double objectiveValue;
    private List<Double> workloads;

    private double maxWorkload;
    private double avgWorkload;

    private boolean feasible;
    private long runtimeMillis;

    private int totalCells;
    private int gridSize;
    private int numDistricts;
    private int year;
    private int maxRecordsUsed;

    private double lambda;

    private double minLat;
    private double maxLat;
    private double minLon;
    private double maxLon;
}