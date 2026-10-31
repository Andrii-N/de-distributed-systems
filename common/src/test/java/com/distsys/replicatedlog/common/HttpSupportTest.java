package com.distsys.replicatedlog.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

public class HttpSupportTest {

    @Test 
    void parsePortPassValidNumber() {
        assertEquals(8080, HttpSupport.parsePort("8080"));
    }
 
    @Test 
    void parsePortRejectsCharLiterals() {
        assertThrows(IllegalArgumentException.class, () -> HttpSupport.parsePort("qwerty"));

        assertThrows(IllegalArgumentException.class, () -> HttpSupport.parsePort("_1"));
    }

    @Test
    void parsePortRejectsNumberOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> HttpSupport.parsePort("65536"));

        assertThrows(IllegalArgumentException.class, () -> HttpSupport.parsePort("0"));
    }

    @Test
    void parseSecondariesReadsLogicalNamesInConfigurationOrder() {
        Map<String, String> secondaries = HttpSupport.parseSecondaries(
                "secondary2=http://secondary2:8080, secondary1=http://secondary1:8080");

        assertEquals(List.of("secondary2", "secondary1"), List.copyOf(secondaries.keySet()));
        assertEquals("http://secondary1:8080", secondaries.get("secondary1"));
    }

    @Test
    void parseSecondariesNamesUnnamedEntriesByPosition() {
        Map<String, String> secondaries = HttpSupport.parseSecondaries("http://localhost:8081,http://localhost:8082");

        assertEquals(Map.of("secondary1", "http://localhost:8081", "secondary2", "http://localhost:8082"), secondaries);
    }

    @Test
    void parseSecondariesRejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> HttpSupport.parseSecondaries(" , "));
        assertThrows(IllegalArgumentException.class, () -> HttpSupport.parseSecondaries("=http://localhost:8081"));
        assertThrows(IllegalArgumentException.class, () -> HttpSupport.parseSecondaries("secondary1="));
        assertThrows(IllegalArgumentException.class,
                () -> HttpSupport.parseSecondaries("s1=http://localhost:8081,s1=http://localhost:8082"));
    }


}
