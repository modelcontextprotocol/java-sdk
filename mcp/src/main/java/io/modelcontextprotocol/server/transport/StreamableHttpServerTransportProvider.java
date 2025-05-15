package io.modelcontextprotocol.server.transport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.spec.McpSession;
import io.modelcontextprotocol.spec.StatelessMcpSession;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Aliaksei_Darafeyeu
 */
public class StreamableHttpServerTransportProvider extends HttpServlet implements McpServerTransportProvider {
    /**
     * Logger for this class
     */
    private static final Logger logger = LoggerFactory.getLogger(StreamableHttpServerTransportProvider.class);

    private static final String MCP_SESSION_ID = "Mcp-Session-Id";
    private static final String APPLICATION_JSON = "application/json";
    private static final String TEXT_EVENT_STREAM = "text/event-stream";

    private McpServerSession.Factory sessionFactory;

    private final ObjectMapper objectMapper;

    private final McpServerTransportProvider legacyTransportProvider;

    private final Set<String> allowedOrigins;

    /**
     * Map of active client sessions, keyed by session ID
     */
    private final Map<String, McpSession> sessions = new ConcurrentHashMap<>();

    public StreamableHttpServerTransportProvider(final ObjectMapper objectMapper, final McpServerTransportProvider legacyTransportProvider, final Set<String> allowedOrigins) {
        this.objectMapper = objectMapper;
        this.legacyTransportProvider = legacyTransportProvider;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void setSessionFactory(McpServerSession.Factory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        if (sessions.isEmpty()) {
            logger.debug("No active sessions to broadcast message to");
            return Mono.empty();
        }

        logger.debug("Attempting to broadcast message to {} active sessions", sessions.size());
        return Flux.fromIterable(sessions.values())
                .flatMap(session -> session.sendNotification(method, params)
                        .doOnError(e -> logger.error("Failed to send message to session {}: {}", session.getId(), e.getMessage()))
                        .onErrorComplete())
                .then();
    }

    @Override
    public Mono<Void> closeGracefully() {
        logger.debug("Initiating graceful shutdown with {} active sessions", sessions.size());
        return Flux.fromIterable(sessions.values()).flatMap(McpSession::closeGracefully).then();
    }

    @Override
    public void destroy() {
        closeGracefully().block();
        super.destroy();
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // 1. Origin header check
        String origin = req.getHeader("Origin");
        if (origin != null && !allowedOrigins.contains(origin)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Origin not allowed");
            return;
        }

        // 2. Accept header routing
        final String accept = Optional.ofNullable(req.getHeader("Accept")).orElse("");
        final List<String> acceptTypes = Arrays.stream(accept.split(","))
                .map(String::trim)
                .toList();

        // todo!!!!
        if (!acceptTypes.contains(APPLICATION_JSON) && !acceptTypes.contains(TEXT_EVENT_STREAM)) {
            if (legacyTransportProvider instanceof HttpServletSseServerTransportProvider legacy) {
                legacy.doPost(req, resp);
            } else {
                resp.sendError(HttpServletResponse.SC_NOT_ACCEPTABLE, "Legacy transport not available");
            }
            return;
        }

        // 3. Enable async
        final AsyncContext asyncContext = req.startAsync();
        asyncContext.setTimeout(0);

        // resp
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setCharacterEncoding("UTF-8");

        final McpServerTransport transport = new StreamableHttpServerTransport(resp.getOutputStream(), objectMapper);
        final McpSession session = getOrCreateSession(req.getHeader(MCP_SESSION_ID), transport);
        if (!"stateless".equals(session.getId())) {
            resp.setHeader(MCP_SESSION_ID, session.getId());
        }
        final Flux<McpSchema.JSONRPCMessage> messages = parseRequestBodyAsStream(req);

        if (accept.contains(TEXT_EVENT_STREAM)) {
            // TODO: Handle streaming JSON-RPC over HTTP
            resp.setContentType(TEXT_EVENT_STREAM);
            resp.setHeader("Connection", "keep-alive");

            messages.flatMap(session::handle)
                    .doOnError(e -> sendError(resp, 500, "Streaming failed: " + e.getMessage()))
                    .then(transport.closeGracefully())
                    .subscribe();
        } else if (accept.contains(APPLICATION_JSON)) {
            // TODO: Handle traditional JSON-RPC response
            resp.setContentType(APPLICATION_JSON);

            messages.flatMap(session::handle)
                    .collectList()
                    .flatMap(responses -> {
                        try {
                            String json = new ObjectMapper().writeValueAsString(
                                    responses.size() == 1 ? responses.get(0) : responses
                            );
                            resp.getWriter().write(json);
                            return transport.closeGracefully();
                        } catch (IOException e) {
                            return Mono.error(e);
                        }
                    })
                    .doOnError(e -> sendError(resp, 500, "JSON response failed: " + e.getMessage()))
                    .subscribe();

        } else {
            resp.sendError(HttpServletResponse.SC_NOT_ACCEPTABLE, "Unsupported Accept header");
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        if (legacyTransportProvider instanceof HttpServletSseServerTransportProvider legacy) {
            legacy.doGet(req, resp);
        } else {
            resp.sendError(HttpServletResponse.SC_NOT_ACCEPTABLE, "Legacy transport not available");
        }
    }

    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        final String sessionId = req.getHeader("mcp-session-id");
        if (sessionId == null || !sessions.containsKey(sessionId)) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Session not found");
            return;
        }

        final McpSession session = sessions.remove(sessionId);
        session.closeGracefully().subscribe();
        resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    // todo:!!!
    private Flux<McpSchema.JSONRPCMessage> parseRequestBodyAsStream(final HttpServletRequest req) {
        return Mono.fromCallable(() -> {
            try (final InputStream inputStream = req.getInputStream()) {
                final JsonNode node = objectMapper.readTree(inputStream);
                if (node.isArray()) {
                    final List<McpSchema.JSONRPCMessage> messages = new ArrayList<>();
                    for (final JsonNode item : node) {
                        messages.add(objectMapper.treeToValue(item, McpSchema.JSONRPCMessage.class));
                    }
                    return messages;
                } else if (node.isObject()) {
                    return List.of(objectMapper.treeToValue(node, McpSchema.JSONRPCMessage.class));
                } else {
                    throw new IllegalArgumentException("Invalid JSON-RPC request: not object or array");
                }
            }
        }).flatMapMany(Flux::fromIterable);
    }

    private McpSession getOrCreateSession(final String sessionId, final McpServerTransport transport) {
        if (sessionId != null && sessionFactory != null) {
            // Reuse or track sessions if you support that; for now, we just create new ones
            return sessions.get(sessionId);
        } else if (sessionFactory != null) {
            final String newSessionId = UUID.randomUUID().toString();
            return sessions.put(newSessionId, sessionFactory.create(transport));
        } else {
            return new StatelessMcpSession(transport);
        }
    }

    private void sendError(final HttpServletResponse resp, final int code, final String msg) {
        try {
            resp.sendError(code, msg);
        } catch (IOException ignored) {
            logger.debug("Exception during send error");
        }
    }

    public static class StreamableHttpServerTransport implements McpServerTransport {
        private final ObjectMapper objectMapper;
        private final OutputStream outputStream;

        public StreamableHttpServerTransport(final OutputStream outputStream, final ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            this.outputStream = outputStream;
        }

        @Override
        public Mono<Void> sendMessage(final McpSchema.JSONRPCMessage message) {
            return Mono.fromRunnable(() -> {
                try {
                    String json = objectMapper.writeValueAsString(message);
                    outputStream.write(json.getBytes(StandardCharsets.UTF_8));
                    outputStream.write('\n');
                    outputStream.flush();
                } catch (IOException e) {
                    throw new RuntimeException("Failed to send message", e);
                }
            });
        }

        @Override
        public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
            return objectMapper.convertValue(data, typeRef);
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.fromRunnable(() -> {
                try {
                    outputStream.flush();
                    outputStream.close();
                } catch (IOException e) {
                    // ignore or log
                }
            });
        }
    }

}
