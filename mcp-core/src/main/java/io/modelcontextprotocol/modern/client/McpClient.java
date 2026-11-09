/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.ElicitFormRequest;
import io.modelcontextprotocol.modern.McpSchema.ElicitUrlRequest;
import io.modelcontextprotocol.modern.McpSchema.Implementation;
import io.modelcontextprotocol.modern.McpSchema.ProgressParams;
import io.modelcontextprotocol.modern.client.transport.McpClientTransport;
import io.modelcontextprotocol.util.Assert;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;

/**
 * Builds clients for MCP {@value McpSchema#LATEST_PROTOCOL_VERSION} servers:
 * {@link #async} for a {@link McpAsyncClient}, {@link #sync} for a {@link McpSyncClient}.
 * <p>
 * Building a client does no I/O: there is no handshake, and the first request goes out
 * when the first call starts. A client is cheap, and several clients may share one
 * transport. A client doesn't own its transport; whoever created the transport closes it.
 *
 * @author Dariusz Jędrzejczyk
 */
public final class McpClient {

	/** The default timeout of one network exchange. */
	public static final Duration DEFAULT_ROUND_TIMEOUT = Duration.ofSeconds(20);

	/** The default number of times a call answers the server's input requests. */
	public static final int DEFAULT_MAX_INPUT_ROUNDS = 10;

	/** The default number of pages a listing of all pages reads. */
	public static final int DEFAULT_MAX_LIST_PAGES = 100;

	private McpClient() {
	}

	/**
	 * A builder for an asynchronous client that sends its requests through
	 * {@code transport}.
	 */
	public static AsyncBuilder async(McpClientTransport transport) {
		return new AsyncBuilder(transport);
	}

	/**
	 * A builder for a synchronous client that sends its requests through
	 * {@code transport}.
	 */
	public static SyncBuilder sync(McpClientTransport transport) {
		return new SyncBuilder(transport);
	}

	/**
	 * The settings both kinds of client have. Not thread-safe; the clients it builds are.
	 *
	 * @param <B> the concrete builder
	 */
	public abstract static class Builder<B extends Builder<B>> {

		private final McpClientTransport transport;

		@Nullable private Implementation clientInfo;

		@Nullable private McpJsonMapper jsonMapper;

		private Duration roundTimeout = DEFAULT_ROUND_TIMEOUT;

		@Nullable private Duration callTimeout;

		// Set by the concrete builders, each for the listener type of its client.
		@Nullable Function<ProgressParams, Mono<Void>> progress;

		private int maxInputRounds = DEFAULT_MAX_INPUT_ROUNDS;

		private int maxListPages = DEFAULT_MAX_LIST_PAGES;

		private List<String> protocolVersions = List.of(McpSchema.LATEST_PROTOCOL_VERSION);

		Builder(McpClientTransport transport) {
			Assert.notNull(transport, "transport must not be null");
			this.transport = transport;
		}

		/**
		 * The client's name and version, sent in {@code _meta} of every request.
		 * Optional, but recommended by the specification.
		 */
		public B clientInfo(Implementation clientInfo) {
			Assert.notNull(clientInfo, "clientInfo must not be null");
			this.clientInfo = clientInfo;
			return self();
		}

		/**
		 * The mapper that binds results to the schema's records. Defaults to
		 * {@link McpJsonDefaults#getMapper()}.
		 */
		public B jsonMapper(McpJsonMapper jsonMapper) {
			Assert.notNull(jsonMapper, "jsonMapper must not be null");
			this.jsonMapper = jsonMapper;
			return self();
		}

		/**
		 * The default timeout of one network exchange: from sending a request until its
		 * response arrives. Notifications on the way don't extend it. Defaults to
		 * {@link #DEFAULT_ROUND_TIMEOUT}; a call can set its own on its request spec.
		 */
		public B roundTimeout(Duration roundTimeout) {
			this.roundTimeout = CallSettings.requirePositive(roundTimeout, "roundTimeout");
			return self();
		}

		/**
		 * The default bound on a whole call: every exchange, every input handler and
		 * every wait. It includes the time a user takes to answer an elicitation, so a
		 * slow user fails the call; use it only where a hard deadline matters more, for
		 * example in unattended jobs. By default a call has none.
		 */
		public B callTimeout(Duration callTimeout) {
			this.callTimeout = CallSettings.requirePositive(callTimeout, "callTimeout");
			return self();
		}

		/**
		 * How many times a call answers the server's input requests, or retries when the
		 * server asks for a retry with its {@code requestState} only, before it fails
		 * with {@link McpInputRoundsExceededException}. Defaults to
		 * {@link #DEFAULT_MAX_INPUT_ROUNDS}.
		 */
		public B maxInputRounds(int maxInputRounds) {
			Assert.isTrue(maxInputRounds > 0, "maxInputRounds must be positive");
			this.maxInputRounds = maxInputRounds;
			return self();
		}

		/**
		 * How many pages a listing of all pages reads, such as {@code listTools()} or the
		 * lookup of a tool by name, before it fails with
		 * {@link McpInvalidResultException}. Defaults to {@link #DEFAULT_MAX_LIST_PAGES}.
		 */
		public B maxListPages(int maxListPages) {
			Assert.isTrue(maxListPages > 0, "maxListPages must be positive");
			this.maxListPages = maxListPages;
			return self();
		}

		/**
		 * The protocol versions the client speaks, newest first.
		 */
		B protocolVersions(List<String> protocolVersions) {
			Assert.notEmpty(protocolVersions, "protocolVersions must not be empty");
			this.protocolVersions = List.copyOf(protocolVersions);
			return self();
		}

