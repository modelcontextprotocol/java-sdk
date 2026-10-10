/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.transport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.CallToolOutcome;
import io.modelcontextprotocol.modern.McpSchema.CallToolRequest;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.Implementation;
import io.modelcontextprotocol.modern.McpSchema.MetaKeys;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.McpSyncResponse;
import io.modelcontextprotocol.modern.server.feature.McpSyncToolRepository;
import io.modelcontextprotocol.modern.server.feature.ToolsFeature;
import io.modelcontextprotocol.modern.server.feature.ToolsPage;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;
import io.modelcontextprotocol.util.ToolsUtils;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sync user code served by {@link HttpServletMcpTransport} sees thread-locals a servlet
 * filter populated, for single and streaming responses alike - the guarantee Spring
 * Security's {@code SecurityContextHolder} relies on.
 */
class HttpServletMcpTransportThreadLocalIntegrationTests {

	private static final int PORT = TomcatTestUtil.findAvailablePort();

	private static final String ENDPOINT = "/mcp";

	private static final String FILTER_THREAD_HEADER = "X-Filter-Thread";

	private static final McpJsonMapper JSON_MAPPER = McpJsonDefaults.getMapper();

	/** Stands in for {@code SecurityContextHolder}. */
	private static final ThreadLocal<String> PRINCIPAL = new ThreadLocal<>();

	private static final Tool WHOAMI = Tool.builder("whoami", ToolsUtils.EMPTY_JSON_SCHEMA).build();

	private static final Tool WHOAMI_STREAMING = Tool.builder("whoami-streaming", ToolsUtils.EMPTY_JSON_SCHEMA).build();

	private static final Tool ADMIN_ONLY = Tool.builder("admin-only", ToolsUtils.EMPTY_JSON_SCHEMA).build();

	private static volatile CountDownLatch progressSeenByClient = new CountDownLatch(0);

	private static Tomcat tomcat;

	/** Authenticates from a header and clears the thread-local once the chain returns. */
	static final class PrincipalFilter implements Filter {

		@Override
		public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
				throws IOException, ServletException {
			PRINCIPAL.set(((HttpServletRequest) request).getHeader("X-Principal"));
			((HttpServletResponse) response).setHeader(FILTER_THREAD_HEADER, Thread.currentThread().getName());
			try {
				chain.doFilter(request, response);
			}
			finally {
				PRINCIPAL.remove();
			}
		}

	}

	private static String whoami() {
		return PRINCIPAL.get() + "@" + Thread.currentThread().getName();
	}

	private static CallToolResult text(String text) {
		return CallToolResult.builder().addContent(TextContent.builder(text).build()).build();
	}

	@BeforeAll
	static void startServer() {
		McpSyncToolRepository repo = new McpSyncToolRepository() {
			@Override
			public ToolsPage list(McpRequestContext ctx, String cursor) {
				return ToolsPage.of("admin".equals(PRINCIPAL.get()) ? List.of(WHOAMI, WHOAMI_STREAMING, ADMIN_ONLY)
						: List.of(WHOAMI, WHOAMI_STREAMING));
			}

			@Override
			public Tool find(McpRequestContext ctx, String name) {
				return list(ctx, null).tools()
					.stream()
					.filter(tool -> tool.name().equals(name))
					.findFirst()
					.orElse(null);
			}

			@Override
			public McpSyncResponse<CallToolOutcome> call(McpRequestContext ctx, CallToolRequest request) {
				return switch (request.name()) {
					case "whoami" -> McpSyncResponse.result(text(whoami()));
					case "whoami-streaming" -> McpSyncResponse.streaming(notifier -> {
						notifier.progress(0.5, 1.0, whoami());
						boolean released;
						try {
							released = progressSeenByClient.await(5, TimeUnit.SECONDS);
						}
						catch (InterruptedException ex) {
							Thread.currentThread().interrupt();
							released = false;
						}
						return text(whoami() + (released ? "" : " (progress never reached the client)"));
					});
					default -> throw McpException.invalidParams("Unknown tool: " + request.name());
				};
			}
		};

		McpServer server = McpServer.builder()
			.serverInfo(Implementation.builder("thread-local-test-server", "1.0.0").build())
			.jsonMapper(JSON_MAPPER)
			.feature(ToolsFeature.ofSync(repo))
			.build();

		HttpServletMcpTransport transport = HttpServletMcpTransport.builder(server)
			.jsonMapper(JSON_MAPPER)
			.endpoint(ENDPOINT)
			.build();

		tomcat = TomcatTestUtil.createTomcatServer("", PORT, transport, new PrincipalFilter());
		try {
			tomcat.start();
		}
		catch (LifecycleException e) {
			throw new RuntimeException(e);
		}
	}

