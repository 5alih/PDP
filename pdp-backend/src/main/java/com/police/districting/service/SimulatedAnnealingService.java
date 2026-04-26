package com.police.districting.service;

import com.police.districting.model.District;
import com.police.districting.model.GridCell;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
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

        /*
         * PDP service is used only for objective/workload evaluation.
         * SA does not use PDP's greedy/local-search initial solution.
         */
        pdpService.initializeForEvaluation(cells, numDistricts);

        log.info("SA starting independently: numDistricts={}, cells={}, grid={}x{}, timeLimitMs={}",
                numDistricts, cells.size(), gridRows, gridCols, timeLimitMillis);

        long startTime = System.currentTimeMillis();

        /*
         * Build independent initial solution.
         * This replaces D'Amico's original call-based setting with our
         * Chicago crime-grid representation, but keeps the SA search structure.
         */
        List<District> currentSolution = null;

        int initialAttempts = 0;
        while ((currentSolution == null || currentSolution.isEmpty())
                && initialAttempts < 3
                && System.currentTimeMillis() - startTime < timeLimitMillis) {

            List<District> candidate = buildIndependentInitialSolution(cells, numDistricts);
            initialAttempts++;

            if (!candidate.isEmpty()
                    && pdpService.isFeasible(candidate, expectedDistrictCount, expectedCellCount)) {
                currentSolution = candidate;
            }
        }

        if (currentSolution == null || currentSolution.isEmpty()) {
            log.warn("SA could not build an independent feasible initial solution after {} attempts",
                    initialAttempts);
            return Collections.emptyList();
        }

        double currentObjective = pdpService.computeObjective(currentSolution);

        List<District> bestSolution = deepCopyDistricts(currentSolution);
        double bestObjective = currentObjective;

        double temperature = initialTemp;
        int totalIterations = 0;

        int generatedMoves = 0;
        int infeasibleMoves = 0;
        int acceptedMoves = 0;
        int improvedMoves = 0;

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

                generatedMoves++;

                applyMove(currentSolution, move);

                boolean feasible = pdpService.isFeasible(
                        currentSolution,
                        expectedDistrictCount,
                        expectedCellCount
                );

                if (!feasible) {
                    revertMove(currentSolution, move);
                    infeasibleMoves++;
                    totalIterations++;
                    continue;
                }

                double newObjective = pdpService.computeObjective(currentSolution);
                double delta = newObjective - currentObjective;

                /*
                 * Standard simulated annealing acceptance rule.
                 * Better moves are accepted directly.
                 * Worse moves can be accepted depending on temperature.
                 */
                boolean accept = delta < 0 || Math.exp(-delta / temperature) > random.nextDouble();

                if (accept) {
                    currentObjective = newObjective;
                    acceptedMoves++;

                    if (currentObjective < bestObjective) {
                        bestSolution = deepCopyDistricts(currentSolution);
                        bestObjective = currentObjective;
                        improvedMoves++;
                    }
                } else {
                    revertMove(currentSolution, move);
                }

                totalIterations++;
            }

            temperature *= coolingRate;
        }

        log.info("SA done: initialAttempts={}, iterations={}, generatedMoves={}, infeasibleMoves={}, acceptedMoves={}, improvedMoves={}, bestObjective={}",
                initialAttempts,
                totalIterations,
                generatedMoves,
                infeasibleMoves,
                acceptedMoves,
                improvedMoves,
                String.format("%.6f", bestObjective));

        return bestSolution;
    }

    private List<District> buildIndependentInitialSolution(List<GridCell> cells, int numDistricts) {
        if (cells == null || cells.isEmpty()) {
            return Collections.emptyList();
        }

        /*
        * Structured independent initial solution for SA.
        *
        * This does not use PDP's greedy/local-search initialization.
        * It creates a simple ordered partition as a starting point for SA.
        *
        * Since Chicago's active grid is vertically elongated, sorting primarily by X
        * gives more regular north-south slices. The SA phase then improves this
        * starting partition through boundary-cell moves.
        */
        List<GridCell> sortedCells = new ArrayList<>(cells);

        sortedCells.sort(
                Comparator
                        .comparingInt(GridCell::getX)
                        .thenComparingInt(GridCell::getY)
        );

        List<District> districts = new ArrayList<>();
        for (int i = 0; i < numDistricts; i++) {
            districts.add(new District());
        }

        int total = sortedCells.size();

        for (int i = 0; i < total; i++) {
            int districtIndex = (int) Math.floor((double) i * numDistricts / total);
            districtIndex = Math.min(districtIndex, numDistricts - 1);
            districts.get(districtIndex).addCell(sortedCells.get(i));
        }

        /*
        * If this simple slicing is not feasible because of removed void cells,
        * try the opposite orientation.
        */
        if (pdpService.isFeasible(districts, expectedDistrictCount, expectedCellCount)) {
            return districts;
        }

        sortedCells.sort(
                Comparator
                        .comparingInt(GridCell::getY)
                        .thenComparingInt(GridCell::getX)
        );

        districts = new ArrayList<>();
        for (int i = 0; i < numDistricts; i++) {
            districts.add(new District());
        }

        for (int i = 0; i < total; i++) {
            int districtIndex = (int) Math.floor((double) i * numDistricts / total);
            districtIndex = Math.min(districtIndex, numDistricts - 1);
            districts.get(districtIndex).addCell(sortedCells.get(i));
        }

        if (pdpService.isFeasible(districts, expectedDistrictCount, expectedCellCount)) {
            return districts;
        }

        /*
        * If both structured orientations fail, return empty so runSa can retry.
        * Keeping this empty return is important because the caller already has
        * retry logic.
        */
        return Collections.emptyList();
    }

    /**
     * Generate a neighboring solution by moving one boundary cell
     * from its current district to an adjacent district.
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
                continue;
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

    private boolean isBoundaryCell(GridCell cell,
                                   Map<String, Integer> cellToDistrict,
                                   int districtId) {

        int[][] dirs = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

        for (int[] dir : dirs) {
            int nx = cell.getX() + dir[0];
            int ny = cell.getY() + dir[1];

            Integer otherDistrict = cellToDistrict.get(nx + "," + ny);

            /*
             * Neighbor does not exist in active grid.
             * This means the cell is on the external active-grid boundary.
             */
            if (otherDistrict == null) {
                return true;
            }

            if (otherDistrict != districtId) {
                return true;
            }
        }

        return false;
    }

    private boolean isNeighborOfDistrict(GridCell cell, District district) {
        for (GridCell other : district.getCells()) {
            if (isNeighbor(cell, other)) {
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