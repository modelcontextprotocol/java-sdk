package io.modelcontextprotocol.client.transport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.SynchronousSink;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;

public class WebFluxStreamableClientTransport implements McpClientTransport {

    private static final Logger logger = LoggerFactory.getLogger(WebFluxStreamableClientTransport.class);

    private static final String MESSAGE_EVENT_TYPE = "message";
    private static final String ENDPOINT_EVENT_TYPE = "endpoint";
    private static final String MESSAGE_ENDPOINT = "/message";

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE = new ParameterizedTypeReference<>() {
    };

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private Disposable inboundSubscription;
    private volatile boolean isClosing = false;
    private final Sinks.One<String> messageEndpointSink = Sinks.one();
    private final Sinks.Many<ServerSentEvent<String>> sseSink = Sinks.many().multicast().onBackpressureBuffer();

    private final AtomicReference<String> sessionId = new AtomicReference<>();
    private final AtomicReference<String> lastEventId = new AtomicReference<>();
    private Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler;

    public WebFluxStreamableClientTransport(WebClient.Builder webClientBuilder) {
        this(webClientBuilder, new ObjectMapper());
    }

    public WebFluxStreamableClientTransport(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        Assert.notNull(objectMapper, "ObjectMapper must not be null");
        Assert.notNull(webClientBuilder, "WebClient.Builder must not be null");

        this.objectMapper = objectMapper;
        this.webClient = webClientBuilder.build();
    }

    @Override
    public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
        sessionId.set(null);
        lastEventId.set(null);
        logger.debug("Initializing session for {}", MESSAGE_ENDPOINT);
        this.handler = handler;

        WebClient.RequestBodySpec request = webClient.post()
                .uri(MESSAGE_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON);

