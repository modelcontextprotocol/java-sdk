/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.modern.McpSchema.CreateMessageRequest;
import io.modelcontextprotocol.modern.McpSchema.ElicitFormRequest;
import io.modelcontextprotocol.modern.McpSchema.ElicitResult;
import io.modelcontextprotocol.modern.McpSchema.ElicitUrlRequest;
import io.modelcontextprotocol.modern.McpSchema.InputRequest;
import io.modelcontextprotocol.modern.McpSchema.UnknownInputRequest;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("deprecation")
class InputHandlersTests {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final McpAsyncElicitationHandler<ElicitFormRequest> ACCEPT = request -> Mono
		.just(ElicitResult.accept(Map.of("message", request.message())));

	private static final McpAsyncElicitationHandler<ElicitUrlRequest> CONSENT = request -> Mono
		.just(ElicitResult.accept(null));

	private static final InputRequest FORM = InputRequest
		.elicit(ElicitFormRequest.builder("Name?", Map.of("type", "object")).build());

	private static final InputRequest URL = InputRequest.elicitUrl("Log in", "https://example.com");

	private static InputHandlers<Mono<?>> none() {
		return InputHandlers.none();
	}

	private static Mono<InputHandlers.HandlerResponses> respond(InputHandlers<Mono<?>> handlers,
			Map<String, InputRequest> requests) {
		return InputHandlers.respond(handlers, McpSchema.METHOD_TOOLS_CALL, requests);
	}

	@Test
	void noHandlersDeclareNothing() {
		assertThat(none().capabilities()).isEqualTo(ClientCapabilities.NONE);
	}

	@Test
	void capabilitiesFollowTheHandlers() {
		ClientCapabilities form = none().withFormElicitation(ACCEPT::elicit).capabilities();
		ClientCapabilities url = none().withUrlElicitation(CONSENT::elicit).capabilities();
		ClientCapabilities all = none().withFormElicitation(ACCEPT::elicit)
			.withUrlElicitation(CONSENT::elicit)
			.capabilities();

		assertThat(form.supportsElicitationForm()).isTrue();
		assertThat(form.supportsElicitationUrl()).isFalse();
		assertThat(form.supportsSampling()).isFalse();
		assertThat(url.supportsElicitationForm()).isFalse();
		assertThat(url.supportsElicitationUrl()).isTrue();
		assertThat(all.supportsElicitationForm()).isTrue();
		assertThat(all.supportsElicitationUrl()).isTrue();
		assertThat(all.supportsSampling()).isFalse();
		assertThat(all.supportsRoots()).isFalse();
	}

	@Test
	void aLaterHandlerReplacesOnlyItsOwnKind() {
		InputHandlers<Mono<?>> handlers = none().withFormElicitation(ACCEPT::elicit)
			.withUrlElicitation(CONSENT::elicit)
			.withFormElicitation(request -> Mono.just(ElicitResult.decline()));

		assertThat(handlers.capabilities().supportsElicitationUrl()).isTrue();
		StepVerifier.create(respond(handlers, Map.of("f", FORM)))
			.assertNext(responses -> assertThat(responses.responses()).containsEntry("f", ElicitResult.decline()))
			.verifyComplete();
	}

	@Test
	void everyKindIsAnsweredUnderItsKey() {
		InputHandlers<Mono<?>> handlers = none().withFormElicitation(ACCEPT::elicit)
			.withUrlElicitation(request -> Mono.just(ElicitResult.decline()));
		Map<String, InputRequest> requests = Map.of("name", FORM, "login", URL);

		StepVerifier.create(respond(handlers, requests)).assertNext(responses -> {
			assertThat(responses.responses()).containsOnlyKeys("name", "login");
			assertThat(responses.responses().get("name")).isEqualTo(ElicitResult.accept(Map.of("message", "Name?")));
			assertThat(responses.responses().get("login")).isEqualTo(ElicitResult.decline());
			assertThat(responses.acceptedUrlElicitation()).isFalse();
		}).verifyComplete();
	}

