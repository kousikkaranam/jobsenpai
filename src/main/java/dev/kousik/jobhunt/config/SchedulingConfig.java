package dev.kousik.jobhunt.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling is switched on separately from the scheduled beans themselves, so
 * that tests can turn the whole thing off with one property rather than having
 * a nightly board sweep fire in the middle of a Testcontainers run.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "jobhunt.sweep.enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
public class SchedulingConfig {
}
