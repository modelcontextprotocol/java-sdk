/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import static io.modelcontextprotocol.util.McpJsonMapperUtils.JSON_MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.BlobResourceContents;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageRequest;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageResult;
import io.modelcontextprotocol.spec.McpSchema.ElicitRequest;
import io.modelcontextprotocol.spec.McpSchema.ElicitResult;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.ResourceContents;
import io.modelcontextprotocol.spec.McpSchema.Root;
import io.modelcontextprotocol.spec.McpSchema.SubscribeRequest;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.UnsubscribeRequest;
import io.modelcontextprotocol.spec.McpTransport;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

/**
 * Test suite for the {@link McpAsyncClient} that can be used with different
 * {@link McpTransport} implementations.
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 */
public abstract class AbstractMcpAsyncClientTests {

	private static final String ECHO_TEST_MESSAGE = "Hello MCP Spring AI!";

	abstract protected McpClientTransport createMcpTransport();

	protected Duration getRequestTimeout() {
		return Duration.ofSeconds(14);
	}

	protected Duration getInitializationTimeout() {
		return Duration.ofSeconds(20);
	}

	McpAsyncClient client(McpClientTransport transport) {
		return client(transport, Function.identity());
	}

	McpAsyncClient client(McpClientTransport transport, Function<McpClient.AsyncSpec, McpClient.AsyncSpec> customizer) {
		AtomicReference<McpAsyncClient> client = new AtomicReference<>();

		assertThatCode(() -> {
			McpClient.AsyncSpec builder = McpClient.async(transport)
				.requestTimeout(getRequestTimeout())
				.initializationTimeout(getInitializationTimeout())
				.sampling(req -> Mono.just(new CreateMessageResult(McpSchema.Role.USER,
						new McpSchema.TextContent("Oh, hi!"), "modelId", CreateMessageResult.StopReason.END_TURN)))
				.capabilities(ClientCapabilities.builder().roots(true).sampling().build());
			builder = customizer.apply(builder);
			client.set(builder.build());
		}).doesNotThrowAnyException();

		return client.get();
	}

	void withClient(McpClientTransport transport, Consumer<McpAsyncClient> c) {
		withClient(transport, Function.identity(), c);
	}

	void withClient(McpClientTransport transport, Function<McpClient.AsyncSpec, McpClient.AsyncSpec> customizer,
			Consumer<McpAsyncClient> c) {
		var client = client(transport, customizer);
		try {
			c.accept(client);
		}
		finally {
			StepVerifier.create(client.closeGracefully()).expectComplete().verify(Duration.ofSeconds(10));
		}
	}

	<T> void verifyNotificationSucceedsWithImplicitInitialization(Function<McpAsyncClient, Mono<T>> operation,
			String action) {
		withClient(createMcpTransport(), mcpAsyncClient -> {
			StepVerifier.create(operation.apply(mcpAsyncClient)).verifyComplete();
		});
	}

	<T> void verifyCallSucceedsWithImplicitInitialization(Function<McpAsyncClient, Mono<T>> operation, String action) {
		withClient(createMcpTransport(), mcpAsyncClient -> {
			StepVerifier.create(operation.apply(mcpAsyncClient)).expectNextCount(1).verifyComplete();
		});
	}

	@Test
	void testConstructorWithInvalidArguments() {
		assertThatThrownBy(() -> McpClient.async(null).build()).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Transport must not be null");

		assertThatThrownBy(() -> McpClient.async(createMcpTransport()).requestTimeout(null).build())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Request timeout must not be null");
	}

	@Test
	void testListToolsWithoutInitialization() {
		verifyCallSucceedsWithImplicitInitialization(client -> client.listTools(McpSchema.FIRST_PAGE), "listing tools");
	}