	@Test
	void urlHandlerReceivesTheUrlRequest() {
		AtomicReference<ElicitUrlRequest> seen = new AtomicReference<>();
		InputHandlers<Mono<?>> handlers = none().withUrlElicitation(request -> {
			seen.set(request);
			return Mono.just(ElicitResult.accept(null));
		});

		StepVerifier.create(respond(handlers, Map.of("u", URL)))
			.assertNext(responses -> assertThat(responses.acceptedUrlElicitation()).isTrue())
			.verifyComplete();
		assertThat(seen.get().url()).isEqualTo("https://example.com");
	}

	@Test
	void declinedUrlElicitationIsNotAccepted() {
		InputHandlers<Mono<?>> handlers = none().withUrlElicitation(request -> Mono.just(ElicitResult.decline()));

		StepVerifier.create(respond(handlers, Map.of("u", URL)))
			.assertNext(responses -> assertThat(responses.acceptedUrlElicitation()).isFalse())
			.verifyComplete();
	}

	@Test
	void missingHandlerFailsBeforeAnyHandlerRuns() {
		AtomicBoolean asked = new AtomicBoolean();
		InputHandlers<Mono<?>> handlers = none().withFormElicitation(request -> {
			asked.set(true);
			return Mono.just(ElicitResult.cancel());
		});

		StepVerifier.create(respond(handlers, orderedRequests("form", FORM, "roots", InputRequest.listRoots())))
			.expectErrorSatisfies(ex -> assertThat(ex).isInstanceOf(McpInputHandlerMissingException.class)
				.hasMessageContaining("'roots'"))
			.verify(TIMEOUT);
		assertThat(asked).isFalse();
	}

	@Test
	void urlElicitationWithOnlyAFormHandlerIsMissing() {
		InputHandlers<Mono<?>> handlers = none().withFormElicitation(ACCEPT::elicit);

		StepVerifier.create(respond(handlers, Map.of("u", URL)))
			.expectErrorSatisfies(ex -> assertThat(ex).isInstanceOf(McpInputHandlerMissingException.class)
				.hasMessageContaining("mode url"))
			.verify(TIMEOUT);
	}

	@Test
	void samplingAndRootsRequestsAreMissing() {
		InputHandlers<Mono<?>> handlers = none().withFormElicitation(ACCEPT::elicit)
			.withUrlElicitation(CONSENT::elicit);

		StepVerifier.create(respond(handlers, Map.of("roots", InputRequest.listRoots())))
			.expectError(McpInputHandlerMissingException.class)
			.verify(TIMEOUT);
		StepVerifier
			.create(respond(handlers,
					Map.of("sample", InputRequest.createMessage(CreateMessageRequest.builder(List.of(), 10).build()))))
			.expectError(McpInputHandlerMissingException.class)
			.verify(TIMEOUT);
	}

	@Test
	void unknownInputMethodIsMissing() {
		StepVerifier.create(respond(none(), Map.of("x", new UnknownInputRequest("example/ask", Map.of()))))
			.expectError(McpInputHandlerMissingException.class)
			.verify(TIMEOUT);
	}

	@Test
	void failingHandlerCancelsTheOthers() {
		AtomicBoolean cancelled = new AtomicBoolean();
		InputHandlers<Mono<?>> handlers = none()
			.withFormElicitation(request -> Mono.<ElicitResult>never().doOnCancel(() -> cancelled.set(true)))
			.withUrlElicitation(
					request -> Mono.delay(Duration.ofMillis(50)).then(Mono.error(new IllegalStateException("boom"))));

		StepVerifier.create(respond(handlers, orderedRequests("form", FORM, "login", URL)))
			.expectErrorMessage("boom")
			.verify(TIMEOUT);
		assertThat(cancelled).isTrue();
	}

	@Test
	void handlerWithoutResultFailsTheRound() {
		InputHandlers<Mono<?>> handlers = none().withFormElicitation(request -> Mono.empty());

		StepVerifier.create(respond(handlers, Map.of("f", FORM)))
			.expectError(IllegalStateException.class)
			.verify(TIMEOUT);
	}

	private static Map<String, InputRequest> orderedRequests(String k1, InputRequest v1, String k2, InputRequest v2) {
		Map<String, InputRequest> requests = new LinkedHashMap<>();
		requests.put(k1, v1);
		requests.put(k2, v2);
		return requests;
	}

}
