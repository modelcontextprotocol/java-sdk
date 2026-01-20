/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.BlobResourceContents;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.ListResourceTemplatesResult;
import io.modelcontextprotocol.spec.McpSchema.ListResourcesResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.ResourceContents;
import io.modelcontextprotocol.spec.McpSchema.Root;
import io.modelcontextprotocol.spec.McpSchema.SubscribeRequest;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.UnsubscribeRequest;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/**
 * Unit tests for MCP Client Session functionality.
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 */
// KEEP IN SYNC with the class in mcp-test module
public abstract class AbstractMcpSyncClientTests {

	private static final Logger logger = LoggerFactory.getLogger(AbstractMcpSyncClientTests.class);

	private static final String TEST_MESSAGE = "Hello MCP Spring AI!";

	abstract protected McpClientTransport createMcpTransport();

	protected Duration getRequestTimeout() {
		return Duration.ofSeconds(14);
	}

	protected Duration getInitializationTimeout() {
		return Duration.ofSeconds(2);
	}

	McpSyncClient client(McpClientTransport transport) {
		return client(transport, Function.identity());
	}

	McpSyncClient client(McpClientTransport transport, Function<McpClient.SyncSpec, McpClient.SyncSpec> customizer) {
		AtomicReference<McpSyncClient> client = new AtomicReference<>();

		assertThatCode(() -> {
			McpClient.SyncSpec builder = McpClient.sync(transport)
				.requestTimeout(getRequestTimeout())
				.initializationTimeout(getInitializationTimeout())
				.capabilities(ClientCapabilities.builder().roots(true).build());
			builder = customizer.apply(builder);
			client.set(builder.build());
		}).doesNotThrowAnyException();

		return client.get();
	}

	void withClient(McpClientTransport transport, Consumer<McpSyncClient> c) {
		withClient(transport, Function.identity(), c);
	}

	void withClient(McpClientTransport transport, Function<McpClient.SyncSpec, McpClient.SyncSpec> customizer,
			Consumer<McpSyncClient> c) {
		var client = client(transport, customizer);
		try {
			c.accept(client);
		}
		finally {
			assertThat(client.closeGracefully()).isTrue();
		}
	}

	static final Object DUMMY_RETURN_VALUE = new Object();

	<T> void verifyNotificationSucceedsWithImplicitInitialization(Consumer<McpSyncClient> operation, String action) {
		verifyCallSucceedsWithImplicitInitialization(client -> {
			operation.accept(client);
			return DUMMY_RETURN_VALUE;
		}, action);
	}

	<T> void verifyCallSucceedsWithImplicitInitialization(Function<McpSyncClient, T> blockingOperation, String action) {
		withClient(createMcpTransport(), mcpSyncClient -> {
			StepVerifier.create(Mono.fromSupplier(() -> blockingOperation.apply(mcpSyncClient))
				// Offload the blocking call to the real scheduler
				.subscribeOn(Schedulers.boundedElastic())).expectNextCount(1).verifyComplete();
		});
	}

	@Test
	void testConstructorWithInvalidArguments() {
		assertThatThrownBy(() -> McpClient.sync(null).build()).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Transport must not be null");

		assertThatThrownBy(() -> McpClient.sync(createMcpTransport()).requestTimeout(null).build())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Request timeout must not be null");
	}

	@Test
	void testListToolsWithoutInitialization() {
		verifyCallSucceedsWithImplicitInitialization(client -> client.listTools(McpSchema.FIRST_PAGE), "listing tools");
	}

	@Test
	void testListTools() {
		withClient(createMcpTransport(), mcpSyncClient -> {
			mcpSyncClient.initialize();
			ListToolsResult tools = mcpSyncClient.listTools(McpSchema.FIRST_PAGE);

			assertThat(tools).isNotNull().satisfies(result -> {
				assertThat(result.tools()).isNotNull().isNotEmpty();

				Tool firstTool = result.tools().get(0);
				assertThat(firstTool.name()).isNotNull();
				assertThat(firstTool.description()).isNotNull();
			});
		});
	}

	@Test
	void testListAllTools() {
		withClient(createMcpTransport(), mcpSyncClient -> {
			mcpSyncClient.initialize();
			ListToolsResult tools = mcpSyncClient.listTools();

			assertThat(tools).isNotNull().satisfies(result -> {
				assertThat(result.tools()).isNotNull().isNotEmpty();

				Tool firstTool = result.tools().get(0);
				assertThat(firstTool.name()).isNotNull();
				assertThat(firstTool.description()).isNotNull();
			});
		});
	}

