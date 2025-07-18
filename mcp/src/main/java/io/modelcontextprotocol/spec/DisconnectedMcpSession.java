package io.modelcontextprotocol.spec;

import com.fasterxml.jackson.core.type.TypeReference;
import reactor.core.publisher.Mono;

public class DisconnectedMcpSession implements McpSession {

    public static final DisconnectedMcpSession INSTANCE = new DisconnectedMcpSession();

    @Override
    public <T> Mono<T> sendRequest(String method, Object requestParams, TypeReference<T> typeRef) {
        return Mono.error(new IllegalStateException("Stream unavailable"));
    }

    @Override
    public Mono<Void> sendNotification(String method, Object params) {
        return Mono.error(new IllegalStateException("Stream unavailable"));
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.empty();
    }

    @Override
    public void close() {
    }
}
