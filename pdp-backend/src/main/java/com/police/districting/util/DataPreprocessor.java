package com.police.districting.util;

import com.police.districting.model.CrimeRecord;
import com.police.districting.model.GridCell;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
public class DataPreprocessor {

    public static class GridBounds {
        public final double minLat;
        public final double maxLat;
        public final double minLon;
        public final double maxLon;
        public final int gridSize;

        public GridBounds(double minLat, double maxLat, double minLon, double maxLon, int gridSize) {
            this.minLat = minLat;
            this.maxLat = maxLat;
            this.minLon = minLon;
            this.maxLon = maxLon;
            this.gridSize = gridSize;
        }

        public double getLatBinSize() {
            return (maxLat - minLat) / gridSize;
        }

        public double getLonBinSize() {
            return (maxLon - minLon) / gridSize;
        }
    }

    public static GridBounds computeBounds(List<CrimeRecord> crimes, int gridSize) {
        double minLat = crimes.stream().mapToDouble(CrimeRecord::getLatitude).min().orElse(41.6);
        double maxLat = crimes.stream().mapToDouble(CrimeRecord::getLatitude).max().orElse(42.0);
        double minLon = crimes.stream().mapToDouble(CrimeRecord::getLongitude).min().orElse(-87.9);
        double maxLon = crimes.stream().mapToDouble(CrimeRecord::getLongitude).max().orElse(-87.5);
        return new GridBounds(minLat, maxLat, minLon, maxLon, gridSize);
    }

    public static List<GridCell> createGrid(List<CrimeRecord> crimes, int gridSize) {
        return createGrid(crimes, gridSize, Collections.emptyMap());
    }

    public static List<GridCell> createGrid(List<CrimeRecord> crimes, int gridSize,
                                             Map<String, Double> streetLengths) {
        GridBounds bounds = computeBounds(crimes, gridSize);
        double latStep = bounds.getLatBinSize();
        double lonStep = bounds.getLonBinSize();

        Map<String, Long> cellCounts = crimes.stream().collect(Collectors.groupingBy(
            crime -> {
                int latBin = Math.min((int) ((crime.getLatitude() - bounds.minLat) / latStep), gridSize - 1);
                int lonBin = Math.min((int) ((crime.getLongitude() - bounds.minLon) / lonStep), gridSize - 1);
                return latBin + "," + lonBin;
            },
            Collectors.counting()
        ));

        long maxCount = cellCounts.values().stream().mapToLong(Long::longValue).max().orElse(1L);

        List<GridCell> cells = new ArrayList<>();
        for (int i = 0; i < gridSize; i++) {
            for (int j = 0; j < gridSize; j++) {
                String key = i + "," + j;
                int count = cellCounts.getOrDefault(key, 0L).intValue();
                double riskScore = maxCount == 0 ? 0.0 : (double) count / maxCount;
                double streetLength = streetLengths.getOrDefault(key, 0.0);
                cells.add(new GridCell(i, j, count, riskScore, streetLength));
            }
        }

        // Remove void cells using crime data as the authoritative Chicago boundary indicator.
        // The crime dataset is exclusively Chicago data, so crimeCount > 0 definitively
        // means the cell is inside Chicago.
        // A cell with streets but no crime and no crime-cell neighbours is a suburb
        // (the bounding-box OSM query includes surrounding areas like Evanston/Oak Park).
        // A cell with streets, no crime, but adjacent to crime cells is a valid interior
        // Chicago cell that simply had no recorded crime that year (park, airport, etc.).
        if (!streetLengths.isEmpty()) {
            Set<String> crimeCellKeys = cells.stream()
                    .filter(c -> c.getCrimeCount() > 0)
                    .map(c -> c.getX() + "," + c.getY())
                    .collect(java.util.stream.Collectors.toSet());

            int[][] dirs = {{-1,0},{1,0},{0,-1},{0,1}};
            int before = cells.size();
            cells = cells.stream()
                    .filter(c -> {
                        if (c.getCrimeCount() > 0) return true;    // definitely Chicago
                        if (c.getStreetLength() == 0) return false; // lake / truly empty
                        // Has streets but no crime: keep only if next to a crime cell
                        for (int[] d : dirs) {
                            if (crimeCellKeys.contains((c.getX()+d[0]) + "," + (c.getY()+d[1])))
                                return true;
                        }
                        return false; // suburban cell — no crime, no crime neighbours
                    })
                    .collect(Collectors.toList());
            log.info("Void removal: {} cells removed ({} active cells remain)",
                    before - cells.size(), cells.size());
        }

        long nonZeroCells = cells.stream().filter(c -> c.getCrimeCount() > 0).count();
        long streetCells  = cells.stream().filter(c -> c.getStreetLength() > 0).count();
        log.info("Grid {}x{}: {} active cells, {} with crime, {} with street data (maxCrimeCount={})",
                gridSize, gridSize, cells.size(), nonZeroCells, streetCells, maxCount);

        return cells;
    }
}