        return request
                .exchangeToMono(response -> {
                    if (!response.statusCode().is2xxSuccessful()) {
                        logger.error("Session initialization failed with status: {}", response.statusCode());
                        return response.createException().flatMap(Mono::error);
                    }

                    String newSessionId = response.headers().header("Mcp-Session-Id").stream()
                            .filter(s -> !s.trim().isEmpty())
                            .findFirst()
                            .orElse(null);
                    if (newSessionId != null) {
                        sessionId.set(newSessionId);
                        logger.debug("Session initialized with sessionId: {}", newSessionId);
                    } else {
                        logger.warn("No session ID returned from server");
                    }

                    return response.bodyToMono(String.class);
                })
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(3))
                        .filter(err -> err instanceof IOException || isServerError(err))
                        .doAfterRetry(signal -> logger.debug("Retrying session initialization: {}", signal.totalRetries())))
                .doOnSuccess(v -> logger.debug("Session initialized successfully"))
                .doOnError(error -> logger.error("Error initializing session: {}", error.getMessage()))
                .then()
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> sendMessage(JSONRPCMessage message) {
        if (isClosing) {
            logger.debug("Client is closing, ignoring sendMessage request");
            return Mono.empty();
        }
        try {
            String jsonText = objectMapper.writeValueAsString(message);
            WebClient.RequestBodySpec request = webClient.post()
                    .uri(MESSAGE_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM, new MediaType("application", "json-seq"));

            String currentSessionId = sessionId.get();
            if (currentSessionId != null) {
                request.header("Mcp-Session-Id", currentSessionId);
            }
            String currentLastEventId = lastEventId.get();
            if (currentLastEventId != null) {
                request.header("Mcp-Last-Event-Id", currentLastEventId);
            }
            return request
                    .bodyValue(jsonText)
                    .exchangeToMono(response -> {
                        if (!response.statusCode().is2xxSuccessful()) {
                            logger.error("Connect request failed with status: {}", response.statusCode());
                            return response.createException().flatMap(Mono::error);
                        }

                        String sessionIdHeader = response.headers().header("Mcp-Session-Id").stream().findFirst().orElse(null);
                        if (sessionIdHeader != null && !sessionIdHeader.trim().isEmpty()) {
                            sessionId.set(sessionIdHeader);
                            logger.debug("Received and stored sessionId: {}", sessionIdHeader);
                        } else {
                            logger.debug("No Mcp-Session-Id header in connect response");
                        }

                        var contentType = response.headers().contentType().orElse(MediaType.APPLICATION_JSON);
                        if (contentType.isCompatibleWith(MediaType.TEXT_EVENT_STREAM)) {
                            Flux<ServerSentEvent<String>> sseFlux = response.bodyToFlux(SSE_TYPE);
                            sseFlux.subscribe(sseSink::tryEmitNext);
                            return processSseEvents(handler);
                        } else if (contentType.isCompatibleWith(new MediaType("application", "json-seq"))) {
                            return handleJsonSeqResponse(response, handler);
                        } else {
                            return handleHttpResponse(response, handler);
                        }
                    })
                    .retryWhen(Retry.backoff(3, Duration.ofSeconds(3))
                            .filter(err -> err instanceof IOException || isServerError(err))
                            .doAfterRetry(signal -> logger.debug("Retrying message send: {}", signal.totalRetries())))
                    .doOnSuccess(v -> logger.debug("Message sent successfully"))
                    .doOnError(error -> {
                        if (!isClosing) {
                            logger.error("Error sending message: {}", error.getMessage());
                        }
                    });
        } catch (IOException e) {
            if (!isClosing) {
                logger.error("Failed to serialize message", e);
                return Mono.error(new RuntimeException("Failed to serialize message", e));
            }
            return Mono.empty();
        }
    }

    private Mono<Void> processSseEvents(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
        this.inboundSubscription = sseSink.asFlux().concatMap(event -> Mono.just(event).<JSONRPCMessage>handle((e, s) -> {
            if (ENDPOINT_EVENT_TYPE.equals(event.event())) {
                String messageEndpointUri = event.data();
                if (messageEndpointSink.tryEmitValue(messageEndpointUri).isSuccess()) {
                    logger.debug("Received endpoint URI: {}", messageEndpointUri);
                    s.complete();
                } else {
                    s.error(new McpError("Failed to handle SSE endpoint event"));
                }
            } else if (MESSAGE_EVENT_TYPE.equals(event.event())) {
                try {
                    JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(this.objectMapper, event.data());
                    s.next(message);
                } catch (IOException ioException) {
                    s.error(ioException);
                }
            } else {
                logger.warn("Received unrecognized SSE event type: {}", event.event());
                s.complete();
            }
        }).transform(handler).flatMap(this::sendMessage)).subscribe();

        return messageEndpointSink.asMono().then();
    }

    private Mono<Void> handleJsonSeqResponse(ClientResponse response, Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
        return response.bodyToFlux(String.class)
                .flatMap(jsonLine -> {
                    try {
                        JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, jsonLine);
                        return handler.apply(Mono.just(message))
                                .onErrorResume(e -> {
                                    logger.error("Error processing message", e);
                                    return Mono.error(e);
                                });
                    } catch (IOException e) {
                        logger.error("Error processing JSON-seq line: {}", jsonLine, e);
                        return Mono.empty();
                    }
                })
                .then();
    }

    private Mono<Void> handleHttpResponse(ClientResponse response, Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
        return response.bodyToMono(String.class)
                .flatMap(body -> {
                    try {
                        JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, body);
                        return handler.apply(Mono.just(message))
                                .then(Mono.empty());
                    } catch (IOException e) {
                        logger.error("Error processing HTTP response body: {}", body, e);
                        return Mono.error(e);
                    }
                });
    }


    public Mono<Void> connectWithGet(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
        sessionId.set(null);
        Flux<ServerSentEvent<String>> getSseFlux = this.webClient
                .get()
                .uri(MESSAGE_ENDPOINT)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(SSE_TYPE)
                .retryWhen(Retry.from(retrySignal -> retrySignal.handle(inboundRetryHandler)));

        getSseFlux.subscribe(sseSink::tryEmitNext);
        return processSseEvents(handler);
    }

    private boolean isServerError(Throwable err) {
        return err instanceof WebClientResponseException && ((WebClientResponseException) err).getStatusCode().is5xxServerError();
    }

    private BiConsumer<Retry.RetrySignal, SynchronousSink<Object>> inboundRetryHandler = (retrySpec, sink) -> {
        if (isClosing) {
            logger.debug("SSE connection closed during shutdown");
            sink.error(retrySpec.failure());
            return;
        }
        if (retrySpec.failure() instanceof IOException || isServerError(retrySpec.failure())) {
            logger.debug("Retrying SSE connection after error");
            sink.next(retrySpec);
            return;
        }
        logger.error("Fatal SSE error, not retrying: {}", retrySpec.failure().getMessage());
        sink.error(retrySpec.failure());
    };

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.fromRunnable(() -> {
            this.isClosing = true;
            this.sessionId.set(null);
            this.lastEventId.set(null);
            if (this.inboundSubscription != null) {
                this.inboundSubscription.dispose();
            }
            this.sseSink.tryEmitComplete();
        }).then().subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
        return this.objectMapper.convertValue(data, typeRef);
    }

    public static class Builder {
        private final WebClient.Builder webClientBuilder;
        private ObjectMapper objectMapper = new ObjectMapper();

        public Builder(WebClient.Builder webClientBuilder) {
            Assert.notNull(webClientBuilder, "WebClient.Builder must not be null");
            this.webClientBuilder = webClientBuilder;
        }

        public Builder objectMapper(ObjectMapper objectMapper) {
            Assert.notNull(objectMapper, "objectMapper must not be null");
            this.objectMapper = objectMapper;
            return this;
        }

        public WebFluxStreamableClientTransport build() {
            return new WebFluxStreamableClientTransport(webClientBuilder, objectMapper);
        }
    }
}