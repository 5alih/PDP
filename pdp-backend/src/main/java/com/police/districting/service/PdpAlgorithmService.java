package com.police.districting.service;

import com.police.districting.model.District;
import com.police.districting.model.GridCell;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class PdpAlgorithmService {

    private int gridRows;
    private int gridCols;
    private double totalDemand;
    private int totalCells;
    private double maxPossibleDistance;
    private double lambda = 0.5;  // balance between max and avg workload
    private final double wAlpha = 0.2;
    private final double wBeta = 0.1;
    private final double wGamma = 0.5;
    private final double wDelta = 0.2;

    public List<District> runPdp(List<GridCell> cells, int numDistricts) {
        // Determine grid dimensions (assuming square grid)
        int maxX = cells.stream().mapToInt(GridCell::getX).max().orElse(0);
        int maxY = cells.stream().mapToInt(GridCell::getY).max().orElse(0);
        this.gridRows = maxX + 1;
        this.gridCols = maxY + 1;
        this.totalDemand = cells.stream().mapToInt(GridCell::getCrimeCount).sum();
        this.totalCells = cells.size();
        this.maxPossibleDistance = gridRows + gridCols;

        // Phase 1: Random initial districts with one random cell each
        List<GridCell> shuffled = new ArrayList<>(cells);
        Collections.shuffle(shuffled);
        List<District> districts = new ArrayList<>();
        for (int i = 0; i < numDistricts; i++) {
            District d = new District();
            d.addCell(shuffled.get(i));
            districts.add(d);
        }

        List<GridCell> remaining = new ArrayList<>(shuffled.subList(numDistricts, shuffled.size()));

        // Phase 2: Greedy expansion
        while (!remaining.isEmpty()) {
            GridCell bestCell = null;
            Integer bestDistrictId = null;
            double bestObjective = Double.MAX_VALUE;

            for (GridCell cell : remaining) {
                for (int dId = 0; dId < districts.size(); dId++) {
                    District district = districts.get(dId);
                    if (district.getCells().isEmpty() || isNeighborOfDistrict(cell, district)) {
                        // Try adding cell to this district
                        district.addCell(cell);
                        double obj = computeObjective(districts);
                        district.getCells().remove(cell);  // rollback
                        if (obj < bestObjective) {
                            bestObjective = obj;
                            bestCell = cell;
                            bestDistrictId = dId;
                        }
                    }
                }
            }

            if (bestCell != null && bestDistrictId != null) {
                districts.get(bestDistrictId).addCell(bestCell);
                remaining.remove(bestCell);
            } else {
                // Should not happen, but break to avoid infinite loop
                break;
            }
        }

        return districts;
    }

    private boolean isNeighborOfDistrict(GridCell cell, District district) {
        for (GridCell dc : district.getCells()) {
            if (isNeighbor(cell, dc)) {
                return true;
            }
        }
        return false;
    }

    private boolean isNeighbor(GridCell a, GridCell b) {
        int dx = Math.abs(a.getX() - b.getX());
        int dy = Math.abs(a.getY() - b.getY());
        return (dx == 1 && dy == 0) || (dx == 0 && dy == 1);
    }

    public double computeObjective(List<District> districts) {
        List<Double> workloads = districts.stream()
                .filter(d -> d.getCells().size() > 0)
                .map(this::computeWorkload)
                .collect(Collectors.toList());
        double maxW = workloads.stream().max(Double::compare).orElse(0.0);
        double avgW = workloads.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        return lambda * maxW + (1 - lambda) * avgW;
    }

    public double computeWorkload(District district) {
        double area = areaRatio(district);
        double support = supportRatio(district);
        double demand = demandRatio(district);
        double diameter = diameterRatio(district);
        return wAlpha * area + wBeta * support + wGamma * demand + wDelta * diameter;
    }

    private double areaRatio(District district) {
        if (district.getCells().isEmpty()) return 0;
        return (double) district.getCells().size() / totalCells;
    }

    private double supportRatio(District district) {
        if (district.getCells().size() <= 1) return 1.0;
        Set<String> cellSet = district.getCells().stream()
                .map(c -> c.getX() + "," + c.getY())
                .collect(Collectors.toSet());
        int connected = 0;
        for (GridCell cell : district.getCells()) {
            int[][] dirs = {{-1,0},{1,0},{0,-1},{0,1}};
            for (int[] dir : dirs) {
                String neighborKey = (cell.getX() + dir[0]) + "," + (cell.getY() + dir[1]);
                if (cellSet.contains(neighborKey)) {
                    connected++;
                }
            }
        }
        return (double) connected / (district.getCells().size() * 4);
    }

    private double demandRatio(District district) {
        if (district.getCells().isEmpty()) return 0;
        return district.getDemand() / totalDemand;
    }

    private double diameterRatio(District district) {
        if (district.getCells().size() <= 1) return 0;
        int maxDist = 0;
        List<GridCell> cells = district.getCells();
        for (int i = 0; i < cells.size(); i++) {
            for (int j = i+1; j < cells.size(); j++) {
                int dist = manhattan(cells.get(i), cells.get(j));
                if (dist > maxDist) maxDist = dist;
            }
        }
        return maxDist / maxPossibleDistance;
    }

    private int manhattan(GridCell a, GridCell b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY());
    }
}