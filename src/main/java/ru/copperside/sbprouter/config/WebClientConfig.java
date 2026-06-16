package ru.copperside.sbprouter.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
public class WebClientConfig {
    @Bean
    public WebClient proxyWebClient() {
        // No global responseTimeout here: ProxyClient sets a per-request responseTimeout (from
        // sbp-router.timeout) via .httpRequest(...), which always wins. Only the connect timeout
        // is a shared, always-effective ceiling.
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000);
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