	@Test
	void testCallToolsWithoutInitialization() {
		verifyCallSucceedsWithImplicitInitialization(
				client -> client.callTool(new CallToolRequest("add", Map.of("a", 3, "b", 4))), "calling tools");
	}

	@Test
	void testCallTools() {
		withClient(createMcpTransport(), mcpSyncClient -> {
			mcpSyncClient.initialize();
			CallToolResult toolResult = mcpSyncClient.callTool(new CallToolRequest("add", Map.of("a", 3, "b", 4)));

			assertThat(toolResult).isNotNull().satisfies(result -> {

				assertThat(result.content()).hasSize(1);

				TextContent content = (TextContent) result.content().get(0);

				assertThat(content).isNotNull();
				assertThat(content.text()).isNotNull();
				assertThat(content.text()).contains("7");
			});
		});
	}

	@Test
	void testPingWithoutInitialization() {
		verifyCallSucceedsWithImplicitInitialization(client -> client.ping(), "pinging the server");
	}

	@Test
	void testPing() {
		withClient(createMcpTransport(), mcpSyncClient -> {
			mcpSyncClient.initialize();
			assertThatCode(() -> mcpSyncClient.ping()).doesNotThrowAnyException();
		});
	}

	@Test
	void testCallToolWithoutInitialization() {
		CallToolRequest callToolRequest = new CallToolRequest("echo", Map.of("message", TEST_MESSAGE));
		verifyCallSucceedsWithImplicitInitialization(client -> client.callTool(callToolRequest), "calling tools");
	}

	@Test
	void testCallTool() {
		withClient(createMcpTransport(), mcpSyncClient -> {
			mcpSyncClient.initialize();
			CallToolRequest callToolRequest = new CallToolRequest("echo", Map.of("message", TEST_MESSAGE));

			CallToolResult callToolResult = mcpSyncClient.callTool(callToolRequest);

			assertThat(callToolResult).isNotNull().satisfies(result -> {
				assertThat(result.content()).isNotNull();
				assertThat(result.isError()).isNull();
			});
		});
	}

	@Test
	void testCallToolWithInvalidTool() {
		withClient(createMcpTransport(), mcpSyncClient -> {
			CallToolRequest invalidRequest = new CallToolRequest("nonexistent_tool", Map.of("message", TEST_MESSAGE));

			assertThatThrownBy(() -> mcpSyncClient.callTool(invalidRequest)).isInstanceOf(Exception.class);
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "success", "error", "debug" })
	void testCallToolWithMessageAnnotations(String messageType) {
		McpClientTransport transport = createMcpTransport();

		withClient(transport, client -> {
			client.initialize();

			McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest("annotatedMessage",
					Map.of("messageType", messageType, "includeImage", true)));

			assertThat(result).isNotNull();
			assertThat(result.isError()).isNotEqualTo(true);
			assertThat(result.content()).isNotEmpty();
			assertThat(result.content()).allSatisfy(content -> {
				switch (content.type()) {
					case "text":
						McpSchema.TextContent textContent = assertInstanceOf(McpSchema.TextContent.class, content);
						assertThat(textContent.text()).isNotEmpty();
						assertThat(textContent.annotations()).isNotNull();

						switch (messageType) {
							case "error":
								assertThat(textContent.annotations().priority()).isEqualTo(1.0);
								assertThat(textContent.annotations().audience()).containsOnly(McpSchema.Role.USER,
										McpSchema.Role.ASSISTANT);
								break;
							case "success":
								assertThat(textContent.annotations().priority()).isEqualTo(0.7);
								assertThat(textContent.annotations().audience()).containsExactly(McpSchema.Role.USER);
								break;
							case "debug":
								assertThat(textContent.annotations().priority()).isEqualTo(0.3);
								assertThat(textContent.annotations().audience())
									.containsExactly(McpSchema.Role.ASSISTANT);
								break;
							default:
								throw new IllegalStateException("Unexpected value: " + content.type());
						}
						break;
					case "image":
						McpSchema.ImageContent imageContent = assertInstanceOf(McpSchema.ImageContent.class, content);
						assertThat(imageContent.data()).isNotEmpty();
						assertThat(imageContent.annotations()).isNotNull();
						assertThat(imageContent.annotations().priority()).isEqualTo(0.5);
						assertThat(imageContent.annotations().audience()).containsExactly(McpSchema.Role.USER);
						break;
					default:
						fail("Unexpected content type: " + content.type());
				}
			});
		});
	}

