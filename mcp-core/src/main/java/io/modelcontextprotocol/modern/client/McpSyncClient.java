/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.io.Closeable;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.CacheableResult;
import io.modelcontextprotocol.modern.McpSchema.CallToolOutcome;
import io.modelcontextprotocol.modern.McpSchema.CallToolRequest;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.CompleteReference;
import io.modelcontextprotocol.modern.McpSchema.CompleteRequest;
import io.modelcontextprotocol.modern.McpSchema.CompleteRequest.CompleteArgument;
import io.modelcontextprotocol.modern.McpSchema.CompleteRequest.CompleteContext;
import io.modelcontextprotocol.modern.McpSchema.CompleteResult;
import io.modelcontextprotocol.modern.McpSchema.DiscoverResult;
import io.modelcontextprotocol.modern.McpSchema.ElicitFormRequest;
import io.modelcontextprotocol.modern.McpSchema.ElicitUrlRequest;
import io.modelcontextprotocol.modern.McpSchema.GetPromptOutcome;
import io.modelcontextprotocol.modern.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.modern.McpSchema.GetPromptResult;
import io.modelcontextprotocol.modern.McpSchema.InputRequiredResult;
import io.modelcontextprotocol.modern.McpSchema.ListPromptsResult;
import io.modelcontextprotocol.modern.McpSchema.ListResourceTemplatesResult;
import io.modelcontextprotocol.modern.McpSchema.ListResourcesResult;
import io.modelcontextprotocol.modern.McpSchema.ListToolsResult;
import io.modelcontextprotocol.modern.McpSchema.PaginatedRequest;
import io.modelcontextprotocol.modern.McpSchema.ProgressParams;
import io.modelcontextprotocol.modern.McpSchema.ReadResourceOutcome;
import io.modelcontextprotocol.modern.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.modern.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.modern.McpSchema.SubscriptionFilter;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.util.Assert;
import org.jspecify.annotations.Nullable;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

/**
 * A synchronous client for MCP {@value McpSchema#LATEST_PROTOCOL_VERSION} servers, built
 * with {@link McpClient#sync}. Immutable and thread-safe.
 * <p>
 * Each operation starts from a request spec, such as {@link #tool(String)}, on which the
 * call is configured. Specs are immutable: every setting returns a new spec, so a
 * configured spec can be kept and reused. Their terminals run the call on the calling
 * thread and return its result. The {@link McpTransportContext} of a call comes from the
 * builder's {@link McpClient.SyncBuilder#transportContextProvider
 * transportContextProvider}, called on the calling thread when the call starts, and is
 * used for every request and retry of the call.
 * <p>
 * When a tool call, resource read or prompt get is answered with input requests, the
 * client answers them with the handlers in effect for the call, on the calling thread,
 * and sends the request again, until the server completes it. The capabilities each
 * request declares are the ones those handlers cover.
 * <p>
 * Progress listeners and listen streams run on other threads, which may block.
 * <p>
 * A call fails with {@link McpException} when the server answers with an error,
 * {@link McpTimeoutException} when a timeout expires, {@link McpResponseLostException}
 * when an exchange breaks before its response arrives, and
 * {@link McpInvalidResultException} when a result can't be accepted.
 *
 * @author Dariusz Jędrzejczyk
 */
public final class McpSyncClient {

	private final SyncClientRequester requester;

	private final DiscoverCache discoverCache = new DiscoverCache();

	McpSyncClient(SyncClientRequester requester) {
		this.requester = requester;
	}

	/**
	 * Lists the server's tools ({@code tools/list}).
	 */
	public ListSpec<ListToolsResult> tools() {
		return new ListSpec<>(this.requester, McpSchema.METHOD_TOOLS_LIST, ListToolsResult.class,
				this.requester::listedTools, null, this.requester.settings());
	}

	/**
	 * Lists the server's prompts ({@code prompts/list}).
	 */
	public ListSpec<ListPromptsResult> prompts() {
		return new ListSpec<>(this.requester, McpSchema.METHOD_PROMPTS_LIST, ListPromptsResult.class,
				UnaryOperator.identity(), null, this.requester.settings());
	}

