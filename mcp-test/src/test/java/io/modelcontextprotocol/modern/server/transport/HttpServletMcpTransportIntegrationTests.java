/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.transport;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.CallToolOutcome;
import io.modelcontextprotocol.modern.McpSchema.CallToolRequest;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.McpSchema.Implementation;
import io.modelcontextprotocol.modern.McpSchema.MetaKeys;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpAsyncResponse;
import io.modelcontextprotocol.modern.server.McpParamHeaders;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.feature.McpAsyncToolRepository;
import io.modelcontextprotocol.modern.server.feature.McpChangePublisher;
import io.modelcontextprotocol.modern.server.feature.ServerChange;
import io.modelcontextprotocol.modern.server.feature.ToolsFeature;
import io.modelcontextprotocol.modern.server.feature.ToolsPage;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;
import io.modelcontextprotocol.util.ToolsUtils;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

class HttpServletMcpTransportIntegrationTests {

	private static final int PORT = TomcatTestUtil.findAvailablePort();

	private static final String ENDPOINT = "/mcp";

	private static Tomcat tomcat;

	private static final McpJsonMapper JSON_MAPPER = McpJsonDefaults.getMapper();

	private static final Tool ECHO_TOOL = Tool.builder("echo", ToolsUtils.EMPTY_JSON_SCHEMA).build();

	private static final Tool REGION_TOOL = Tool.builder("region", JSON_MAPPER, """
			{
			  "type": "object",
			  "properties": {
			    "region": { "type": "string", "x-mcp-header": "Region" }
			  }
			}
			""").build();

	/**
	 * A request carrying this header makes the context extractor claim headers aren't
	 * mirrored.
	 */
	private static final String FORGE_NOT_MIRRORED = "X-Forge-Not-Mirrored";

	private static final int QUOTA_EXCEEDED = -32000;

	private static final CountDownLatch LISTEN_CANCELLED = new CountDownLatch(1);

	private static CallToolResult text(String text) {
		return CallToolResult.builder().addContent(TextContent.builder(text).build()).build();
	}

