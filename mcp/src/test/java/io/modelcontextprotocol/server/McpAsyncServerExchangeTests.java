/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.common.McpTransportContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link McpAsyncServerExchange}.
 *
 * @author Christian Tzolov
 */
class McpAsyncServerExchangeTests {

	@Mock
	private McpServerSession mockSession;

	private McpSchema.ClientCapabilities clientCapabilities;

	private McpSchema.Implementation clientInfo;

	private McpAsyncServerExchange exchange;

	@BeforeEach
	void setUp() {
		MockitoAnnotations.openMocks(this);

		clientCapabilities = McpSchema.ClientCapabilities.builder().roots(true).build();

		clientInfo = new McpSchema.Implementation("test-client", "1.0.0");

		exchange = new McpAsyncServerExchange("testSessionId", mockSession, clientCapabilities, clientInfo,
				McpTransportContext.EMPTY);
	}

	@Test
	void testListRootsWithSinglePage() {

		List<McpSchema.Root> roots = Arrays.asList(new McpSchema.Root("file:///home/user/project1", "Project 1"),
				new McpSchema.Root("file:///home/user/project2", "Project 2"));
		McpSchema.ListRootsResult singlePageResult = new McpSchema.ListRootsResult(roots, null);

		when(mockSession.sendRequest(eq(McpSchema.METHOD_ROOTS_LIST), any(McpSchema.PaginatedRequest.class),
				any(TypeReference.class)))
			.thenReturn(Mono.just(singlePageResult));

		StepVerifier.create(exchange.listRoots()).assertNext(result -> {
			assertThat(result.roots()).hasSize(2);
			assertThat(result.roots().get(0).uri()).isEqualTo("file:///home/user/project1");
			assertThat(result.roots().get(0).name()).isEqualTo("Project 1");
			assertThat(result.roots().get(1).uri()).isEqualTo("file:///home/user/project2");
			assertThat(result.roots().get(1).name()).isEqualTo("Project 2");
			assertThat(result.nextCursor()).isNull();

			// Verify that the returned list is unmodifiable
			assertThatThrownBy(() -> result.roots().add(new McpSchema.Root("file:///test", "Test")))
				.isInstanceOf(UnsupportedOperationException.class);
		}).verifyComplete();
	}

	@Test
	void testListRootsWithMultiplePages() {

		List<McpSchema.Root> page1Roots = Arrays.asList(new McpSchema.Root("file:///home/user/project1", "Project 1"),
				new McpSchema.Root("file:///home/user/project2", "Project 2"));
		List<McpSchema.Root> page2Roots = Arrays.asList(new McpSchema.Root("file:///home/user/project3", "Project 3"));

		McpSchema.ListRootsResult page1Result = new McpSchema.ListRootsResult(page1Roots, "cursor1");
		McpSchema.ListRootsResult page2Result = new McpSchema.ListRootsResult(page2Roots, null);

		when(mockSession.sendRequest(eq(McpSchema.METHOD_ROOTS_LIST), eq(new McpSchema.PaginatedRequest(null)),
				any(TypeReference.class)))
			.thenReturn(Mono.just(page1Result));

		when(mockSession.sendRequest(eq(McpSchema.METHOD_ROOTS_LIST), eq(new McpSchema.PaginatedRequest("cursor1")),
				any(TypeReference.class)))
			.thenReturn(Mono.just(page2Result));

		StepVerifier.create(exchange.listRoots()).assertNext(result -> {
			assertThat(result.roots()).hasSize(3);
			assertThat(result.roots().get(0).uri()).isEqualTo("file:///home/user/project1");
			assertThat(result.roots().get(1).uri()).isEqualTo("file:///home/user/project2");
			assertThat(result.roots().get(2).uri()).isEqualTo("file:///home/user/project3");
			assertThat(result.nextCursor()).isNull();

			// Verify that the returned list is unmodifiable
			assertThatThrownBy(() -> result.roots().add(new McpSchema.Root("file:///test", "Test")))
				.isInstanceOf(UnsupportedOperationException.class);
		}).verifyComplete();
	}

