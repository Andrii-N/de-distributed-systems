package com.distsys.replicatedlog.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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


}
