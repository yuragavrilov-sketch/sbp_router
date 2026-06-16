package ru.copperside.sbprouter.proxy;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.web.reactive.function.server.RequestPredicates.POST;

@Configuration
public class GcsvcRouter {
    @Bean
    public RouterFunction<ServerResponse> gcsvcRoute(GcsvcHandler handler) {
        // Match both /api/gcsvc and /api/gcsvc/ — real GCSvc clients send a trailing slash, and
        // Spring 6 / Boot 3+ disable trailing-slash matching by default (otherwise the request
        // falls through to the static-resource handler and 404s).
        return RouterFunctions.route(POST("/api/gcsvc").or(POST("/api/gcsvc/")), handler::handle);
    }
}