	@Test
	void testListRootsWithEmptyResult() {

		McpSchema.ListRootsResult emptyResult = new McpSchema.ListRootsResult(new ArrayList<>(), null);

		when(mockSession.sendRequest(eq(McpSchema.METHOD_ROOTS_LIST), any(McpSchema.PaginatedRequest.class),
				any(TypeReference.class)))
			.thenReturn(Mono.just(emptyResult));

		StepVerifier.create(exchange.listRoots()).assertNext(result -> {
			assertThat(result.roots()).isEmpty();
			assertThat(result.nextCursor()).isNull();

			// Verify that the returned list is unmodifiable
			assertThatThrownBy(() -> result.roots().add(new McpSchema.Root("file:///test", "Test")))
				.isInstanceOf(UnsupportedOperationException.class);
		}).verifyComplete();
	}

	@Test
	void testListRootsWithSpecificCursor() {

		List<McpSchema.Root> roots = Arrays.asList(new McpSchema.Root("file:///home/user/project3", "Project 3"));
		McpSchema.ListRootsResult result = new McpSchema.ListRootsResult(roots, "nextCursor");

		when(mockSession.sendRequest(eq(McpSchema.METHOD_ROOTS_LIST), eq(new McpSchema.PaginatedRequest("someCursor")),
				any(TypeReference.class)))
			.thenReturn(Mono.just(result));

		StepVerifier.create(exchange.listRoots("someCursor")).assertNext(listResult -> {
			assertThat(listResult.roots()).hasSize(1);
			assertThat(listResult.roots().get(0).uri()).isEqualTo("file:///home/user/project3");
			assertThat(listResult.nextCursor()).isEqualTo("nextCursor");
		}).verifyComplete();
	}

