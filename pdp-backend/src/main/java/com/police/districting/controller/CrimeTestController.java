package com.police.districting.controller;

import com.police.districting.model.CrimeRecord;
import com.police.districting.service.ChicagoDataService;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/test")
public class CrimeTestController {

    @Autowired
    private ChicagoDataService chicagoDataService;

    @GetMapping("/crimes")
    public Flux<CrimeRecord> getTestCrimes(@RequestParam(defaultValue = "2023") int year,
                                           @RequestParam(defaultValue = "100") int limit) {
        return chicagoDataService.getCrimesForYear(year, limit);
    }

	@GetMapping("/crimes/list")
	public List<CrimeRecord> getTestCrimesList(@RequestParam(defaultValue = "2023") int year,
											@RequestParam(defaultValue = "100") int limit) {
		return chicagoDataService.getCrimesForYear(year, limit).collectList().block();
	}
}