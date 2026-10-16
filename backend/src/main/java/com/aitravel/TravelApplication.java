package com.aitravel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(TravelSettings.class)
public class TravelApplication {
    public static void main(String[] args) { SpringApplication.run(TravelApplication.class, args); }
}