	@AfterAll
	static void stopServer() throws LifecycleException {
		if (tomcat != null) {
			tomcat.stop();
			tomcat.destroy();
		}
	}

	private static HttpRequest post(String principal, String method, String name) throws IOException {
		Map<String, Object> meta = new HashMap<>();
		meta.put(MetaKeys.PROTOCOL_VERSION, McpSchema.LATEST_PROTOCOL_VERSION);
		meta.put(MetaKeys.CLIENT_CAPABILITIES, Map.of());
		meta.put(MetaKeys.PROGRESS_TOKEN, "tok-1");
		Map<String, Object> params = new HashMap<>();
		params.put("_meta", meta);
		if (name != null) {
			params.put("name", name);
		}
		Map<String, Object> body = Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params);
		HttpRequest.Builder builder = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + ENDPOINT))
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.header("Mcp-Method", method)
			.header("MCP-Protocol-Version", McpSchema.LATEST_PROTOCOL_VERSION)
			.header("X-Principal", principal)
			.POST(HttpRequest.BodyPublishers.ofString(JSON_MAPPER.writeValueAsString(body)));
		if (name != null) {
			builder.header("Mcp-Name", name);
		}
		return builder.build();
	}

	@Test
	void crossOriginRequestIsRejectedByDefault() throws Exception {
		HttpRequest request = HttpRequest.newBuilder(post("alice", "tools/list", null), (name, value) -> true)
			.header("Origin", "http://evil.example.com")
			.build();
		HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(403);
	}

	private static Map<String, Object> parse(String json) throws IOException {
		return JSON_MAPPER.readValue(json, new TypeRef<Map<String, Object>>() {
		});
	}

	@SuppressWarnings("unchecked")
	private static String resultText(Map<String, Object> response) {
		Map<String, Object> result = (Map<String, Object>) response.get("result");
		List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
		return (String) content.get(0).get("text");
	}

	@Test
	void syncSingleHandlerSeesFilterThreadLocalOnFilterThread() throws Exception {
		HttpResponse<String> response = HttpClient.newHttpClient()
			.send(post("alice", "tools/call", "whoami"), HttpResponse.BodyHandlers.ofString());

		String filterThread = response.headers().firstValue(FILTER_THREAD_HEADER).orElseThrow();
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(resultText(parse(response.body()))).isEqualTo("alice@" + filterThread);
	}

	@Test
	@SuppressWarnings("unchecked")
	void syncListDecidesWhatToServeFromFilterThreadLocal() throws Exception {
		HttpClient client = HttpClient.newHttpClient();
		HttpResponse<String> asAdmin = client.send(post("admin", "tools/list", null),
				HttpResponse.BodyHandlers.ofString());
		HttpResponse<String> asAlice = client.send(post("alice", "tools/list", null),
				HttpResponse.BodyHandlers.ofString());

		List<Object> adminTools = (List<Object>) ((Map<String, Object>) parse(asAdmin.body()).get("result"))
			.get("tools");
		List<Object> aliceTools = (List<Object>) ((Map<String, Object>) parse(asAlice.body()).get("result"))
			.get("tools");
		assertThat(adminTools).hasSize(3);
		assertThat(aliceTools).hasSize(2);
	}

	@Test
	@SuppressWarnings("unchecked")
	void syncStreamingHandlerSeesFilterThreadLocalAndStreamsProgressBeforeReturning() throws Exception {
		progressSeenByClient = new CountDownLatch(1);
		HttpResponse<Stream<String>> response = HttpClient.newHttpClient()
			.send(post("alice", "tools/call", "whoami-streaming"), HttpResponse.BodyHandlers.ofLines());

		String filterThread = response.headers().firstValue(FILTER_THREAD_HEADER).orElseThrow();
		assertThat(response.headers().firstValue("Content-Type"))
			.hasValueSatisfying(v -> assertThat(v).contains("text/event-stream"));

		Iterator<String> dataLines = response.body()
			.filter(line -> line.startsWith("data: "))
			.map(line -> line.substring("data: ".length()))
			.iterator();

		// The handler is blocked until this latch opens, so reading the progress event
		// here proves it was written and flushed while the handler was still running.
		Map<String, Object> progress = parse(dataLines.next());
		assertThat(progress.get("method")).isEqualTo("notifications/progress");
		assertThat(((Map<String, Object>) progress.get("params")).get("message")).isEqualTo("alice@" + filterThread);
		progressSeenByClient.countDown();

		Map<String, Object> terminal = parse(dataLines.next());
		assertThat(resultText(terminal)).isEqualTo("alice@" + filterThread);
		assertThat(dataLines.hasNext()).isFalse();
	}

}
