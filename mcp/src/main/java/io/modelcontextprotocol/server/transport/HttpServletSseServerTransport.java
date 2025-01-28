/*
* Copyright 2024 - 2024 the original author or authors.
*/
package io.modelcontextprotocol.server.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ServerMcpTransport;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * A Servlet-based implementation of the MCP HTTP with Server-Sent Events (SSE) transport
 * specification. This implementation provides similar functionality to
 * WebFluxSseServerTransport but uses the traditional Servlet API instead of WebFlux.
 *
 * <p>
 * The transport handles two types of endpoints:
 * <ul>
 * <li>SSE endpoint (/sse) - Establishes a long-lived connection for server-to-client
 * events</li>
 * <li>Message endpoint (configurable) - Handles client-to-server message requests</li>
 * </ul>
 *
 * <p>
 * Features:
 * <ul>
 * <li>Asynchronous message handling using Servlet 6.0 async support</li>
 * <li>Session management for multiple client connections</li>
 * <li>Graceful shutdown support</li>
 * <li>Error handling and response formatting</li>
 * </ul>
 *
 * @author Christian Tzolov
 * @author Alexandros Pappas
 * @see ServerMcpTransport
 * @see HttpServlet
 */

@WebServlet(asyncSupported = true)
public class HttpServletSseServerTransport extends HttpServlet implements ServerMcpTransport {

	/** Logger for this class */
	private static final Logger logger = LoggerFactory.getLogger(HttpServletSseServerTransport.class);

	public static final String UTF_8 = "UTF-8";

	public static final String APPLICATION_JSON = "application/json";

	public static final String FAILED_TO_SEND_ERROR_RESPONSE = "Failed to send error response: {}";

	/** Default endpoint path for SSE connections */
	public static final String DEFAULT_SSE_ENDPOINT = "/sse";

	/** Event type for regular messages */
	public static final String MESSAGE_EVENT_TYPE = "message";

	/** Event type for endpoint information */
	public static final String ENDPOINT_EVENT_TYPE = "endpoint";

	/** JSON object mapper for serialization/deserialization */
	private final ObjectMapper objectMapper;

	/** The endpoint path for handling client messages */
	private final String messageEndpoint;

	/** The endpoint path for handling SSE connections */
	private final String sseEndpoint;

	/** Map of active client sessions, keyed by session ID */
	private final Map<String, ClientSession> sessions = new ConcurrentHashMap<>();

	/** Flag indicating if the transport is in the process of shutting down */
	private final AtomicBoolean isClosing = new AtomicBoolean(false);

	/** Handler for processing incoming messages */
	private Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> connectHandler;

	/**
	 * Creates a new HttpServletSseServerTransport instance with a custom SSE endpoint.
	 * @param objectMapper The JSON object mapper to use for message
	 * serialization/deserialization
	 * @param messageEndpoint The endpoint path where clients will send their messages
	 * @param sseEndpoint The endpoint path where clients will establish SSE connections
	 */
	public HttpServletSseServerTransport(ObjectMapper objectMapper, String messageEndpoint, String sseEndpoint) {
		this.objectMapper = objectMapper;
		this.messageEndpoint = messageEndpoint;
		this.sseEndpoint = sseEndpoint;
	}

	/**
	 * Creates a new HttpServletSseServerTransport instance with the default SSE endpoint.
	 * @param objectMapper The JSON object mapper to use for message
	 * serialization/deserialization
	 * @param messageEndpoint The endpoint path where clients will send their messages
	 */
	public HttpServletSseServerTransport(ObjectMapper objectMapper, String messageEndpoint) {
		this(objectMapper, messageEndpoint, DEFAULT_SSE_ENDPOINT);
	}