	@Test
	void testListRootsWithError() {

		when(mockSession.sendRequest(eq(McpSchema.METHOD_ROOTS_LIST), any(McpSchema.PaginatedRequest.class),
				any(TypeReference.class)))
			.thenReturn(Mono.error(new RuntimeException("Network error")));

		// When & Then
		StepVerifier.create(exchange.listRoots()).verifyErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(RuntimeException.class).hasMessage("Network error");
		});
	}

	@Test
	void testListRootsUnmodifiabilityAfterAccumulation() {

		List<McpSchema.Root> page1Roots = new ArrayList<>(
				Arrays.asList(new McpSchema.Root("file:///home/user/project1", "Project 1")));
		List<McpSchema.Root> page2Roots = new ArrayList<>(
				Arrays.asList(new McpSchema.Root("file:///home/user/project2", "Project 2")));

		McpSchema.ListRootsResult page1Result = new McpSchema.ListRootsResult(page1Roots, "cursor1");
		McpSchema.ListRootsResult page2Result = new McpSchema.ListRootsResult(page2Roots, null);

		when(mockSession.sendRequest(eq(McpSchema.METHOD_ROOTS_LIST), eq(new McpSchema.PaginatedRequest(null)),
				any(TypeReference.class)))
			.thenReturn(Mono.just(page1Result));

		when(mockSession.sendRequest(eq(McpSchema.METHOD_ROOTS_LIST), eq(new McpSchema.PaginatedRequest("cursor1")),
				any(TypeReference.class)))
			.thenReturn(Mono.just(page2Result));

		StepVerifier.create(exchange.listRoots()).assertNext(result -> {
			// Verify the accumulated result is correct
			assertThat(result.roots()).hasSize(2);

			// Verify that the returned list is unmodifiable
			assertThatThrownBy(() -> result.roots().add(new McpSchema.Root("file:///test", "Test")))
				.isInstanceOf(UnsupportedOperationException.class);

			// Verify that clear() also throws UnsupportedOperationException
			assertThatThrownBy(() -> result.roots().clear()).isInstanceOf(UnsupportedOperationException.class);

			// Verify that remove() also throws UnsupportedOperationException
			assertThatThrownBy(() -> result.roots().remove(0)).isInstanceOf(UnsupportedOperationException.class);
		}).verifyComplete();
	}

	@Test
	void testGetClientCapabilities() {
		assertThat(exchange.getClientCapabilities()).isEqualTo(clientCapabilities);
	}

	@Test
	void testGetClientInfo() {
		assertThat(exchange.getClientInfo()).isEqualTo(clientInfo);
	}

	// ---------------------------------------
	// Logging Notification Tests
	// ---------------------------------------

	@Test
	void testLoggingNotificationWithNullMessage() {
		StepVerifier.create(exchange.loggingNotification(null)).verifyErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(McpError.class).hasMessage("Logging message must not be null");
		});
	}

	@Test
	void testSetMinLoggingLevelWithNullValue() {
		assertThatThrownBy(() -> exchange.setMinLoggingLevel(null)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("minLoggingLevel must not be null");
	}

	@Test
	void testLoggingNotificationWithAllowedLevel() {
		McpSchema.LoggingMessageNotification notification = McpSchema.LoggingMessageNotification.builder()
			.level(McpSchema.LoggingLevel.ERROR)
			.logger("test-logger")
			.data("Test error message")
			.build();

		when(mockSession.isNotificationForLevelAllowed(any())).thenReturn(Boolean.TRUE);
		when(mockSession.sendNotification(eq(McpSchema.METHOD_NOTIFICATION_MESSAGE), eq(notification)))
			.thenReturn(Mono.empty());

		StepVerifier.create(exchange.loggingNotification(notification)).verifyComplete();

		verify(mockSession, times(1)).isNotificationForLevelAllowed(eq(McpSchema.LoggingLevel.ERROR));
		verify(mockSession, times(1)).sendNotification(eq(McpSchema.METHOD_NOTIFICATION_MESSAGE), eq(notification));
	}

	@Test
	void testLoggingNotificationWithFilteredLevel() {
		exchange.setMinLoggingLevel(McpSchema.LoggingLevel.DEBUG);
		verify(mockSession, times(1)).setMinLoggingLevel(eq(McpSchema.LoggingLevel.DEBUG));

		McpSchema.LoggingMessageNotification debugNotification = McpSchema.LoggingMessageNotification.builder()
			.level(McpSchema.LoggingLevel.DEBUG)
			.logger("test-logger")
			.data("Debug message that should be filtered")
			.build();

		when(mockSession.isNotificationForLevelAllowed(eq(McpSchema.LoggingLevel.DEBUG))).thenReturn(Boolean.TRUE);
		when(mockSession.sendNotification(eq(McpSchema.METHOD_NOTIFICATION_MESSAGE), eq(debugNotification)))
			.thenReturn(Mono.empty());

		StepVerifier.create(exchange.loggingNotification(debugNotification)).verifyComplete();

		verify(mockSession, times(1)).isNotificationForLevelAllowed(eq(McpSchema.LoggingLevel.DEBUG));
		verify(mockSession, times(1)).sendNotification(eq(McpSchema.METHOD_NOTIFICATION_MESSAGE),
				eq(debugNotification));

		McpSchema.LoggingMessageNotification warningNotification = McpSchema.LoggingMessageNotification.builder()
			.level(McpSchema.LoggingLevel.WARNING)
			.logger("test-logger")
			.data("Debug message that should be filtered")
			.build();

		StepVerifier.create(exchange.loggingNotification(warningNotification)).verifyComplete();

		verify(mockSession, times(1)).isNotificationForLevelAllowed(eq(McpSchema.LoggingLevel.WARNING));
		verify(mockSession, never()).sendNotification(eq(McpSchema.METHOD_NOTIFICATION_MESSAGE),
				eq(warningNotification));
	}

	@Test
	void testLoggingNotificationWithSessionError() {
		McpSchema.LoggingMessageNotification notification = McpSchema.LoggingMessageNotification.builder()
			.level(McpSchema.LoggingLevel.ERROR)
			.logger("test-logger")
			.data("Test error message")
			.build();

		when(mockSession.isNotificationForLevelAllowed(any())).thenReturn(Boolean.TRUE);
		when(mockSession.sendNotification(eq(McpSchema.METHOD_NOTIFICATION_MESSAGE), eq(notification)))
			.thenReturn(Mono.error(new RuntimeException("Session error")));

		StepVerifier.create(exchange.loggingNotification(notification)).verifyErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(RuntimeException.class).hasMessage("Session error");
		});
	}

	// ---------------------------------------
	// Create Elicitation Tests
	// ---------------------------------------

	@Test
	void testCreateElicitationWithNullCapabilities() {
		// Given - Create exchange with null capabilities
		McpAsyncServerExchange exchangeWithNullCapabilities = new McpAsyncServerExchange(mockSession, null, clientInfo);

		McpSchema.ElicitRequest elicitRequest = McpSchema.ElicitRequest.builder()
			.message("Please provide your name")
			.build();

		StepVerifier.create(exchangeWithNullCapabilities.createElicitation(elicitRequest))
			.verifyErrorSatisfies(error -> {
				assertThat(error).isInstanceOf(McpError.class)
					.hasMessage("Client must be initialized. Call the initialize method first!");
			});

		// Verify that sendRequest was never called due to null capabilities
		verify(mockSession, never()).sendRequest(eq(McpSchema.METHOD_ELICITATION_CREATE), any(),
				any(TypeReference.class));
	}

	@Test
	void testCreateElicitationWithoutElicitationCapabilities() {
		// Given - Create exchange without elicitation capabilities
		McpSchema.ClientCapabilities capabilitiesWithoutElicitation = McpSchema.ClientCapabilities.builder()
			.roots(true)
			.build();

		McpAsyncServerExchange exchangeWithoutElicitation = new McpAsyncServerExchange(mockSession,
				capabilitiesWithoutElicitation, clientInfo);

		McpSchema.ElicitRequest elicitRequest = McpSchema.ElicitRequest.builder()
			.message("Please provide your name")
			.build();

		StepVerifier.create(exchangeWithoutElicitation.createElicitation(elicitRequest)).verifyErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(McpError.class)
				.hasMessage("Client must be configured with elicitation capabilities");
		});

		// Verify that sendRequest was never called due to missing elicitation
		// capabilities
		verify(mockSession, never()).sendRequest(eq(McpSchema.METHOD_ELICITATION_CREATE), any(),
				any(TypeReference.class));
	}

	@Test
	void testCreateElicitationWithComplexRequest() {

		McpSchema.ClientCapabilities capabilitiesWithElicitation = McpSchema.ClientCapabilities.builder()
			.elicitation()
			.build();

		McpAsyncServerExchange exchangeWithElicitation = new McpAsyncServerExchange(mockSession,
				capabilitiesWithElicitation, clientInfo);

		// Create a complex elicit request with schema
		java.util.Map<String, Object> requestedSchema = new java.util.HashMap<>();
		requestedSchema.put("type", "object");
		requestedSchema.put("properties", java.util.Map.of("name", java.util.Map.of("type", "string"), "age",
				java.util.Map.of("type", "number")));
		requestedSchema.put("required", java.util.List.of("name"));

		McpSchema.ElicitRequest elicitRequest = McpSchema.ElicitRequest.builder()
			.message("Please provide your personal information")
			.requestedSchema(requestedSchema)
			.build();

		java.util.Map<String, Object> responseContent = new java.util.HashMap<>();
		responseContent.put("name", "John Doe");
		responseContent.put("age", 30);

		McpSchema.ElicitResult expectedResult = McpSchema.ElicitResult.builder()
			.message(McpSchema.ElicitResult.Action.ACCEPT)
			.content(responseContent)
			.build();

		when(mockSession.sendRequest(eq(McpSchema.METHOD_ELICITATION_CREATE), eq(elicitRequest),
				any(TypeReference.class)))
			.thenReturn(Mono.just(expectedResult));

		StepVerifier.create(exchangeWithElicitation.createElicitation(elicitRequest)).assertNext(result -> {
			assertThat(result).isEqualTo(expectedResult);
			assertThat(result.action()).isEqualTo(McpSchema.ElicitResult.Action.ACCEPT);
			assertThat(result.content()).isNotNull();
			assertThat(result.content().get("name")).isEqualTo("John Doe");
			assertThat(result.content().get("age")).isEqualTo(30);
		}).verifyComplete();
	}

	@Test
	void testCreateElicitationWithDeclineAction() {

		McpSchema.ClientCapabilities capabilitiesWithElicitation = McpSchema.ClientCapabilities.builder()
			.elicitation()
			.build();

		McpAsyncServerExchange exchangeWithElicitation = new McpAsyncServerExchange(mockSession,
				capabilitiesWithElicitation, clientInfo);

		McpSchema.ElicitRequest elicitRequest = McpSchema.ElicitRequest.builder()
			.message("Please provide sensitive information")
			.build();

		McpSchema.ElicitResult expectedResult = McpSchema.ElicitResult.builder()
			.message(McpSchema.ElicitResult.Action.DECLINE)
			.build();

		when(mockSession.sendRequest(eq(McpSchema.METHOD_ELICITATION_CREATE), eq(elicitRequest),
				any(TypeReference.class)))
			.thenReturn(Mono.just(expectedResult));

		StepVerifier.create(exchangeWithElicitation.createElicitation(elicitRequest)).assertNext(result -> {
			assertThat(result).isEqualTo(expectedResult);
			assertThat(result.action()).isEqualTo(McpSchema.ElicitResult.Action.DECLINE);
		}).verifyComplete();
	}

	@Test
	void testCreateElicitationWithCancelAction() {

		McpSchema.ClientCapabilities capabilitiesWithElicitation = McpSchema.ClientCapabilities.builder()
			.elicitation()
			.build();

		McpAsyncServerExchange exchangeWithElicitation = new McpAsyncServerExchange(mockSession,
				capabilitiesWithElicitation, clientInfo);

		McpSchema.ElicitRequest elicitRequest = McpSchema.ElicitRequest.builder()
			.message("Please provide your information")
			.build();

		McpSchema.ElicitResult expectedResult = McpSchema.ElicitResult.builder()
			.message(McpSchema.ElicitResult.Action.CANCEL)
			.build();

		when(mockSession.sendRequest(eq(McpSchema.METHOD_ELICITATION_CREATE), eq(elicitRequest),
				any(TypeReference.class)))
			.thenReturn(Mono.just(expectedResult));

		StepVerifier.create(exchangeWithElicitation.createElicitation(elicitRequest)).assertNext(result -> {
			assertThat(result).isEqualTo(expectedResult);
			assertThat(result.action()).isEqualTo(McpSchema.ElicitResult.Action.CANCEL);
		}).verifyComplete();
	}

	@Test
	void testCreateElicitationWithSessionError() {

		McpSchema.ClientCapabilities capabilitiesWithElicitation = McpSchema.ClientCapabilities.builder()
			.elicitation()
			.build();

		McpAsyncServerExchange exchangeWithElicitation = new McpAsyncServerExchange(mockSession,
				capabilitiesWithElicitation, clientInfo);

		McpSchema.ElicitRequest elicitRequest = McpSchema.ElicitRequest.builder()
			.message("Please provide your name")
			.build();

		when(mockSession.sendRequest(eq(McpSchema.METHOD_ELICITATION_CREATE), eq(elicitRequest),
				any(TypeReference.class)))
			.thenReturn(Mono.error(new RuntimeException("Session communication error")));

		StepVerifier.create(exchangeWithElicitation.createElicitation(elicitRequest)).verifyErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(RuntimeException.class).hasMessage("Session communication error");
		});
	}

	// ---------------------------------------
	// Create Message Tests
	// ---------------------------------------

	@Test
	void testCreateMessageWithNullCapabilities() {

		McpAsyncServerExchange exchangeWithNullCapabilities = new McpAsyncServerExchange(mockSession, null, clientInfo);

		McpSchema.CreateMessageRequest createMessageRequest = McpSchema.CreateMessageRequest.builder()
			.messages(Arrays
				.asList(new McpSchema.SamplingMessage(McpSchema.Role.USER, new McpSchema.TextContent("Hello, world!"))))
			.build();

		StepVerifier.create(exchangeWithNullCapabilities.createMessage(createMessageRequest))
			.verifyErrorSatisfies(error -> {
				assertThat(error).isInstanceOf(McpError.class)
					.hasMessage("Client must be initialized. Call the initialize method first!");
			});

		// Verify that sendRequest was never called due to null capabilities
		verify(mockSession, never()).sendRequest(eq(McpSchema.METHOD_SAMPLING_CREATE_MESSAGE), any(),
				any(TypeReference.class));
	}

	@Test
	void testCreateMessageWithoutSamplingCapabilities() {

		McpSchema.ClientCapabilities capabilitiesWithoutSampling = McpSchema.ClientCapabilities.builder()
			.roots(true)
			.build();

		McpAsyncServerExchange exchangeWithoutSampling = new McpAsyncServerExchange(mockSession,
				capabilitiesWithoutSampling, clientInfo);

		McpSchema.CreateMessageRequest createMessageRequest = McpSchema.CreateMessageRequest.builder()
			.messages(Arrays
				.asList(new McpSchema.SamplingMessage(McpSchema.Role.USER, new McpSchema.TextContent("Hello, world!"))))
			.build();

		StepVerifier.create(exchangeWithoutSampling.createMessage(createMessageRequest)).verifyErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(McpError.class)
				.hasMessage("Client must be configured with sampling capabilities");
		});

		// Verify that sendRequest was never called due to missing sampling capabilities
		verify(mockSession, never()).sendRequest(eq(McpSchema.METHOD_SAMPLING_CREATE_MESSAGE), any(),
				any(TypeReference.class));
	}

	@Test
	void testCreateMessageWithBasicRequest() {

		McpSchema.ClientCapabilities capabilitiesWithSampling = McpSchema.ClientCapabilities.builder()
			.sampling()
			.build();

		McpAsyncServerExchange exchangeWithSampling = new McpAsyncServerExchange(mockSession, capabilitiesWithSampling,
				clientInfo);

		McpSchema.CreateMessageRequest createMessageRequest = McpSchema.CreateMessageRequest.builder()
			.messages(Arrays
				.asList(new McpSchema.SamplingMessage(McpSchema.Role.USER, new McpSchema.TextContent("Hello, world!"))))
			.build();

		McpSchema.CreateMessageResult expectedResult = McpSchema.CreateMessageResult.builder()
			.role(McpSchema.Role.ASSISTANT)
			.content(new McpSchema.TextContent("Hello! How can I help you today?"))
			.model("gpt-4")
			.stopReason(McpSchema.CreateMessageResult.StopReason.END_TURN)
			.build();

		when(mockSession.sendRequest(eq(McpSchema.METHOD_SAMPLING_CREATE_MESSAGE), eq(createMessageRequest),
				any(TypeReference.class)))
			.thenReturn(Mono.just(expectedResult));

		StepVerifier.create(exchangeWithSampling.createMessage(createMessageRequest)).assertNext(result -> {
			assertThat(result).isEqualTo(expectedResult);
			assertThat(result.role()).isEqualTo(McpSchema.Role.ASSISTANT);
			assertThat(result.content()).isInstanceOf(McpSchema.TextContent.class);
			assertThat(((McpSchema.TextContent) result.content()).text()).isEqualTo("Hello! How can I help you today?");
			assertThat(result.model()).isEqualTo("gpt-4");
			assertThat(result.stopReason()).isEqualTo(McpSchema.CreateMessageResult.StopReason.END_TURN);
		}).verifyComplete();
	}

	@Test
	void testCreateMessageWithImageContent() {

		McpSchema.ClientCapabilities capabilitiesWithSampling = McpSchema.ClientCapabilities.builder()
			.sampling()
			.build();

		McpAsyncServerExchange exchangeWithSampling = new McpAsyncServerExchange(mockSession, capabilitiesWithSampling,
				clientInfo);

		// Create request with image content
		McpSchema.CreateMessageRequest createMessageRequest = McpSchema.CreateMessageRequest.builder()
			.messages(Arrays.asList(new McpSchema.SamplingMessage(McpSchema.Role.USER,
					new McpSchema.ImageContent(null, "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQEAYABgAAD...",
							"image/jpeg"))))
			.build();

		McpSchema.CreateMessageResult expectedResult = McpSchema.CreateMessageResult.builder()
			.role(McpSchema.Role.ASSISTANT)
			.content(new McpSchema.TextContent("I can see an image. It appears to be a photograph."))
			.model("gpt-4-vision")
			.stopReason(McpSchema.CreateMessageResult.StopReason.END_TURN)
			.build();

		when(mockSession.sendRequest(eq(McpSchema.METHOD_SAMPLING_CREATE_MESSAGE), eq(createMessageRequest),
				any(TypeReference.class)))
			.thenReturn(Mono.just(expectedResult));

		StepVerifier.create(exchangeWithSampling.createMessage(createMessageRequest)).assertNext(result -> {
			assertThat(result).isEqualTo(expectedResult);
			assertThat(result.role()).isEqualTo(McpSchema.Role.ASSISTANT);
			assertThat(result.model()).isEqualTo("gpt-4-vision");
		}).verifyComplete();
	}

	@Test
	void testCreateMessageWithSessionError() {

		McpSchema.ClientCapabilities capabilitiesWithSampling = McpSchema.ClientCapabilities.builder()
			.sampling()
			.build();

		McpAsyncServerExchange exchangeWithSampling = new McpAsyncServerExchange(mockSession, capabilitiesWithSampling,
				clientInfo);

		McpSchema.CreateMessageRequest createMessageRequest = McpSchema.CreateMessageRequest.builder()
			.messages(Arrays
				.asList(new McpSchema.SamplingMessage(McpSchema.Role.USER, new McpSchema.TextContent("Hello"))))
			.build();

		when(mockSession.sendRequest(eq(McpSchema.METHOD_SAMPLING_CREATE_MESSAGE), eq(createMessageRequest),
				any(TypeReference.class)))
			.thenReturn(Mono.error(new RuntimeException("Session communication error")));

		StepVerifier.create(exchangeWithSampling.createMessage(createMessageRequest)).verifyErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(RuntimeException.class).hasMessage("Session communication error");
		});
	}

	@Test
	void testCreateMessageWithIncludeContext() {

		McpSchema.ClientCapabilities capabilitiesWithSampling = McpSchema.ClientCapabilities.builder()
			.sampling()
			.build();

		McpAsyncServerExchange exchangeWithSampling = new McpAsyncServerExchange(mockSession, capabilitiesWithSampling,
				clientInfo);

		McpSchema.CreateMessageRequest createMessageRequest = McpSchema.CreateMessageRequest.builder()
			.messages(Arrays.asList(new McpSchema.SamplingMessage(McpSchema.Role.USER,
					new McpSchema.TextContent("What files are available?"))))
			.includeContext(McpSchema.CreateMessageRequest.ContextInclusionStrategy.ALL_SERVERS)
			.build();

		McpSchema.CreateMessageResult expectedResult = McpSchema.CreateMessageResult.builder()
			.role(McpSchema.Role.ASSISTANT)
			.content(new McpSchema.TextContent("Based on the available context, I can see several files..."))
			.model("gpt-4")
			.stopReason(McpSchema.CreateMessageResult.StopReason.END_TURN)
			.build();

		when(mockSession.sendRequest(eq(McpSchema.METHOD_SAMPLING_CREATE_MESSAGE), eq(createMessageRequest),
				any(TypeReference.class)))
			.thenReturn(Mono.just(expectedResult));

		StepVerifier.create(exchangeWithSampling.createMessage(createMessageRequest)).assertNext(result -> {
			assertThat(result).isEqualTo(expectedResult);
			assertThat(((McpSchema.TextContent) result.content()).text()).contains("context");
		}).verifyComplete();
	}

	// ---------------------------------------
	// Ping Tests
	// ---------------------------------------

	@Test
	void testPingWithSuccessfulResponse() {

		java.util.Map<String, Object> expectedResponse = java.util.Map.of();

		when(mockSession.sendRequest(eq(McpSchema.METHOD_PING), eq(null), any(TypeReference.class)))
			.thenReturn(Mono.just(expectedResponse));

		StepVerifier.create(exchange.ping()).assertNext(result -> {
			assertThat(result).isEqualTo(expectedResponse);
			assertThat(result).isInstanceOf(java.util.Map.class);
		}).verifyComplete();

		// Verify that sendRequest was called with correct parameters
		verify(mockSession, times(1)).sendRequest(eq(McpSchema.METHOD_PING), eq(null), any(TypeReference.class));
	}

	@Test
	void testPingWithMcpError() {
		// Given - Mock an MCP-specific error during ping
		McpError mcpError = new McpError("Server unavailable");
		when(mockSession.sendRequest(eq(McpSchema.METHOD_PING), eq(null), any(TypeReference.class)))
			.thenReturn(Mono.error(mcpError));

		// When & Then
		StepVerifier.create(exchange.ping()).verifyErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(McpError.class).hasMessage("Server unavailable");
		});

		verify(mockSession, times(1)).sendRequest(eq(McpSchema.METHOD_PING), eq(null), any(TypeReference.class));
	}

	@Test
	void testPingMultipleCalls() {

		when(mockSession.sendRequest(eq(McpSchema.METHOD_PING), eq(null), any(TypeReference.class)))
			.thenReturn(Mono.just(Map.of()))
			.thenReturn(Mono.just(Map.of()));

		// First call
		StepVerifier.create(exchange.ping()).assertNext(result -> {
			assertThat(result).isInstanceOf(Map.class);
		}).verifyComplete();

		// Second call
		StepVerifier.create(exchange.ping()).assertNext(result -> {
			assertThat(result).isInstanceOf(Map.class);
		}).verifyComplete();

		// Verify that sendRequest was called twice
		verify(mockSession, times(2)).sendRequest(eq(McpSchema.METHOD_PING), eq(null), any(TypeReference.class));
	}

}
