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

    /**
     * Run Simulated Annealing to improve an initial solution.
     * @param cells all grid cells
     * @param numDistricts number of districts (p)
     * @param initialTemp starting temperature
     * @param coolingRate multiplicative cooling factor (e.g., 0.95)
     * @param iterationsPerTemp number of iterations at each temperature
     * @param maxIterations total iteration limit
     * @return optimized list of districts
     */
    public List<District> runSa(List<GridCell> cells, int numDistricts,
                                double initialTemp, double coolingRate,
                                int iterationsPerTemp, int maxIterations) {
        // Determine grid dimensions
        int maxX = cells.stream().mapToInt(GridCell::getX).max().orElse(0);
        int maxY = cells.stream().mapToInt(GridCell::getY).max().orElse(0);
        this.gridRows = maxX + 1;
        this.gridCols = maxY + 1;

        // Start from greedy solution
        List<District> currentSolution = pdpService.runPdp(cells, numDistricts);
        double currentObjective = pdpService.computeObjective(currentSolution);

        List<District> bestSolution = deepCopyDistricts(currentSolution);
        double bestObjective = currentObjective;

        double temperature = initialTemp;
        int totalIterations = 0;

        while (temperature > 0.01 && totalIterations < maxIterations) {
            for (int i = 0; i < iterationsPerTemp; i++) {
                // Generate a neighbor solution
                NeighborMove move = generateNeighborMove(currentSolution);
                if (move == null) continue; // no valid move found

                // Apply move temporarily
                applyMove(currentSolution, move);
                double newObjective = pdpService.computeObjective(currentSolution);
                double delta = newObjective - currentObjective;

                if (delta < 0 || Math.exp(-delta / temperature) > random.nextDouble()) {
                    // Accept the move
                    currentObjective = newObjective;
                    if (currentObjective < bestObjective) {
                        bestSolution = deepCopyDistricts(currentSolution);
                        bestObjective = currentObjective;
                    }
                } else {
                    // Reject: revert the move
                    revertMove(currentSolution, move);
                }
                totalIterations++;
                if (totalIterations >= maxIterations) break;
            }
            temperature *= coolingRate;
        }
        return bestSolution;
    }

    /**
     * Generates a valid neighbor move: move a boundary cell from one district to an adjacent district.
     * A boundary cell is one that has at least one neighbor in a different district.
     * The target district must be adjacent (neighbor cell belongs to target district) and not empty.
     */
    private NeighborMove generateNeighborMove(List<District> districts) {
        // Build a mapping from cell coordinates to district index for quick lookup
        Map<String, Integer> cellToDistrict = new HashMap<>();
        for (int dIdx = 0; dIdx < districts.size(); dIdx++) {
            for (GridCell cell : districts.get(dIdx).getCells()) {
                cellToDistrict.put(cell.getX() + "," + cell.getY(), dIdx);
            }
        }

        // Collect all boundary cells and possible moves
        List<NeighborMove> possibleMoves = new ArrayList<>();
        for (int fromIdx = 0; fromIdx < districts.size(); fromIdx++) {
            District district = districts.get(fromIdx);
            for (GridCell cell : district.getCells()) {
                // Check each neighbor of the cell
                int[][] dirs = {{-1,0},{1,0},{0,-1},{0,1}};
                for (int[] dir : dirs) {
                    int nx = cell.getX() + dir[0];
                    int ny = cell.getY() + dir[1];
                    if (nx >= 0 && nx < gridRows && ny >= 0 && ny < gridCols) {
                        String neighborKey = nx + "," + ny;
                        Integer toIdx = cellToDistrict.get(neighborKey);
                        if (toIdx != null && !toIdx.equals(fromIdx)) {
                            // Moving this cell to neighbor's district is allowed
                            possibleMoves.add(new NeighborMove(cell, fromIdx, toIdx));
                        }
                    }
                }
            }
        }

        if (possibleMoves.isEmpty()) return null;
        // Return a random valid move
        return possibleMoves.get(random.nextInt(possibleMoves.size()));
    }

    /**
     * Apply a move: remove cell from source district and add to target district.
     */
    private void applyMove(List<District> districts, NeighborMove move) {
        districts.get(move.fromDistrict).getCells().remove(move.cell);
        districts.get(move.toDistrict).addCell(move.cell);
    }

    /**
     * Revert a move: move cell back from target to source.
     */
    private void revertMove(List<District> districts, NeighborMove move) {
        districts.get(move.toDistrict).getCells().remove(move.cell);
        districts.get(move.fromDistrict).addCell(move.cell);
    }

    /**
     * Deep copy a list of districts.
     */
    private List<District> deepCopyDistricts(List<District> original) {
        List<District> copy = new ArrayList<>();
        for (District d : original) {
            District newDist = new District();
            for (GridCell cell : d.getCells()) {
                newDist.addCell(new GridCell(cell.getX(), cell.getY(), cell.getCrimeCount()));
            }
            copy.add(newDist);
        }
        return copy;
    }

    /**
     * Helper class to represent a move.
     */
    private static class NeighborMove {
        GridCell cell;
        int fromDistrict;
        int toDistrict;
        NeighborMove(GridCell cell, int from, int to) {
            this.cell = cell;
            this.fromDistrict = from;
            this.toDistrict = to;
        }
    }
}