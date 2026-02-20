/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.modelcontextprotocol.client.LifecycleInitializer.Initialization;
import io.modelcontextprotocol.spec.McpClientSession;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link LifecycleInitializer}.
 */
class LifecycleInitializerTests {

	private static final Duration INITIALIZATION_TIMEOUT = Duration.ofSeconds(5);

	private static final McpSchema.ClientCapabilities CLIENT_CAPABILITIES = McpSchema.ClientCapabilities.builder()
		.build();

	private static final McpSchema.Implementation CLIENT_INFO = new McpSchema.Implementation("test-client", "1.0.0");

	private static final List<String> PROTOCOL_VERSIONS = List.of("1.0.0", "2.0.0");

	private static final McpSchema.InitializeResult MOCK_INIT_RESULT = new McpSchema.InitializeResult("2.0.0",
			McpSchema.ServerCapabilities.builder().build(), new McpSchema.Implementation("test-server", "1.0.0"),
			"Test instructions");

	@Mock
	private McpClientSession mockClientSession;

	@Mock
	private Function<ContextView, McpClientSession> mockSessionSupplier;

	@Mock
	private Function<Initialization, Mono<Void>> mockPostInitializationHook;

	private LifecycleInitializer initializer;

	@BeforeEach
	void setUp() {
		MockitoAnnotations.openMocks(this);

		when(mockPostInitializationHook.apply(any(Initialization.class))).thenReturn(Mono.empty());
		when(mockSessionSupplier.apply(any(ContextView.class))).thenReturn(mockClientSession);
		when(mockClientSession.sendRequest(eq(McpSchema.METHOD_INITIALIZE), any(), any()))
			.thenReturn(Mono.just(MOCK_INIT_RESULT));
		when(mockClientSession.sendRequestWithId(eq(McpSchema.METHOD_INITIALIZE), any(), any()))
			.thenAnswer(inv -> new McpClientSession.RequestMono<>("init-1", Mono.just(MOCK_INIT_RESULT)));
		when(mockClientSession.sendNotification(eq(McpSchema.METHOD_NOTIFICATION_INITIALIZED), any()))
			.thenReturn(Mono.empty());
		when(mockClientSession.closeGracefully()).thenReturn(Mono.empty());

		initializer = new LifecycleInitializer(CLIENT_CAPABILITIES, CLIENT_INFO, PROTOCOL_VERSIONS,
				INITIALIZATION_TIMEOUT, mockSessionSupplier, mockPostInitializationHook);
	}

	@Test
	void constructorShouldValidateParameters() {
		assertThatThrownBy(() -> new LifecycleInitializer(null, CLIENT_INFO, PROTOCOL_VERSIONS, INITIALIZATION_TIMEOUT,
				mockSessionSupplier, mockPostInitializationHook))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Client capabilities must not be null");

		assertThatThrownBy(() -> new LifecycleInitializer(CLIENT_CAPABILITIES, null, PROTOCOL_VERSIONS,
				INITIALIZATION_TIMEOUT, mockSessionSupplier, mockPostInitializationHook))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Client info must not be null");

		assertThatThrownBy(() -> new LifecycleInitializer(CLIENT_CAPABILITIES, CLIENT_INFO, null,
				INITIALIZATION_TIMEOUT, mockSessionSupplier, mockPostInitializationHook))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Protocol versions must not be empty");

		assertThatThrownBy(() -> new LifecycleInitializer(CLIENT_CAPABILITIES, CLIENT_INFO, List.of(),
				INITIALIZATION_TIMEOUT, mockSessionSupplier, mockPostInitializationHook))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Protocol versions must not be empty");

		assertThatThrownBy(() -> new LifecycleInitializer(CLIENT_CAPABILITIES, CLIENT_INFO, PROTOCOL_VERSIONS, null,
				mockSessionSupplier, mockPostInitializationHook))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Initialization timeout must not be null");

		assertThatThrownBy(() -> new LifecycleInitializer(CLIENT_CAPABILITIES, CLIENT_INFO, PROTOCOL_VERSIONS,
				INITIALIZATION_TIMEOUT, null, mockPostInitializationHook))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Session supplier must not be null");
	}

