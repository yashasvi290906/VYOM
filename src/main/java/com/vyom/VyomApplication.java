package com.vyom;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class VyomApplication {

    public static void main(String[] args) {
        SpringApplication.run(VyomApplication.class, args);
    }
}