	@Test
	void testRootsListChangedWithoutInitialization() {
		verifyNotificationSucceedsWithImplicitInitialization(client -> client.rootsListChangedNotification(),
				"sending roots list changed notification");
	}

	@Test
	void testRootsListChanged() {
		withClient(createMcpTransport(), mcpSyncClient -> {
			mcpSyncClient.initialize();
			assertThatCode(() -> mcpSyncClient.rootsListChangedNotification()).doesNotThrowAnyException();
		});
	}

	@Test
	void testListResourcesWithoutInitialization() {
		verifyCallSucceedsWithImplicitInitialization(client -> client.listResources(McpSchema.FIRST_PAGE),
				"listing resources");
	}

	@Test
	void testListResources() {
		withClient(createMcpTransport(), mcpSyncClient -> {
			mcpSyncClient.initialize();
			ListResourcesResult resources = mcpSyncClient.listResources(McpSchema.FIRST_PAGE);

			assertThat(resources).isNotNull().satisfies(result -> {
				assertThat(result.resources()).isNotNull();

				if (!result.resources().isEmpty()) {
					Resource firstResource = result.resources().get(0);
					assertThat(firstResource.uri()).isNotNull();
					assertThat(firstResource.name()).isNotNull();
				}
			});
		});
	}

	@Test
	void testListAllResources() {
		withClient(createMcpTransport(), mcpSyncClient -> {
			mcpSyncClient.initialize();
			ListResourcesResult resources = mcpSyncClient.listResources();

			assertThat(resources).isNotNull().satisfies(result -> {
				assertThat(result.resources()).isNotNull();

				if (!result.resources().isEmpty()) {
					Resource firstResource = result.resources().get(0);
					assertThat(firstResource.uri()).isNotNull();
					assertThat(firstResource.name()).isNotNull();
				}
			});
		});
	}

	@Test
	void testClientSessionState() {
		withClient(createMcpTransport(), mcpSyncClient -> {
			assertThat(mcpSyncClient).isNotNull();
		});
	}

	@Test
	void testInitializeWithRootsListProviders() {
		withClient(createMcpTransport(), builder -> builder.roots(new Root("file:///test/path", "test-root")),
				mcpSyncClient -> {

					assertThatCode(() -> {
						mcpSyncClient.initialize();
						mcpSyncClient.close();
					}).doesNotThrowAnyException();
				});
	}

	@Test
	void testAddRoot() {
		withClient(createMcpTransport(), mcpSyncClient -> {
			Root newRoot = new Root("file:///new/test/path", "new-test-root");
			assertThatCode(() -> mcpSyncClient.addRoot(newRoot)).doesNotThrowAnyException();
		});
	}

	@Test
	void testAddRootWithNullValue() {
		withClient(createMcpTransport(), mcpSyncClient -> {
			assertThatThrownBy(() -> mcpSyncClient.addRoot(null)).hasMessageContaining("Root must not be null");
		});
	}

	@Test
	void testRemoveRoot() {
		withClient(createMcpTransport(), mcpSyncClient -> {
			Root root = new Root("file:///test/path/to/remove", "root-to-remove");
			assertThatCode(() -> {
				mcpSyncClient.addRoot(root);
				mcpSyncClient.removeRoot(root.uri());
			}).doesNotThrowAnyException();
		});
	}

	@Test
	void testRemoveNonExistentRoot() {
		withClient(createMcpTransport(), mcpSyncClient -> {
			assertThatThrownBy(() -> mcpSyncClient.removeRoot("nonexistent-uri"))
				.hasMessageContaining("Root with uri 'nonexistent-uri' not found");
		});
	}

	@Test
	void testReadResourceWithoutInitialization() {
		AtomicReference<List<Resource>> resources = new AtomicReference<>();
		withClient(createMcpTransport(), mcpSyncClient -> {
			mcpSyncClient.initialize();
			resources.set(mcpSyncClient.listResources().resources());
		});

		verifyCallSucceedsWithImplicitInitialization(client -> client.readResource(resources.get().get(0)),
				"reading resources");
	}

