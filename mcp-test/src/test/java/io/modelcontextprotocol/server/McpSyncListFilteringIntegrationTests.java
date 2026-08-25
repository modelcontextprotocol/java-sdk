package io.modelcontextprotocol.server;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.Servlet;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.AfterParameterizedClassInvocation;
import org.junit.jupiter.params.BeforeParameterizedClassInvocation;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;

import static io.modelcontextprotocol.util.ToolsUtils.EMPTY_JSON_SCHEMA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Named.named;

/**
 * Tests for {@link McpSyncListFilter} integration with sync and async servers. Most tests
 * are implemented using the sync API, as it falls through to the async implementation.
 * Some tests leverage the async API where it has specificities.
 * <p>
 * This is parameterized because the configuration API is duplicated across Server and
 * StatelessServer implementations.
 *
 * @author Daniel Garnier-Moiroux
 */
@ParameterizedClass
@MethodSource("serverTypes")
class McpSyncListFilteringIntegrationTests<SYNC_TOOL_SPEC, ASYNC_TOOL_SPEC> {

	@Parameter
	ServerFactory<SYNC_TOOL_SPEC, ASYNC_TOOL_SPEC> serverFactory;

	private static Tomcat tomcat;

	private static String baseUrl;

	private McpSyncClient mcpClient;

	private McpSyncHttpClientRequestCustomizer requestCustomizer = (builder, method, endpoint, body, context) -> {

	};

	@BeforeParameterizedClassInvocation
	static void createTransportAndStartTomcat(ServerFactory serverFactory) {
		var port = TomcatTestUtil.findAvailablePort();
		baseUrl = "http://localhost:" + port;
		startTomcat(serverFactory.transport(), port);
	}

	@BeforeEach
	void setUp() {
		var clientTransport = HttpClientStreamableHttpTransport.builder(baseUrl)
			.jsonMapper(McpJsonDefaults.getMapper())
			.httpRequestCustomizer((builder, method, endpoint, body, context) -> requestCustomizer.customize(builder,
					method, endpoint, body, context))
			.openConnectionOnStartup(true)
			.build();

		mcpClient = McpClient.sync(clientTransport).initializationTimeout(Duration.ofMillis(500)).build();

	}

	@AfterEach
	void tearDown() {
		mcpClient.closeGracefully();
	}

	@AfterParameterizedClassInvocation
	static void afterAll() {
		stopTomcat();
	}

	@Test
	void basicFilter() {
		var visible = serverFactory.namedSyncTool("visible-tool");
		var hidden = serverFactory.namedSyncTool("hidden-tool");

		serverFactory.syncServer()
			.tools(List.of(visible.spec(), hidden.spec()))
			.addToolFilter((context, tool) -> !tool.name().equals("hidden-tool"))
			.build();

		mcpClient.initialize();

		assertThat(mcpClient.listTools().tools()).containsExactly(visible.tool());
	}

	@Test
	void dynamicFilter() {
		var toolSpec = serverFactory.namedSyncTool("tool");
		var listCallCount = new AtomicInteger();

		serverFactory.syncServer()
			.tools(List.of(toolSpec.spec()))
			.addToolFilter((context, tool) -> listCallCount.incrementAndGet() < 2)
			.build();

		mcpClient.initialize();
		assertThat(mcpClient.listTools().tools()).containsExactly(toolSpec.tool());
		assertThat(mcpClient.listTools().tools()).isEmpty();
	}

	@Test
	void contextBasedFilter() {
		var toolSpec = serverFactory.namedSyncTool("toolSpec");

		serverFactory.syncServer()
			.tools(List.of(toolSpec.spec()))
			.addToolFilter(
					(McpTransportContext context, McpSchema.Tool tool) -> !"true".equals(context.get("x-filter-on")))
			.build();

		mcpClient.initialize();
		assertThat(mcpClient.listTools().tools()).containsExactly(toolSpec.tool());

		requestCustomizer = (builder, method, endpoint, body, context) -> {
			builder.header("x-filter-on", "true");
		};
		assertThat(mcpClient.listTools().tools()).isEmpty();
	}

	@Test
	void hiddenToolIsCallable() {
		var hidden = serverFactory.namedSyncTool("hidden-tool");

		serverFactory.syncServer().tools(List.of(hidden.spec())).addToolFilter((context, tool) -> false).build();

		mcpClient.initialize();
		assertThat(mcpClient.listTools().tools()).isEmpty();
		var response = mcpClient.callTool(McpSchema.CallToolRequest.builder("hidden-tool").arguments(Map.of()).build());
		assertThat(response.content()).containsExactly(McpSchema.TextContent.builder("called hidden-tool").build());
	}

