package com.police.districting.client;

import com.police.districting.model.CrimeRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Component
public class ChicagoDataClient {

    private static final String CRIMES_ENDPOINT = "/resource/ijzp-q8t2.json";

    @Autowired
    private WebClient webClient;

    public Flux<CrimeRecord> fetchCrimesByYear(int year, int limit) {
        // Build query parameters: where year = ? and latitude is not null
        String whereClause = String.format("year=%d AND latitude IS NOT NULL AND longitude IS NOT NULL", year);
        
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(CRIMES_ENDPOINT)
                        .queryParam("$where", whereClause)
                        .queryParam("$limit", limit)
                        .queryParam("$order", "date DESC")
                        .build())
                .retrieve()
                .bodyToFlux(Map.class)
                .map(this::mapToCrimeRecord);
    }

    private CrimeRecord mapToCrimeRecord(Map<String, Object> raw) {
        CrimeRecord record = new CrimeRecord();
        record.setId((String) raw.get("id"));
        record.setCaseNumber((String) raw.get("case_number"));
        
        // Parse date: e.g., "2023-12-25T14:30:00.000"
        String dateStr = (String) raw.get("date");
        if (dateStr != null) {
            record.setDate(LocalDateTime.parse(dateStr, DateTimeFormatter.ISO_DATE_TIME));
        }
        
        record.setBlock((String) raw.get("block"));
        record.setPrimaryType((String) raw.get("primary_type"));
        record.setDescription((String) raw.get("description"));
        
        // Latitude and longitude come as strings or numbers
        Object latObj = raw.get("latitude");
        Object lonObj = raw.get("longitude");
        record.setLatitude(latObj instanceof Number ? ((Number) latObj).doubleValue() : Double.parseDouble((String) latObj));
        record.setLongitude(lonObj instanceof Number ? ((Number) lonObj).doubleValue() : Double.parseDouble((String) lonObj));
        
        record.setWard(raw.get("ward") != null ? raw.get("ward").toString() : null);
        record.setCommunityArea(raw.get("community_area") != null ? raw.get("community_area").toString() : null);
        
        record.setArrest(Boolean.TRUE.equals(raw.get("arrest")));
        record.setDomestic(Boolean.TRUE.equals(raw.get("domestic")));
        
        return record;
    }
}