	@Test
	void shouldInitializeSuccessfully() {
		StepVerifier.create(initializer.withInitialization("test", init -> Mono.just(init.initializeResult())))
			.assertNext(result -> {
				assertThat(result).isEqualTo(MOCK_INIT_RESULT);
				assertThat(initializer.isInitialized()).isTrue();
				assertThat(initializer.currentInitializationResult()).isEqualTo(MOCK_INIT_RESULT);
			})
			.verifyComplete();

		verify(mockClientSession).sendRequestWithId(eq(McpSchema.METHOD_INITIALIZE),
				any(McpSchema.InitializeRequest.class), any());
		verify(mockClientSession).sendNotification(eq(McpSchema.METHOD_NOTIFICATION_INITIALIZED), eq(null));
	}

	@Test
	void shouldUseLatestProtocolVersionInInitializeRequest() {
		AtomicReference<McpSchema.InitializeRequest> capturedRequest = new AtomicReference<>();

		when(mockClientSession.sendRequestWithId(eq(McpSchema.METHOD_INITIALIZE), any(), any()))
			.thenAnswer(invocation -> {
				capturedRequest.set((McpSchema.InitializeRequest) invocation.getArgument(1));
				return new McpClientSession.RequestMono<>("init-1", Mono.just(MOCK_INIT_RESULT));
			});

		StepVerifier.create(initializer.withInitialization("test", init -> Mono.just(init.initializeResult())))
			.assertNext(result -> {
				assertThat(capturedRequest.get().protocolVersion()).isEqualTo("2.0.0"); // Latest
																						// version
				assertThat(capturedRequest.get().capabilities()).isEqualTo(CLIENT_CAPABILITIES);
				assertThat(capturedRequest.get().clientInfo()).isEqualTo(CLIENT_INFO);
			})
			.verifyComplete();
	}

	@Test
	void shouldFailForUnsupportedProtocolVersion() {
		McpSchema.InitializeResult unsupportedResult = new McpSchema.InitializeResult("999.0.0", // Unsupported
																									// version
				McpSchema.ServerCapabilities.builder().build(), new McpSchema.Implementation("test-server", "1.0.0"),
				"Test instructions");

		when(mockClientSession.sendRequestWithId(eq(McpSchema.METHOD_INITIALIZE), any(), any()))
			.thenAnswer(inv -> new McpClientSession.RequestMono<>("init-1", Mono.just(unsupportedResult)));

		StepVerifier.create(initializer.withInitialization("test", init -> Mono.just(init.initializeResult())))
			.expectError(RuntimeException.class)
			.verify();

		verify(mockClientSession, never()).sendNotification(eq(McpSchema.METHOD_NOTIFICATION_INITIALIZED), any());
	}

	@Test
	void shouldTimeoutOnSlowInitialization() {
		VirtualTimeScheduler virtualTimeScheduler = VirtualTimeScheduler.getOrSet();

		Duration INITIALIZE_TIMEOUT = Duration.ofSeconds(1);
		Duration SLOW_RESPONSE_DELAY = Duration.ofSeconds(5);

		LifecycleInitializer shortTimeoutInitializer = new LifecycleInitializer(CLIENT_CAPABILITIES, CLIENT_INFO,
				PROTOCOL_VERSIONS, INITIALIZE_TIMEOUT, mockSessionSupplier, mockPostInitializationHook);

		when(mockClientSession.sendRequestWithId(eq(McpSchema.METHOD_INITIALIZE), any(), any()))
			.thenAnswer(inv -> new McpClientSession.RequestMono<>("init-1",
					Mono.just(MOCK_INIT_RESULT).delayElement(SLOW_RESPONSE_DELAY, virtualTimeScheduler)));

		StepVerifier
			.withVirtualTime(() -> shortTimeoutInitializer.withInitialization("test",
					init -> Mono.just(init.initializeResult())), () -> virtualTimeScheduler, Long.MAX_VALUE)
			.expectSubscription()
			.expectNoEvent(INITIALIZE_TIMEOUT)
			.expectError(RuntimeException.class)
			.verify();
	}

	@Test
	void shouldReuseExistingInitialization() {
		// First initialization
		StepVerifier.create(initializer.withInitialization("test1", init -> Mono.just("result1")))
			.expectNext("result1")
			.verifyComplete();

		// Second call should reuse the same initialization
		StepVerifier.create(initializer.withInitialization("test2", init -> Mono.just("result2")))
			.expectNext("result2")
			.verifyComplete();

		// Verify session was created only once
		verify(mockSessionSupplier, times(1)).apply(any(ContextView.class));
		verify(mockClientSession, times(1)).sendRequestWithId(eq(McpSchema.METHOD_INITIALIZE), any(), any());
	}

