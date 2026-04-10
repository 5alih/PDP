package com.police.districting.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class District {
    private List<GridCell> cells = new ArrayList<>();
    
    public District(List<GridCell> cells) {
        this.cells = new ArrayList<>(cells);
    }
    
    public void addCell(GridCell cell) {
        cells.add(cell);
    }
    
    public void removeCell(GridCell cell) {
        cells.remove(cell);
    }
    
    public int getDemand() {
        return cells.stream().mapToInt(GridCell::getCrimeCount).sum();
    }
    
    public int size() {
        return cells.size();
    }
}