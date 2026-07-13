package com.workshopper.service;
import org.junit.jupiter.api.Test;
public class TestPptxEnums {
    @Test
    public void printEnums() {
        for (org.apache.poi.sl.usermodel.Placeholder p : org.apache.poi.sl.usermodel.Placeholder.values()) {
            System.out.println(p.name());
        }
    }
}
