/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import java.util.List;

public class McpStreamableHttpClient {

	private McpStreamableHttpClient() {
	}

	/** ["Origin","Accept"] */
	public static final List<String> REQUIRED_HEADERS = List.of("Origin", "Accept");

	/** ["application/json","text/event-stream"] */
	public static final List<String> REQUIRED_ACCEPTED_CONTENT = List.of("application/json", "text/event-stream");

	public static final String ACCEPT_HEADER_NAME = "Accept";

	public static final String ACCEPT_HEADER_POST_VALUE = "application/json, text/event-stream";

	public static final String ACCEPT_HEADER_GET_VALUE = "text/event-stream";

	public static final String CONTENT_TYPE_HEADER_NAME = "Content-Type";

	public static final String CONTENT_TYPE_HEADER_VALUE = "application/json";

	public static final String MCP_SESSION_ID_HEADER_NAME = "Mcp-Session-Id";

}
