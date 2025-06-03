package io.modelcontextprotocol.spec;

import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * @author Aliaksei_Darafeyeu
 */
public class McpStatelessSession implements McpSession, McpLastEventId {

	/**
	 * Logger for this class
	 */
	private static final Logger logger = LoggerFactory.getLogger(McpStatelessSession.class);

	private final McpTransport transport;

	public McpStatelessSession(final McpTransport transport) {
		this.transport = transport;
	}

	@Override
	public String getId() {
		return "stateless";
	}

	@Override
	public Mono<Void> handle(McpSchema.JSONRPCMessage message) {
		logger.info("Handling message: {}", message);

		if (message instanceof McpSchema.JSONRPCRequest request) {
			if (McpSchema.METHOD_PING.equals(request.method())) {
				McpSchema.JSONRPCResponse pong = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(),
						"", null);
				return transport.sendMessage(pong);
			}

			// Stateless sessions do not support incoming requests
			McpSchema.JSONRPCResponse errorResponse = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION,
					request.id(), null, new McpSchema.JSONRPCResponse.JSONRPCError(
							McpSchema.ErrorCodes.METHOD_NOT_FOUND, "Stateless session does not handle requests", null));
			return transport.sendMessage(errorResponse);
		}
		else if (message instanceof McpSchema.JSONRPCNotification notification) {
			// Stateless session ignores incoming notifications
			return Mono.empty();
		}
		else if (message instanceof McpSchema.JSONRPCResponse response) {
			// No request/response correlation in stateless mode
			return Mono.empty();
		}
		else {
			return Mono.empty();
		}
	}

	@Override
	public <T> Mono<T> sendRequest(String method, Object requestParams, TypeReference<T> typeRef) {
		// Stateless = no request/response correlation
		String requestId = UUID.randomUUID().toString();
		McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, method, requestId,
				requestParams);

		return Mono.defer(() -> Mono.from(this.transport.sendMessage(request))
			.then(Mono.error(new IllegalStateException("Stateless session cannot receive responses"))));
	}

	@Override
	public Mono<Void> sendNotification(String method, Object params) {
		logger.debug("sendNotification: {}, {}", method, params);
		return this.transport.sendMessage(new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION, method, params));
	}

	@Override
	public Mono<Void> closeGracefully() {
		return this.transport.closeGracefully();
	}

	@Override
	public void close() {
		this.closeGracefully().subscribe();
	}

	@Override
	public void resumeFrom(String lastEventId) {
		logger.info("session received Last-Event-ID: {}", lastEventId);
	}

}