	/**
	 * Lists the server's resources ({@code resources/list}).
	 */
	public ListSpec<ListResourcesResult> resources() {
		return new ListSpec<>(this.requester, McpSchema.METHOD_RESOURCES_LIST, ListResourcesResult.class,
				UnaryOperator.identity(), null, this.requester.settings());
	}

	/**
	 * Lists the server's resource templates ({@code resources/templates/list}).
	 */
	public ListSpec<ListResourceTemplatesResult> resourceTemplates() {
		return new ListSpec<>(this.requester, McpSchema.METHOD_RESOURCES_TEMPLATES_LIST,
				ListResourceTemplatesResult.class, UnaryOperator.identity(), null, this.requester.settings());
	}

	/**
	 * Calls the tool named {@code name} ({@code tools/call}).
	 * <p>
	 * When the transport mirrors tool arguments into headers, the arguments the tool's
	 * definition annotates with {@code x-mcp-header} are sent as {@code Mcp-Param-{Name}}
	 * headers. The definition comes from the client's earlier listings of the tools, or
	 * from listing all tools when the client hasn't seen the tool yet. A call the server
	 * rejects for its headers is sent once more after listing the tools again. A tool the
	 * server doesn't list, or whose annotations are invalid, isn't called; the call fails
	 * with {@link IllegalArgumentException}.
	 */
	public ToolSpec tool(String name) {
		Assert.hasText(name, "name must not be empty");
		return new ToolSpec(this.requester, name, null, null, null, null, this.requester.settings());
	}

	/**
	 * Calls a tool whose definition the caller already has ({@code tools/call}), as
	 * {@link #tool(String)} but with {@code tool} as the tool's definition.
	 */
	public ToolSpec tool(Tool tool) {
		Assert.notNull(tool, "tool must not be null");
		Assert.hasText(tool.name(), "tool name must not be empty");
		return new ToolSpec(this.requester, tool.name(), tool, null, null, null, this.requester.settings());
	}

	/**
	 * Reads the resource at {@code uri} ({@code resources/read}).
	 */
	public ResourceSpec resource(String uri) {
		Assert.hasText(uri, "uri must not be empty");
		return new ResourceSpec(this.requester, uri, null, null, this.requester.settings());
	}

	/**
	 * Gets the prompt named {@code name} ({@code prompts/get}).
	 */
	public PromptSpec prompt(String name) {
		Assert.hasText(name, "name must not be empty");
		return new PromptSpec(this.requester, name, null, null, null, this.requester.settings());
	}

	/**
	 * Asks for completions of the argument {@code argument} of a prompt or resource
	 * template ({@code completion/complete}), whose value so far is {@code value}.
	 */
	public CompletionSpec completion(CompleteReference ref, String argument, String value) {
		Assert.notNull(ref, "ref must not be null");
		return new CompletionSpec(this.requester, ref, new CompleteArgument(argument, value), null,
				this.requester.settings());
	}

	/**
	 * Listens for changes on the server ({@code subscriptions/listen}). The changes to
	 * listen for are set on the returned spec.
	 */
	public ListenSpec listen() {
		return new ListenSpec(this.requester, SubscriptionFilter.EMPTY, Map.of());
	}

	/**
	 * Asks the server for its versions, capabilities, instructions and info
	 * ({@code server/discover}). The result is kept for its {@code ttlMs}, during which
	 * it is returned without asking the server again.
	 */
	public DiscoverResult discover() {
		DiscoverResult fresh = this.discoverCache.fresh();
		if (fresh != null) {
			return fresh;
		}
		return this.discoverCache.keep(this.requester.call(McpSchema.METHOD_SERVER_DISCOVER, null, Map.of(),
				this.requester.settings(), DiscoverResult.class));
	}

	/**
	 * All the server's tools, as one result: the listing the client keeps, or every page
	 * of {@code tools().list()} when it keeps none; see {@link ListToolsSpec}.
	 */
	public ListToolsSpec listTools() {
		return new ListToolsSpec(this.requester, false, this.requester.settings());
	}