	@Test
	void asyncFilter() {
		var visible = serverFactory.namedAsyncTool("visible-tool");
		var hidden = serverFactory.namedAsyncTool("hidden-tool");

		serverFactory.asyncServer()
			.tools(List.of(visible.spec(), hidden.spec()))
			.addToolFilter(
					(ctx, tool) -> Mono.delay(Duration.ofMillis(10)).thenReturn(tool.name().equals("visible-tool")))
			.build();

		mcpClient.initialize();
		assertThat(mcpClient.listTools().tools()).containsExactly(visible.tool());
	}

	@Test
	void filterErrorPropagates() {
		var toolSpec = serverFactory.namedAsyncTool("tool");

		serverFactory.asyncServer()
			.tools(List.of(toolSpec.spec()))
			.addToolFilter((ctx, tool) -> Mono.error(new RuntimeException("filter error")))
			.build();

		mcpClient.initialize();
		assertThatThrownBy(mcpClient::listTools).isInstanceOf(McpError.class).hasMessage("filter error");
	}

	@Test
	void filterEmptyCompletionOmits() {
		var visible = serverFactory.namedAsyncTool("visible-tool");
		var hidden = serverFactory.namedAsyncTool("hidden-tool");

		serverFactory.asyncServer().tools(List.of(visible.spec(), hidden.spec())).addToolFilter((ctx, tool) -> {
			if (tool.name().equals("hidden-tool")) {
				return Mono.empty();
			}
			else {
				return Mono.just(true);
			}
		}).build();

		mcpClient.initialize();
		assertThat(mcpClient.listTools().tools()).containsExactly(visible.tool());
	}

	@Test
	void multipleFilters() {
		var visible = serverFactory.namedSyncTool("visible-tool");
		var hidden = serverFactory.namedSyncTool("hidden-tool");
		var otherHidden = serverFactory.namedSyncTool("other-hidden-tool");

		serverFactory.syncServer()
			.tools(List.of(visible.spec(), hidden.spec(), otherHidden.spec()))
			.addToolFilter((context, tool) -> true)
			.addToolFilter((context, tool) -> !tool.name().equals("hidden-tool"))
			.addToolFilter((context, tool) -> !tool.name().equals("other-hidden-tool"))
			.build();

		mcpClient.initialize();

		assertThat(mcpClient.listTools().tools()).containsExactly(visible.tool());
	}

	@Test
	void multipleFiltersShortCircuitOnFirstRejection() {
		var visible = serverFactory.namedSyncTool("visible-tool");
		var hidden = serverFactory.namedSyncTool("hidden-tool");

		serverFactory.syncServer()
			.tools(List.of(visible.spec(), hidden.spec()))
			.addToolFilter((context, tool) -> "visible-tool".equals(tool.name()))
			.addToolFilter((context, tool) -> {
				if (tool.name().equals("hidden-tool")) {
					throw new RuntimeException("filter error");
				}
				else {
					return true;
				}
			})
			.build();

		mcpClient.initialize();

		assertThat(mcpClient.listTools().tools()).containsExactly(visible.tool());
	}

	@Test
	void toolsFilterConsumerSync() {
		var toolSpec = serverFactory.namedSyncTool("tool");

		serverFactory.syncServer()
			.tools(List.of(toolSpec.spec()))
			.addToolFilter((context, tool) -> false)
			.toolFilters(filters -> {
				assertThat(filters).hasSize(1);
				filters.clear();
			})
			.build();

		mcpClient.initialize();

		assertThat(mcpClient.listTools().tools()).containsExactly(toolSpec.tool());
	}

	@Test
	void toolsFilterConsumerAsync() {
		var toolSpec = serverFactory.namedAsyncTool("tool");

		serverFactory.asyncServer()
			.tools(List.of(toolSpec.spec()))
			.addToolFilter((context, tool) -> Mono.just(false))
			.toolFilters(filters -> {
				assertThat(filters).hasSize(1);
				filters.clear();
			})
			.build();

		mcpClient.initialize();

		assertThat(mcpClient.listTools().tools()).containsExactly(toolSpec.tool());
	}

