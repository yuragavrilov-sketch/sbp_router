package ru.copperside.sbprouter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "sbp-router")
public class SbpRouterProperties {

    /** Name of the group all traffic is forwarded to until switched at runtime. */
    private String activeGroup = "default";
    /** Named backend groups; one is active at a time. */
    private Map<String, Group> groups = new LinkedHashMap<>();
    /** Per-attempt response timeout. */
    private Duration timeout = Duration.ofSeconds(30);
    private Failover failover = new Failover();
    private CircuitBreaker circuitBreaker = new CircuitBreaker();
    private Kafka kafka = new Kafka();
    private Heartbeat heartbeat = new Heartbeat();
    private RoutingConfig routingConfig = new RoutingConfig();
    private AuthPay authPay = new AuthPay();

    public String getActiveGroup() { return activeGroup; }
    public void setActiveGroup(String activeGroup) { this.activeGroup = activeGroup; }
    public Map<String, Group> getGroups() { return groups; }
    public void setGroups(Map<String, Group> groups) { this.groups = groups; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public Failover getFailover() { return failover; }
    public void setFailover(Failover failover) { this.failover = failover; }
    public CircuitBreaker getCircuitBreaker() { return circuitBreaker; }
    public void setCircuitBreaker(CircuitBreaker circuitBreaker) { this.circuitBreaker = circuitBreaker; }
    public Kafka getKafka() { return kafka; }
    public void setKafka(Kafka kafka) { this.kafka = kafka; }
    public Heartbeat getHeartbeat() { return heartbeat; }
    public void setHeartbeat(Heartbeat heartbeat) { this.heartbeat = heartbeat; }
    public RoutingConfig getRoutingConfig() { return routingConfig; }
    public void setRoutingConfig(RoutingConfig routingConfig) { this.routingConfig = routingConfig; }
    public AuthPay getAuthPay() { return authPay; }
    public void setAuthPay(AuthPay authPay) { this.authPay = authPay; }

    /** A group is just an ordered set of backend URLs. */
    public static class Group {
        private List<String> backends = new ArrayList<>();
        public List<String> getBackends() { return backends; }
        public void setBackends(List<String> backends) { this.backends = backends; }
    }

    public static class Failover {
        /** K: max distinct backends tried per request before giving up. */
        private int maxAttempts = 2;
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    }

    public static class CircuitBreaker {
        /** N consecutive transport errors before a backend is banned. */
        private int failureThreshold = 3;
        /** How long a banned backend stays out of rotation. */
        private Duration banDuration = Duration.ofSeconds(30);
        public int getFailureThreshold() { return failureThreshold; }
        public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }
        public Duration getBanDuration() { return banDuration; }
        public void setBanDuration(Duration banDuration) { this.banDuration = banDuration; }
    }

    public static class Kafka {
        private boolean enabled = false;
        private String bootstrapServers = "localhost:9092";
        private String topic = "sbp-router-traffic";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBootstrapServers() { return bootstrapServers; }
        public void setBootstrapServers(String bootstrapServers) { this.bootstrapServers = bootstrapServers; }
        public String getTopic() { return topic; }
        public void setTopic(String topic) { this.topic = topic; }
    }

    public static class Heartbeat {
        private boolean enabled = false;
        private String topic = "sbp-router-heartbeat";
        private java.time.Duration interval = java.time.Duration.ofSeconds(15);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getTopic() { return topic; }
        public void setTopic(String topic) { this.topic = topic; }
        public java.time.Duration getInterval() { return interval; }
        public void setInterval(java.time.Duration interval) { this.interval = interval; }
    }

    public static class RoutingConfig {
        private boolean enabled = false;
        private String topic = "sbp-router-routing-config";
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getTopic() { return topic; }
        public void setTopic(String topic) { this.topic = topic; }
    }

    /** Optional route: when enabled, ReqAuthPay messages go to this backend pool. */
    public static class AuthPay {
        private boolean enabled = false;
        private List<String> backends = new ArrayList<>();
        private Integer timeoutMs;
        private java.util.List<String> sbpOperations = new java.util.ArrayList<>();
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getBackends() { return backends; }
        public void setBackends(List<String> backends) { this.backends = backends; }
        public Integer getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(Integer timeoutMs) { this.timeoutMs = timeoutMs; }
        public java.util.List<String> getSbpOperations() { return sbpOperations; }
        public void setSbpOperations(java.util.List<String> sbpOperations) { this.sbpOperations = sbpOperations; }
    }
}