	@Nullable private static <V> Map<String, V> copy(@Nullable Map<String, V> map) {
		return map == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(map));
	}

	private static <H> H requireHandler(H handler) {
		Assert.notNull(handler, "handler must not be null");
		return handler;
	}

	/**
	 * Lists one page of a kind of primitive. Immutable.
	 *
	 * @param <R> the list result
	 */
	public static final class ListSpec<R extends CacheableResult> {

		private final SyncClientRequester requester;

		private final String method;

		private final Class<R> resultType;

		// What the client does with each page it lists before returning it.
		private final UnaryOperator<R> listed;

		@Nullable private final String cursor;

		private final CallSettings<Object> settings;

		private ListSpec(SyncClientRequester requester, String method, Class<R> resultType, UnaryOperator<R> listed,
				@Nullable String cursor, CallSettings<Object> settings) {
			this.requester = requester;
			this.method = method;
			this.resultType = resultType;
			this.listed = listed;
			this.cursor = cursor;
			this.settings = settings;
		}

		/**
		 * The page to list: the {@code nextCursor} of the previous page, or {@code null}
		 * for the first page.
		 */
		public ListSpec<R> cursor(@Nullable String cursor) {
			return new ListSpec<>(this.requester, this.method, this.resultType, this.listed, cursor, this.settings);
		}

		/**
		 * The timeout of each exchange, instead of the client's.
		 */
		public ListSpec<R> roundTimeout(Duration roundTimeout) {
			return with(this.settings.withRoundTimeout(roundTimeout));
		}

		/**
		 * The bound on the whole call, instead of the client's.
		 */
		public ListSpec<R> callTimeout(Duration callTimeout) {
			return with(this.settings.withCallTimeout(callTimeout));
		}

		/**
		 * Receives the call's progress, instead of the client's listener. It is called
		 * one notification at a time, in order, on a thread that may block.
		 */
		public ListSpec<R> progress(Consumer<ProgressParams> listener) {
			return with(this.settings.withProgress(SyncClientRequester.onBlockingThread(listener)));
		}

		/**
		 * Entries added to the {@code _meta} of the request and of each of its retries,
		 * replacing those set before.
		 * @throws IllegalArgumentException for {@code progressToken} and keys with the
		 * {@code io.modelcontextprotocol/} prefix, which the client sets itself
		 */
		public ListSpec<R> meta(Map<String, ?> meta) {
			return with(this.settings.withMeta(meta));
		}

		/**
		 * Lists the page.
		 */
		public R list() {
			return this.listed.apply(this.requester.call(this.method, null, new PaginatedRequest(this.cursor, null),
					this.settings, this.resultType));
		}

		private ListSpec<R> with(CallSettings<Object> settings) {
			return new ListSpec<>(this.requester, this.method, this.resultType, this.listed, this.cursor, settings);
		}

	}

	/**
	 * Lists all the server's tools as one result, kept for the shortest {@code ttlMs} of
	 * its pages and private if any page is. Immutable.
	 * <p>
	 * The client keeps the last such listing until its {@code ttlMs} has passed, and
	 * returns it instead of asking the server again. A client stands for one identity, so
	 * a private listing is kept as well. The kept listing is replaced by every listing of
	 * all tools, including the one a call by name makes, and dropped when a listen stream
	 * reports that the tools may have changed. A listing with a {@code ttlMs} of
	 * {@code 0} isn't kept.
	 * <p>
	 * A listing longer than {@link McpClient.Builder#maxListPages maxListPages} fails
	 * with {@link McpInvalidResultException}. When the transport mirrors tool arguments
	 * into headers, tools whose {@code x-mcp-header} annotations are invalid are left
	 * out, and logged.
	 */
	public static final class ListToolsSpec {

		private final SyncClientRequester requester;

		private final boolean skipCache;

		private final CallSettings<Object> settings;

		private ListToolsSpec(SyncClientRequester requester, boolean skipCache, CallSettings<Object> settings) {
			this.requester = requester;
			this.skipCache = skipCache;
			this.settings = settings;
		}

		/**
		 * Asks the server even when the client keeps a listing, and keeps the new one.
		 */
		public ListToolsSpec skipCache() {
			return new ListToolsSpec(this.requester, true, this.settings);
		}

		/**
		 * The timeout of each exchange, instead of the client's.
		 */
		public ListToolsSpec roundTimeout(Duration roundTimeout) {
			return with(this.settings.withRoundTimeout(roundTimeout));
		}

		/**
		 * The bound on each page's call, instead of the client's.
		 */
		public ListToolsSpec callTimeout(Duration callTimeout) {
			return with(this.settings.withCallTimeout(callTimeout));
		}

		/**
		 * Receives the progress of each page's call, instead of the client's listener, on
		 * a thread that may block.
		 */
		public ListToolsSpec progress(Consumer<ProgressParams> listener) {
			return with(this.settings.withProgress(SyncClientRequester.onBlockingThread(listener)));
		}

		/**
		 * Entries added to the {@code _meta} of each page's request, replacing those set
		 * before.
		 * @throws IllegalArgumentException for {@code progressToken} and keys with the
		 * {@code io.modelcontextprotocol/} prefix, which the client sets itself
		 */
		public ListToolsSpec meta(Map<String, ?> meta) {
			return with(this.settings.withMeta(meta));
		}

		/**
		 * Lists the tools, or returns the kept listing.
		 */
		public ListToolsResult list() {
			ListToolsResult kept = this.skipCache ? null : this.requester.keptTools();
			if (kept != null) {
				return kept;
			}
			ListSpec<ListToolsResult> pages = new ListSpec<>(this.requester, McpSchema.METHOD_TOOLS_LIST,
					ListToolsResult.class, UnaryOperator.identity(), null, this.settings);
			// Each page is a call of its own on this thread.
			return this.requester.listAllTools(cursor -> Mono.fromCallable(() -> pages.cursor(cursor).list())).block();
		}

		private ListToolsSpec with(CallSettings<Object> settings) {
			return new ListToolsSpec(this.requester, this.skipCache, settings);
		}

	}

	/**
	 * A call of one tool. Immutable.
	 */
	@SuppressWarnings("deprecation")
	public static final class ToolSpec {

		private final SyncClientRequester requester;

		private final String name;

		@Nullable private final Tool definition;

		@Nullable private final Map<String, Object> arguments;

		@Nullable private final Map<String, Object> inputResponses;

		@Nullable private final String requestState;

		private final CallSettings<Object> settings;

		private ToolSpec(SyncClientRequester requester, String name, @Nullable Tool definition,
				@Nullable Map<String, Object> arguments, @Nullable Map<String, Object> inputResponses,
				@Nullable String requestState, CallSettings<Object> settings) {
			this.requester = requester;
			this.name = name;
			this.definition = definition;
			this.arguments = arguments;
			this.inputResponses = inputResponses;
			this.requestState = requestState;
			this.settings = settings;
		}

		/**
		 * The tool's arguments.
		 */
		public ToolSpec arguments(@Nullable Map<String, Object> arguments) {
			return new ToolSpec(this.requester, this.name, this.definition, copy(arguments), this.inputResponses,
					this.requestState, this.settings);
		}

		/**
		 * The answers to the input requests of an earlier round of this call, keyed as
		 * the server keyed the requests.
		 */
		public ToolSpec inputResponses(@Nullable Map<String, Object> inputResponses) {
			return new ToolSpec(this.requester, this.name, this.definition, this.arguments, copy(inputResponses),
					this.requestState, this.settings);
		}

		/**
		 * The {@code requestState} of an earlier round of this call, sent back as it was
		 * received.
		 */
		public ToolSpec requestState(@Nullable String requestState) {
			return new ToolSpec(this.requester, this.name, this.definition, this.arguments, this.inputResponses,
					requestState, this.settings);
		}

		/**
		 * The timeout of each exchange, instead of the client's.
		 */
		public ToolSpec roundTimeout(Duration roundTimeout) {
			return with(this.settings.withRoundTimeout(roundTimeout));
		}

		/**
		 * The bound on the whole call, including the time spent answering input, instead
		 * of the client's. A handler can't be interrupted; the bound is checked when it
		 * returns.
		 */
		public ToolSpec callTimeout(Duration callTimeout) {
			return with(this.settings.withCallTimeout(callTimeout));
		}

		/**
		 * Receives the call's progress, instead of the client's listener. It is called
		 * one notification at a time, in order, on a thread that may block.
		 */
		public ToolSpec progress(Consumer<ProgressParams> listener) {
			return with(this.settings.withProgress(SyncClientRequester.onBlockingThread(listener)));
		}

		/**
		 * Entries added to the {@code _meta} of the request and of each of its retries,
		 * replacing those set before.
		 * @throws IllegalArgumentException for {@code progressToken} and keys with the
		 * {@code io.modelcontextprotocol/} prefix, which the client sets itself
		 */
		public ToolSpec meta(Map<String, ?> meta) {
			return with(this.settings.withMeta(meta));
		}

		/**
		 * Answers the form-mode elicitations of this call, instead of the client's form
		 * handler.
		 */
		public ToolSpec formElicitation(McpSyncElicitationHandler<ElicitFormRequest> handler) {
			return with(this.settings.withFormElicitation(requireHandler(handler)::elicit));
		}

		/**
		 * Answers the URL-mode elicitations of this call, instead of the client's URL
		 * handler.
		 */
		public ToolSpec urlElicitation(McpSyncElicitationHandler<ElicitUrlRequest> handler) {
			return with(this.settings.withUrlElicitation(requireHandler(handler)::elicit));
		}

		/**
		 * Calls the tool and returns its result, answering the server's input requests
		 * along the way.
		 */
		public CallToolResult call() {
			return this.requester.callTool(this.name, request(), this.settings, this.definition);
		}

		/**
		 * Sends the call once and returns the server's answer: a {@link CallToolResult}
		 * or an {@link InputRequiredResult}.
		 */
		public CallToolOutcome callOnce() {
			return (CallToolOutcome) this.requester.onceTool(this.name, request(), this.settings, this.definition);
		}

		private CallToolRequest request() {
			return new CallToolRequest(this.name, this.arguments, this.inputResponses, this.requestState, null);
		}

		private ToolSpec with(CallSettings<Object> settings) {
			return new ToolSpec(this.requester, this.name, this.definition, this.arguments, this.inputResponses,
					this.requestState, settings);
		}

	}

	/**
	 * A read of one resource. Immutable.
	 */
	@SuppressWarnings("deprecation")
	public static final class ResourceSpec {

		private final SyncClientRequester requester;

		private final String uri;

		@Nullable private final Map<String, Object> inputResponses;

		@Nullable private final String requestState;

		private final CallSettings<Object> settings;

		private ResourceSpec(SyncClientRequester requester, String uri, @Nullable Map<String, Object> inputResponses,
				@Nullable String requestState, CallSettings<Object> settings) {
			this.requester = requester;
			this.uri = uri;
			this.inputResponses = inputResponses;
			this.requestState = requestState;
			this.settings = settings;
		}

		/**
		 * The answers to the input requests of an earlier round of this read, keyed as
		 * the server keyed the requests.
		 */
		public ResourceSpec inputResponses(@Nullable Map<String, Object> inputResponses) {
			return new ResourceSpec(this.requester, this.uri, copy(inputResponses), this.requestState, this.settings);
		}

		/**
		 * The {@code requestState} of an earlier round of this read, sent back as it was
		 * received.
		 */
		public ResourceSpec requestState(@Nullable String requestState) {
			return new ResourceSpec(this.requester, this.uri, this.inputResponses, requestState, this.settings);
		}

		/**
		 * The timeout of each exchange, instead of the client's.
		 */
		public ResourceSpec roundTimeout(Duration roundTimeout) {
			return with(this.settings.withRoundTimeout(roundTimeout));
		}

		/**
		 * The bound on the whole read, including the time spent answering input, instead
		 * of the client's. A handler can't be interrupted; the bound is checked when it
		 * returns.
		 */
		public ResourceSpec callTimeout(Duration callTimeout) {
			return with(this.settings.withCallTimeout(callTimeout));
		}

		/**
		 * Receives the read's progress, instead of the client's listener. It is called
		 * one notification at a time, in order, on a thread that may block.
		 */
		public ResourceSpec progress(Consumer<ProgressParams> listener) {
			return with(this.settings.withProgress(SyncClientRequester.onBlockingThread(listener)));
		}

		/**
		 * Entries added to the {@code _meta} of the request and of each of its retries,
		 * replacing those set before.
		 * @throws IllegalArgumentException for {@code progressToken} and keys with the
		 * {@code io.modelcontextprotocol/} prefix, which the client sets itself
		 */
		public ResourceSpec meta(Map<String, ?> meta) {
			return with(this.settings.withMeta(meta));
		}

		/**
		 * Answers the form-mode elicitations of this read, instead of the client's form
		 * handler.
		 */
		public ResourceSpec formElicitation(McpSyncElicitationHandler<ElicitFormRequest> handler) {
			return with(this.settings.withFormElicitation(requireHandler(handler)::elicit));
		}

		/**
		 * Answers the URL-mode elicitations of this read, instead of the client's URL
		 * handler.
		 */
		public ResourceSpec urlElicitation(McpSyncElicitationHandler<ElicitUrlRequest> handler) {
			return with(this.settings.withUrlElicitation(requireHandler(handler)::elicit));
		}

		/**
		 * Reads the resource and returns its contents, answering the server's input
		 * requests along the way.
		 */
		public ReadResourceResult read() {
			return this.requester.call(McpSchema.METHOD_RESOURCES_READ, this.uri, request(), this.settings,
					ReadResourceResult.class);
		}

		/**
		 * Sends the read once and returns the server's answer: a
		 * {@link ReadResourceResult} or an {@link InputRequiredResult}.
		 */
		public ReadResourceOutcome readOnce() {
			return (ReadResourceOutcome) this.requester.once(McpSchema.METHOD_RESOURCES_READ, this.uri, request(),
					this.settings, ReadResourceResult.class);
		}

		private ReadResourceRequest request() {
			return new ReadResourceRequest(this.uri, this.inputResponses, this.requestState, null);
		}

		private ResourceSpec with(CallSettings<Object> settings) {
			return new ResourceSpec(this.requester, this.uri, this.inputResponses, this.requestState, settings);
		}

	}

	/**
	 * A get of one prompt. Immutable.
	 */
	@SuppressWarnings("deprecation")
	public static final class PromptSpec {

		private final SyncClientRequester requester;

		private final String name;

		@Nullable private final Map<String, String> arguments;

		@Nullable private final Map<String, Object> inputResponses;

		@Nullable private final String requestState;

		private final CallSettings<Object> settings;

		private PromptSpec(SyncClientRequester requester, String name, @Nullable Map<String, String> arguments,
				@Nullable Map<String, Object> inputResponses, @Nullable String requestState,
				CallSettings<Object> settings) {
			this.requester = requester;
			this.name = name;
			this.arguments = arguments;
			this.inputResponses = inputResponses;
			this.requestState = requestState;
			this.settings = settings;
		}

		/**
		 * The prompt's arguments.
		 */
		public PromptSpec arguments(@Nullable Map<String, String> arguments) {
			return new PromptSpec(this.requester, this.name, copy(arguments), this.inputResponses, this.requestState,
					this.settings);
		}

		/**
		 * The answers to the input requests of an earlier round of this get, keyed as the
		 * server keyed the requests.
		 */
		public PromptSpec inputResponses(@Nullable Map<String, Object> inputResponses) {
			return new PromptSpec(this.requester, this.name, this.arguments, copy(inputResponses), this.requestState,
					this.settings);
		}

		/**
		 * The {@code requestState} of an earlier round of this get, sent back as it was
		 * received.
		 */
		public PromptSpec requestState(@Nullable String requestState) {
			return new PromptSpec(this.requester, this.name, this.arguments, this.inputResponses, requestState,
					this.settings);
		}

		/**
		 * The timeout of each exchange, instead of the client's.
		 */
		public PromptSpec roundTimeout(Duration roundTimeout) {
			return with(this.settings.withRoundTimeout(roundTimeout));
		}

		/**
		 * The bound on the whole get, including the time spent answering input, instead
		 * of the client's. A handler can't be interrupted; the bound is checked when it
		 * returns.
		 */
		public PromptSpec callTimeout(Duration callTimeout) {
			return with(this.settings.withCallTimeout(callTimeout));
		}

		/**
		 * Receives the get's progress, instead of the client's listener. It is called one
		 * notification at a time, in order, on a thread that may block.
		 */
		public PromptSpec progress(Consumer<ProgressParams> listener) {
			return with(this.settings.withProgress(SyncClientRequester.onBlockingThread(listener)));
		}

		/**
		 * Entries added to the {@code _meta} of the request and of each of its retries,
		 * replacing those set before.
		 * @throws IllegalArgumentException for {@code progressToken} and keys with the
		 * {@code io.modelcontextprotocol/} prefix, which the client sets itself
		 */
		public PromptSpec meta(Map<String, ?> meta) {
			return with(this.settings.withMeta(meta));
		}

		/**
		 * Answers the form-mode elicitations of this get, instead of the client's form
		 * handler.
		 */
		public PromptSpec formElicitation(McpSyncElicitationHandler<ElicitFormRequest> handler) {
			return with(this.settings.withFormElicitation(requireHandler(handler)::elicit));
		}

		/**
		 * Answers the URL-mode elicitations of this get, instead of the client's URL
		 * handler.
		 */
		public PromptSpec urlElicitation(McpSyncElicitationHandler<ElicitUrlRequest> handler) {
			return with(this.settings.withUrlElicitation(requireHandler(handler)::elicit));
		}

		/**
		 * Gets the prompt and returns its messages, answering the server's input requests
		 * along the way.
		 */
		public GetPromptResult get() {
			return this.requester.call(McpSchema.METHOD_PROMPTS_GET, this.name, request(), this.settings,
					GetPromptResult.class);
		}

		/**
		 * Sends the get once and returns the server's answer: a {@link GetPromptResult}
		 * or an {@link InputRequiredResult}.
		 */
		public GetPromptOutcome getOnce() {
			return (GetPromptOutcome) this.requester.once(McpSchema.METHOD_PROMPTS_GET, this.name, request(),
					this.settings, GetPromptResult.class);
		}

		private GetPromptRequest request() {
			return new GetPromptRequest(this.name, this.arguments, this.inputResponses, this.requestState, null);
		}

		private PromptSpec with(CallSettings<Object> settings) {
			return new PromptSpec(this.requester, this.name, this.arguments, this.inputResponses, this.requestState,
					settings);
		}

	}

	/**
	 * A request for completions of one argument. Immutable.
	 */
	public static final class CompletionSpec {

		private final SyncClientRequester requester;

		private final CompleteReference ref;

		private final CompleteArgument argument;

		@Nullable private final Map<String, String> context;

		private final CallSettings<Object> settings;

		private CompletionSpec(SyncClientRequester requester, CompleteReference ref, CompleteArgument argument,
				@Nullable Map<String, String> context, CallSettings<Object> settings) {
			this.requester = requester;
			this.ref = ref;
			this.argument = argument;
			this.context = context;
			this.settings = settings;
		}

		/**
		 * The values of the other arguments, already resolved.
		 */
		public CompletionSpec context(@Nullable Map<String, String> arguments) {
			return new CompletionSpec(this.requester, this.ref, this.argument, copy(arguments), this.settings);
		}

		/**
		 * The timeout of each exchange, instead of the client's.
		 */
		public CompletionSpec roundTimeout(Duration roundTimeout) {
			return with(this.settings.withRoundTimeout(roundTimeout));
		}

		/**
		 * The bound on the whole call, instead of the client's.
		 */
		public CompletionSpec callTimeout(Duration callTimeout) {
			return with(this.settings.withCallTimeout(callTimeout));
		}

		/**
		 * Receives the call's progress, instead of the client's listener. It is called
		 * one notification at a time, in order, on a thread that may block.
		 */
		public CompletionSpec progress(Consumer<ProgressParams> listener) {
			return with(this.settings.withProgress(SyncClientRequester.onBlockingThread(listener)));
		}

		/**
		 * Entries added to the {@code _meta} of the request and of each of its retries,
		 * replacing those set before.
		 * @throws IllegalArgumentException for {@code progressToken} and keys with the
		 * {@code io.modelcontextprotocol/} prefix, which the client sets itself
		 */
		public CompletionSpec meta(Map<String, ?> meta) {
			return with(this.settings.withMeta(meta));
		}

		/**
		 * Asks for the completions.
		 */
		public CompleteResult complete() {
			CompleteContext completeContext = this.context == null ? null : new CompleteContext(this.context);
			return this.requester.call(McpSchema.METHOD_COMPLETION_COMPLETE, null,
					new CompleteRequest(this.ref, this.argument, null, completeContext), this.settings,
					CompleteResult.class);
		}

		private CompletionSpec with(CallSettings<Object> settings) {
			return new CompletionSpec(this.requester, this.ref, this.argument, this.context, settings);
		}

	}

	/**
	 * A listen stream: the changes to listen for, and the terminal that opens it.
	 * Immutable.
	 */
	public static final class ListenSpec {

		private final SyncClientRequester requester;

		private final SubscriptionFilter filter;

		private final Map<String, Object> meta;

		private ListenSpec(SyncClientRequester requester, SubscriptionFilter filter, Map<String, Object> meta) {
			this.requester = requester;
			this.filter = filter;
			this.meta = meta;
		}

		/**
		 * Listens for changes of the server's tools.
		 */
		public ListenSpec toolsChanged() {
			return withFilter(SubscriptionFilter.TOOLS_LIST_CHANGED, true);
		}

		/**
		 * Listens for changes of the server's prompts.
		 */
		public ListenSpec promptsChanged() {
			return withFilter(SubscriptionFilter.PROMPTS_LIST_CHANGED, true);
		}

		/**
		 * Listens for changes of the server's list of resources.
		 */
		public ListenSpec resourcesChanged() {
			return withFilter(SubscriptionFilter.RESOURCES_LIST_CHANGED, true);
		}

		/**
		 * Listens for updates of the resources at {@code uris}, in addition to those
		 * already set.
		 */
		public ListenSpec resourceUpdates(String... uris) {
			Assert.notNull(uris, "uris must not be null");
			Set<String> merged = new LinkedHashSet<>();
			merged.addAll(this.filter.resourceSubscriptionsOrEmpty());
			for (String uri : uris) {
				Assert.hasText(uri, "uri must not be empty");
				merged.add(uri);
			}
			return withFilter(SubscriptionFilter.RESOURCE_SUBSCRIPTIONS, List.copyOf(merged));
		}

		/**
		 * Entries added to the {@code _meta} of the stream's request, replacing those set
		 * before.
		 * @throws IllegalArgumentException for {@code progressToken} and keys with the
		 * {@code io.modelcontextprotocol/} prefix, which the client sets itself
		 */
		public ListenSpec meta(Map<String, ?> meta) {
			return new ListenSpec(this.requester, this.filter, RequestMeta.applicationEntries(meta));
		}

		/**
		 * Opens the stream and returns without waiting for the server. The request is
		 * sent for the {@link McpTransportContext} the client's provider supplies on the
		 * calling thread. {@code listener} receives {@link SubscriptionEvent.Subscribed}
		 * first, then the changes the server agreed to report; changes the server reports
		 * beyond those are dropped and logged. No timeout applies. To keep listening
		 * after the stream ended, open it again; each new stream starts with
		 * {@code Subscribed} again.
		 * @see McpSyncSubscriptionListener
		 */
		public Listening open(McpSyncSubscriptionListener listener) {
			Assert.notNull(listener, "listener must not be null");
			Disposable stream = this.requester.listen(this.filter, this.meta, listener);
			return stream::dispose;
		}

		/**
		 * Listens for what an extension defines: {@code value} under {@code field} in the
		 * request's filter, replacing a value set before for that field.
		 * {@link SubscriptionEvent.Subscribed#missing()} reports it like the core fields
		 * when the server doesn't agree to it.
		 * @param value a JSON value: a boolean, number, string, list or map
		 */
		public ListenSpec filter(String field, Object value) {
			Assert.hasText(field, "field must not be empty");
			Assert.notNull(value, "value must not be null");
			return withFilter(field, value);
		}

		private ListenSpec withFilter(String field, Object value) {
			return new ListenSpec(this.requester, this.filter.with(field, value), this.meta);
		}

	}

	/**
	 * An open listen stream.
	 */
	@FunctionalInterface
	public interface Listening extends Closeable {

		/**
		 * Closes the stream. Idempotent.
		 */
		@Override
		void close();

	}

}