	@Test
	void testListTools() {
		withClient(createMcpTransport(), mcpAsyncClient -> {
			StepVerifier.create(mcpAsyncClient.initialize().then(mcpAsyncClient.listTools(McpSchema.FIRST_PAGE)))
				.consumeNextWith(result -> {
					assertThat(result.tools()).isNotNull().isNotEmpty();

					Tool firstTool = result.tools().get(0);
					assertThat(firstTool.name()).isNotNull();
					assertThat(firstTool.description()).isNotNull();
				})
				.verifyComplete();
		});
	}

	@Test
	void testListAllTools() {
		withClient(createMcpTransport(), mcpAsyncClient -> {
			StepVerifier.create(mcpAsyncClient.initialize().then(mcpAsyncClient.listTools()))
				.consumeNextWith(result -> {
					assertThat(result.tools()).isNotNull().isNotEmpty();

					Tool firstTool = result.tools().get(0);
					assertThat(firstTool.name()).isNotNull();
					assertThat(firstTool.description()).isNotNull();
				})
				.verifyComplete();
		});
	}

	@Test
	void testListAllToolsReturnsImmutableList() {
		withClient(createMcpTransport(), mcpAsyncClient -> {
			StepVerifier.create(mcpAsyncClient.initialize().then(mcpAsyncClient.listTools()))
				.consumeNextWith(result -> {
					assertThat(result.tools()).isNotNull();
					// Verify that the returned list is immutable
					assertThatThrownBy(() -> result.tools()
						.add(Tool.builder()
							.name("test")
							.title("test")
							.inputSchema(JSON_MAPPER, "{\"type\":\"object\"}")
							.build()))
						.isInstanceOf(UnsupportedOperationException.class);
				})
				.verifyComplete();
		});
	}

	@Test
	void testPingWithoutInitialization() {
		verifyCallSucceedsWithImplicitInitialization(client -> client.ping(), "pinging the server");
	}

	@Test
	void testPing() {
		withClient(createMcpTransport(), mcpAsyncClient -> {
			StepVerifier.create(mcpAsyncClient.initialize().then(mcpAsyncClient.ping()))
				.expectNextCount(1)
				.verifyComplete();
		});
	}

	@Test
	void testCallToolWithoutInitialization() {
		CallToolRequest callToolRequest = new CallToolRequest("echo", Map.of("message", ECHO_TEST_MESSAGE));
		verifyCallSucceedsWithImplicitInitialization(client -> client.callTool(callToolRequest), "calling tools");
	}

	@Test
	void testCallTool() {
		withClient(createMcpTransport(), mcpAsyncClient -> {
			CallToolRequest callToolRequest = new CallToolRequest("echo", Map.of("message", ECHO_TEST_MESSAGE));

			StepVerifier.create(mcpAsyncClient.initialize().then(mcpAsyncClient.callTool(callToolRequest)))
				.consumeNextWith(callToolResult -> {
					assertThat(callToolResult).isNotNull().satisfies(result -> {
						assertThat(result.content()).isNotNull();
						assertThat(result.isError()).isNull();
					});
				})
				.verifyComplete();
		});
	}

