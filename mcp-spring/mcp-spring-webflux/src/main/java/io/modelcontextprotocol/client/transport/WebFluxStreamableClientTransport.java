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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;


/**
 * <p>
 * The connection process follows these steps:
 * <ol>
 * <li>The client sends an initialization POST request to the message endpoint,
 * and the server may optionally generate and return a session ID to the client.
 * This process is not required, only for stateful connections</li>
 *
 * <li>All messages are sent via HTTP POST requests to the message endpoint.
 * If a session ID exists, it MUST be included in the request </li>
 *
 * <li>The client handles responses from the server accordingly based on the protocol specifications.</li>
 * </ol>
 *
 * This implementation uses {@link WebClient} for HTTP communications
 * @author Yeaury
 */
public class WebFluxStreamableClientTransport implements McpClientTransport {

    private static final Logger logger = LoggerFactory.getLogger(WebFluxStreamableClientTransport.class);

    private static final String MESSAGE_EVENT_TYPE = "message";

    private static final String ENDPOINT_EVENT_TYPE = "endpoint";

    private static final String DEFAULT_MCP_ENDPOINT = "/message";

    private static final String MCP_SESSION_ID = "Mcp-Session-Id";

    private static final String LAST_EVENT_ID = "Last-Event-ID";

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE = new ParameterizedTypeReference<>() {
    };

    private final WebFluxSseClientTransport sseClientTransport;

    private final WebClient webClient;

    protected ObjectMapper objectMapper;

    private Disposable inboundSubscription;

    private volatile boolean isClosing = false;

    protected final Sinks.One<String> messageEndpointSink = Sinks.one();

    private final Sinks.Many<ServerSentEvent<String>> sseSink = Sinks.many().multicast().onBackpressureBuffer();

    private final AtomicReference<String> sessionId = new AtomicReference<>(null);

    private final AtomicReference<String> lastEventId = new AtomicReference<>(null);

    private final AtomicBoolean fallbackToSse = new AtomicBoolean(false);

    private Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler;

    /**
     * The endpoint URI provided by the server.
     * Used to send outbound messages via HTTP POST requests, and is also used when initializing a connection.
     */
    private final String endpoint;

    public WebFluxStreamableClientTransport(WebClient.Builder webClientBuilder) {
        this(webClientBuilder, new ObjectMapper());
    }

    public WebFluxStreamableClientTransport(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this(webClientBuilder, objectMapper, DEFAULT_MCP_ENDPOINT);
    }

    public WebFluxStreamableClientTransport(WebClient.Builder webClientBuilder, ObjectMapper objectMapper, String defaultMcpEndpoint) {
        this(webClientBuilder, objectMapper, defaultMcpEndpoint, Function.identity(), WebFluxSseClientTransport.builder(webClientBuilder).objectMapper(objectMapper).build());
    }

    public WebFluxStreamableClientTransport(WebClient.Builder webClientBuilder, ObjectMapper objectMapper,
                                            String sseEndpoint, Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler,
                                            WebFluxSseClientTransport sseClientTransport) {
        Assert.notNull(objectMapper, "ObjectMapper must not be null");
        Assert.notNull(webClientBuilder, "WebClient.Builder must not be null");
        Assert.hasText(sseEndpoint, "SSE endpoint must not be null or empty");
        Assert.notNull(handler, "Handler must not be null");

        this.objectMapper = objectMapper;
        this.webClient = webClientBuilder.build();
        this.endpoint = sseEndpoint;
        this.handler = handler;
        this.sseClientTransport = sseClientTransport;
    }

    /**
     * Connect to the MCP server using the WebClient.
     *
     * <p>
     * The connection is only used as an initialization operation, and the Mcp-Session-ID and Last-Event-ID are set.
     * <p>
     * The initialization process is not required in Streamable HTTP, only when stateful management is implemented,
     * and after Initializated, all subsequent requests need to be accompanied by the mcp-session-id.
     */
    @Override
    public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
        if (fallbackToSse.get()) {
            return sseClientTransport.connect(handler);
        }
        this.handler = handler;

        WebClient.RequestBodySpec request = webClient.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Last-Event-ID", lastEventId.get() != null ? lastEventId.get() : null)
                .header("MCP-SESSION-ID", sessionId.get() != null ? sessionId.get() : null);

