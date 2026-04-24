package com.police.districting.service;

import com.police.districting.model.District;
import com.police.districting.model.GridCell;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class SimulatedAnnealingService {

    @Autowired
    private PdpAlgorithmService pdpService;

    private final Random random = new Random();

    private int gridRows;
    private int gridCols;
    private int expectedDistrictCount;
    private int expectedCellCount;

    public List<District> runSa(List<GridCell> cells,
                                int numDistricts,
                                double initialTemp,
                                double coolingRate,
                                int iterationsPerTemp,
                                int maxIterations,
                                long timeLimitMillis) {

        if (cells == null || cells.isEmpty()) {
            return Collections.emptyList();
        }
        if (numDistricts <= 0) {
            throw new IllegalArgumentException("numDistricts must be > 0");
        }
        if (numDistricts > cells.size()) {
            throw new IllegalArgumentException("numDistricts cannot be greater than total number of cells");
        }

        int maxX = cells.stream().mapToInt(GridCell::getX).max().orElse(0);
        int maxY = cells.stream().mapToInt(GridCell::getY).max().orElse(0);
        this.gridRows = maxX + 1;
        this.gridCols = maxY + 1;
        this.expectedDistrictCount = numDistricts;
        this.expectedCellCount = cells.size();

        // Generate a single-pass feasible initial solution so SA runs independently
        // of the full multi-start PDP. Retry up to 20 times because the random
        // greedy pass can occasionally fail to satisfy convexity for all cells.
        List<District> currentSolution = null;
        for (int attempt = 0; attempt < 20 && (currentSolution == null || currentSolution.isEmpty()); attempt++) {
            currentSolution = pdpService.buildSinglePassInitialSolution(cells, numDistricts);
        }
        if (currentSolution == null || currentSolution.isEmpty()) {
            return Collections.emptyList();
        }

        double currentObjective = pdpService.computeObjective(currentSolution);

        List<District> bestSolution = deepCopyDistricts(currentSolution);
        double bestObjective = currentObjective;

        long startTime = System.currentTimeMillis();

        double temperature = initialTemp;
        int totalIterations = 0;

        while (temperature > 0.01
                && totalIterations < maxIterations
                && System.currentTimeMillis() - startTime < timeLimitMillis) {

            for (int i = 0;
                 i < iterationsPerTemp
                         && totalIterations < maxIterations
                         && System.currentTimeMillis() - startTime < timeLimitMillis;
                 i++) {

                NeighborMove move = generateNeighborMove(currentSolution);
                if (move == null) {
                    totalIterations++;
                    continue;
                }

                applyMove(currentSolution, move);

                boolean feasible = pdpService.isFeasible(
                        currentSolution,
                        expectedDistrictCount,
                        expectedCellCount
                );

                if (!feasible) {
                    revertMove(currentSolution, move);
                    totalIterations++;
                    continue;
                }

                double newObjective = pdpService.computeObjective(currentSolution);
                double delta = newObjective - currentObjective;

                boolean accept = delta < 0 || Math.exp(-delta / temperature) > random.nextDouble();

                if (accept) {
                    currentObjective = newObjective;

                    if (currentObjective < bestObjective) {
                        bestSolution = deepCopyDistricts(currentSolution);
                        bestObjective = currentObjective;
                    }
                } else {
                    revertMove(currentSolution, move);
                }

                totalIterations++;
            }

            temperature *= coolingRate;
        }

        return bestSolution;
    }

    /**
     * Generate a move by selecting a boundary cell and moving it
     * to an adjacent district.
     */
    private NeighborMove generateNeighborMove(List<District> districts) {
        Map<String, Integer> cellToDistrict = new HashMap<>();

        for (int dIdx = 0; dIdx < districts.size(); dIdx++) {
            for (GridCell cell : districts.get(dIdx).getCells()) {
                cellToDistrict.put(cellKey(cell), dIdx);
            }
        }

        List<NeighborMove> possibleMoves = new ArrayList<>();

        for (int fromIdx = 0; fromIdx < districts.size(); fromIdx++) {
            District source = districts.get(fromIdx);

            if (source.size() <= 1) {
                continue; // cannot empty a district
            }

            for (GridCell cell : source.getCells()) {
                if (!isBoundaryCell(cell, cellToDistrict, fromIdx)) {
                    continue;
                }

                int[][] dirs = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
                Set<Integer> adjacentDistricts = new HashSet<>();

                for (int[] dir : dirs) {
                    int nx = cell.getX() + dir[0];
                    int ny = cell.getY() + dir[1];

                    if (nx < 0 || nx >= gridRows || ny < 0 || ny >= gridCols) {
                        continue;
                    }

                    Integer toIdx = cellToDistrict.get(nx + "," + ny);
                    if (toIdx != null && toIdx != fromIdx) {
                        adjacentDistricts.add(toIdx);
                    }
                }

                for (Integer toIdx : adjacentDistricts) {
                    possibleMoves.add(new NeighborMove(cell, fromIdx, toIdx));
                }
            }
        }

        if (possibleMoves.isEmpty()) {
            return null;
        }

        return possibleMoves.get(random.nextInt(possibleMoves.size()));
    }

    private boolean isBoundaryCell(GridCell cell, Map<String, Integer> cellToDistrict, int districtId) {
        int[][] dirs = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

        for (int[] dir : dirs) {
            int nx = cell.getX() + dir[0];
            int ny = cell.getY() + dir[1];

            if (nx < 0 || nx >= gridRows || ny < 0 || ny >= gridCols) {
                return true;
            }

            Integer otherDistrict = cellToDistrict.get(nx + "," + ny);
            if (otherDistrict != null && otherDistrict != districtId) {
                return true;
            }
        }

        return false;
    }

    private void applyMove(List<District> districts, NeighborMove move) {
        districts.get(move.fromDistrict).removeCell(move.cell);
        districts.get(move.toDistrict).addCell(move.cell);
    }

    private void revertMove(List<District> districts, NeighborMove move) {
        districts.get(move.toDistrict).removeCell(move.cell);
        districts.get(move.fromDistrict).addCell(move.cell);
    }

    private List<District> deepCopyDistricts(List<District> original) {
        List<District> copy = new ArrayList<>();

        for (District d : original) {
            District newDist = new District();
            for (GridCell cell : d.getCells()) {
                newDist.addCell(new GridCell(
                        cell.getX(),
                        cell.getY(),
                        cell.getCrimeCount(),
                        cell.getRiskScore(),
                        cell.getStreetLength()
                ));
            }
            copy.add(newDist);
        }

        return copy;
    }

    private String cellKey(GridCell cell) {
        return cell.getX() + "," + cell.getY();
    }

    private static class NeighborMove {
        GridCell cell;
        int fromDistrict;
        int toDistrict;

        NeighborMove(GridCell cell, int fromDistrict, int toDistrict) {
            this.cell = cell;
            this.fromDistrict = fromDistrict;
            this.toDistrict = toDistrict;
        }
    }
}