	/**
	 * Handles GET requests to establish SSE connections.
	 * <p>
	 * This method sets up a new SSE connection when a client connects to the SSE
	 * endpoint. It configures the response headers for SSE, creates a new session, and
	 * sends the initial endpoint information to the client.
	 * @param request The HTTP servlet request
	 * @param response The HTTP servlet response
	 * @throws ServletException If a servlet-specific error occurs
	 * @throws IOException If an I/O error occurs
	 */
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		String pathInfo = request.getPathInfo();
		if (!sseEndpoint.equals(pathInfo)) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}

		if (isClosing.get()) {
			response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Server is shutting down");
			return;
		}

		response.setContentType("text/event-stream");
		response.setCharacterEncoding(UTF_8);
		response.setHeader("Cache-Control", "no-cache");
		response.setHeader("Connection", "keep-alive");
		response.setHeader("Access-Control-Allow-Origin", "*");

		String sessionId = UUID.randomUUID().toString();
		AsyncContext asyncContext = request.startAsync();
		asyncContext.setTimeout(0);

		PrintWriter writer = response.getWriter();
		ClientSession session = new ClientSession(sessionId, asyncContext, writer);
		this.sessions.put(sessionId, session);

		// Send initial endpoint event
		this.sendEvent(writer, ENDPOINT_EVENT_TYPE, messageEndpoint);
	}

	/**
	 * Handles POST requests for client messages.
	 * <p>
	 * This method processes incoming messages from clients, routes them through the
	 * connect handler if configured, and sends back the appropriate response. It handles
	 * error cases and formats error responses according to the MCP specification.
	 * @param request The HTTP servlet request
	 * @param response The HTTP servlet response
	 * @throws ServletException If a servlet-specific error occurs
	 * @throws IOException If an I/O error occurs
	 */
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		if (isClosing.get()) {
			response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Server is shutting down");
			return;
		}

		String pathInfo = request.getPathInfo();
		if (!messageEndpoint.equals(pathInfo)) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}

		try {
			BufferedReader reader = request.getReader();
			StringBuilder body = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				body.append(line);
			}

			McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, body.toString());

			if (connectHandler != null) {
				connectHandler.apply(Mono.just(message)).subscribe(responseMessage -> {
					try {
						response.setContentType(APPLICATION_JSON);
						response.setCharacterEncoding(UTF_8);
						String jsonResponse = objectMapper.writeValueAsString(responseMessage);
						PrintWriter writer = response.getWriter();
						writer.write(jsonResponse);
						writer.flush();
					}
					catch (Exception e) {
						logger.error("Error sending response: {}", e.getMessage());
						try {
							response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
									"Error processing response: " + e.getMessage());
						}
						catch (IOException ex) {
							logger.error(FAILED_TO_SEND_ERROR_RESPONSE, ex.getMessage());
						}
					}
				}, error -> {
					try {
						logger.error("Error processing message: {}", error.getMessage());
						McpError mcpError = new McpError(error.getMessage());
						response.setContentType(APPLICATION_JSON);
						response.setCharacterEncoding(UTF_8);
						response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
						String jsonError = objectMapper.writeValueAsString(mcpError);
						PrintWriter writer = response.getWriter();
						writer.write(jsonError);
						writer.flush();
					}
					catch (IOException e) {
						logger.error(FAILED_TO_SEND_ERROR_RESPONSE, e.getMessage());
						try {
							response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
									"Error sending error response: " + e.getMessage());
						}
						catch (IOException ex) {
							logger.error(FAILED_TO_SEND_ERROR_RESPONSE, ex.getMessage());
						}
					}
				});
			}
			else {
				response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "No message handler configured");
			}
		}
		catch (Exception e) {
			logger.error("Invalid message format: {}", e.getMessage());
			try {
				McpError mcpError = new McpError("Invalid message format: " + e.getMessage());
				response.setContentType(APPLICATION_JSON);
				response.setCharacterEncoding(UTF_8);
				response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
				String jsonError = objectMapper.writeValueAsString(mcpError);
				PrintWriter writer = response.getWriter();
				writer.write(jsonError);
				writer.flush();
			}
			catch (IOException ex) {
				logger.error(FAILED_TO_SEND_ERROR_RESPONSE, ex.getMessage());
				response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid message format");
			}
		}
	}

	/**
	 * Sets up the message handler for processing client requests.
	 * @param handler The function to process incoming messages and produce responses
	 * @return A Mono that completes when the handler is set up
	 */
	@Override
	public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
		this.connectHandler = handler;
		return Mono.empty();
	}

	/**
	 * Broadcasts a message to all connected clients.
	 * <p>
	 * This method serializes the message and sends it to all active client sessions. If a
	 * client is disconnected, its session is removed.
	 * @param message The message to broadcast
	 * @return A Mono that completes when the message has been sent to all clients
	 */
	@Override
	public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
		if (sessions.isEmpty()) {
			logger.debug("No active sessions to broadcast message to");
			return Mono.empty();
		}

		return Mono.create(sink -> {
			try {
				String jsonText = objectMapper.writeValueAsString(message);

				sessions.values().forEach(session -> {
					try {
						this.sendEvent(session.writer, MESSAGE_EVENT_TYPE, jsonText);
					}
					catch (IOException e) {
						logger.error("Failed to send message to session {}: {}", session.id, e.getMessage());
						removeSession(session);
					}
				});

				sink.success();
			}
			catch (Exception e) {
				logger.error("Failed to process message: {}", e.getMessage());
				sink.error(new McpError("Failed to process message: " + e.getMessage()));
			}
		});
	}

	/**
	 * Closes the transport.
	 * <p>
	 * This implementation delegates to the super class's close method.
	 */
	@Override
	public void close() {
		ServerMcpTransport.super.close();
	}

	/**
	 * Unmarshals data from one type to another using the object mapper.
	 * @param <T> The target type
	 * @param data The source data
	 * @param typeRef The type reference for the target type
	 * @return The unmarshaled data
	 */
	@Override
	public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
		return objectMapper.convertValue(data, typeRef);
	}

	/**
	 * Initiates a graceful shutdown of the transport.
	 * <p>
	 * This method marks the transport as closing and closes all active client sessions.
	 * New connection attempts will be rejected during shutdown.
	 * @return A Mono that completes when all sessions have been closed
	 */
	@Override
	public Mono<Void> closeGracefully() {
		isClosing.set(true);
		logger.debug("Initiating graceful shutdown with {} active sessions", sessions.size());

		return Mono.create(sink -> {
			sessions.values().forEach(this::removeSession);
			sink.success();
		});
	}

	/**
	 * Sends an SSE event to a client.
	 * @param writer The writer to send the event through
	 * @param eventType The type of event (message or endpoint)
	 * @param data The event data
	 * @throws IOException If an error occurs while writing the event
	 */
	private void sendEvent(PrintWriter writer, String eventType, String data) throws IOException {
		writer.write("event: " + eventType + "\n");
		writer.write("data: " + data + "\n\n");
		writer.flush();

		if (writer.checkError()) {
			throw new IOException("Client disconnected");
		}
	}

	/**
	 * Removes a client session and completes its async context.
	 * @param session The session to remove
	 */
	private void removeSession(ClientSession session) {
		sessions.remove(session.id);
		session.asyncContext.complete();
	}

	/**
	 * Represents a client connection session.
	 * <p>
	 * This class holds the necessary information about a client's SSE connection,
	 * including its ID, async context, and output writer.
	 */
	private static class ClientSession {

		private final String id;

		private final AsyncContext asyncContext;

		private final PrintWriter writer;

		ClientSession(String id, AsyncContext asyncContext, PrintWriter writer) {
			this.id = id;
			this.asyncContext = asyncContext;
			this.writer = writer;
		}

	}

	/**
	 * Cleans up resources when the servlet is being destroyed.
	 * <p>
	 * This method ensures a graceful shutdown by closing all client connections before
	 * calling the parent's destroy method.
	 */
	@Override
	public void destroy() {
		closeGracefully().block();
		super.destroy();
	}

}