	@Test
	void testReadResource() {
		withClient(createMcpTransport(), mcpSyncClient -> {

			int readResourceCount = 0;

			mcpSyncClient.initialize();
			ListResourcesResult resources = mcpSyncClient.listResources(null);

			assertThat(resources).isNotNull();
			assertThat(resources.resources()).isNotNull();

			assertThat(resources.resources()).isNotNull().isNotEmpty();

			// Test reading each resource individually for better error isolation
			for (Resource resource : resources.resources()) {
				ReadResourceResult result = mcpSyncClient.readResource(resource);

				assertThat(result).isNotNull();
				assertThat(result.contents()).isNotNull().isNotEmpty();

				readResourceCount++;

				// Validate each content item
				for (ResourceContents content : result.contents()) {
					assertThat(content).isNotNull();
					assertThat(content.uri()).isNotNull().isNotEmpty();
					assertThat(content.mimeType()).isNotNull().isNotEmpty();

					// Validate content based on its type with more comprehensive
					// checks
					switch (content.mimeType()) {
						case "text/plain" -> {
							TextResourceContents textContent = assertInstanceOf(TextResourceContents.class, content);
							assertThat(textContent.text()).isNotNull().isNotEmpty();
							// Verify URI consistency
							assertThat(textContent.uri()).isEqualTo(resource.uri());
						}
						case "application/octet-stream" -> {
							BlobResourceContents blobContent = assertInstanceOf(BlobResourceContents.class, content);
							assertThat(blobContent.blob()).isNotNull().isNotEmpty();
							// Verify URI consistency
							assertThat(blobContent.uri()).isEqualTo(resource.uri());
							// Validate base64 encoding format
							assertThat(blobContent.blob()).matches("^[A-Za-z0-9+/]*={0,2}$");
						}
						default -> {
							// More flexible handling of additional MIME types
							// Log the unexpected type for debugging but don't fail
							// the test
							logger.warn("Warning: Encountered unexpected MIME type: {} for resource: {}",
									content.mimeType(), resource.uri());

							// Still validate basic properties
							if (content instanceof TextResourceContents textContent) {
								assertThat(textContent.text()).isNotNull();
							}
							else if (content instanceof BlobResourceContents blobContent) {
								assertThat(blobContent.blob()).isNotNull();
							}
						}
					}
				}
			}

			// Assert that we read exactly 10 resources
			assertThat(readResourceCount).isEqualTo(10);
		});
	}

	@Test
	void testListResourceTemplatesWithoutInitialization() {
		verifyCallSucceedsWithImplicitInitialization(client -> client.listResourceTemplates(McpSchema.FIRST_PAGE),
				"listing resource templates");
	}

	@Test
	void testListResourceTemplates() {
		withClient(createMcpTransport(), mcpSyncClient -> {
			mcpSyncClient.initialize();
			ListResourceTemplatesResult result = mcpSyncClient.listResourceTemplates(McpSchema.FIRST_PAGE);

			assertThat(result).isNotNull();
			assertThat(result.resourceTemplates()).isNotNull();
		});
	}

	@Test
	void testListAllResourceTemplates() {
		withClient(createMcpTransport(), mcpSyncClient -> {
			mcpSyncClient.initialize();
			ListResourceTemplatesResult result = mcpSyncClient.listResourceTemplates();

			assertThat(result).isNotNull();
			assertThat(result.resourceTemplates()).isNotNull();
		});
	}

	// @Test
	void testResourceSubscription() {
		withClient(createMcpTransport(), mcpSyncClient -> {
			ListResourcesResult resources = mcpSyncClient.listResources(null);

			if (!resources.resources().isEmpty()) {
				Resource firstResource = resources.resources().get(0);

				// Test subscribe
				assertThatCode(() -> mcpSyncClient.subscribeResource(new SubscribeRequest(firstResource.uri())))
					.doesNotThrowAnyException();

				// Test unsubscribe
				assertThatCode(() -> mcpSyncClient.unsubscribeResource(new UnsubscribeRequest(firstResource.uri())))
					.doesNotThrowAnyException();
			}
		});
	}

	@Test
	void testNotificationHandlers() {
		AtomicBoolean toolsNotificationReceived = new AtomicBoolean(false);
		AtomicBoolean resourcesNotificationReceived = new AtomicBoolean(false);
		AtomicBoolean promptsNotificationReceived = new AtomicBoolean(false);
		AtomicBoolean resourcesUpdatedNotificationReceived = new AtomicBoolean(false);

		withClient(createMcpTransport(),
				builder -> builder.toolsChangeConsumer(tools -> toolsNotificationReceived.set(true))
					.resourcesChangeConsumer(resources -> resourcesNotificationReceived.set(true))
					.promptsChangeConsumer(prompts -> promptsNotificationReceived.set(true))
					.resourcesUpdateConsumer(resources -> resourcesUpdatedNotificationReceived.set(true)),
				client -> {

					assertThatCode(() -> {
						client.initialize();
						client.close();
					}).doesNotThrowAnyException();
				});
	}

	// ---------------------------------------
	// Logging Tests
	// ---------------------------------------

