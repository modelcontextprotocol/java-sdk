/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server;

import java.util.Map;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.modern.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.modern.McpSchema.Implementation;
import io.modelcontextprotocol.util.Assert;

/**
 * The per-request view a modern handler sees. Nothing here is ever cached across
 * requests; the protocol is stateless, so a new context is built for every dispatch.
 *
 * @author Dariusz Jędrzejczyk
 */
public final class McpRequestContext {

	private final Object requestId;

	private final String method;

	private final String protocolVersion;

	private final ClientCapabilities clientCapabilities;

	private final Implementation clientInfo;

	private final Object progressToken;

	private final String primitiveName;

	private final Map<String, Object> meta;

	private final McpTransportContext transportContext;

	private final boolean retry;

	private final boolean blocking;

	McpRequestContext(Object requestId, String method, String protocolVersion, ClientCapabilities clientCapabilities,
			Implementation clientInfo, Object progressToken, String primitiveName, Map<String, Object> meta,
			McpTransportContext transportContext, boolean retry, boolean blocking) {
		Assert.notNull(requestId, "requestId must not be null");
		Assert.hasText(method, "method must not be empty");
		Assert.hasText(protocolVersion, "protocolVersion must not be empty");
		Assert.notNull(clientCapabilities, "clientCapabilities must not be null");
		this.requestId = requestId;
		this.method = method;
		this.protocolVersion = protocolVersion;
		this.clientCapabilities = clientCapabilities;
		this.clientInfo = clientInfo;
		this.progressToken = progressToken;
		this.primitiveName = primitiveName;
		this.meta = meta == null ? Map.of() : meta;
		this.transportContext = transportContext == null ? McpTransportContext.EMPTY : transportContext;
		this.retry = retry;
		this.blocking = blocking;
	}

	/** The JSON-RPC id of the request being served. */
	public Object requestId() {
		return this.requestId;
	}

	/** The JSON-RPC method being served, e.g. {@code tools/call}. */
	public String method() {
		return this.method;
	}

	/** The protocol version declared in {@code _meta} for this request. */
	public String protocolVersion() {
		return this.protocolVersion;
	}

	/** The client capabilities declared in {@code _meta} for this request. */
	public ClientCapabilities clientCapabilities() {
		return this.clientCapabilities;
	}

	/** The client info declared in {@code _meta}, if any. Display/logging only. */
	public Implementation clientInfo() {
		return this.clientInfo;
	}

	/** The progress token, or {@code null} if the request declared none. */
	public Object progressToken() {
		return this.progressToken;
	}

	/**
	 * The {@code name} (for tools/prompts) or {@code uri} (for resources) the request
	 * targets, or {@code null} if the method has no such primitive. Used by
	 * {@link RequestStateCodec} to bind sealed {@code requestState} to the primitive.
	 */
	public String primitiveName() {
		return this.primitiveName;
	}

	/** The raw {@code _meta} map for this request. */
	public Map<String, Object> meta() {
		return this.meta;
	}

	/** The transport-level context (headers, connection info, ...). */
	public McpTransportContext transportContext() {
		return this.transportContext;
	}

	/**
	 * Whether this request is an MRTR retry, i.e. its params carried
	 * {@code inputResponses} or {@code requestState}. Results for a retry MUST NOT be
	 * cached.
	 */
	public boolean isRetry() {
		return this.retry;
	}

	/**
	 * Whether the transport resolved this request through
	 * {@link McpRequestManager#handleBlocking}, so sync code may run on the calling
	 * thread.
	 */
	public boolean isBlocking() {
		return this.blocking;
	}

}