        return request
                .exchangeToMono(response -> {
                    if (response.statusCode().value() == 405 || response.statusCode().value() == 404) {
                        fallbackToSse.set(true);
                        return Mono.error(new IllegalStateException("Operation not allowed, falling back to SSE"));
                    }
                    if (!response.statusCode().is2xxSuccessful()) {
                        logger.error("Session initialization failed with status: {}", response.statusCode());
                        return response.createException().flatMap(Mono::error);
                    }
                    setSessionId(response);

                    return response.bodyToMono(Void.class)
                            .onErrorResume(e -> Mono.empty());
                })
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(3))
                        .filter(err -> err instanceof IOException || isServerError(err))
                        .doAfterRetry(signal -> logger.debug("Retrying session initialization: {}", signal.totalRetries())))
                .doOnError(error -> logger.error("Error initializing session: {}", error.getMessage()))
                .onErrorResume(e -> {
                    logger.error("WebClient transport connection error", e);
                    if (fallbackToSse.get()) {
                        return sseClientTransport.connect(handler);
                    }
                    return Mono.empty();
                })
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
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM, new MediaType("application", "json-seq"));

            if (lastEventId.get() != null) {
                request.header(LAST_EVENT_ID, lastEventId.get());
            }
            if (sessionId.get() != null) {
                request.header(MCP_SESSION_ID, sessionId.get());
            }
            return request
                    .bodyValue(jsonText)
                    .exchangeToMono(response -> {
                        if (!response.statusCode().is2xxSuccessful()) {
                            logger.error("Connect request failed with status: {}", response.statusCode());
                            return response.createException().flatMap(Mono::error);
                        }
                        setSessionId(response);

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

    private void setSessionId(ClientResponse response) {
        String sessionIdHeader = response.headers().header(MCP_SESSION_ID).stream().findFirst().orElse(null);
        if (sessionIdHeader != null && !sessionIdHeader.trim().isEmpty()) {
            sessionId.set(sessionIdHeader);
            logger.debug("Received and stored sessionId: {}", sessionIdHeader);
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

    /**
     * It is used for the client to actively establish a SSE connection with the server
     */
    public Mono<Void> connectWithGet(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
        sessionId.set(null);
        Flux<ServerSentEvent<String>> getSseFlux = this.webClient
                .get()
                .uri(endpoint)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(SSE_TYPE)
                .retryWhen(Retry.from(retrySignal -> retrySignal.handle(inboundRetryHandler)));

        getSseFlux.subscribe(sseSink::tryEmitNext);
        return processSseEvents(handler);
    }

    private boolean isServerError(Throwable err) {
        return err instanceof WebClientResponseException
                && ((WebClientResponseException) err).getStatusCode().is5xxServerError();
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

    public static Builder builder(WebClient.Builder webClientBuilder) {
        return new Builder(webClientBuilder);
    }

    public static class Builder {

        private final WebClient.Builder webClientBuilder;

        private String endpoint = DEFAULT_MCP_ENDPOINT;

        private ObjectMapper objectMapper = new ObjectMapper();

        private Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler = Function.identity();

        public Builder(WebClient.Builder webClientBuilder) {
            Assert.notNull(webClientBuilder, "WebClient.Builder must not be null");
            this.webClientBuilder = webClientBuilder;
        }

        public Builder sseEndpoint(String sseEndpoint) {
            Assert.hasText(sseEndpoint, "sseEndpoint must not be empty");
            this.endpoint = sseEndpoint;
            return this;
        }

        public Builder objectMapper(ObjectMapper objectMapper) {
            Assert.notNull(objectMapper, "objectMapper must not be null");
            this.objectMapper = objectMapper;
            return this;
        }

        public Builder handler(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
            Assert.notNull(handler, "handler must not be null");
            this.handler = handler;
            return this;
        }

        public WebFluxStreamableClientTransport build() {
            final WebFluxSseClientTransport.Builder builder = WebFluxSseClientTransport.builder(webClientBuilder)
                    .objectMapper(objectMapper);

            if (!endpoint.equals(DEFAULT_MCP_ENDPOINT)) {
                builder.sseEndpoint(endpoint);
            }

            return new WebFluxStreamableClientTransport(webClientBuilder, objectMapper, endpoint,
                    handler, builder.build());
        }

    }
}