	@Test
	void testLoggingLevelsWithoutInitialization() {
		verifyNotificationSucceedsWithImplicitInitialization(
				client -> client.setLoggingLevel(McpSchema.LoggingLevel.DEBUG), "setting logging level");
	}

	@Test
	void testLoggingLevels() {
		withClient(createMcpTransport(), mcpSyncClient -> {
			mcpSyncClient.initialize();
			// Test all logging levels
			for (McpSchema.LoggingLevel level : McpSchema.LoggingLevel.values()) {
				assertThatCode(() -> mcpSyncClient.setLoggingLevel(level)).doesNotThrowAnyException();
			}
		});
	}

	@Test
	void testLoggingConsumer() {
		AtomicBoolean logReceived = new AtomicBoolean(false);
		withClient(createMcpTransport(), builder -> builder.requestTimeout(getRequestTimeout())
			.loggingConsumer(notification -> logReceived.set(true)), client -> {
				assertThatCode(() -> {
					client.initialize();
					client.close();
				}).doesNotThrowAnyException();
			});
	}

	@Test
	void testLoggingWithNullNotification() {
		withClient(createMcpTransport(), mcpSyncClient -> assertThatThrownBy(() -> mcpSyncClient.setLoggingLevel(null))
			.hasMessageContaining("Logging level must not be null"));
	}

	@Test
	void testSampling() {
		McpClientTransport transport = createMcpTransport();

		final String message = "Hello, world!";
		final String response = "Goodbye, world!";
		final int maxTokens = 100;

		AtomicReference<String> receivedPrompt = new AtomicReference<>();
		AtomicReference<String> receivedMessage = new AtomicReference<>();
		AtomicInteger receivedMaxTokens = new AtomicInteger();

		withClient(transport, spec -> spec.capabilities(McpSchema.ClientCapabilities.builder().sampling().build())
			.sampling(request -> {
				McpSchema.TextContent messageText = assertInstanceOf(McpSchema.TextContent.class,
						request.messages().get(0).content());
				receivedPrompt.set(request.systemPrompt());
				receivedMessage.set(messageText.text());
				receivedMaxTokens.set(request.maxTokens());

				return new McpSchema.CreateMessageResult(McpSchema.Role.USER, new McpSchema.TextContent(response),
						"modelId", McpSchema.CreateMessageResult.StopReason.END_TURN);
			}), client -> {
				client.initialize();

				McpSchema.CallToolResult result = client.callTool(
						new McpSchema.CallToolRequest("sampleLLM", Map.of("prompt", message, "maxTokens", maxTokens)));

				// Verify tool response to ensure our sampling response was passed through
				assertThat(result.content()).hasAtLeastOneElementOfType(McpSchema.TextContent.class);
				assertThat(result.content()).allSatisfy(content -> {
					if (!(content instanceof McpSchema.TextContent text))
						return;

					assertThat(text.text()).contains(response);
				});

				// Verify sampling request parameters received in our callback
				assertThat(receivedPrompt.get()).isNotEmpty();
				assertThat(receivedMessage.get()).endsWith(message); // Prefixed
				assertThat(receivedMaxTokens.get()).isEqualTo(maxTokens);
			});
	}

	// ---------------------------------------
	// Progress Notification Tests
	// ---------------------------------------

	@Test
	void testProgressConsumer() {
		AtomicInteger progressNotificationCount = new AtomicInteger(0);
		List<McpSchema.ProgressNotification> receivedNotifications = new CopyOnWriteArrayList<>();
		CountDownLatch latch = new CountDownLatch(2);

		withClient(createMcpTransport(), builder -> builder.progressConsumer(notification -> {
			System.out.println("Received progress notification: " + notification);
			receivedNotifications.add(notification);
			progressNotificationCount.incrementAndGet();
			latch.countDown();
		}), client -> {
			client.initialize();

			// Call a tool that sends progress notifications
			CallToolRequest request = CallToolRequest.builder()
				.name("longRunningOperation")
				.arguments(Map.of("duration", 1, "steps", 2))
				.progressToken("test-token")
				.build();

			CallToolResult result = client.callTool(request);

			assertThat(result).isNotNull();

			try {
				// Wait for progress notifications to be processed
				latch.await(3, TimeUnit.SECONDS);
			}
			catch (InterruptedException e) {
				e.printStackTrace();
			}

			assertThat(progressNotificationCount.get()).isEqualTo(2);

			assertThat(receivedNotifications).isNotEmpty();
			assertThat(receivedNotifications.get(0).progressToken()).isEqualTo("test-token");
		});
	}

}
