package ru.copperside.sbprouter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "sbp-router")
public class SbpRouterProperties {

    private Backend backend = new Backend();
    private Kafka kafka = new Kafka();

    public Backend getBackend() { return backend; }
    public void setBackend(Backend backend) { this.backend = backend; }
    public Kafka getKafka() { return kafka; }
    public void setKafka(Kafka kafka) { this.kafka = kafka; }

    /** The single upstream backend every GCSvc request is proxied to (flat pass-through proxy). */
    public static class Backend {
        private String url;
        private Duration timeout = Duration.ofSeconds(30);
        private RetryConfig retry = new RetryConfig();
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
        public RetryConfig getRetry() { return retry; }
        public void setRetry(RetryConfig retry) { this.retry = retry; }
    }

    public static class RetryConfig {
        private int maxAttempts = 2;
        private Duration backoff = Duration.ofMillis(500);
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public Duration getBackoff() { return backoff; }
        public void setBackoff(Duration backoff) { this.backoff = backoff; }
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
}