	@Test
	void shouldHandleConcurrentInitializationRequests() {
		AtomicInteger sessionCreationCount = new AtomicInteger(0);

		when(mockSessionSupplier.apply(any(ContextView.class))).thenAnswer(invocation -> {
			sessionCreationCount.incrementAndGet();
			return mockClientSession;
		});

		// Start multiple concurrent initializations using subscribeOn with parallel
		// scheduler
		Mono<String> init1 = initializer.withInitialization("test1", init -> Mono.just("result1"))
			.subscribeOn(Schedulers.parallel());
		Mono<String> init2 = initializer.withInitialization("test2", init -> Mono.just("result2"))
			.subscribeOn(Schedulers.parallel());
		Mono<String> init3 = initializer.withInitialization("test3", init -> Mono.just("result3"))
			.subscribeOn(Schedulers.parallel());

		StepVerifier.create(Mono.zip(init1, init2, init3)).assertNext(tuple -> {
			assertThat(tuple.getT1()).isEqualTo("result1");
			assertThat(tuple.getT2()).isEqualTo("result2");
			assertThat(tuple.getT3()).isEqualTo("result3");
		}).verifyComplete();

		// Should only create one session despite concurrent requests
		assertThat(sessionCreationCount.get()).isEqualTo(1);
		verify(mockClientSession, times(1)).sendRequestWithId(eq(McpSchema.METHOD_INITIALIZE), any(), any());
	}

	@Test
	void shouldHandleInitializationFailure() {
		when(mockClientSession.sendRequestWithId(eq(McpSchema.METHOD_INITIALIZE), any(), any()))
			.thenAnswer(inv -> new McpClientSession.RequestMono<>("init-1",
					Mono.error(new RuntimeException("Connection failed"))))
			.thenAnswer(inv -> new McpClientSession.RequestMono<>("init-2", Mono.just(MOCK_INIT_RESULT)));

		StepVerifier.create(initializer.withInitialization("test", init -> Mono.just(init.initializeResult())))
			.expectError(RuntimeException.class)
			.verify();

		assertThat(initializer.isInitialized()).isFalse();
		assertThat(initializer.currentInitializationResult()).isNull();

		// The initializer can recover from previous errors
		StepVerifier
			.create(initializer.withInitialization("successful init", init -> Mono.just(init.initializeResult())))
			.expectNext(MOCK_INIT_RESULT)
			.verifyComplete();

		assertThat(initializer.isInitialized()).isTrue();
		assertThat(initializer.currentInitializationResult()).isEqualTo(MOCK_INIT_RESULT);
	}

	@Test
	void shouldHandleTransportSessionNotFoundException() {
		// successful initialization first
		StepVerifier.create(initializer.withInitialization("test", init -> Mono.just(init.initializeResult())))
			.expectNext(MOCK_INIT_RESULT)
			.verifyComplete();

		assertThat(initializer.isInitialized()).isTrue();

		// Simulate transport session not found
		initializer.handleException(new McpTransportSessionNotFoundException("Session not found"));

		assertThat(initializer.isInitialized()).isTrue();

		// Verify that the session was closed and re-initialized
		verify(mockClientSession).close();

		// Verify session was created 2 times (once for initial and once for
		// re-initialization)
		verify(mockSessionSupplier, times(2)).apply(any(ContextView.class));
	}

	@Test
	void shouldHandleOtherExceptions() {
		// Simulate a successful initialization first
		StepVerifier.create(initializer.withInitialization("test", init -> Mono.just(init.initializeResult())))
			.expectNext(MOCK_INIT_RESULT)
			.verifyComplete();

		assertThat(initializer.isInitialized()).isTrue();

		// Simulate other exception (should not trigger re-initialization)
		initializer.handleException(new RuntimeException("Some other error"));

		// Should still be initialized
		assertThat(initializer.isInitialized()).isTrue();
		verify(mockClientSession, never()).close();
		// Verify that the session was not re-created
		verify(mockSessionSupplier, times(1)).apply(any(ContextView.class));
	}

	@Test
	void shouldCloseGracefully() {
		StepVerifier.create(initializer.withInitialization("test", init -> Mono.just(init.initializeResult())))
			.expectNext(MOCK_INIT_RESULT)
			.verifyComplete();

		StepVerifier.create(initializer.closeGracefully()).verifyComplete();

		verify(mockClientSession).closeGracefully();
		assertThat(initializer.isInitialized()).isFalse();
	}