	@BeforeAll
	static void startServer() {
		McpAsyncToolRepository repo = new McpAsyncToolRepository() {
			@Override
			public Mono<ToolsPage> list(McpRequestContext ctx, String cursor) {
				return Mono.just(ToolsPage.of(List.of(ECHO_TOOL)));
			}

			@Override
			public Mono<Tool> find(McpRequestContext ctx, String name) {
				if ("region".equals(name)) {
					return Mono.just(REGION_TOOL);
				}
				return "does-not-exist".equals(name) ? Mono.empty()
						: Mono.just(Tool.builder(name, ToolsUtils.EMPTY_JSON_SCHEMA).build());
			}

			@Override
			public Mono<McpAsyncResponse<CallToolOutcome>> call(McpRequestContext ctx, CallToolRequest request) {
				McpAsyncResponse<CallToolOutcome> response = switch (request.name()) {
					case "echo" -> McpAsyncResponse.result(text("echo:" + request.name()));
					case "region" -> McpAsyncResponse.result(text("region:" + request.arguments().get("region")));
					case "streamer" -> McpAsyncResponse
						.streaming(notifier -> notifier.progress(1.0, 1.0, "done").thenReturn(text("streamed")));
					case "slow-streamer" -> McpAsyncResponse
						.streaming(notifier -> Mono.delay(Duration.ofMillis(350)).thenReturn(text("slow")));
					case "fails-early" -> throw McpException.invalidParams("bad arguments");
					case "fails-streaming" -> McpAsyncResponse
						.streaming(notifier -> Mono.error(new IllegalStateException("bug in the tool")));
					case "fails-streaming-deliberately" ->
						McpAsyncResponse.streaming(notifier -> Mono.error(McpException.invalidParams("bad arguments")));
					case "quota" -> throw new McpException(QUOTA_EXCEEDED, "Quota exceeded");
					case "unmapped" -> throw new McpException(-32001, "Application error");
					default -> throw McpException.invalidParams("Unknown tool: " + request.name());
				};
				return Mono.just(response);
			}
		};

		McpChangePublisher publisher = () -> Flux.<ServerChange>never().doOnCancel(LISTEN_CANCELLED::countDown);

		McpServer server = McpServer.builder()
			.serverInfo(Implementation.builder("modern-test-server", "1.0.0").build())
			.jsonMapper(JSON_MAPPER)
			.feature(ToolsFeature.ofAsync(repo))
			.subscriptions(publisher)
			.build();

		HttpServletMcpTransport transport = HttpServletMcpTransport.builder(server)
			.jsonMapper(JSON_MAPPER)
			.endpoint(ENDPOINT)
			.keepAliveInterval(Duration.ofMillis(100))
			.errorStatus(QUOTA_EXCEEDED, 429)
			.contextExtractor(request -> request.getHeader(FORGE_NOT_MIRRORED) != null
					? McpTransportContext
						.create(Map.of(McpParamHeaders.TRANSPORT_CONTEXT_KEY, McpParamHeaders.NOT_MIRRORED))
					: McpTransportContext.EMPTY)
			.httpHeaderValidator(DefaultServerTransportSecurityValidator.builder()
				.allowedOrigin("http://localhost:*")
				.allowedHost("localhost:*")
				.build())
			.build();

		tomcat = TomcatTestUtil.createTomcatServer("", PORT, transport);
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

	private static HttpRequest.Builder post(String method, Map<String, Object> params) throws IOException {
		Map<String, Object> body = new HashMap<>();
		body.put("jsonrpc", "2.0");
		body.put("id", 1);
		body.put("method", method);
		body.put("params", params);
		String json = JSON_MAPPER.writeValueAsString(body);
		return HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + ENDPOINT))
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.header("Mcp-Method", method)
			.header("MCP-Protocol-Version", McpSchema.LATEST_PROTOCOL_VERSION)
			.POST(HttpRequest.BodyPublishers.ofString(json));
	}

	private static Map<String, Object> meta() {
		Map<String, Object> meta = new HashMap<>();
		meta.put(MetaKeys.PROTOCOL_VERSION, McpSchema.LATEST_PROTOCOL_VERSION);
		meta.put(MetaKeys.CLIENT_CAPABILITIES, Map.of());
		return meta;
	}

	@Test
	void discoverReturnsSupportedVersions() throws Exception {
		HttpRequest request = post("server/discover", Map.of("_meta", meta())).build();
		HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type"))
			.hasValueSatisfying(v -> assertThat(v).contains("application/json"));
		Map<String, Object> parsed = JSON_MAPPER.readValue(response.body(), new TypeRef<Map<String, Object>>() {
		});
		@SuppressWarnings("unchecked")
		Map<String, Object> result = (Map<String, Object>) parsed.get("result");
		assertThat(result.get("supportedVersions")).isEqualTo(List.of(McpSchema.LATEST_PROTOCOL_VERSION));
	}

	@Test
	void singleToolCallReturnsJson() throws Exception {
		Map<String, Object> params = new HashMap<>();
		params.put("_meta", meta());
		params.put("name", "echo");
		HttpRequest request = post("tools/call", params).header("Mcp-Name", "echo").build();
		HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type"))
			.hasValueSatisfying(v -> assertThat(v).contains("application/json"));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "text/plain", "application/x-www-form-urlencoded", "multipart/form-data",
			"application/json-rpc", "application/jsonp", "text/plain; a=application/json" })
	void postWithNonJsonContentTypeIsRejected(String contentType) throws Exception {
		HttpRequest original = post("tools/call", Map.of("_meta", meta(), "name", "echo")).header("Mcp-Name", "echo")
			.build();
		HttpRequest.Builder builder = HttpRequest.newBuilder(original,
				(name, value) -> !name.equalsIgnoreCase("Content-Type"));
		if (contentType != null) {
			builder.header("Content-Type", contentType);
		}
		HttpResponse<String> response = HttpClient.newHttpClient()
			.send(builder.build(), HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(415);
	}

	@ParameterizedTest
	@ValueSource(strings = { "application/json; charset=utf-8", "Application/JSON", "Application/JSON; charset=UTF-8" })
	void postWithJsonContentTypeIsAccepted(String contentType) throws Exception {
		HttpRequest request = post("tools/call", Map.of("_meta", meta(), "name", "echo")).header("Mcp-Name", "echo")
			.setHeader("Content-Type", contentType)
			.build();
		HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("echo:echo");
	}

	@Test
	void streamingToolCallReturnsSse() throws Exception {
		Map<String, Object> meta = meta();
		meta.put(MetaKeys.PROGRESS_TOKEN, "tok-1");
		Map<String, Object> params = new HashMap<>();
		params.put("_meta", meta);
		params.put("name", "streamer");
		HttpRequest request = post("tools/call", params).header("Mcp-Name", "streamer").build();
		HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type"))
			.hasValueSatisfying(v -> assertThat(v).contains("text/event-stream"));
		assertThat(response.headers().firstValue("Cache-Control")).contains("no-cache, no-transform");
		assertThat(response.headers().firstValue("X-Accel-Buffering")).contains("no");
		assertThat(response.headers().firstValue("Connection")).isEmpty();
		assertThat(response.body()).contains("notifications/progress");
		assertThat(response.body()).contains("\"result\"");
	}

	private static HttpResponse<String> callTool(String name) throws Exception {
		Map<String, Object> params = new HashMap<>();
		params.put("_meta", meta());
		params.put("name", name);
		HttpRequest request = post("tools/call", params).header("Mcp-Name", name).build();
		return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void errorIsAnsweredAsJsonWithTheStatusForItsCode() throws Exception {
		HttpResponse<String> response = callTool("fails-early");

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.headers().firstValue("Content-Type"))
			.hasValueSatisfying(v -> assertThat(v).contains("application/json"));
		assertThat(errorCode(response)).isEqualTo(ErrorCodes.INVALID_PARAMS);
	}

	@Test
	void customErrorCodeUsesConfiguredStatus() throws Exception {
		HttpResponse<String> response = callTool("quota");

		assertThat(response.statusCode()).isEqualTo(429);
		assertThat(errorCode(response)).isEqualTo(QUOTA_EXCEEDED);
	}

	@Test
	void unmappedErrorCodeIsAnsweredWith200() throws Exception {
		HttpResponse<String> response = callTool("unmapped");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(errorCode(response)).isEqualTo(-32001);
	}

	@Test
	void unknownToolIsInvalidParams() throws Exception {
		HttpResponse<String> response = callTool("does-not-exist");

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(errorCode(response)).isEqualTo(ErrorCodes.INVALID_PARAMS);
	}

	@Test
	void streamingBodyExceptionIsAnsweredInStream() throws Exception {
		HttpResponse<String> response = callTool("fails-streaming");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type"))
			.hasValueSatisfying(v -> assertThat(v).contains("text/event-stream"));
		assertThat(response.body()).contains("\"code\":" + ErrorCodes.INTERNAL_ERROR);
	}

	@Test
	void streamingBodyMcpExceptionIsAnsweredInStreamWithItsCode() throws Exception {
		HttpResponse<String> response = callTool("fails-streaming-deliberately");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"code\":" + ErrorCodes.INVALID_PARAMS);
	}

	@Test
	void quietStreamGetsKeepAlives() throws Exception {
		HttpResponse<String> response = callTool("slow-streamer");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).startsWith(":\n\n").contains("\"result\"");
	}

	@Test
	void listenStreamNoticesClientDisconnect() throws Exception {
		Map<String, Object> params = Map.of("_meta", meta(), "notifications", Map.of("toolsListChanged", true));
		byte[] body = JSON_MAPPER
			.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", "subscriptions/listen", "params", params))
			.getBytes(StandardCharsets.UTF_8);
		String head = "POST " + ENDPOINT + " HTTP/1.1\r\n" + "Host: localhost:" + PORT + "\r\n"
				+ "Content-Type: application/json\r\n" + "Accept: application/json, text/event-stream\r\n"
				+ "Mcp-Method: subscriptions/listen\r\n" + "MCP-Protocol-Version: " + McpSchema.LATEST_PROTOCOL_VERSION
				+ "\r\n" + "Content-Length: " + body.length + "\r\n\r\n";

		try (Socket socket = new Socket("localhost", PORT)) {
			socket.setSoTimeout(5000);
			OutputStream out = socket.getOutputStream();
			out.write(head.getBytes(StandardCharsets.US_ASCII));
			out.write(body);
			out.flush();
			BufferedReader in = new BufferedReader(
					new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
			String line;
			while ((line = in.readLine()) != null && !line.contains("notifications/subscriptions/acknowledged")) {
				// skip status line, headers and chunk sizes until the ack arrives
			}
			assertThat(line).isNotNull();
		}

		// The stream is idle, so only a failing keep-alive write can reveal the
		// disconnect.
		assertThat(LISTEN_CANCELLED.await(5, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void getIsRejected() throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + ENDPOINT))
			.GET()
			.build();
		HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(405);
	}

	@Test
	void headerMismatchIsRejected() throws Exception {
		Map<String, Object> params = new HashMap<>();
		params.put("_meta", meta());
		params.put("name", "echo");
		HttpRequest request = post("tools/call", params).setHeader("Mcp-Method", "tools/list").build();
		HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(400);
		Map<String, Object> parsed = JSON_MAPPER.readValue(response.body(), new TypeRef<Map<String, Object>>() {
		});
		@SuppressWarnings("unchecked")
		Map<String, Object> error = (Map<String, Object>) parsed.get("error");
		assertThat(((Number) error.get("code")).intValue()).isEqualTo(ErrorCodes.HEADER_MISMATCH);
	}

	@Test
	void protocolVersionHeaderMismatchIsRejected() throws Exception {
		Map<String, Object> meta = meta();
		meta.put(MetaKeys.PROTOCOL_VERSION, "v999.0.0");
		HttpRequest request = post("server/discover", Map.of("_meta", meta)).build();
		HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(errorCode(response)).isEqualTo(ErrorCodes.HEADER_MISMATCH);
	}

	@Test
	void missingProtocolVersionHeaderIsRejected() throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + ENDPOINT))
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.header("Mcp-Method", "server/discover")
			.POST(HttpRequest.BodyPublishers.ofString(JSON_MAPPER.writeValueAsString(
					Map.of("jsonrpc", "2.0", "id", 1, "method", "server/discover", "params", Map.of("_meta", meta())))))
			.build();
		HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(errorCode(response)).isEqualTo(ErrorCodes.HEADER_MISMATCH);
	}

	@Test
	void malformedBase64McpNameIsRejected() throws Exception {
		Map<String, Object> params = new HashMap<>();
		params.put("_meta", meta());
		params.put("name", "echo");
		HttpRequest request = post("tools/call", params).header("Mcp-Name", "=?base64?not*base64?=").build();
		HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(errorCode(response)).isEqualTo(ErrorCodes.HEADER_MISMATCH);
	}

	@Test
	void base64McpNameIsAccepted() throws Exception {
		Map<String, Object> params = new HashMap<>();
		params.put("_meta", meta());
		params.put("name", "echo");
		HttpRequest request = post("tools/call", params).header("Mcp-Name", "=?base64?ZWNobw==?=").build();
		HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
	}

	@Test
	void unpaddedBase64McpNameIsRejected() throws Exception {
		Map<String, Object> params = new HashMap<>();
		params.put("_meta", meta());
		params.put("name", "echo");
		HttpRequest request = post("tools/call", params).header("Mcp-Name", "=?base64?ZWNobw?=").build();
		HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(errorCode(response)).isEqualTo(ErrorCodes.HEADER_MISMATCH);
	}

	@Test
	void mcpNameIsCheckedAgainstTheUriOfAResourceRead() throws Exception {
		Map<String, Object> params = new HashMap<>();
		params.put("_meta", meta());
		params.put("uri", "file:///b.txt");
		params.put("name", "file:///a.txt");
		HttpRequest request = post("resources/read", params).header("Mcp-Name", "file:///a.txt").build();
		HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(errorCode(response)).isEqualTo(ErrorCodes.HEADER_MISMATCH);
	}

	@Test
	void missingMcpNameIsRejectedForNonAsciiName() throws Exception {
		Map<String, Object> params = new HashMap<>();
		params.put("_meta", meta());
		params.put("name", "café");
		HttpRequest request = post("tools/call", params).build();
		HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(errorCode(response)).isEqualTo(ErrorCodes.HEADER_MISMATCH);
	}

	private static HttpRequest.Builder callRegion(String region) throws IOException {
		return post("tools/call", Map.of("_meta", meta(), "name", "region", "arguments", Map.of("region", region)))
			.header("Mcp-Name", "region");
	}

	@Test
	void paramHeaderMismatchIsRejectedAsJson() throws Exception {
		HttpRequest request = callRegion("eu-west1").header("Mcp-Param-Region", "us-east1").build();
		HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.headers().firstValue("Content-Type"))
			.hasValueSatisfying(v -> assertThat(v).contains("application/json"));
		assertThatJson(response.body()).inPath("$.error.code").isEqualTo(ErrorCodes.HEADER_MISMATCH);
		assertThatJson(response.body()).inPath("$.error.message")
			.isEqualTo("Mcp-Param-Region header does not match argument 'region'");
	}

	@Test
	void missingParamHeaderIsRejected() throws Exception {
		HttpRequest request = callRegion("eu-west1").build();
		HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(400);
		assertThatJson(response.body()).inPath("$.error.code").isEqualTo(ErrorCodes.HEADER_MISMATCH);
		assertThatJson(response.body()).inPath("$.error.message")
			.isEqualTo("Missing required header: Mcp-Param-Region");
	}

	@Test
	void matchingBase64ParamHeaderIsAccepted() throws Exception {
		HttpRequest request = callRegion("Hello").header("Mcp-Param-Region", "=?base64?SGVsbG8=?=").build();
		HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThatJson(response.body()).inPath("$.result.content[0].text").isEqualTo("region:Hello");
	}

	@Test
	void contextExtractorCannotSwitchOffParamHeaderValidation() throws Exception {
		HttpRequest request = callRegion("eu-west1").header("Mcp-Param-Region", "us-east1")
			.header(FORGE_NOT_MIRRORED, "true")
			.build();
		HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(400);
		assertThatJson(response.body()).inPath("$.error.code").isEqualTo(ErrorCodes.HEADER_MISMATCH);
	}

	@Test
	void lowercaseParamHeaderNameIsAccepted() throws Exception {
		HttpRequest request = callRegion("eu-west1").header("mcp-param-region", "eu-west1").build();
		HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThatJson(response.body()).inPath("$.result.content[0].text").isEqualTo("region:eu-west1");
	}

	@Test
	void whitespaceAroundPlainParamHeaderValueIsTrimmed() throws Exception {
		HttpRequest request = callRegion("eu-west1").header("Mcp-Param-Region", "  eu-west1\t ").build();
		HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThatJson(response.body()).inPath("$.result.content[0].text").isEqualTo("region:eu-west1");
	}

	@Test
	void duplicatedParamHeaderIsRejected() throws Exception {
		HttpRequest request = callRegion("eu-west1").header("Mcp-Param-Region", "eu-west1")
			.header("mcp-param-region", "eu-west1")
			.build();
		HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(400);
		assertThatJson(response.body()).inPath("$.error.code").isEqualTo(ErrorCodes.HEADER_MISMATCH);
		assertThatJson(response.body()).inPath("$.error.message").isEqualTo("Mcp-Param-Region header is malformed");
	}

	@Test
	void rawNonAsciiBytesInPlainParamHeaderValueAreRejected() throws Exception {
		// HttpClient replaces non-ASCII header characters with '?', so the raw UTF-8
		// bytes of "café" are written to a socket instead.
		Map<String, Object> params = Map.of("_meta", meta(), "name", "region", "arguments", Map.of("region", "café"));
		byte[] body = JSON_MAPPER
			.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", params))
			.getBytes(StandardCharsets.UTF_8);
		ByteArrayOutputStream request = new ByteArrayOutputStream();
		// HTTP/1.0, so the response is not chunked and ends when the server closes
		request.write(("POST " + ENDPOINT + " HTTP/1.0\r\n" + "Host: localhost:" + PORT + "\r\n"
				+ "Content-Type: application/json\r\n" + "Accept: application/json, text/event-stream\r\n"
				+ "Mcp-Method: tools/call\r\n" + "Mcp-Name: region\r\n" + "MCP-Protocol-Version: "
				+ McpSchema.LATEST_PROTOCOL_VERSION + "\r\n" + "Content-Length: " + body.length + "\r\n"
				+ "Mcp-Param-Region: ")
			.getBytes(StandardCharsets.US_ASCII));
		request.write("café".getBytes(StandardCharsets.UTF_8));
		request.write("\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
		request.write(body);

		String response;
		try (Socket socket = new Socket("localhost", PORT)) {
			socket.setSoTimeout(5000);
			socket.getOutputStream().write(request.toByteArray());
			socket.getOutputStream().flush();
			response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		}
		String statusLine = response.substring(0, response.indexOf("\r\n"));
		String responseBody = response.substring(response.indexOf("\r\n\r\n") + 4);

		assertThat(statusLine).contains(" 400 ");
		assertThatJson(responseBody).inPath("$.error.code").isEqualTo(ErrorCodes.HEADER_MISMATCH);
		assertThatJson(responseBody).inPath("$.error.message").isEqualTo("Mcp-Param-Region header is malformed");
	}

	@Test
	void invalidJsonIsAnsweredWithJsonRpcParseError() throws Exception {
		HttpResponse<String> response = HttpClient.newHttpClient()
			.send(rawPost("not json at all"), HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(400);
		Map<String, Object> parsed = JSON_MAPPER.readValue(response.body(), new TypeRef<Map<String, Object>>() {
		});
		assertThat(parsed).containsOnlyKeys("jsonrpc", "error");
		assertThat(errorCode(response)).isEqualTo(ErrorCodes.PARSE_ERROR);
	}

	@Test
	void nullIdIsAnsweredWithInvalidRequest() throws Exception {
		HttpResponse<String> response = HttpClient.newHttpClient()
			.send(rawPost("{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"tools/list\",\"params\":{}}"),
					HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(400);
		Map<String, Object> parsed = JSON_MAPPER.readValue(response.body(), new TypeRef<Map<String, Object>>() {
		});
		assertThat(parsed).containsOnlyKeys("jsonrpc", "error");
		assertThat(errorCode(response)).isEqualTo(ErrorCodes.INVALID_REQUEST);
	}

	private static HttpRequest rawPost(String body) {
		return HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + ENDPOINT))
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build();
	}

	@Test
	void disallowedOriginIsRejected() throws Exception {
		HttpRequest request = post("server/discover", Map.of("_meta", meta()))
			.header("Origin", "http://evil.example.com")
			.build();
		HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(403);
	}

	private static int errorCode(HttpResponse<String> response) throws IOException {
		Map<String, Object> parsed = JSON_MAPPER.readValue(response.body(), new TypeRef<Map<String, Object>>() {
		});
		@SuppressWarnings("unchecked")
		Map<String, Object> error = (Map<String, Object>) parsed.get("error");
		return ((Number) error.get("code")).intValue();
	}

	@Test
	void missingMetaIsRejected() throws Exception {
		HttpRequest request = post("tools/list", Map.of()).build();
		HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(400);
	}

	@Test
	void notificationIsAccepted() throws Exception {
		Map<String, Object> body = Map.of("jsonrpc", "2.0", "method", "notifications/cancelled", "params",
				Map.of("requestId", 1));
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + ENDPOINT))
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.header("Mcp-Method", "notifications/cancelled")
			.POST(HttpRequest.BodyPublishers.ofString(JSON_MAPPER.writeValueAsString(body)))
			.build();
		HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(202);
	}

}
