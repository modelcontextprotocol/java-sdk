/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.common;

import java.util.Collections;
import java.util.Map;

/**
 * Context associated with the transport layer. It allows to add transport-level metadata
 * for use further down the line. Specifically, it can be beneficial to extract HTTP
 * request metadata for use in MCP feature implementations.
 * <p>
 * The context travels in the Reactor context, under {@link #KEY}. On the server side, the
 * transports populate it from the incoming request. On the client side, writing it is the
 * caller's responsibility:
 * <ul>
 * <li>with a synchronous client, configure
 * {@code McpClient.SyncSpec#transportContextProvider(Supplier)}, which is invoked on the
 * calling thread before every operation;
 * <li>with an asynchronous client, write it into the Reactor context once, where the
 * reactive chain starts, using
 * {@code contextWrite(ctx -> ctx.put(McpTransportContext.KEY, context))}. Every client
 * call downstream inherits it, so there is no need to repeat it at each call site.
 * </ul>
 *
 * @author Dariusz Jędrzejczyk
 */
public interface McpTransportContext {

	/**
	 * Key for use in Reactor Context to transport the context to user land. Write the
	 * context under this key to make it visible to the transport, for example
	 * {@code contextWrite(ctx -> ctx.put(McpTransportContext.KEY, context))}.
	 */
	String KEY = "MCP_TRANSPORT_CONTEXT";

	/**
	 * An empty, unmodifiable context.
	 */
	@SuppressWarnings("unchecked")
	McpTransportContext EMPTY = new DefaultMcpTransportContext(Collections.EMPTY_MAP);

	/**
	 * Create an unmodifiable context containing the given metadata.
	 * @param metadata the transport metadata
	 * @return the context containing the metadata
	 */
	static McpTransportContext create(Map<String, Object> metadata) {
		return new DefaultMcpTransportContext(metadata);
	}

	/**
	 * Extract a value from the context.
	 * @param key the key under the data is expected
	 * @return the associated value or {@code null} if missing.
	 */
	Object get(String key);

}
