package com.police.districting.client;

import com.police.districting.util.DataPreprocessor.GridBounds;
import com.police.districting.util.GeoUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class OsmStreetDataClient {

    private static final String OVERPASS_URL = "https://overpass-api.de/api/interpreter";

    private final WebClient webClient;

    // Cache the last successful result so transient OSM failures don't silently
    // fall back to 400 cells and skip void removal.
    private Map<String, Double> cachedStreetLengths = null;

    public OsmStreetDataClient() {
        this.webClient = WebClient.builder()
                .baseUrl(OVERPASS_URL)
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(config -> config.defaultCodecs().maxInMemorySize(64 * 1024 * 1024))
                        .build())
                .build();
    }

    /**
     * Queries the Overpass API for all highway ways within the grid bounding box,
     * then sums each road segment's length (Haversine) into the grid cell that
     * contains the segment's midpoint.
     *
     * Returns an empty map on any network/parse error so callers can fall back
     * to the uniform cell-count area approximation.
     */
    public Mono<Map<String, Double>> fetchStreetLengths(GridBounds bounds, int gridSize) {
        // Query by bounding box — the Chicago area OSM approach was tried but removed too many
        // interior cells (parks, airport, industrial areas with no public roads), leaving only
        // ~227 cells in a fragmented shape that the convexity constraint cannot satisfy.
        String query = String.format(
                java.util.Locale.ROOT,
                "[out:json][timeout:60];"
                + "way[\"highway\"][\"highway\"!~\"footway|cycleway|path|pedestrian|steps|track|service\"]"
                + "(%f,%f,%f,%f);out geom;",
                bounds.minLat, bounds.minLon, bounds.maxLat, bounds.maxLon
        );

        log.info("OSM: fetching street data for bbox [{},{}] -> [{},{}]",
                String.format("%.4f", bounds.minLat), String.format("%.4f", bounds.minLon),
                String.format("%.4f", bounds.maxLat), String.format("%.4f", bounds.maxLon));

        if (cachedStreetLengths != null) {
            log.info("OSM: using cached street data ({} cells)", cachedStreetLengths.size());
            return Mono.just(cachedStreetLengths);
        }

        return webClient.post()
                .body(BodyInserters.fromFormData("data", query))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .map(response -> parseStreetLengths(response, bounds, gridSize))
                .defaultIfEmpty(Collections.emptyMap())
                .doOnSuccess(result -> {
                    if (!result.isEmpty()) {
                        cachedStreetLengths = result;
                        log.info("OSM: got street data for {} cells, total={}m (cached for future requests)",
                                result.size(),
                                String.format("%.1f", result.values().stream().mapToDouble(Double::doubleValue).sum()));
                    }
                })
                .onErrorResume(e -> {
                    if (cachedStreetLengths != null) {
                        log.warn("OSM: fetch failed ({}), using cached data", e.getMessage());
                        return Mono.just(cachedStreetLengths);
                    }
                    log.warn("OSM: fetch failed ({}), no cache available — void removal will be skipped", e.getMessage());
                    return Mono.just(Collections.emptyMap());
                });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Double> parseStreetLengths(Map<String, Object> response,
                                                    GridBounds bounds, int gridSize) {
        Map<String, Double> cellLengths = new HashMap<>();
        try {
            List<Map<String, Object>> elements =
                    (List<Map<String, Object>>) response.get("elements");
            if (elements == null) return cellLengths;
            log.info("OSM: parsing {} road elements", elements.size());

            double latStep = bounds.getLatBinSize();
            double lonStep = bounds.getLonBinSize();

            for (Map<String, Object> element : elements) {
                List<Map<String, Object>> geometry =
                        (List<Map<String, Object>>) element.get("geometry");
                if (geometry == null || geometry.size() < 2) continue;

                for (int i = 0; i < geometry.size() - 1; i++) {
                    double lat1 = ((Number) geometry.get(i).get("lat")).doubleValue();
                    double lon1 = ((Number) geometry.get(i).get("lon")).doubleValue();
                    double lat2 = ((Number) geometry.get(i + 1).get("lat")).doubleValue();
                    double lon2 = ((Number) geometry.get(i + 1).get("lon")).doubleValue();

                    double segmentLength = GeoUtils.haversine(lat1, lon1, lat2, lon2);

                    // Attribute the segment to the cell containing its midpoint
                    double midLat = (lat1 + lat2) / 2.0;
                    double midLon = (lon1 + lon2) / 2.0;

                    if (midLat < bounds.minLat || midLat > bounds.maxLat
                            || midLon < bounds.minLon || midLon > bounds.maxLon) {
                        continue;
                    }

                    int x = Math.min((int) ((midLat - bounds.minLat) / latStep), gridSize - 1);
                    int y = Math.min((int) ((midLon - bounds.minLon) / lonStep), gridSize - 1);
                    cellLengths.merge(x + "," + y, segmentLength, Double::sum);
                }
            }
        } catch (Exception ignored) {
            // Return whatever was collected; areaRatio() falls back to cell count if empty
        }
        return cellLengths;
    }
}
