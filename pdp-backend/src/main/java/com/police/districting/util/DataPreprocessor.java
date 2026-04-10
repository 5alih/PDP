package com.police.districting.util;

import com.police.districting.model.CrimeRecord;
import com.police.districting.model.GridCell;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
        GridBounds bounds = computeBounds(crimes, gridSize);
        double latStep = bounds.getLatBinSize();
        double lonStep = bounds.getLonBinSize();

        // Assign each crime to a grid cell
        Map<String, Long> cellCounts = crimes.stream().collect(Collectors.groupingBy(
            crime -> {
                int latBin = Math.min((int) ((crime.getLatitude() - bounds.minLat) / latStep), gridSize - 1);
                int lonBin = Math.min((int) ((crime.getLongitude() - bounds.minLon) / lonStep), gridSize - 1);
                return latBin + "," + lonBin;
            },
            Collectors.counting()
        ));

        List<GridCell> cells = new ArrayList<>();
        for (int i = 0; i < gridSize; i++) {
            for (int j = 0; j < gridSize; j++) {
                String key = i + "," + j;
                int count = cellCounts.getOrDefault(key, 0L).intValue();
                cells.add(new GridCell(i, j, count));
            }
        }
        return cells;
    }
}