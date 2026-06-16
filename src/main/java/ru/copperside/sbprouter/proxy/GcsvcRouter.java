package ru.copperside.sbprouter.proxy;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

@Configuration
public class GcsvcRouter {

    private static final String GCSVC_PATH = "/api/gcsvc";

    @Bean
    public RouterFunction<ServerResponse> gcsvcRoute(GcsvcHandler handler) {
        // Match POST /api/gcsvc with any number of trailing slashes — real GCSvc clients send a
        // trailing slash, and Spring 6 / Boot 3+ disable trailing-slash matching by default
        // (otherwise such a request falls through to the static-resource handler and 404s).
        return RouterFunctions.route(GcsvcRouter::isGcsvcPost, handler::handle);
    }

    private static boolean isGcsvcPost(ServerRequest request) {
        return HttpMethod.POST.equals(request.method())
                && GCSVC_PATH.equals(stripTrailingSlashes(request.path()));
    }

    private static String stripTrailingSlashes(String path) {
        int end = path.length();
        while (end > 1 && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(0, end);
    }
}
