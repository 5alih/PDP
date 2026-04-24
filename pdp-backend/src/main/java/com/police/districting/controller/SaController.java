package com.police.districting.controller;

import com.police.districting.client.OsmStreetDataClient;
import com.police.districting.model.CrimeRecord;
import com.police.districting.model.District;
import com.police.districting.model.GridCell;
import com.police.districting.model.api.DistrictingResponse;
import com.police.districting.model.api.SaRequest;
import com.police.districting.service.ChicagoDataService;
import com.police.districting.service.PdpAlgorithmService;
import com.police.districting.service.SimulatedAnnealingService;
import com.police.districting.util.DataPreprocessor;
import com.police.districting.util.DataPreprocessor.GridBounds;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/sa")
@CrossOrigin(origins = "*")
public class SaController {

    @Autowired
    private ChicagoDataService dataService;

    @Autowired
    private SimulatedAnnealingService saService;

    @Autowired
    private PdpAlgorithmService pdpService;

    @Autowired
    private OsmStreetDataClient osmClient;

    @PostMapping("/solve")
    public DistrictingResponse solveSa(@Valid @RequestBody SaRequest request) {

        long startTime = System.currentTimeMillis();

        int maxRecords = request.getMaxRecords() != null ? request.getMaxRecords() : 50000;
        int gridSize = request.getGridSize() != null ? request.getGridSize() : 20;
        double lambda = request.getLambda() != null ? request.getLambda() : 0.5;

        pdpService.setLambda(lambda);

        List<CrimeRecord> crimes = dataService
                .getCrimesForYear(request.getYear(), maxRecords)
                .collectList()
                .block();

        if (crimes == null || crimes.isEmpty()) {
            return new DistrictingResponse(
                    List.of(), 0.0, List.of(), 0.0, 0.0, false,
                    System.currentTimeMillis() - startTime,
                    0, gridSize, request.getNumDistricts(), request.getYear(),
                    maxRecords, lambda, 0.0, 0.0, 0.0, 0.0
            );
        }

        GridBounds bounds = DataPreprocessor.computeBounds(crimes, gridSize);

        Map<String, Double> streetLengths = osmClient
                .fetchStreetLengths(bounds, gridSize)
                .blockOptional()
                .orElse(Collections.emptyMap());

        List<GridCell> cells = DataPreprocessor.createGrid(crimes, gridSize, streetLengths);

        long algorithmTimeLimitMillis =
                request.getTimeLimitMillis() != null ? request.getTimeLimitMillis() : 60000L;

        var districts = saService.runSa(
                cells,
                request.getNumDistricts(),
                request.getInitialTemperature(),
                request.getCoolingRate(),
                request.getIterationsPerTemp(),
                request.getMaxIterations(),
                algorithmTimeLimitMillis
        );

        if (districts == null || districts.isEmpty()) {
            return new DistrictingResponse(
                    List.of(), 0.0, List.of(), 0.0, 0.0, false,
                    System.currentTimeMillis() - startTime,
                    cells.size(), gridSize, request.getNumDistricts(), request.getYear(),
                    maxRecords, lambda,
                    bounds.minLat, bounds.maxLat, bounds.minLon, bounds.maxLon
            );
        }

        List<List<int[]>> districtCells = districts.stream()
                .map(d -> d.getCells().stream()
                        .map(cell -> new int[]{cell.getX(), cell.getY()})
                        .collect(Collectors.toList()))
                .collect(Collectors.toList());

        double finalObjective = pdpService.computeObjective(districts);

        final List<District> finalDistricts = districts;
        List<Double> workloads = districts.stream()
                .map(d -> pdpService.computeWorkload(d, finalDistricts))
                .collect(Collectors.toList());

        double maxWorkload = workloads.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        double avgWorkload = workloads.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        boolean feasible = pdpService.isFeasible(districts, request.getNumDistricts(), cells.size());

        return new DistrictingResponse(
                districtCells, finalObjective, workloads, maxWorkload, avgWorkload, feasible,
                System.currentTimeMillis() - startTime,
                cells.size(), gridSize, request.getNumDistricts(), request.getYear(),
                maxRecords, lambda,
                bounds.minLat, bounds.maxLat, bounds.minLon, bounds.maxLon
        );
    }
}
