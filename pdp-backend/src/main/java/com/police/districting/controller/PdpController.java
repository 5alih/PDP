package com.police.districting.controller;

import com.police.districting.model.GridCell;
import com.police.districting.model.api.DistrictingResponse;
import com.police.districting.model.api.PdpRequest;
import com.police.districting.service.ChicagoDataService;
import com.police.districting.service.PdpAlgorithmService;
import com.police.districting.util.DataPreprocessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/pdp")
public class PdpController {

    @Autowired
    private ChicagoDataService dataService;
    
    @Autowired
    private PdpAlgorithmService pdpService;

    @PostMapping("/solve")
    public DistrictingResponse solvePdp(@Valid @RequestBody PdpRequest request) {
        // Fetch crime data for the year
        int maxRecords = request.getMaxRecords() != null ? request.getMaxRecords() : 50000;
        List<com.police.districting.model.CrimeRecord> crimes = 
                dataService.getCrimesForYear(request.getYear(), maxRecords).collectList().block();
        
        // Build grid
        int gridSize = 20;  // can be made configurable
        List<GridCell> cells = DataPreprocessor.createGrid(crimes, gridSize);
        
        // Run PDP algorithm
        var districts = pdpService.runPdp(cells, request.getNumDistricts());
        
        // Prepare response
        List<List<int[]>> districtCells = districts.stream()
                .map(d -> d.getCells().stream()
                        .map(cell -> new int[]{cell.getX(), cell.getY()})
                        .collect(Collectors.toList()))
                .collect(Collectors.toList());
        
        // Compute objective value for final solution (using same method)
        double finalObjective = pdpService.computeObjective(districts); // need to make computeObjective public or add getter
        
        // Workloads for each district
        List<Double> workloads = districts.stream()
                .map(pdpService::computeWorkload)
                .collect(Collectors.toList());
        
        // Bounds
        var bounds = DataPreprocessor.computeBounds(crimes, gridSize);
        
        return new DistrictingResponse(
                districtCells,
                finalObjective,
                workloads,
                cells.size(),
                gridSize,
                bounds.minLat,
                bounds.maxLat,
                bounds.minLon,
                bounds.maxLon
        );
    }
}