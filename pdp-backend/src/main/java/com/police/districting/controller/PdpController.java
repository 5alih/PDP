package com.police.districting.controller;

import com.police.districting.client.OsmStreetDataClient;
import com.police.districting.model.CrimeRecord;
import com.police.districting.model.GridCell;
import com.police.districting.model.api.DistrictingResponse;
import com.police.districting.model.api.PdpRequest;
import com.police.districting.service.ChicagoDataService;
import com.police.districting.service.PdpAlgorithmService;
import com.police.districting.util.DataPreprocessor;
import com.police.districting.util.DataPreprocessor.GridBounds;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/pdp")
@CrossOrigin(origins = "*")
public class PdpController {

    @Autowired
    private ChicagoDataService dataService;

    @Autowired
    private PdpAlgorithmService pdpService;

    @Autowired
    private OsmStreetDataClient osmClient;

    @PostMapping("/solve")
    public DistrictingResponse solvePdp(@Valid @RequestBody PdpRequest request) {

        long startTime = System.currentTimeMillis();

        int maxRecords = request.getMaxRecords() != null ? request.getMaxRecords() : 50000;
        int gridSize = request.getGridSize() != null ? request.getGridSize() : 20;
        double lambda = request.getLambda() != null ? request.getLambda() : 0.5;

        log.info("PDP request: year={}, districts={}, gridSize={}, maxRecords={}, lambda={}, timeLimitMs={}",
                request.getYear(), request.getNumDistricts(), gridSize, maxRecords, lambda,
                request.getTimeLimitMillis() != null ? request.getTimeLimitMillis() : 5000);

        pdpService.setLambda(lambda);

        List<CrimeRecord> crimes = dataService
                .getCrimesForYear(request.getYear(), maxRecords)
                .collectList()
                .block();

        log.info("Crime data loaded: {} records", crimes == null ? 0 : crimes.size());

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
        var districts = pdpService.runPdp(cells, request.getNumDistricts(), algorithmTimeLimitMillis);

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

        List<Double> workloads = districts.stream()
                .map(pdpService::computeWorkload)
                .collect(Collectors.toList());

        double maxWorkload = workloads.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        double avgWorkload = workloads.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        boolean feasible = pdpService.isFeasible(districts, request.getNumDistricts(), cells.size());

        log.info("PDP result: feasible={}, objective={}, maxWorkload={}, avgWorkload={}, totalMs={}",
                feasible,
                String.format("%.6f", finalObjective),
                String.format("%.4f", maxWorkload),
                String.format("%.4f", avgWorkload),
                System.currentTimeMillis() - startTime);

        return new DistrictingResponse(
                districtCells, finalObjective, workloads, maxWorkload, avgWorkload, feasible,
                System.currentTimeMillis() - startTime,
                cells.size(), gridSize, request.getNumDistricts(), request.getYear(),
                maxRecords, lambda,
                bounds.minLat, bounds.maxLat, bounds.minLon, bounds.maxLon
        );
    }
}
