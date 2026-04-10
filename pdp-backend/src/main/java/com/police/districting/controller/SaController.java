package com.police.districting.controller;

import com.police.districting.model.GridCell;
import com.police.districting.model.api.DistrictingResponse;
import com.police.districting.model.api.SaRequest;
import com.police.districting.service.ChicagoDataService;
import com.police.districting.service.SimulatedAnnealingService;
import com.police.districting.service.PdpAlgorithmService;
import com.police.districting.util.DataPreprocessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/sa")
public class SaController {

    @Autowired
    private ChicagoDataService dataService;
    
    @Autowired
    private SimulatedAnnealingService saService;
    
    @Autowired
    private PdpAlgorithmService pdpService; // for objective computation

    @PostMapping("/solve")
    public DistrictingResponse solveSa(@Valid @RequestBody SaRequest request) {
        // Fetch crime data
        int maxRecords = request.getMaxRecords() != null ? request.getMaxRecords() : 50000;
        List<com.police.districting.model.CrimeRecord> crimes = 
                dataService.getCrimesForYear(request.getYear(), maxRecords).collectList().block();
        
        // Build grid
        int gridSize = 20;  // can be made configurable later
        List<GridCell> cells = DataPreprocessor.createGrid(crimes, gridSize);
        
        // Run Simulated Annealing
        var districts = saService.runSa(
                cells,
                request.getNumDistricts(),
                request.getInitialTemperature(),
                request.getCoolingRate(),
                request.getIterationsPerTemp(),
                request.getMaxIterations()
        );
        
        // Prepare response
        List<List<int[]>> districtCells = districts.stream()
                .map(d -> d.getCells().stream()
                        .map(cell -> new int[]{cell.getX(), cell.getY()})
                        .collect(Collectors.toList()))
                .collect(Collectors.toList());
        
        double finalObjective = pdpService.computeObjective(districts);
        List<Double> workloads = districts.stream()
                .map(pdpService::computeWorkload)
                .collect(Collectors.toList());
        
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