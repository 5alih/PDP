package com.police.districting.service;

import com.police.districting.client.ChicagoDataClient;
import com.police.districting.model.CrimeRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class ChicagoDataService {

    @Autowired
    private ChicagoDataClient crimeClient;

    public Flux<CrimeRecord> getCrimesForYear(int year, int maxRecords) {
        return crimeClient.fetchCrimesByYear(year, maxRecords);
    }
}