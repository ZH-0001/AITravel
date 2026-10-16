package com.aitravel;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("travel")
public record TravelSettings(String mode, String deepseekUrl, String deepseekKey, String model,
        String searchUrl, String searchKey, int workers, int queueSize, int perMinute,
        int phaseSeconds, int taskSeconds, String adminUsername, String adminPassword,
        String inputPricePerMillion, String outputPricePerMillion, String searchPricePerRequest,
        String priceCurrency) {
    public boolean demo() { return "demo".equals(mode); }
}
