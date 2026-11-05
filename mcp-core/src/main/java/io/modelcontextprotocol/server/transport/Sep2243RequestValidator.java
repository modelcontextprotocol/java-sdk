/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.util.List;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.util.Assert;

/**
 * Implements the SEP-2243 checks that need the parsed JSON-RPC body: negotiating the
 * {@code MCP-Protocol-Version} header and mirroring {@code Mcp-Method} / {@code Mcp-Name}
 * against the request or notification carried in the body. These checks sit beside rather
 * than inside {@link ServerHttpHeaderValidator} because that validator only ever sees raw
 * HTTP headers, never the deserialized message.
 *
 * @author Sylwester Lachiewicz
 * @since 2.1.0
 */
final class Sep2243RequestValidator {

	private static final Logger logger = LoggerFactory.getLogger(Sep2243RequestValidator.class);

	private final McpJsonMapper jsonMapper;

	private final Supplier<List<String>> supportedProtocolVersions;

	private final boolean requireMcpHeaders;

	Sep2243RequestValidator(McpJsonMapper jsonMapper, Supplier<List<String>> supportedProtocolVersions,
			boolean requireMcpHeaders) {
		Assert.notNull(jsonMapper, "jsonMapper must not be null");
		Assert.notNull(supportedProtocolVersions, "supportedProtocolVersions must not be null");
		this.jsonMapper = jsonMapper;
		this.supportedProtocolVersions = supportedProtocolVersions;
		this.requireMcpHeaders = requireMcpHeaders;
	}

	/**
	 * Validates the {@code MCP-Protocol-Version} header against the supported protocol
	 * versions. A missing header is allowed and falls back to the negotiated protocol
	 * version, while a header carrying an unsupported version is rejected. Initialize
	 * requests are exempt: no version has been negotiated yet, so any header value
	 * carried on them is resolved through regular body-based version negotiation instead
	 * of being rejected here.
	 * @param headers the request headers
	 * @param initializationRequest whether the request being validated is an
	 * {@code initialize} request
	 * @return an {@link McpError} to reject the request with, or {@code null} if the
	 * request passes
	 */
	McpError validateProtocolVersion(HeaderAccessor headers, boolean initializationRequest) {
		if (initializationRequest) {
			return null;
		}

		String protocolVersion = firstHeader(headers, HttpHeaders.PROTOCOL_VERSION);
		if (protocolVersion == null || this.supportedProtocolVersions.get().contains(protocolVersion)) {
			return null;
		}

		return McpError.builder(McpSchema.ErrorCodes.INVALID_REQUEST)
			.message("Unsupported protocol version (supported versions: "
					+ String.join(", ", this.supportedProtocolVersions.get()) + ")")
			.build();
	}

	/**
	 * Validates SEP-2243 {@code Mcp-Method} / {@code Mcp-Name} header-to-body mirroring.
	 * A present header that mismatches the body is always rejected. Absent headers are
	 * tolerated unless {@code requireMcpHeaders} was enabled, in which case a request or
	 * notification must carry {@code Mcp-Method}, and one that targets a tool, prompt, or
	 * resource must also carry {@code Mcp-Name}. Responses carry no method and always
	 * pass.
	 * @param headers the request headers
	 * @param message the deserialized JSON-RPC message
	 * @return an {@link McpError} to reject the request with, or {@code null} if the
	 * request passes
	 */
	McpError validateMirroringHeaders(HeaderAccessor headers, McpSchema.JSONRPCMessage message) {
		String method = message instanceof McpSchema.JSONRPCRequest req ? req.method()
				: message instanceof McpSchema.JSONRPCNotification notif ? notif.method() : null;
		if (method == null) {
			return null;
		}

		String methodHeader = firstHeader(headers, HttpHeaders.MCP_METHOD);
		if (methodHeader == null || methodHeader.isBlank()) {
			if (this.requireMcpHeaders) {
				return McpError.builder(McpSchema.ErrorCodes.HEADER_MISMATCH)
					.message("Missing required Mcp-Method header")
					.build();
			}
		}
		else if (!method.equals(methodHeader)) {
			return McpError.builder(McpSchema.ErrorCodes.HEADER_MISMATCH)
				.message("Mcp-Method header mismatch: expected '" + method + "' but was '" + methodHeader + "'")
				.build();
		}

		Object params = message instanceof McpSchema.JSONRPCRequest req ? req.params()
				: message instanceof McpSchema.JSONRPCNotification notif ? notif.params() : null;
		String name = extractNameFromParams(method, params);
		if (name != null) {
			String nameHeader = firstHeader(headers, HttpHeaders.MCP_NAME);
			if (nameHeader == null || nameHeader.isBlank()) {
				if (this.requireMcpHeaders) {
					return McpError.builder(McpSchema.ErrorCodes.HEADER_MISMATCH)
						.message("Missing required Mcp-Name header")
						.build();
				}
			}
			else {
				String decodedName = HttpHeaders.decodeHeaderValue(nameHeader);
				if (!name.equals(decodedName)) {
					return McpError.builder(McpSchema.ErrorCodes.HEADER_MISMATCH)
						.message("Mcp-Name header mismatch: expected '" + name + "' but was '" + nameHeader + "'")
						.build();
				}
			}
		}

		return null;
	}

	/**
	 * Returns whether the given message is an {@code initialize} request.
	 * @param message the deserialized JSON-RPC message
	 * @return {@code true} if the message is a JSON-RPC request for
	 * {@link McpSchema#METHOD_INITIALIZE}
	 */
	static boolean isInitializeRequest(McpSchema.JSONRPCMessage message) {
		return message instanceof McpSchema.JSONRPCRequest req && McpSchema.METHOD_INITIALIZE.equals(req.method());
	}

	/**
	 * Extracts the name or URI of the tool, prompt, or resource referenced by a request,
	 * as used to validate the SEP-2243 {@code Mcp-Name} header.
	 * @param method the JSON-RPC method of the request
	 * @param params the request parameters
	 * @return the target name or URI when the method references one, otherwise
	 * {@code null}
	 */
	private String extractNameFromParams(String method, Object params) {
		if (params == null) {
			return null;
		}

		try {
			return switch (method) {
				case McpSchema.METHOD_TOOLS_CALL ->
					this.jsonMapper.convertValue(params, new TypeRef<McpSchema.CallToolRequest>() {
					}).name();
				case McpSchema.METHOD_PROMPT_GET ->
					this.jsonMapper.convertValue(params, new TypeRef<McpSchema.GetPromptRequest>() {
					}).name();
				case McpSchema.METHOD_RESOURCES_READ ->
					this.jsonMapper.convertValue(params, new TypeRef<McpSchema.ReadResourceRequest>() {
					}).uri();
				case McpSchema.METHOD_RESOURCES_SUBSCRIBE ->
					this.jsonMapper.convertValue(params, new TypeRef<McpSchema.SubscribeRequest>() {
					}).uri();
				case McpSchema.METHOD_RESOURCES_UNSUBSCRIBE ->
					this.jsonMapper.convertValue(params, new TypeRef<McpSchema.UnsubscribeRequest>() {
					}).uri();
				default -> null;
			};
		}
		catch (Exception e) {
			logger.debug("Failed to extract name from params for method {}: {}", method, e.getMessage());
			return null;
		}
	}

	private static String firstHeader(HeaderAccessor headers, String name) {
		List<String> values = headers.getHeader(name);
		return values.isEmpty() ? null : values.get(0);
	}

}