	@Test
	void testCallToolWithInvalidTool() {
		withClient(createMcpTransport(), mcpAsyncClient -> {
			CallToolRequest invalidRequest = new CallToolRequest("nonexistent_tool",
					Map.of("message", ECHO_TEST_MESSAGE));

			StepVerifier.create(mcpAsyncClient.initialize().then(mcpAsyncClient.callTool(invalidRequest)))
				.consumeErrorWith(
						e -> assertThat(e).isInstanceOf(McpError.class).hasMessage("Unknown tool: nonexistent_tool"))
				.verify();
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "success", "error", "debug" })
	void testCallToolWithMessageAnnotations(String messageType) {
		McpClientTransport transport = createMcpTransport();

		withClient(transport, mcpAsyncClient -> {
			StepVerifier.create(mcpAsyncClient.initialize()
				.then(mcpAsyncClient.callTool(new McpSchema.CallToolRequest("annotatedMessage",
						Map.of("messageType", messageType, "includeImage", true)))))
				.consumeNextWith(result -> {
					assertThat(result).isNotNull();
					assertThat(result.isError()).isNotEqualTo(true);
					assertThat(result.content()).isNotEmpty();
					assertThat(result.content()).allSatisfy(content -> {
						switch (content.type()) {
							case "text":
								McpSchema.TextContent textContent = assertInstanceOf(McpSchema.TextContent.class,
										content);
								assertThat(textContent.text()).isNotEmpty();
								assertThat(textContent.annotations()).isNotNull();

								switch (messageType) {
									case "error":
										assertThat(textContent.annotations().priority()).isEqualTo(1.0);
										assertThat(textContent.annotations().audience())
											.containsOnly(McpSchema.Role.USER, McpSchema.Role.ASSISTANT);
										break;
									case "success":
										assertThat(textContent.annotations().priority()).isEqualTo(0.7);
										assertThat(textContent.annotations().audience())
											.containsExactly(McpSchema.Role.USER);
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
								McpSchema.ImageContent imageContent = assertInstanceOf(McpSchema.ImageContent.class,
										content);
								assertThat(imageContent.data()).isNotEmpty();
								assertThat(imageContent.annotations()).isNotNull();
								assertThat(imageContent.annotations().priority()).isEqualTo(0.5);
								assertThat(imageContent.annotations().audience()).containsExactly(McpSchema.Role.USER);
								break;
							default:
								fail("Unexpected content type: " + content.type());
						}
					});
				})
				.verifyComplete();
		});
	}

	@Test
	void testListResourcesWithoutInitialization() {
		verifyCallSucceedsWithImplicitInitialization(client -> client.listResources(McpSchema.FIRST_PAGE),
				"listing resources");
	}

	@Test
	void testListResources() {
		withClient(createMcpTransport(), mcpAsyncClient -> {
			StepVerifier.create(mcpAsyncClient.initialize().then(mcpAsyncClient.listResources(McpSchema.FIRST_PAGE)))
				.consumeNextWith(resources -> {
					assertThat(resources).isNotNull().satisfies(result -> {
						assertThat(result.resources()).isNotNull();

						if (!result.resources().isEmpty()) {
							Resource firstResource = result.resources().get(0);
							assertThat(firstResource.uri()).isNotNull();
							assertThat(firstResource.name()).isNotNull();
						}
					});
				})
				.verifyComplete();
		});
	}

	@Test
	void testListAllResources() {
		withClient(createMcpTransport(), mcpAsyncClient -> {
			StepVerifier.create(mcpAsyncClient.initialize().then(mcpAsyncClient.listResources()))
				.consumeNextWith(resources -> {
					assertThat(resources).isNotNull().satisfies(result -> {
						assertThat(result.resources()).isNotNull();

						if (!result.resources().isEmpty()) {
							Resource firstResource = result.resources().get(0);
							assertThat(firstResource.uri()).isNotNull();
							assertThat(firstResource.name()).isNotNull();
						}
					});
				})
				.verifyComplete();
		});
	}

	@Test
	void testListAllResourcesReturnsImmutableList() {
		withClient(createMcpTransport(), mcpAsyncClient -> {
			StepVerifier.create(mcpAsyncClient.initialize().then(mcpAsyncClient.listResources()))
				.consumeNextWith(result -> {
					assertThat(result.resources()).isNotNull();
					// Verify that the returned list is immutable
					assertThatThrownBy(
							() -> result.resources().add(Resource.builder().uri("test://uri").name("test").build()))
						.isInstanceOf(UnsupportedOperationException.class);
				})
				.verifyComplete();
		});
	}

	@Test
	void testMcpAsyncClientState() {
		withClient(createMcpTransport(), mcpAsyncClient -> {
			assertThat(mcpAsyncClient).isNotNull();
		});
	}

	@Test
	void testListPromptsWithoutInitialization() {
		verifyCallSucceedsWithImplicitInitialization(client -> client.listPrompts(McpSchema.FIRST_PAGE),
				"listing " + "prompts");
	}

	@Test
	void testListPrompts() {
		withClient(createMcpTransport(), mcpAsyncClient -> {
			StepVerifier.create(mcpAsyncClient.initialize().then(mcpAsyncClient.listPrompts(McpSchema.FIRST_PAGE)))
				.consumeNextWith(prompts -> {
					assertThat(prompts).isNotNull().satisfies(result -> {
						assertThat(result.prompts()).isNotNull();

						if (!result.prompts().isEmpty()) {
							Prompt firstPrompt = result.prompts().get(0);
							assertThat(firstPrompt.name()).isNotNull();
							assertThat(firstPrompt.description()).isNotNull();
						}
					});
				})
				.verifyComplete();
		});
	}

	@Test
	void testListAllPrompts() {
		withClient(createMcpTransport(), mcpAsyncClient -> {
			StepVerifier.create(mcpAsyncClient.initialize().then(mcpAsyncClient.listPrompts()))
				.consumeNextWith(prompts -> {
					assertThat(prompts).isNotNull().satisfies(result -> {
						assertThat(result.prompts()).isNotNull();

						if (!result.prompts().isEmpty()) {
							Prompt firstPrompt = result.prompts().get(0);
							assertThat(firstPrompt.name()).isNotNull();
							assertThat(firstPrompt.description()).isNotNull();
						}
					});
				})
				.verifyComplete();
		});
	}

	@Test
	void testListAllPromptsReturnsImmutableList() {
		withClient(createMcpTransport(), mcpAsyncClient -> {
			StepVerifier.create(mcpAsyncClient.initialize().then(mcpAsyncClient.listPrompts()))
				.consumeNextWith(result -> {
					assertThat(result.prompts()).isNotNull();
					// Verify that the returned list is immutable
					assertThatThrownBy(() -> result.prompts().add(new Prompt("test", "test", "test", null)))
						.isInstanceOf(UnsupportedOperationException.class);
				})
				.verifyComplete();
		});
	}

	@Test
	void testGetPromptWithoutInitialization() {
		GetPromptRequest request = new GetPromptRequest("simple_prompt", Map.of());
		verifyCallSucceedsWithImplicitInitialization(client -> client.getPrompt(request), "getting " + "prompts");
	}

	@Test
	void testGetPrompt() {
		withClient(createMcpTransport(), mcpAsyncClient -> {
			StepVerifier
				.create(mcpAsyncClient.initialize()
					.then(mcpAsyncClient.getPrompt(new GetPromptRequest("simple_prompt", Map.of()))))
				.consumeNextWith(prompt -> {
					assertThat(prompt).isNotNull().satisfies(result -> {
						assertThat(result.messages()).isNotEmpty();
						assertThat(result.messages()).hasSize(1);
					});
				})
				.verifyComplete();
		});
	}

	@Test
	void testRootsListChangedWithoutInitialization() {
		verifyNotificationSucceedsWithImplicitInitialization(client -> client.rootsListChangedNotification(),
				"sending roots list changed notification");
	}

	@Test
	void testRootsListChanged() {
		withClient(createMcpTransport(), mcpAsyncClient -> {
			StepVerifier.create(mcpAsyncClient.initialize().then(mcpAsyncClient.rootsListChangedNotification()))
				.verifyComplete();
		});
	}

	@Test
	void testInitializeWithRootsListProviders() {
		withClient(createMcpTransport(), builder -> builder.roots(new Root("file:///test/path", "test-root")),
				client -> {
					StepVerifier.create(client.initialize().then(client.closeGracefully())).verifyComplete();
				});
	}

	@Test
	void testAddRoot() {
		withClient(createMcpTransport(), mcpAsyncClient -> {
			Root newRoot = new Root("file:///new/test/path", "new-test-root");
			StepVerifier.create(mcpAsyncClient.addRoot(newRoot)).verifyComplete();
		});
	}

	@Test
	void testAddRootWithNullValue() {
		withClient(createMcpTransport(), mcpAsyncClient -> {
			StepVerifier.create(mcpAsyncClient.addRoot(null))
				.consumeErrorWith(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class)
					.hasMessage("Root must not be null"))
				.verify();
		});
	}

	@Test
	void testRemoveRoot() {
		withClient(createMcpTransport(), mcpAsyncClient -> {
			Root root = new Root("file:///test/path/to/remove", "root-to-remove");
			StepVerifier.create(mcpAsyncClient.addRoot(root)).verifyComplete();

			StepVerifier.create(mcpAsyncClient.removeRoot(root.uri())).verifyComplete();
		});
	}

	@Test
	void testRemoveNonExistentRoot() {
		withClient(createMcpTransport(), mcpAsyncClient -> {
			StepVerifier.create(mcpAsyncClient.removeRoot("nonexistent-uri"))
				.consumeErrorWith(e -> assertThat(e).isInstanceOf(IllegalStateException.class)
					.hasMessage("Root with uri 'nonexistent-uri' not found"))
				.verify();
		});
	}

	@Test
	void testReadResource() {
		AtomicInteger resourceCount = new AtomicInteger();
		withClient(createMcpTransport(), client -> {
			Flux<McpSchema.ReadResourceResult> resources = client.initialize()
				.then(client.listResources(null))
				.flatMapMany(r -> {
					List<Resource> l = r.resources();
					resourceCount.set(l.size());
					return Flux.fromIterable(l);
				})
				.flatMap(r -> client.readResource(r));

			StepVerifier.create(resources)
				.recordWith(ArrayList::new)
				.thenConsumeWhile(res -> true)
				.consumeRecordedWith(readResourceResults -> {
					assertThat(readResourceResults.size()).isEqualTo(resourceCount.get());
					for (ReadResourceResult result : readResourceResults) {

						assertThat(result).isNotNull();
						assertThat(result.contents()).isNotNull().isNotEmpty();

						// Validate each content item
						for (ResourceContents content : result.contents()) {
							assertThat(content).isNotNull();
							assertThat(content.uri()).isNotNull().isNotEmpty();
							assertThat(content.mimeType()).isNotNull().isNotEmpty();

							// Validate content based on its type with more comprehensive
							// checks
							switch (content.mimeType()) {
								case "text/plain" -> {
									TextResourceContents textContent = assertInstanceOf(TextResourceContents.class,
											content);
									assertThat(textContent.text()).isNotNull().isNotEmpty();
									assertThat(textContent.uri()).isNotEmpty();
								}
								case "application/octet-stream" -> {
									BlobResourceContents blobContent = assertInstanceOf(BlobResourceContents.class,
											content);
									assertThat(blobContent.blob()).isNotNull().isNotEmpty();
									assertThat(blobContent.uri()).isNotNull().isNotEmpty();
									// Validate base64 encoding format
									assertThat(blobContent.blob()).matches("^[A-Za-z0-9+/]*={0,2}$");
								}
								default -> {

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
				})
				.verifyComplete();
		});
	}

	@Test
	void testListResourceTemplatesWithoutInitialization() {
		verifyCallSucceedsWithImplicitInitialization(client -> client.listResourceTemplates(McpSchema.FIRST_PAGE),
				"listing resource templates");
	}

	@Test
	void testListResourceTemplates() {
		withClient(createMcpTransport(), mcpAsyncClient -> {
			StepVerifier
				.create(mcpAsyncClient.initialize().then(mcpAsyncClient.listResourceTemplates(McpSchema.FIRST_PAGE)))
				.consumeNextWith(result -> {
					assertThat(result).isNotNull();
					assertThat(result.resourceTemplates()).isNotNull();
				})
				.verifyComplete();
		});
	}

	@Test
	void testListAllResourceTemplates() {
		withClient(createMcpTransport(), mcpAsyncClient -> {
			StepVerifier.create(mcpAsyncClient.initialize().then(mcpAsyncClient.listResourceTemplates()))
				.consumeNextWith(result -> {
					assertThat(result).isNotNull();
					assertThat(result.resourceTemplates()).isNotNull();
				})
				.verifyComplete();
		});
	}

	@Test
	void testListAllResourceTemplatesReturnsImmutableList() {
		withClient(createMcpTransport(), mcpAsyncClient -> {
			StepVerifier.create(mcpAsyncClient.initialize().then(mcpAsyncClient.listResourceTemplates()))
				.consumeNextWith(result -> {
					assertThat(result.resourceTemplates()).isNotNull();
					// Verify that the returned list is immutable
					assertThatThrownBy(() -> result.resourceTemplates()
						.add(new McpSchema.ResourceTemplate("test://template", "test", "test", null, null, null)))
						.isInstanceOf(UnsupportedOperationException.class);
				})
				.verifyComplete();
		});
	}

	// @Test
	void testResourceSubscription() {
		withClient(createMcpTransport(), mcpAsyncClient -> {
			StepVerifier.create(mcpAsyncClient.listResources()).consumeNextWith(resources -> {
				if (!resources.resources().isEmpty()) {
					Resource firstResource = resources.resources().get(0);

					// Test subscribe
					StepVerifier.create(mcpAsyncClient.subscribeResource(new SubscribeRequest(firstResource.uri())))
						.verifyComplete();

					// Test unsubscribe
					StepVerifier.create(mcpAsyncClient.unsubscribeResource(new UnsubscribeRequest(firstResource.uri())))
						.verifyComplete();
				}
			}).verifyComplete();
		});
	}

	@Test
	void testNotificationHandlers() {
		AtomicBoolean toolsNotificationReceived = new AtomicBoolean(false);
		AtomicBoolean resourcesNotificationReceived = new AtomicBoolean(false);
		AtomicBoolean promptsNotificationReceived = new AtomicBoolean(false);

		withClient(createMcpTransport(),
				builder -> builder
					.toolsChangeConsumer(tools -> Mono.fromRunnable(() -> toolsNotificationReceived.set(true)))
					.resourcesChangeConsumer(
							resources -> Mono.fromRunnable(() -> resourcesNotificationReceived.set(true)))
					.promptsChangeConsumer(prompts -> Mono.fromRunnable(() -> promptsNotificationReceived.set(true))),
				mcpAsyncClient -> {
					StepVerifier.create(mcpAsyncClient.initialize())
						.expectNextMatches(Objects::nonNull)
						.verifyComplete();
				});
	}

	@Test
	void testInitializeWithSamplingCapability() {
		ClientCapabilities capabilities = ClientCapabilities.builder().sampling().build();
		CreateMessageResult createMessageResult = CreateMessageResult.builder()
			.message("test")
			.model("test-model")
			.build();
		withClient(createMcpTransport(),
				builder -> builder.capabilities(capabilities).sampling(request -> Mono.just(createMessageResult)),
				client -> {
					StepVerifier.create(client.initialize()).expectNextMatches(Objects::nonNull).verifyComplete();
				});
	}

	@Test
	void testInitializeWithElicitationCapability() {
		ClientCapabilities capabilities = ClientCapabilities.builder().elicitation().build();
		ElicitResult elicitResult = ElicitResult.builder()
			.message(ElicitResult.Action.ACCEPT)
			.content(Map.of("foo", "bar"))
			.build();
		withClient(createMcpTransport(),
				builder -> builder.capabilities(capabilities).elicitation(request -> Mono.just(elicitResult)),
				client -> {
					StepVerifier.create(client.initialize()).expectNextMatches(Objects::nonNull).verifyComplete();
				});
	}

	@Test
	void testInitializeWithAllCapabilities() {
		var capabilities = ClientCapabilities.builder()
			.experimental(Map.of("feature", Map.of("featureFlag", true)))
			.roots(true)
			.sampling()
			.build();

		Function<CreateMessageRequest, Mono<CreateMessageResult>> samplingHandler = request -> Mono
			.just(CreateMessageResult.builder().message("test").model("test-model").build());

		Function<ElicitRequest, Mono<ElicitResult>> elicitationHandler = request -> Mono
			.just(ElicitResult.builder().message(ElicitResult.Action.ACCEPT).content(Map.of("foo", "bar")).build());

		withClient(createMcpTransport(),
				builder -> builder.capabilities(capabilities).sampling(samplingHandler).elicitation(elicitationHandler),
				client ->

				StepVerifier.create(client.initialize()).assertNext(result -> {
					assertThat(result).isNotNull();
					assertThat(result.capabilities()).isNotNull();
				}).verifyComplete());
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
		withClient(createMcpTransport(), mcpAsyncClient -> {
			StepVerifier
				.create(mcpAsyncClient.initialize()
					.thenMany(Flux.fromArray(McpSchema.LoggingLevel.values()).flatMap(mcpAsyncClient::setLoggingLevel)))
				.verifyComplete();
		});
	}

	@Test
	void testLoggingConsumer() {
		AtomicBoolean logReceived = new AtomicBoolean(false);

		withClient(createMcpTransport(),
				builder -> builder.loggingConsumer(notification -> Mono.fromRunnable(() -> logReceived.set(true))),
				client -> {
					StepVerifier.create(client.initialize()).expectNextMatches(Objects::nonNull).verifyComplete();
					StepVerifier.create(client.closeGracefully()).verifyComplete();

				});

	}

	@Test
	void testLoggingWithNullNotification() {
		withClient(createMcpTransport(), mcpAsyncClient -> {
			StepVerifier.create(mcpAsyncClient.setLoggingLevel(null))
				.expectErrorMatches(error -> error.getMessage().contains("Logging level must not be null"))
				.verify();
		});
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

				return Mono
					.just(new McpSchema.CreateMessageResult(McpSchema.Role.USER, new McpSchema.TextContent(response),
							"modelId", McpSchema.CreateMessageResult.StopReason.END_TURN));
			}), client -> {
				StepVerifier.create(client.initialize()).expectNextMatches(Objects::nonNull).verifyComplete();

				StepVerifier.create(client.callTool(
						new McpSchema.CallToolRequest("sampleLLM", Map.of("prompt", message, "maxTokens", maxTokens))))
					.consumeNextWith(result -> {
						// Verify tool response to ensure our sampling response was passed
						// through
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
					})
					.verifyComplete();
			});
	}

	// ---------------------------------------
	// Progress Notification Tests
	// ---------------------------------------

	@Test
	void testProgressConsumer() {
		Sinks.Many<McpSchema.ProgressNotification> sink = Sinks.many().unicast().onBackpressureBuffer();
		List<McpSchema.ProgressNotification> receivedNotifications = new CopyOnWriteArrayList<>();

		withClient(createMcpTransport(), builder -> builder.progressConsumer(notification -> {
			receivedNotifications.add(notification);
			sink.tryEmitNext(notification);
			return Mono.empty();
		}), client -> {
			StepVerifier.create(client.initialize()).expectNextMatches(Objects::nonNull).verifyComplete();

			// Call a tool that sends progress notifications
			CallToolRequest request = CallToolRequest.builder()
				.name("longRunningOperation")
				.arguments(Map.of("duration", 1, "steps", 2))
				.progressToken("test-token")
				.build();

			StepVerifier.create(client.callTool(request)).consumeNextWith(result -> {
				assertThat(result).isNotNull();
			}).verifyComplete();

			// Use StepVerifier to verify the progress notifications via the sink
			StepVerifier.create(sink.asFlux()).expectNextCount(2).thenCancel().verify(Duration.ofSeconds(3));

			assertThat(receivedNotifications).hasSize(2);
			assertThat(receivedNotifications.get(0).progressToken()).isEqualTo("test-token");
		});
	}

}
