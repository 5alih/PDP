package com.police.districting.util;

import com.police.districting.service.ChicagoDataService.GridCell;

public class GeoUtils {

    public static GridCell toGridCell(double lat, double lon, double cellSizeDeg) {
        double minLat = Math.floor(lat / cellSizeDeg) * cellSizeDeg;
        double minLon = Math.floor(lon / cellSizeDeg) * cellSizeDeg;
        return new GridCell(minLat, minLon);
    }

    // Haversine distance in meters
    public static double haversine(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371000; // Earth radius in meters
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                   Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}