	@Test
	void shouldCloseImmediately() {
		StepVerifier.create(initializer.withInitialization("test", init -> Mono.just(init.initializeResult())))
			.expectNext(MOCK_INIT_RESULT)
			.verifyComplete();

		// Close immediately
		initializer.close();

		verify(mockClientSession).close();
		assertThat(initializer.isInitialized()).isFalse();
	}

	@Test
	void shouldHandleCloseWithoutInitialization() {
		// Close without initialization should not throw
		initializer.close();

		StepVerifier.create(initializer.closeGracefully()).verifyComplete();

		verify(mockClientSession, never()).close();
		verify(mockClientSession, never()).closeGracefully();
	}

	@Test
	void shouldSetProtocolVersionsForTesting() {
		List<String> newVersions = List.of("3.0.0", "4.0.0");
		initializer.setProtocolVersions(newVersions);

		AtomicReference<McpSchema.InitializeRequest> capturedRequest = new AtomicReference<>();

		when(mockClientSession.sendRequestWithId(eq(McpSchema.METHOD_INITIALIZE), any(), any()))
			.thenAnswer(invocation -> {
				capturedRequest.set((McpSchema.InitializeRequest) invocation.getArgument(1));
				return new McpClientSession.RequestMono<>("init-1",
						Mono.just(
								new McpSchema.InitializeResult("4.0.0", McpSchema.ServerCapabilities.builder().build(),
										new McpSchema.Implementation("test-server", "1.0.0"), "Test instructions")));
			});

		StepVerifier.create(initializer.withInitialization("test", init -> Mono.just(init.initializeResult())))
			.assertNext(result -> {
				// Latest from new versions
				assertThat(capturedRequest.get().protocolVersion()).isEqualTo("4.0.0");
			})
			.verifyComplete();
	}

	@Test
	void shouldPassContextToSessionSupplier() {
		String contextKey = "test.key";
		String contextValue = "test.value";

		AtomicReference<ContextView> capturedContext = new AtomicReference<>();

		when(mockSessionSupplier.apply(any(ContextView.class))).thenAnswer(invocation -> {
			capturedContext.set(invocation.getArgument(0));
			return mockClientSession;
		});

		StepVerifier
			.create(initializer.withInitialization("test", init -> Mono.just(init.initializeResult()))
				.contextWrite(Context.of(contextKey, contextValue)))
			.expectNext(MOCK_INIT_RESULT)
			.verifyComplete();

		assertThat(capturedContext.get().hasKey(contextKey)).isTrue();
		assertThat((String) capturedContext.get().get(contextKey)).isEqualTo(contextValue);
	}

	@Test
	void shouldProvideAccessToMcpSessionAndInitializeResult() {
		StepVerifier.create(initializer.withInitialization("test", init -> {
			assertThat(init.mcpSession()).isEqualTo(mockClientSession);
			assertThat(init.initializeResult()).isEqualTo(MOCK_INIT_RESULT);
			return Mono.just("success");
		})).expectNext("success").verifyComplete();
	}

	@Test
	void shouldHandleNotificationFailure() {
		when(mockClientSession.sendNotification(eq(McpSchema.METHOD_NOTIFICATION_INITIALIZED), any()))
			.thenReturn(Mono.error(new RuntimeException("Notification failed")));

		StepVerifier.create(initializer.withInitialization("test", init -> Mono.just(init.initializeResult())))
			.expectError(RuntimeException.class)
			.verify();

		verify(mockClientSession).sendRequestWithId(eq(McpSchema.METHOD_INITIALIZE), any(), any());
		verify(mockClientSession).sendNotification(eq(McpSchema.METHOD_NOTIFICATION_INITIALIZED), eq(null));
	}

	@Test
	void shouldReturnNullWhenNotInitialized() {
		assertThat(initializer.isInitialized()).isFalse();
		assertThat(initializer.currentInitializationResult()).isNull();
	}

	@Test
	void shouldReinitializeAfterTransportSessionException() {
		// First initialization
		StepVerifier.create(initializer.withInitialization("test1", init -> Mono.just("result1")))
			.expectNext("result1")
			.verifyComplete();

		// Simulate transport session exception
		initializer.handleException(new McpTransportSessionNotFoundException("Session lost"));

		// Should be able to initialize again
		StepVerifier.create(initializer.withInitialization("test2", init -> Mono.just("result2")))
			.expectNext("result2")
			.verifyComplete();

		// Verify two separate initializations occurred
		verify(mockSessionSupplier, times(2)).apply(any(ContextView.class));
		verify(mockClientSession, times(2)).sendRequestWithId(eq(McpSchema.METHOD_INITIALIZE), any(), any());
	}

}
