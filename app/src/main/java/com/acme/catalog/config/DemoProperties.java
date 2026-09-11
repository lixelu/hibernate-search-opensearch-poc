package com.acme.catalog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("catalog.demo")
public record DemoProperties(
        @DefaultValue("true") boolean seedEnabled,
        @DefaultValue("20000") int products,
        /** Push every seeded product through the outbox so the relay is exercised end to end. */
        @DefaultValue("true") boolean enqueueAfterSeed) {
}
