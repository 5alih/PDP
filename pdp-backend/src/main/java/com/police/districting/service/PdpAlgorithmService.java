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
    private int currentNumDistricts;
    private List<District> currentSolutionReference = new ArrayList<>();

    // Objective parameters
    private double lambda = 0.5;   // balance between worst district and average district
    private final double wAlpha = 0.2; // area
    private final double wBeta = 0.1;  // isolation / lack of support
    private final double wGamma = 0.5; // demand
    private final double wDelta = 0.2; // diameter

    // Multi-start search count
    // private static final int DEFAULT_RESTARTS = 5;

    private final Random random = new Random();

    public void setLambda(double lambda) {
    if (lambda < 0.0 || lambda > 1.0) {
        throw new IllegalArgumentException("lambda must be between 0 and 1");
    }
    this.lambda = lambda;
    }

    public boolean isFeasible(List<District> districts, int expectedDistrictCount, int expectedCellCount) {
        return isFeasibleSolution(districts, expectedDistrictCount, expectedCellCount);
    }

    public List<District> runPdp(List<GridCell> cells, int numDistricts, long timeLimitMillis) {
        if (cells == null || cells.isEmpty()) {
            return Collections.emptyList();
        }
        if (numDistricts <= 0) {
            throw new IllegalArgumentException("numDistricts must be > 0");
        }
        if (numDistricts > cells.size()) {
            throw new IllegalArgumentException("numDistricts cannot be greater than total number of cells");
        }

        this.currentNumDistricts = numDistricts;
        initializeContext(cells);

        long startTime = System.currentTimeMillis();

        List<District> bestSolution = null;
        double bestObjective = Double.MAX_VALUE;

        while (System.currentTimeMillis() - startTime < timeLimitMillis) {
            List<District> candidate = buildGreedyInitialSolution(cells, numDistricts);

            if (!isCompleteAssignment(candidate, cells.size())) {
                continue;
            }

            candidate = improveWithLocalSearch(candidate);

            if (!isFeasibleSolution(candidate, numDistricts, cells.size())) {
                continue;
            }

            double obj = computeObjective(candidate);
            if (obj < bestObjective) {
                bestObjective = obj;
                bestSolution = deepCopyDistricts(candidate);
            }
        }

        if (bestSolution == null) {
            return Collections.emptyList();
        }

        this.currentSolutionReference = deepCopyDistricts(bestSolution);
        return bestSolution;
    }

    private void initializeContext(List<GridCell> cells) {
        int maxX = cells.stream().mapToInt(GridCell::getX).max().orElse(0);
        int maxY = cells.stream().mapToInt(GridCell::getY).max().orElse(0);

        this.gridRows = maxX + 1;
        this.gridCols = maxY + 1;
        this.totalDemand = cells.stream().mapToDouble(GridCell::getRiskScore).sum();
        this.totalCells = cells.size();
        this.maxPossibleDistance = Math.max(1, (gridRows - 1) + (gridCols - 1));
    }

    /**
     * Phase 1: Random seed cells
     * Phase 2: Greedy expansion while preserving connectivity
     */
    private List<District> buildGreedyInitialSolution(List<GridCell> allCells, int numDistricts) {
        List<GridCell> shuffled = new ArrayList<>(allCells);
        Collections.shuffle(shuffled, random);

        List<District> districts = new ArrayList<>();
        Set<String> assigned = new HashSet<>();

        // Random initialization: one distinct cell per district
        for (int i = 0; i < numDistricts; i++) {
            District district = new District();
            GridCell seed = shuffled.get(i);
            district.addCell(seed);
            districts.add(district);
            assigned.add(cellKey(seed));
        }

        List<GridCell> remaining = shuffled.subList(numDistricts, shuffled.size())
                .stream()
                .filter(cell -> !assigned.contains(cellKey(cell)))
                .collect(Collectors.toCollection(ArrayList::new));

        // Greedy expansion
        while (!remaining.isEmpty()) {
            GridCell bestCell = null;
            Integer bestDistrictIndex = null;
            double bestObjective = Double.MAX_VALUE;

            for (GridCell cell : remaining) {
                for (int d = 0; d < districts.size(); d++) {
                    District district = districts.get(d);

                    // only grow through adjacency to preserve connectivity
                    if (!isNeighborOfDistrict(cell, district)) {
                        continue;
                    }

                    district.addCell(cell);

                    double obj = Double.MAX_VALUE;
                    if (isPaperStyleConvex(district.getCells())) {
                        obj = computeObjective(districts);
                    }

                    district.removeCell(cell);

                    if (obj < bestObjective) {
                        bestObjective = obj;
                        bestCell = cell;
                        bestDistrictIndex = d;
                    }
                }
            }

            // Fallback: if no adjacent placement found, assign to best possible district
            // This should rarely happen on a full rectangular grid, but prevents dead-end failure.
            if (bestCell == null) {
                GridCell fallbackCell = remaining.get(0);
                int fallbackDistrict = findClosestDistrictIndex(fallbackCell, districts);

                districts.get(fallbackDistrict).addCell(fallbackCell);
                remaining.remove(fallbackCell);
            } else {
                districts.get(bestDistrictIndex).addCell(bestCell);
                remaining.remove(bestCell);
            }
        }

        return districts;
    }

    /**
     * Local search:
     * Move one border cell from one district to a neighboring district
     * if objective improves and source district remains connected.
     */
    private List<District> improveWithLocalSearch(List<District> initialSolution) {
        List<District> current = deepCopyDistricts(initialSolution);
        boolean improved = true;

        while (improved) {
            improved = false;
            double currentObjective = computeObjective(current);

            GridCell bestCell = null;
            Integer fromDistrict = null;
            Integer toDistrict = null;
            double bestObjective = currentObjective;

            for (int from = 0; from < current.size(); from++) {
                District source = current.get(from);

                for (GridCell cell : new ArrayList<>(source.getCells())) {
                    if (source.size() <= 1) {
                        continue; // no empty district allowed
                    }

                    if (!isBorderCell(cell, source)) {
                        continue;
                    }

                    for (int to = 0; to < current.size(); to++) {
                        if (from == to) {
                            continue;
                        }

                        District target = current.get(to);

                        if (!isNeighborOfDistrict(cell, target)) {
                            continue;
                        }

                        // Apply tentative move
                        source.removeCell(cell);
                        target.addCell(cell);

                        boolean feasible =
                                isConnected(source.getCells()) &&
                                isConnected(target.getCells()) &&
                                isPaperStyleConvex(source.getCells()) &&
                                isPaperStyleConvex(target.getCells());

                        double obj = feasible ? computeObjective(current) : Double.MAX_VALUE;

                        // Rollback
                        target.removeCell(cell);
                        source.addCell(cell);

                        if (feasible && obj < bestObjective) {
                            bestObjective = obj;
                            bestCell = cell;
                            fromDistrict = from;
                            toDistrict = to;
                        }
                    }
                }
            }

            if (bestCell != null) {
                current.get(fromDistrict).removeCell(bestCell);
                current.get(toDistrict).addCell(bestCell);
                improved = true;
            }
        }

        return current;
    }

    private int findClosestDistrictIndex(GridCell cell, List<District> districts) {
        int bestIndex = 0;
        int bestDistance = Integer.MAX_VALUE;

        for (int i = 0; i < districts.size(); i++) {
            District district = districts.get(i);
            for (GridCell districtCell : district.getCells()) {
                int dist = manhattan(cell, districtCell);
                if (dist < bestDistance) {
                    bestDistance = dist;
                    bestIndex = i;
                }
            }
        }
        return bestIndex;
    }

    private boolean isCompleteAssignment(List<District> districts, int expectedCellCount) {
        int assignedCount = districts.stream().mapToInt(District::size).sum();
        return assignedCount == expectedCellCount;
    }

    private boolean isFeasibleSolution(List<District> districts, int expectedDistrictCount, int expectedCellCount) {
        if (districts == null || districts.size() != expectedDistrictCount) {
            return false;
        }

        int totalAssigned = 0;
        Set<String> seen = new HashSet<>();

        for (District district : districts) {
            if (district == null || district.getCells().isEmpty()) {
                return false;
            }

            if (!isConnected(district.getCells())) {
                return false;
            }
            if (!isPaperStyleConvex(district.getCells())) {
                return false;
            }

            for (GridCell cell : district.getCells()) {
                totalAssigned++;
                if (!seen.add(cellKey(cell))) {
                    return false; // duplicate assignment
                }
            }
        }

        return totalAssigned == expectedCellCount;
    }

    private List<District> deepCopyDistricts(List<District> districts) {
        List<District> copy = new ArrayList<>();
        for (District district : districts) {
            copy.add(new District(district.getCells()));
        }
        return copy;
    }

    private String cellKey(GridCell cell) {
        return cell.getX() + "," + cell.getY();
    }

    private boolean isNeighborOfDistrict(GridCell cell, District district) {
        for (GridCell districtCell : district.getCells()) {
            if (isNeighbor(cell, districtCell)) {
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

    private boolean isBorderCell(GridCell cell, District district) {
        Set<String> cellSet = district.getCells().stream()
                .map(this::cellKey)
                .collect(Collectors.toSet());

        int[][] dirs = {{-1,0},{1,0},{0,-1},{0,1}};
        for (int[] dir : dirs) {
            String neighborKey = (cell.getX() + dir[0]) + "," + (cell.getY() + dir[1]);
            if (!cellSet.contains(neighborKey)) {
                return true;
            }
        }
        return false;
    }

    private boolean isConnected(List<GridCell> cells) {
        if (cells == null || cells.isEmpty()) {
            return false;
        }
        if (cells.size() == 1) {
            return true;
        }

        Set<String> cellSet = cells.stream()
                .map(this::cellKey)
                .collect(Collectors.toSet());

        Deque<GridCell> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();

        GridCell start = cells.get(0);
        queue.add(start);
        visited.add(cellKey(start));

        while (!queue.isEmpty()) {
            GridCell current = queue.poll();

            int[][] dirs = {{-1,0},{1,0},{0,-1},{0,1}};
            for (int[] dir : dirs) {
                int nx = current.getX() + dir[0];
                int ny = current.getY() + dir[1];
                String neighborKey = nx + "," + ny;

                if (cellSet.contains(neighborKey) && !visited.contains(neighborKey)) {
                    GridCell neighbor = findCellByCoordinates(cells, nx, ny);
                    if (neighbor != null) {
                        visited.add(neighborKey);
                        queue.add(neighbor);
                    }
                }
            }
        }

        return visited.size() == cells.size();
    }

    private GridCell findCellByCoordinates(List<GridCell> cells, int x, int y) {
        for (GridCell cell : cells) {
            if (cell.getX() == x && cell.getY() == y) {
                return cell;
            }
        }
        return null;
    }

    public double computeObjective(List<District> districts) {
        List<Double> workloads = districts.stream()
                .filter(d -> !d.getCells().isEmpty())
                .map(d -> computeWorkload(d, districts))
                .collect(Collectors.toList());

        double maxW = workloads.stream().max(Double::compareTo).orElse(0.0);
        double avgW = workloads.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        return lambda * maxW + (1.0 - lambda) * avgW;
    }

    public double computeWorkload(District district) {
        return computeWorkload(district, currentSolutionReference);
    }

    private double computeWorkload(District district, List<District> allDistricts) {
        double area = areaRatio(district);
        double isolation = isolationRatio(district, allDistricts);
        double demand = demandRatio(district);
        double diameter = diameterRatio(district);

        return wAlpha * area
                + wBeta * isolation
                + wGamma * demand
                + wDelta * diameter;
    }

    private double areaRatio(District district) {
        if (district.getCells().isEmpty() || totalCells == 0) {
            return 0.0;
        }
        return (double) district.getCells().size() / totalCells;
    }

    /**
     * Paper-inspired isolation:
     * support is computed using distances between district medoids.
     * Isolation decreases when a district has more nearby supporting districts.
     */
    private double isolationRatio(District district, List<District> allDistricts) {
        if (district == null || district.getCells().isEmpty()) {
            return 1.0;
        }

        if (allDistricts == null || allDistricts.size() <= 1) {
            return 1.0;
        }

        GridCell districtMedian = findDistrictMedoid(district);

        int supportCount = 0;
        int k = computeSupportThreshold();

        for (District other : allDistricts) {
            if (other == null || other == district || other.getCells().isEmpty()) {
                continue;
            }

            GridCell otherMedian = findDistrictMedoid(other);
            int dist = manhattan(districtMedian, otherMedian);

            if (dist <= k) {
                supportCount++;
            }
        }

        int pMinusOne = allDistricts.size() - 1;
        if (pMinusOne <= 0) {
            return 1.0;
        }

        return (double) (pMinusOne - supportCount) / pMinusOne;
    }

    private double demandRatio(District district) {
        if (district.getCells().isEmpty() || totalDemand == 0) {
            return 0.0;
        }
        return district.getDemand() / totalDemand;
    }

    private double diameterRatio(District district) {
        if (district.getCells().size() <= 1) {
            return 0.0;
        }

        int maxDist = 0;
        List<GridCell> cells = district.getCells();

        for (int i = 0; i < cells.size(); i++) {
            for (int j = i + 1; j < cells.size(); j++) {
                int dist = manhattan(cells.get(i), cells.get(j));
                if (dist > maxDist) {
                    maxDist = dist;
                }
            }
        }

        return maxDist / maxPossibleDistance;
    }

    private int computeSupportThreshold() {
        if (currentNumDistricts <= 0) {
            return 1;
        }

        return Math.max(1, (int) Math.ceil(
                Math.max(gridRows, gridCols) / Math.sqrt(currentNumDistricts)
        ));
    }

    private GridCell findDistrictMedoid(District district) {
        List<GridCell> cells = district.getCells();

        if (cells == null || cells.isEmpty()) {
            return null;
        }

        GridCell bestCell = cells.get(0);
        int bestSum = Integer.MAX_VALUE;

        for (GridCell candidate : cells) {
            int sumDistances = 0;
            for (GridCell other : cells) {
                sumDistances += manhattan(candidate, other);
            }

            if (sumDistances < bestSum) {
                bestSum = sumDistances;
                bestCell = candidate;
            }
        }

        return bestCell;
    }

    private boolean isOrthogonallyConvex(List<GridCell> cells) {
        if (cells == null || cells.isEmpty()) {
            return false;
        }
        if (cells.size() <= 2) {
            return true;
        }

        Set<String> cellSet = cells.stream()
                .map(this::cellKey)
                .collect(Collectors.toSet());

        // Row-wise convexity:
        // for each row, occupied columns must form one continuous interval
        Map<Integer, List<Integer>> rowToCols = new HashMap<>();
        for (GridCell cell : cells) {
            rowToCols.computeIfAbsent(cell.getX(), k -> new ArrayList<>()).add(cell.getY());
        }

        for (Map.Entry<Integer, List<Integer>> entry : rowToCols.entrySet()) {
            int row = entry.getKey();
            List<Integer> cols = entry.getValue();
            int minCol = cols.stream().min(Integer::compareTo).orElse(0);
            int maxCol = cols.stream().max(Integer::compareTo).orElse(0);

            for (int col = minCol; col <= maxCol; col++) {
                if (!cellSet.contains(row + "," + col)) {
                    return false;
                }
            }
        }

        // Column-wise convexity:
        // for each column, occupied rows must form one continuous interval
        Map<Integer, List<Integer>> colToRows = new HashMap<>();
        for (GridCell cell : cells) {
            colToRows.computeIfAbsent(cell.getY(), k -> new ArrayList<>()).add(cell.getX());
        }

        for (Map.Entry<Integer, List<Integer>> entry : colToRows.entrySet()) {
            int col = entry.getKey();
            List<Integer> rows = entry.getValue();
            int minRow = rows.stream().min(Integer::compareTo).orElse(0);
            int maxRow = rows.stream().max(Integer::compareTo).orElse(0);

            for (int row = minRow; row <= maxRow; row++) {
                if (!cellSet.contains(row + "," + col)) {
                    return false;
                }
            }
        }

        return true;
    }

    private boolean isPaperStyleConvex(List<GridCell> cells) {
        if (cells == null || cells.isEmpty()) {
            return false;
        }
        if (cells.size() <= 2) {
            return true;
        }

        // Fast pre-filter
        if (!isOrthogonallyConvex(cells)) {
            return false;
        }

        for (int i = 0; i < cells.size(); i++) {
            for (int j = i + 1; j < cells.size(); j++) {
                GridCell a = cells.get(i);
                GridCell b = cells.get(j);

                int manhattanDistance = manhattan(a, b);
                int shortestPathDistance = shortestPathWithinDistrict(cells, a, b);

                if (shortestPathDistance == Integer.MAX_VALUE) {
                    return false;
                }

                if (shortestPathDistance > manhattanDistance) {
                    return false;
                }
            }
        }

        return true;
    }

    private int shortestPathWithinDistrict(List<GridCell> cells, GridCell start, GridCell target) {
        Set<String> cellSet = cells.stream()
                .map(this::cellKey)
                .collect(Collectors.toSet());

        Deque<GridCell> queue = new ArrayDeque<>();
        Map<String, Integer> distance = new HashMap<>();

        queue.add(start);
        distance.put(cellKey(start), 0);

        while (!queue.isEmpty()) {
            GridCell current = queue.poll();
            int currentDist = distance.get(cellKey(current));

            if (current.getX() == target.getX() && current.getY() == target.getY()) {
                return currentDist;
            }

            int[][] dirs = {{-1,0},{1,0},{0,-1},{0,1}};
            for (int[] dir : dirs) {
                int nx = current.getX() + dir[0];
                int ny = current.getY() + dir[1];
                String neighborKey = nx + "," + ny;

                if (cellSet.contains(neighborKey) && !distance.containsKey(neighborKey)) {
                    GridCell neighbor = findCellByCoordinates(cells, nx, ny);
                    if (neighbor != null) {
                        distance.put(neighborKey, currentDist + 1);
                        queue.add(neighbor);
                    }
                }
            }
        }

        return Integer.MAX_VALUE;
    }

    private int manhattan(GridCell a, GridCell b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY());
    }
}