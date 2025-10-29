/*
 * Copyright 2017-2025 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.modelcontextprotocol.server.httpserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.server.transport.HttpJsonRpcResponse;
import io.modelcontextprotocol.server.transport.HttpServerMcpStatelessServerTransport;
import io.modelcontextprotocol.server.McpHttpServer;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;

/**
 * Mcp HTTP Server class which uses a {@link HttpServer}.
 */
public class McpSimpleHttpServer implements McpHttpServer {

	private static final Logger LOG = LoggerFactory.getLogger(McpSimpleHttpServer.class);

	private static final String METHOD_POST = "POST";

	private static final String HEADER_CONTENT_TYPE = "Content-Type";

	private static final String MEDIA_TYPE_APPLICATION_JSON = "application/json";

	private static final String DEFAULT_ENDPOINT = "/mcp";

	protected final HttpServer server;

	protected final String endpoint;

	protected final McpJsonMapper jsonMapper;

	protected final HttpServerMcpStatelessServerTransport<HttpExchange> transport;

	/**
	 * @param transport Transport
	 * @throws IOException IO Exception while invoking
	 * {@link HttpServer#create(InetSocketAddress, int)}
	 */
	public McpSimpleHttpServer(HttpServerMcpStatelessServerTransport<HttpExchange> transport) throws IOException {
		this(new InetSocketAddress(0), DEFAULT_ENDPOINT, transport, McpJsonMapper.getDefault());
	}

	/**
	 * @param transport Transport
	 * @param jsonMapper JSON Mapper
	 * @throws IOException IO Exception while invoking
	 * {@link HttpServer#create(InetSocketAddress, int)}
	 */
	public McpSimpleHttpServer(HttpServerMcpStatelessServerTransport<HttpExchange> transport, McpJsonMapper jsonMapper)
			throws IOException {
		this(new InetSocketAddress(0), DEFAULT_ENDPOINT, transport, jsonMapper);
	}

	/**
	 * @param inetSocketAddress address
	 * @param endpoint endpoint
	 * @param transport transport
	 * @param jsonMapper JSON Mapper
	 * @throws IOException IO Exception while invoking
	 * {@link HttpServer#create(InetSocketAddress, int)}
	 */
	public McpSimpleHttpServer(InetSocketAddress inetSocketAddress, String endpoint,
			HttpServerMcpStatelessServerTransport<HttpExchange> transport, McpJsonMapper jsonMapper)
			throws IOException {
		this.endpoint = endpoint;
		this.jsonMapper = jsonMapper;
		this.transport = transport;
		this.server = HttpServer.create(inetSocketAddress, 0);
		server.createContext(endpoint, createHttpHandler());
	}

	@Override
	public int getPort() {
		return getAddress().getPort();
	}

	@Override
	public void start() {
		server.start();
	}

	@Override
	public String getEndpoint() {
		return this.endpoint;
	}

	@Override
	public void close() {
		stop();
	}

	/**
	 * Stop this server.
	 */
	public void stop() {
		server.stop(0);
	}

	/**
	 * Stop this server.
	 * @param delay the maximum time in seconds to wait until exchanges have finished
	 */
	public void stop(int delay) {
		server.stop(delay);
	}

	private HttpHandler createHttpHandler() {
		return exchange -> {
			try {
				if (exchange.getRequestMethod().equalsIgnoreCase(METHOD_POST)) {
					if (hasJsonContentType(exchange)) {
						Map<String, Object> body = body(exchange);
						HttpJsonRpcResponse rsp = transport.handlePost(exchange, body).block();
						sendResponse(rsp, exchange);
					}
					else {
						exchange.sendResponseHeaders(422, -1);
					}
				}
				else {
					exchange.sendResponseHeaders(405, -1);
				}
			}
			catch (IOException e) {
				if (LOG.isErrorEnabled()) {
					LOG.error(e.getMessage(), e);
				}
			}
			finally {
				exchange.close();
			}
		};
	}

	private void sendResponse(HttpJsonRpcResponse rsp, HttpExchange exchange) throws IOException {
		if (rsp == null || rsp.body() == null) {
			exchange.sendResponseHeaders(rsp != null ? rsp.statusCode() : 202, -1);
			return;
		}
		byte[] responseBytes = jsonMapper.writeValueAsBytes(rsp.body());
		exchange.getResponseHeaders().add(HEADER_CONTENT_TYPE, MEDIA_TYPE_APPLICATION_JSON);
		exchange.sendResponseHeaders(rsp.statusCode(), responseBytes.length);
		exchange.getResponseBody().write(responseBytes);
	}

	private boolean hasJsonContentType(HttpExchange exchange) {
		return exchange.getRequestHeaders().containsKey(HEADER_CONTENT_TYPE)
				&& exchange.getRequestHeaders().getFirst(HEADER_CONTENT_TYPE).equals(MEDIA_TYPE_APPLICATION_JSON);
	}

	private Map<String, Object> body(HttpExchange exchange) throws IOException {
		TypeRef<Map<String, Object>> typeRef = new TypeRef<>() {

		};
		byte[] requestBytes = exchange.getRequestBody().readAllBytes();
		return jsonMapper.readValue(requestBytes, typeRef);
	}

	private InetSocketAddress getAddress() {
		return server.getAddress();
	}

}
