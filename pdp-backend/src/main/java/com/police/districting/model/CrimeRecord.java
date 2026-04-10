package com.police.districting.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CrimeRecord {
    private String id;
    private String caseNumber;
    private LocalDateTime date;
    private String block;
    private String primaryType;
    private String description;
    private double latitude;
    private double longitude;
    private String ward;
    private String communityArea;
    private boolean arrest;
    private boolean domestic;
}