		@SuppressWarnings("unchecked")
		private B self() {
			return (B) this;
		}

		ClientRequestFactory requests() {
			McpJsonMapper mapper = this.jsonMapper != null ? this.jsonMapper : McpJsonDefaults.getMapper();
			JsonRpcRequestSender sender = new JsonRpcRequestSender(this.transport, mapper, this.clientInfo,
					this.protocolVersions);
			return new ClientRequestFactory(sender,
					this.transport.mirrorsToolParams() ? new ToolCatalog(this.maxListPages) : null, this.maxInputRounds,
					this.maxListPages);
		}

		/**
		 * The client's settings, which every request spec starts from.
		 */
		<A> CallSettings<A> settings(InputHandlers<A> handlers) {
			return new CallSettings<>(this.roundTimeout, this.callTimeout, this.progress, Map.of(), handlers);
		}

	}

	/**
	 * Configures a {@link McpAsyncClient}, whose input handlers return a {@code Mono}.
	 */
	@SuppressWarnings("deprecation")
	public static final class AsyncBuilder extends Builder<AsyncBuilder> {

		private InputHandlers<Mono<?>> handlers = InputHandlers.none();

		private AsyncBuilder(McpClientTransport transport) {
			super(transport);
		}

		/**
		 * Answers the form-mode elicitations of every call that sets no form handler of
		 * its own, and declares the {@code elicitation} capability for form mode.
		 */
		public AsyncBuilder formElicitation(McpAsyncElicitationHandler<ElicitFormRequest> handler) {
			Assert.notNull(handler, "handler must not be null");
			this.handlers = this.handlers.withFormElicitation(handler::elicit);
			return this;
		}

		/**
		 * Answers the URL-mode elicitations of every call that sets no URL handler of its
		 * own, and declares the {@code elicitation} capability for URL mode.
		 */
		public AsyncBuilder urlElicitation(McpAsyncElicitationHandler<ElicitUrlRequest> handler) {
			Assert.notNull(handler, "handler must not be null");
			this.handlers = this.handlers.withUrlElicitation(handler::elicit);
			return this;
		}

		/**
		 * Receives the progress of every call that sets no listener of its own. While a
		 * listener is in effect, each request asks the server for progress. The
		 * notifications of a call reach the listener one at a time and in order: the next
		 * waits for the {@code Mono} it returns to complete, and so does the call's
		 * result. A listener that fails is logged and called for the next notification.
		 */
		public AsyncBuilder progress(Function<ProgressParams, Mono<Void>> listener) {
			Assert.notNull(listener, "listener must not be null");
			this.progress = listener;
			return this;
		}

		/**
		 * Builds an asynchronous client.
		 */
		public McpAsyncClient build() {
			return new McpAsyncClient(new AsyncClientRequester(requests(), settings(this.handlers)));
		}

	}

	/**
	 * Configures a {@link McpSyncClient}, whose input handlers return their answer and
	 * run on the thread that made the call.
	 */
	@SuppressWarnings("deprecation")
	public static final class SyncBuilder extends Builder<SyncBuilder> {

		private InputHandlers<Object> handlers = InputHandlers.none();

		private Supplier<McpTransportContext> transportContextProvider = () -> McpTransportContext.EMPTY;

		private SyncBuilder(McpClientTransport transport) {
			super(transport);
		}

		/**
		 * Supplies the {@link McpTransportContext} of a call, for example the identity of
		 * the current user. It is called on the calling thread when a call starts or a
		 * listen stream opens, so it may read thread-locals, and its result is used for
		 * every request of that call, also on other threads. Defaults to
		 * {@link McpTransportContext#EMPTY}.
		 */
		public SyncBuilder transportContextProvider(Supplier<McpTransportContext> provider) {
			Assert.notNull(provider, "provider must not be null");
			this.transportContextProvider = provider;
			return this;
		}

		/**
		 * Answers the form-mode elicitations of every call that sets no form handler of
		 * its own, and declares the {@code elicitation} capability for form mode.
		 */
		public SyncBuilder formElicitation(McpSyncElicitationHandler<ElicitFormRequest> handler) {
			Assert.notNull(handler, "handler must not be null");
			this.handlers = this.handlers.withFormElicitation(handler::elicit);
			return this;
		}

		/**
		 * Answers the URL-mode elicitations of every call that sets no URL handler of its
		 * own, and declares the {@code elicitation} capability for URL mode.
		 */
		public SyncBuilder urlElicitation(McpSyncElicitationHandler<ElicitUrlRequest> handler) {
			Assert.notNull(handler, "handler must not be null");
			this.handlers = this.handlers.withUrlElicitation(handler::elicit);
			return this;
		}

		/**
		 * Receives the progress of every call that sets no listener of its own. While a
		 * listener is in effect, each request asks the server for progress. It is called
		 * one notification at a time, in order, on a thread that may block, and the call
		 * returns after it has seen every notification before the result.
		 */
		public SyncBuilder progress(Consumer<ProgressParams> listener) {
			Assert.notNull(listener, "listener must not be null");
			this.progress = SyncClientRequester.onBlockingThread(listener);
			return this;
		}

		/**
		 * Builds a synchronous client.
		 */
		public McpSyncClient build() {
			return new McpSyncClient(
					new SyncClientRequester(requests(), settings(this.handlers), this.transportContextProvider));
		}

	}

}