	// ----------------------------------------------------
	// Test infrastructure
	//
	// ServerBuilderWrapper wraps stateless and stateful servers
	// into a common API that the tests can use, basically
	// hiding the builders behind a common type.
	//
	// The wrapper can produce BOTH sync and async variants
	// because the filters are different in every case, one
	// returning a boolean and the other a Mono<Boolean>.
	// The tests use BOTH apis.
	//
	// The factory allows you to build a wrapper fluently,
	// independent from the underlying type.
	//
	// ----------------------------------------------------

	static Stream<Arguments> serverTypes() {
		return Stream.of(Arguments.arguments(named("stateful", new StatefulServerFactory())),
				Arguments.arguments(named("stateless", new StatelessServerFactory())));
	}

	interface ServerBuilderWrapper<TOOL_SPEC, TOOL_FILTER> {

		ServerBuilderWrapper<TOOL_SPEC, TOOL_FILTER> tools(List<TOOL_SPEC> tools);

		ServerBuilderWrapper<TOOL_SPEC, TOOL_FILTER> addToolFilter(TOOL_FILTER toolFilter);

		ServerBuilderWrapper<TOOL_SPEC, TOOL_FILTER> toolFilters(Consumer<List<TOOL_FILTER>> toolFilterConsumer);

		void build();

	}

	interface ToolWrapper<TOOL_SPEC> {

		String name();

		McpSchema.Tool tool();

		TOOL_SPEC spec();

	}

	interface ServerFactory<SYNC_TOOL_SPEC, ASYNC_TOOL_SPEC> {

		ServerBuilderWrapper<SYNC_TOOL_SPEC, McpSyncListFilter<McpSchema.Tool>> syncServer();

		ServerBuilderWrapper<ASYNC_TOOL_SPEC, McpAsyncListFilter<McpSchema.Tool>> asyncServer();

		ToolWrapper<SYNC_TOOL_SPEC> namedSyncTool(String name);

		ToolWrapper<ASYNC_TOOL_SPEC> namedAsyncTool(String name);

		Servlet transport();

	}

