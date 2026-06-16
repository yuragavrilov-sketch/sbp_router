package ru.copperside.sbprouter.balancing;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class BalancingConfig {

    /** System UTC clock; tests inject a fixed Clock into LoadBalancer/ProxyClient directly. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
