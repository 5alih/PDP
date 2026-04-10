package com.police.districting.service;

import com.police.districting.client.ChicagoDataClient;
import com.police.districting.model.CrimeRecord;
import com.police.districting.util.GeoUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ChicagoDataService {

    @Autowired
    private ChicagoDataClient crimeClient;

    public Flux<CrimeRecord> getCrimesForYear(int year, int maxRecords) {
        return crimeClient.fetchCrimesByYear(year, maxRecords);
    }

    // Aggregate crime counts into a grid for algorithm input
    // Grid cells defined by lat/lon boundaries and cell size (e.g., 0.01 degrees ~ 1km)
    public Mono<Map<GridCell, Integer>> getCrimeCountsByGrid(int year, int maxRecords, double cellSizeDegrees) {
        return getCrimesForYear(year, maxRecords)
                .collectList()
                .map(crimes -> crimes.stream()
                        .collect(Collectors.groupingBy(
                                crime -> GeoUtils.toGridCell(crime.getLatitude(), crime.getLongitude(), cellSizeDegrees),
                                Collectors.summingInt(c -> 1)
                        )));
    }

    // Simple container for grid cell coordinates
    public static class GridCell {
        public final double minLat;
        public final double minLon;
        public GridCell(double minLat, double minLon) {
            this.minLat = minLat;
            this.minLon = minLon;
        }
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof GridCell)) return false;
            GridCell other = (GridCell) o;
            return Double.compare(minLat, other.minLat) == 0 && Double.compare(minLon, other.minLon) == 0;
        }
        @Override
        public int hashCode() {
            return 31 * Double.hashCode(minLat) + Double.hashCode(minLon);
        }
    }
}