	static class StatefulServerFactory implements
			ServerFactory<McpServerFeatures.SyncToolSpecification, McpServerFeatures.AsyncToolSpecification> {

		private final HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider
			.builder()
			.contextExtractor(request -> {
				var headers = new HashMap<String, Object>();
				var names = request.getHeaderNames();
				while (names.hasMoreElements()) {
					String name = names.nextElement();
					headers.put(name, request.getHeader(name));
				}
				return McpTransportContext.create(headers);
			})
			.build();

		@Override
		public ServerBuilderWrapper<McpServerFeatures.SyncToolSpecification, McpSyncListFilter<McpSchema.Tool>> syncServer() {
			return new ServerBuilderWrapper<>() {
				private final McpServer.SyncSpecification<McpServer.StreamableSyncSpecification> spec = McpServer
					.sync(transport)
					.serverInfo("test-server", "1.0.0")
					.capabilities(McpSchema.ServerCapabilities.builder().tools(false).build());

				@Override
				public ServerBuilderWrapper<McpServerFeatures.SyncToolSpecification, McpSyncListFilter<McpSchema.Tool>> tools(
						List<McpServerFeatures.SyncToolSpecification> tools) {
					spec.tools(tools);
					return this;
				}

				@Override
				public ServerBuilderWrapper<McpServerFeatures.SyncToolSpecification, McpSyncListFilter<McpSchema.Tool>> addToolFilter(
						McpSyncListFilter<McpSchema.Tool> toolFilter) {
					spec.addToolFilter(toolFilter);
					return this;
				}

				@Override
				public ServerBuilderWrapper<McpServerFeatures.SyncToolSpecification, McpSyncListFilter<McpSchema.Tool>> toolFilters(
						Consumer<List<McpSyncListFilter<McpSchema.Tool>>> toolFilterConsumer) {
					spec.toolFilters(toolFilterConsumer);
					return this;
				}

				@Override
				public void build() {
					spec.build();
				}
			};
		}

		@Override
		public ServerBuilderWrapper<McpServerFeatures.AsyncToolSpecification, McpAsyncListFilter<McpSchema.Tool>> asyncServer() {
			return new ServerBuilderWrapper<>() {
				private final McpServer.StreamableServerAsyncSpecification spec = (McpServer.StreamableServerAsyncSpecification) McpServer
					.async(transport)
					.serverInfo("test-server", "1.0.0")
					.capabilities(McpSchema.ServerCapabilities.builder().tools(false).build());

				@Override
				public ServerBuilderWrapper<McpServerFeatures.AsyncToolSpecification, McpAsyncListFilter<McpSchema.Tool>> tools(
						List<McpServerFeatures.AsyncToolSpecification> tools) {
					spec.tools(tools);
					return this;
				}

				@Override
				public ServerBuilderWrapper<McpServerFeatures.AsyncToolSpecification, McpAsyncListFilter<McpSchema.Tool>> addToolFilter(
						McpAsyncListFilter<McpSchema.Tool> toolFilter) {
					spec.addToolFilter(toolFilter);
					return this;
				}

				@Override
				public ServerBuilderWrapper<McpServerFeatures.AsyncToolSpecification, McpAsyncListFilter<McpSchema.Tool>> toolFilters(
						Consumer<List<McpAsyncListFilter<McpSchema.Tool>>> toolFilterConsumer) {
					spec.toolFilters(toolFilterConsumer);
					return this;
				}

				@Override
				public void build() {
					spec.build();
				}
			};
		}

		@Override
		public ToolWrapper<McpServerFeatures.SyncToolSpecification> namedSyncTool(String name) {
			var tool = McpServerFeatures.SyncToolSpecification.builder()
				.tool(McpSchema.Tool.builder(name, EMPTY_JSON_SCHEMA).description(name + " description").build())
				.callHandler((exchange, request) -> McpSchema.CallToolResult.builder()
					.addContent(McpSchema.TextContent.builder("called " + name).build())
					.build())
				.build();
			return new ToolWrapper<>() {

				@Override
				public String name() {
					return name;
				}

				@Override
				public McpSchema.Tool tool() {
					return tool.tool();
				}

				@Override
				public McpServerFeatures.SyncToolSpecification spec() {
					return tool;
				}
			};
		}

		@Override
		public ToolWrapper<McpServerFeatures.AsyncToolSpecification> namedAsyncTool(String name) {
			var tool = McpServerFeatures.AsyncToolSpecification.builder()
				.tool(McpSchema.Tool.builder(name, EMPTY_JSON_SCHEMA).description(name + " description").build())
				.callHandler((exchange,
						request) -> Mono.just(McpSchema.CallToolResult.builder()
							.addContent(McpSchema.TextContent.builder("called " + name).build())
							.build()))
				.build();
			return new ToolWrapper<>() {

				@Override
				public String name() {
					return name;
				}

				@Override
				public McpSchema.Tool tool() {
					return tool.tool();
				}

				@Override
				public McpServerFeatures.AsyncToolSpecification spec() {
					return tool;
				}
			};

		}

		@Override
		public Servlet transport() {
			return transport;
		}

	}

