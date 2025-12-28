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
import reactor.util.context.ContextView;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link LifecycleInitializer} postInitializationHook functionality.
 * 
 * @author Christian Tzolov
 */
class LifecycleInitializerPostInitializationHookTests {

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
		when(mockClientSession.sendNotification(eq(McpSchema.METHOD_NOTIFICATION_INITIALIZED), any()))
			.thenReturn(Mono.empty());
		when(mockClientSession.closeGracefully()).thenReturn(Mono.empty());

		initializer = new LifecycleInitializer(CLIENT_CAPABILITIES, CLIENT_INFO, PROTOCOL_VERSIONS,
				INITIALIZATION_TIMEOUT, mockSessionSupplier, mockPostInitializationHook);
	}

	@Test
	void shouldInvokePostInitializationHook() {
		AtomicReference<Initialization> capturedInit = new AtomicReference<>();

		when(mockPostInitializationHook.apply(any(Initialization.class))).thenAnswer(invocation -> {
			capturedInit.set(invocation.getArgument(0));
			return Mono.empty();
		});

		StepVerifier.create(initializer.withInitialization("test", init -> Mono.just(init.initializeResult())))
			.expectNext(MOCK_INIT_RESULT)
			.verifyComplete();

		// Verify hook was called
		verify(mockPostInitializationHook, times(1)).apply(any(Initialization.class));

		// Verify the hook received correct initialization data
		assertThat(capturedInit.get()).isNotNull();
		assertThat(capturedInit.get().mcpSession()).isEqualTo(mockClientSession);
		assertThat(capturedInit.get().initializeResult()).isEqualTo(MOCK_INIT_RESULT);
	}

	@Test
	void shouldInvokePostInitializationHookOnlyOnce() {
		// First initialization
		StepVerifier.create(initializer.withInitialization("test1", init -> Mono.just("result1")))
			.expectNext("result1")
			.verifyComplete();

		// Second call should reuse initialization and NOT call hook again
		StepVerifier.create(initializer.withInitialization("test2", init -> Mono.just("result2")))
			.expectNext("result2")
			.verifyComplete();

		// Hook should only be called once
		verify(mockPostInitializationHook, times(1)).apply(any(Initialization.class));
	}

	@Test
	void shouldInvokePostInitializationHookOnlyOnceWithConcurrentRequests() {
		AtomicInteger hookInvocationCount = new AtomicInteger(0);

		when(mockPostInitializationHook.apply(any(Initialization.class))).thenAnswer(invocation -> {
			hookInvocationCount.incrementAndGet();
			return Mono.empty();
		});

		// Start multiple concurrent initializations
		Mono<String> init1 = initializer.withInitialization("test1", init -> Mono.just("result1"))
			.subscribeOn(Schedulers.parallel());
		Mono<String> init2 = initializer.withInitialization("test2", init -> Mono.just("result2"))
			.subscribeOn(Schedulers.parallel());
		Mono<String> init3 = initializer.withInitialization("test3", init -> Mono.just("result3"))
			.subscribeOn(Schedulers.parallel());

		// TODO: can we assume the order of results?
		StepVerifier.create(Mono.zip(init1, init2, init3)).assertNext(tuple -> {
			assertThat(tuple.getT1()).isEqualTo("result1");
			assertThat(tuple.getT2()).isEqualTo("result2");
			assertThat(tuple.getT3()).isEqualTo("result3");
		}).verifyComplete();

		// Hook should only be called once despite concurrent requests
		assertThat(hookInvocationCount.get()).isEqualTo(1);
	}

	@Test
	void shouldFailInitializationWhenPostInitializationHookFails() {
		RuntimeException hookError = new RuntimeException("Post-initialization hook failed");
		when(mockPostInitializationHook.apply(any(Initialization.class))).thenReturn(Mono.error(hookError));

		StepVerifier.create(initializer.withInitialization("test", init -> Mono.just(init.initializeResult())))
			.expectErrorMatches(ex -> ex instanceof RuntimeException && ex.getCause() == hookError)
			.verify();

		// Verify initialization was not completed
		assertThat(initializer.isInitialized()).isFalse();
		assertThat(initializer.currentInitializationResult()).isNull();

		// Verify the hook was called
		verify(mockPostInitializationHook, times(1)).apply(any(Initialization.class));
	}

	@Test
	void shouldNotInvokePostInitializationHookWhenInitializationFails() {
		when(mockClientSession.sendRequest(eq(McpSchema.METHOD_INITIALIZE), any(), any()))
			.thenReturn(Mono.error(new RuntimeException("Initialization failed")));

		StepVerifier.create(initializer.withInitialization("test", init -> Mono.just(init.initializeResult())))
			.expectError(RuntimeException.class)
			.verify();

		// Hook should NOT be called when initialization fails
		verify(mockPostInitializationHook, never()).apply(any(Initialization.class));
	}

	@Test
	void shouldNotInvokePostInitializationHookWhenNotificationFails() {
		when(mockClientSession.sendNotification(eq(McpSchema.METHOD_NOTIFICATION_INITIALIZED), any()))
			.thenReturn(Mono.error(new RuntimeException("Notification failed")));

		StepVerifier.create(initializer.withInitialization("test", init -> Mono.just(init.initializeResult())))
			.expectError(RuntimeException.class)
			.verify();

		// Hook should NOT be called when notification fails
		verify(mockPostInitializationHook, never()).apply(any(Initialization.class));
	}

	@Test
	void shouldInvokePostInitializationHookAgainAfterReinitialization() {
		AtomicInteger hookInvocationCount = new AtomicInteger(0);

		when(mockPostInitializationHook.apply(any(Initialization.class))).thenAnswer(invocation -> {
			hookInvocationCount.incrementAndGet();
			return Mono.empty();
		});

		// First initialization
		StepVerifier.create(initializer.withInitialization("test1", init -> Mono.just("result1")))
			.expectNext("result1")
			.verifyComplete();

		assertThat(hookInvocationCount.get()).isEqualTo(1);

		// Simulate transport session exception to trigger re-initialization
		initializer.handleException(new McpTransportSessionNotFoundException("Session lost"));

		// Hook should be called twice (once for each initialization)
		assertThat(hookInvocationCount.get()).isEqualTo(2);
	}

	@Test
	void shouldAllowPostInitializationHookToPerformAsyncOperations() {
		AtomicInteger operationCount = new AtomicInteger(0);

		when(mockPostInitializationHook.apply(any(Initialization.class)))
			.thenReturn(Mono.fromRunnable(() -> operationCount.incrementAndGet()).then());

		StepVerifier.create(initializer.withInitialization("test", init -> Mono.just(init.initializeResult())))
			.expectNext(MOCK_INIT_RESULT)
			.verifyComplete();

		// Verify the async operation was executed
		assertThat(operationCount.get()).isEqualTo(1);
		verify(mockPostInitializationHook, times(1)).apply(any(Initialization.class));
	}

	@Test
	void shouldProvideCorrectInitializationDataToHook() {
		AtomicReference<McpClientSession> capturedSession = new AtomicReference<>();
		AtomicReference<McpSchema.InitializeResult> capturedResult = new AtomicReference<>();

		when(mockPostInitializationHook.apply(any(Initialization.class))).thenAnswer(invocation -> {
			Initialization init = invocation.getArgument(0);
			capturedSession.set(init.mcpSession());
			capturedResult.set(init.initializeResult());
			return Mono.empty();
		});

		StepVerifier.create(initializer.withInitialization("test", init -> Mono.just(init.initializeResult())))
			.expectNext(MOCK_INIT_RESULT)
			.verifyComplete();

		// Verify the hook received the correct session and result
		assertThat(capturedSession.get()).isEqualTo(mockClientSession);
		assertThat(capturedResult.get()).isEqualTo(MOCK_INIT_RESULT);
		assertThat(capturedResult.get().protocolVersion()).isEqualTo("2.0.0");
		assertThat(capturedResult.get().serverInfo().name()).isEqualTo("test-server");
	}

	@Test
	void shouldInvokePostInitializationHookAfterSuccessfulInitialization() {
		AtomicReference<Boolean> notificationSent = new AtomicReference<>(false);
		AtomicReference<Boolean> hookCalledAfterNotification = new AtomicReference<>(false);

		when(mockClientSession.sendNotification(eq(McpSchema.METHOD_NOTIFICATION_INITIALIZED), any()))
			.thenAnswer(invocation -> {
				notificationSent.set(true);
				return Mono.empty();
			});

		when(mockPostInitializationHook.apply(any(Initialization.class))).thenAnswer(invocation -> {
			// Due to flatMap chaining in doInitialize, if the hook is called,
			// the notification must have been sent first
			hookCalledAfterNotification.set(notificationSent.get());
			return Mono.empty();
		});

		StepVerifier.create(initializer.withInitialization("test", init -> Mono.just(init.initializeResult())))
			.expectNext(MOCK_INIT_RESULT)
			.verifyComplete();

		// Verify the hook was called and notification was already sent at that point
		assertThat(hookCalledAfterNotification.get()).isTrue();
		verify(mockClientSession).sendNotification(eq(McpSchema.METHOD_NOTIFICATION_INITIALIZED), any());
		verify(mockPostInitializationHook).apply(any(Initialization.class));
	}

}