	static class StatelessServerFactory implements
			ServerFactory<McpStatelessServerFeatures.SyncToolSpecification, McpStatelessServerFeatures.AsyncToolSpecification> {

		private final HttpServletStatelessServerTransport transport = HttpServletStatelessServerTransport.builder()
			.contextExtractor(request -> {
				var headers = new HashMap<String, Object>();
				var names = request.getHeaderNames();
				while (names.hasMoreElements()) {
					String name = names.nextElement();
					headers.put(name, request.getHeader(name));
				}
				return McpTransportContext.create(headers);
			})
			.build();

		@Override
		public ServerBuilderWrapper<McpStatelessServerFeatures.SyncToolSpecification, McpSyncListFilter<McpSchema.Tool>> syncServer() {
			return new ServerBuilderWrapper<>() {
				private final McpServer.StatelessSyncSpecification spec = McpServer.sync(transport)
					.serverInfo("test-server", "1.0.0")
					.capabilities(McpSchema.ServerCapabilities.builder().tools(false).build());

				@Override
				public ServerBuilderWrapper<McpStatelessServerFeatures.SyncToolSpecification, McpSyncListFilter<McpSchema.Tool>> tools(
						List<McpStatelessServerFeatures.SyncToolSpecification> tools) {
					spec.tools(tools);
					return this;
				}

				@Override
				public ServerBuilderWrapper<McpStatelessServerFeatures.SyncToolSpecification, McpSyncListFilter<McpSchema.Tool>> addToolFilter(
						McpSyncListFilter<McpSchema.Tool> toolFilter) {
					spec.addToolFilter(toolFilter);
					return this;
				}

				@Override
				public ServerBuilderWrapper<McpStatelessServerFeatures.SyncToolSpecification, McpSyncListFilter<McpSchema.Tool>> toolFilters(
						Consumer<List<McpSyncListFilter<McpSchema.Tool>>> toolFilterConsumer) {
					spec.toolFilters(toolFilterConsumer);
					return this;
				}

				@Override
				public void build() {
					spec.build();
				}
			};
		}

		@Override
		public ServerBuilderWrapper<McpStatelessServerFeatures.AsyncToolSpecification, McpAsyncListFilter<McpSchema.Tool>> asyncServer() {
			return new ServerBuilderWrapper<>() {
				private final McpServer.StatelessAsyncSpecification spec = McpServer.async(transport)
					.serverInfo("test-server", "1.0.0")
					.capabilities(McpSchema.ServerCapabilities.builder().tools(false).build());

				@Override
				public ServerBuilderWrapper<McpStatelessServerFeatures.AsyncToolSpecification, McpAsyncListFilter<McpSchema.Tool>> tools(
						List<McpStatelessServerFeatures.AsyncToolSpecification> tools) {
					spec.tools(tools);
					return this;
				}

				@Override
				public ServerBuilderWrapper<McpStatelessServerFeatures.AsyncToolSpecification, McpAsyncListFilter<McpSchema.Tool>> addToolFilter(
						McpAsyncListFilter<McpSchema.Tool> toolFilter) {
					spec.addToolFilter(toolFilter);
					return this;
				}

				@Override
				public ServerBuilderWrapper<McpStatelessServerFeatures.AsyncToolSpecification, McpAsyncListFilter<McpSchema.Tool>> toolFilters(
						Consumer<List<McpAsyncListFilter<McpSchema.Tool>>> toolFilterConsumer) {
					spec.toolFilters(toolFilterConsumer);
					return this;
				}

				@Override
				public void build() {
					spec.build();
				}
			};
		}

		@Override
		public ToolWrapper<McpStatelessServerFeatures.SyncToolSpecification> namedSyncTool(String name) {
			var tool = McpStatelessServerFeatures.SyncToolSpecification.builder()
				.tool(McpSchema.Tool.builder(name, EMPTY_JSON_SCHEMA).description(name + " description").build())
				.callHandler((exchange, request) -> McpSchema.CallToolResult.builder()
					.addContent(McpSchema.TextContent.builder("called " + name).build())
					.build())
				.build();
			return new ToolWrapper<>() {

				@Override
				public String name() {
					return name;
				}

				@Override
				public McpSchema.Tool tool() {
					return tool.tool();
				}

				@Override
				public McpStatelessServerFeatures.SyncToolSpecification spec() {
					return tool;
				}
			};
		}

		@Override
		public ToolWrapper<McpStatelessServerFeatures.AsyncToolSpecification> namedAsyncTool(String name) {
			var tool = McpStatelessServerFeatures.AsyncToolSpecification.builder()
				.tool(McpSchema.Tool.builder(name, EMPTY_JSON_SCHEMA).description(name + " description").build())
				.callHandler((exchange,
						request) -> Mono.just(McpSchema.CallToolResult.builder()
							.addContent(McpSchema.TextContent.builder("called " + name).build())
							.build()))
				.build();
			return new ToolWrapper<>() {

				@Override
				public String name() {
					return name;
				}

				@Override
				public McpSchema.Tool tool() {
					return tool.tool();
				}

				@Override
				public McpStatelessServerFeatures.AsyncToolSpecification spec() {
					return tool;
				}
			};

		}

		@Override
		public Servlet transport() {
			return transport;
		}

	}

	// ----------------------------------------------------
	// Tomcat management
	// ----------------------------------------------------

	private static void startTomcat(jakarta.servlet.Servlet servlet, int port) {
		tomcat = TomcatTestUtil.createTomcatServer("", port, servlet);
		try {
			tomcat.start();
			assertThat(tomcat.getServer().getState()).isEqualTo(LifecycleState.STARTED);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to start Tomcat", e);
		}
	}

	private static void stopTomcat() {
		if (tomcat != null) {
			try {
				tomcat.stop();
				tomcat.destroy();
			}
			catch (LifecycleException e) {
				throw new RuntimeException("Failed to stop Tomcat", e);
			}
		}
	}

}
