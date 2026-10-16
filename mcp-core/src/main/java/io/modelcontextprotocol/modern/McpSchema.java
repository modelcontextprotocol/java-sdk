/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wire types for the MCP {@code 2026-07-28} ("modern") revision of the protocol: no
 * handshake, no session, per-request {@code _meta}, and a required {@code resultType} on
 * every result. The JSON-RPC envelope lives in {@link JsonRpc}.
 *
 * @author Dariusz Jędrzejczyk
 */
public final class McpSchema {

	private static final Logger logger = LoggerFactory.getLogger(McpSchema.class);

	private static final TypeRef<HashMap<String, Object>> MAP_TYPE_REF = new TypeRef<>() {
	};

	private McpSchema() {
	}

	// ---------------------------
	// Protocol constants
	// ---------------------------

	/** The protocol revision implemented by this package. */
	public static final String LATEST_PROTOCOL_VERSION = "2026-07-28";

	public static final String METHOD_SERVER_DISCOVER = "server/discover";

	public static final String METHOD_TOOLS_LIST = "tools/list";

	public static final String METHOD_TOOLS_CALL = "tools/call";

	public static final String METHOD_RESOURCES_LIST = "resources/list";

	public static final String METHOD_RESOURCES_TEMPLATES_LIST = "resources/templates/list";

	public static final String METHOD_RESOURCES_READ = "resources/read";

	public static final String METHOD_PROMPTS_LIST = "prompts/list";

	public static final String METHOD_PROMPTS_GET = "prompts/get";

	public static final String METHOD_COMPLETION_COMPLETE = "completion/complete";

	public static final String METHOD_SUBSCRIPTIONS_LISTEN = "subscriptions/listen";

	public static final String METHOD_NOTIFICATION_CANCELLED = "notifications/cancelled";

	public static final String METHOD_NOTIFICATION_PROGRESS = "notifications/progress";

	public static final String METHOD_NOTIFICATION_MESSAGE = "notifications/message";

	public static final String METHOD_NOTIFICATION_SUBSCRIPTIONS_ACKNOWLEDGED = "notifications/subscriptions/acknowledged";

	public static final String METHOD_NOTIFICATION_RESOURCES_UPDATED = "notifications/resources/updated";

	public static final String METHOD_NOTIFICATION_TOOLS_LIST_CHANGED = "notifications/tools/list_changed";

	public static final String METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED = "notifications/prompts/list_changed";

	public static final String METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED = "notifications/resources/list_changed";

	/** MRTR input-request payload methods; these never carry a JSON-RPC id. */
	public static final String METHOD_ELICITATION_CREATE = "elicitation/create";

	public static final String METHOD_SAMPLING_CREATE_MESSAGE = "sampling/createMessage";

	public static final String METHOD_ROOTS_LIST = "roots/list";

	/**
	 * The reserved {@code _meta} key names used on the modern wire.
	 */
	public static final class MetaKeys {

		public static final String PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";

		public static final String CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";

		public static final String CLIENT_INFO = "io.modelcontextprotocol/clientInfo";

		public static final String LOG_LEVEL = "io.modelcontextprotocol/logLevel";

		public static final String SERVER_INFO = "io.modelcontextprotocol/serverInfo";

		public static final String SUBSCRIPTION_ID = "io.modelcontextprotocol/subscriptionId";

		public static final String PROGRESS_TOKEN = "progressToken";

		private MetaKeys() {
		}

	}

	/**
	 * JSON-RPC and MCP-specific error codes for the modern revision.
	 * <p>
	 * {@code -32002} ({@code RESOURCE_NOT_FOUND}) and {@code -32042}
	 * ({@code URL_ELICITATION_REQUIRED}) from the legacy revision must never be emitted
	 * on the modern path; a missing resource is reported as {@link #INVALID_PARAMS}.
	 */
	public static final class ErrorCodes {

		public static final int PARSE_ERROR = -32700;

		public static final int INVALID_REQUEST = -32600;

		public static final int METHOD_NOT_FOUND = -32601;

		public static final int INVALID_PARAMS = -32602;

		public static final int INTERNAL_ERROR = -32603;

		public static final int HEADER_MISMATCH = -32020;

		public static final int MISSING_REQUIRED_CLIENT_CAPABILITY = -32021;

		public static final int UNSUPPORTED_PROTOCOL_VERSION = -32022;

		private ErrorCodes() {
		}

	}

	// ---------------------------
	// Result base types
	// ---------------------------

	/**
	 * Base type for every modern result; {@code resultType} is spec-required (see
	 * {@link ResultType}).
	 */
	public interface Result {

		String resultType();

		Map<String, Object> meta();

	}

	/**
	 * The two {@code resultType} values defined by this revision.
	 */
	public static final class ResultType {

		public static final String COMPLETE = "complete";

		public static final String INPUT_REQUIRED = "input_required";

		private ResultType() {
		}

	}

	/**
	 * A result that carries caching hints. Required on {@code resultType: "complete"}
	 * results from the list methods, {@code resources/read} and {@code server/discover};
	 * never present on an {@code "input_required"} result.
	 */
	public interface CacheableResult extends Result {

		Long ttlMs();

		CacheScope cacheScope();

	}

	/**
	 * A result asking the client for more input before the request can complete. An
	 * extension method supporting multi round-trip requests returns its own record
	 * implementing this interface and the method's outcome type.
	 */
	public interface InputRequired extends Result {

		Map<String, InputRequest> inputRequests();

		String requestState();

	}

	/**
	 * What a {@code tools/call} handler returns: a {@link CallToolResult} or input
	 * required.
	 */
	public interface CallToolOutcome extends Result {

	}

	/**
	 * What a {@code prompts/get} handler returns: a {@link GetPromptResult} or input
	 * required.
	 */
	public interface GetPromptOutcome extends Result {

	}

	/**
	 * What a {@code resources/read} handler returns: a {@link ReadResourceResult} or
	 * input required.
	 */
	public interface ReadResourceOutcome extends Result {

	}

	/**
	 * Who may reuse a cached response. {@code PUBLIC} asserts the response contains no
	 * user-specific data and may be served to any client across access tokens; it must
	 * never be the default. {@code PRIVATE} permits reuse only within the same
	 * authorization context.
	 */
	public enum CacheScope {

		@JsonProperty("public")
		PUBLIC, @JsonProperty("private")
		PRIVATE

	}

	// ---------------------------
	// Capabilities
	// ---------------------------

	/**
	 * Capabilities a client declares on every request via {@code _meta}.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ClientCapabilities( // @formatter:off
		@JsonProperty("experimental") Map<String, Object> experimental,
		@JsonProperty("roots") Roots roots,
		@JsonProperty("sampling") Sampling sampling,
		@JsonProperty("elicitation") Elicitation elicitation,
		@JsonProperty("extensions") Map<String, Map<String, Object>> extensions) { // @formatter:on

		public static final ClientCapabilities NONE = new ClientCapabilities(null, null, null, null, null);

		public boolean supportsRoots() {
			return this.roots != null;
		}

		public boolean supportsSampling() {
			return this.sampling != null;
		}

		public boolean supportsElicitationForm() {
			return this.elicitation != null && (this.elicitation.form() != null || this.elicitation.url() == null);
		}

		public boolean supportsElicitationUrl() {
			return this.elicitation != null && this.elicitation.url() != null;
		}

		public static Builder builder() {
			return new Builder();
		}

		/** Marker for roots support; deprecated by the spec. */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record Roots() {
		}

		/** Marker for sampling support; deprecated by the spec. */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record Sampling() {
		}

		/**
		 * Elicitation support. An empty object is equivalent to {@code form} only.
		 */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record Elicitation(@JsonProperty("form") Form form, @JsonProperty("url") Url url) {

			@JsonInclude(JsonInclude.Include.NON_ABSENT)
			@JsonIgnoreProperties(ignoreUnknown = true)
			public record Form() {
			}

			@JsonInclude(JsonInclude.Include.NON_ABSENT)
			@JsonIgnoreProperties(ignoreUnknown = true)
			public record Url() {
			}

		}

		public static final class Builder {

			private Map<String, Object> experimental;

			private Roots roots;

			private Sampling sampling;

			private Elicitation elicitation;

			private Map<String, Map<String, Object>> extensions;

			public Builder experimental(Map<String, Object> experimental) {
				this.experimental = experimental;
				return this;
			}

			public Builder roots() {
				this.roots = new Roots();
				return this;
			}

			public Builder sampling() {
				this.sampling = new Sampling();
				return this;
			}

			public Builder elicitation(Elicitation elicitation) {
				this.elicitation = elicitation;
				return this;
			}

			public Builder extensions(Map<String, Map<String, Object>> extensions) {
				this.extensions = extensions;
				return this;
			}

			public ClientCapabilities build() {
				return new ClientCapabilities(experimental, roots, sampling, elicitation, extensions);
			}

		}
	}

	/**
	 * Capabilities a server advertises via {@code server/discover}. Populated by
	 * aggregating every registered {@code McpFeature}.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ServerCapabilities( // @formatter:off
		@JsonProperty("experimental") Map<String, Object> experimental,
		@JsonProperty("logging") Logging logging,
		@JsonProperty("completions") Completions completions,
		@JsonProperty("prompts") Prompts prompts,
		@JsonProperty("resources") Resources resources,
		@JsonProperty("tools") Tools tools,
		@JsonProperty("extensions") Map<String, Map<String, Object>> extensions) { // @formatter:on

		public static Builder builder() {
			return new Builder();
		}

		/** Present if the server accepts a per-request {@code logLevel}. Deprecated. */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record Logging() {
		}

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record Completions() {
		}

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record Prompts(@JsonProperty("listChanged") Boolean listChanged) {
		}

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record Resources(@JsonProperty("subscribe") Boolean subscribe,
				@JsonProperty("listChanged") Boolean listChanged) {
		}

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record Tools(@JsonProperty("listChanged") Boolean listChanged) {
		}

		/**
		 * Collects the capabilities contributed by each registered feature.
		 */
		public static final class Builder {

			// Prefix: dot-separated labels then '/'; name: alphanumeric at both ends,
			// '-', '_', '.' allowed inside, or empty.
			private static final Pattern EXTENSION_ID = Pattern
				.compile("[A-Za-z](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*/"
						+ "(?:[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?)?");

			private Map<String, Object> experimental;

			private Completions completions;

			private Prompts prompts;

			private Resources resources;

			private Tools tools;

			private Map<String, Map<String, Object>> extensions = new HashMap<>();

			public Builder experimental(Map<String, Object> experimental) {
				this.experimental = experimental;
				return this;
			}

			public Builder completions() {
				this.completions = new Completions();
				return this;
			}

			public Builder prompts(Boolean listChanged) {
				this.prompts = new Prompts(listChanged);
				return this;
			}

			public Builder resources(Boolean subscribe, Boolean listChanged) {
				this.resources = new Resources(subscribe, listChanged);
				return this;
			}

			public Builder tools(Boolean listChanged) {
				this.tools = new Tools(listChanged);
				return this;
			}

			/**
			 * Advertises an extension. {@code id} must be a {@code _meta}-style key with
			 * a prefix, e.g. {@code com.example/my-extension}.
			 */
			public Builder extension(String id, Map<String, Object> settings) {
				Assert.isTrue(id != null && EXTENSION_ID.matcher(id).matches(),
						"extension id must be a prefixed _meta key, e.g. com.example/name: " + id);
				this.extensions.put(id, settings == null ? Map.of() : settings);
				return this;
			}

			public boolean hasTools() {
				return this.tools != null;
			}

			public boolean hasPrompts() {
				return this.prompts != null;
			}

			public boolean hasResources() {
				return this.resources != null;
			}

			public boolean hasResourcesSubscribe() {
				return this.resources != null && Boolean.TRUE.equals(this.resources.subscribe());
			}

			/** Sets {@code tools.listChanged}; a no-op unless tools are advertised. */
			public Builder toolsListChanged(boolean listChanged) {
				if (this.tools != null) {
					this.tools = new Tools(listChanged);
				}
				return this;
			}

			/**
			 * Sets {@code prompts.listChanged}; a no-op unless prompts are advertised.
			 */
			public Builder promptsListChanged(boolean listChanged) {
				if (this.prompts != null) {
					this.prompts = new Prompts(listChanged);
				}
				return this;
			}

			/**
			 * Sets {@code resources.subscribe} and {@code resources.listChanged}; a no-op
			 * unless resources are advertised.
			 */
			public Builder resourcesSubscribe(boolean subscribe, boolean listChanged) {
				if (this.resources != null) {
					this.resources = new Resources(subscribe, listChanged);
				}
				return this;
			}

			public ServerCapabilities build() {
				// Logging is deprecated and not offered by modern servers.
				return new ServerCapabilities(experimental, null, completions, prompts, resources, tools,
						extensions.isEmpty() ? null : Map.copyOf(extensions));
			}

		}
	}

	// ---------------------------
	// server/discover
	// ---------------------------

	/**
	 * The response to {@code server/discover}. Cacheable; the client uses it to determine
	 * supported protocol versions and capabilities before issuing any other request.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record DiscoverResult( // @formatter:off
		@JsonProperty("supportedVersions") List<String> supportedVersions,
		@JsonProperty("capabilities") ServerCapabilities capabilities,
		@JsonProperty("instructions") String instructions,
		@JsonProperty("ttlMs") Long ttlMs,
		@JsonProperty("cacheScope") CacheScope cacheScope,
		@JsonProperty("resultType") String resultType,
		@JsonProperty("_meta") Map<String, Object> meta) implements CacheableResult { // @formatter:on

		public DiscoverResult {
			Assert.notNull(supportedVersions, "supportedVersions must not be null");
			Assert.notNull(capabilities, "capabilities must not be null");
			Assert.notNull(ttlMs, "ttlMs must not be null");
			Assert.notNull(cacheScope, "cacheScope must not be null");
			resultType = resultType == null ? ResultType.COMPLETE : resultType;
		}

		@JsonCreator
		static DiscoverResult fromJson(@JsonProperty("supportedVersions") List<String> supportedVersions,
				@JsonProperty("capabilities") ServerCapabilities capabilities,
				@JsonProperty("instructions") String instructions, @JsonProperty("ttlMs") Long ttlMs,
				@JsonProperty("cacheScope") CacheScope cacheScope, @JsonProperty("resultType") String resultType,
				@JsonProperty("_meta") Map<String, Object> meta) {
			if (supportedVersions == null || capabilities == null || ttlMs == null || cacheScope == null) {
				logger.warn("DiscoverResult: missing required fields during deserialization; substituting defaults");
				supportedVersions = supportedVersions == null ? List.of() : supportedVersions;
				capabilities = capabilities == null ? ServerCapabilities.builder().build() : capabilities;
				ttlMs = ttlMs == null ? 0L : ttlMs;
				cacheScope = cacheScope == null ? CacheScope.PRIVATE : cacheScope;
			}
			return new DiscoverResult(supportedVersions, capabilities, instructions, ttlMs, cacheScope, resultType,
					meta);
		}

		public static Builder builder(List<String> supportedVersions, ServerCapabilities capabilities) {
			return new Builder(supportedVersions, capabilities);
		}

		public static final class Builder {

			private final List<String> supportedVersions;

			private final ServerCapabilities capabilities;

			private String instructions;

			private Long ttlMs = 0L;

			private CacheScope cacheScope = CacheScope.PRIVATE;

			private Map<String, Object> meta;

			private Builder(List<String> supportedVersions, ServerCapabilities capabilities) {
				Assert.notNull(supportedVersions, "supportedVersions must not be null");
				Assert.notNull(capabilities, "capabilities must not be null");
				this.supportedVersions = supportedVersions;
				this.capabilities = capabilities;
			}

			public Builder instructions(String instructions) {
				this.instructions = instructions;
				return this;
			}

			public Builder ttlMs(long ttlMs) {
				Assert.isTrue(ttlMs >= 0, "ttlMs must not be negative");
				this.ttlMs = ttlMs;
				return this;
			}

			public Builder cacheScope(CacheScope cacheScope) {
				Assert.notNull(cacheScope, "cacheScope must not be null");
				this.cacheScope = cacheScope;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public DiscoverResult build() {
				return new DiscoverResult(supportedVersions, capabilities, instructions, ttlMs, cacheScope,
						ResultType.COMPLETE, meta);
			}

		}
	}

	// ---------------------------
	// Requests that may carry MRTR retry data
	// ---------------------------

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record CallToolRequest( // @formatter:off
		@JsonProperty("name") String name,
		@JsonProperty("arguments") Map<String, Object> arguments,
		@JsonProperty("inputResponses") Map<String, Object> inputResponses,
		@JsonProperty("requestState") String requestState,
		@JsonProperty("_meta") Map<String, Object> meta) { // @formatter:on

		public CallToolRequest {
			Assert.notNull(name, "name must not be null");
		}

		public static Builder builder(String name) {
			return new Builder(name);
		}

		public static final class Builder {

			private final String name;

			private Map<String, Object> arguments;

			private Map<String, Object> inputResponses;

			private String requestState;

			private Map<String, Object> meta;

			private Builder(String name) {
				Assert.hasText(name, "name must not be empty");
				this.name = name;
			}

			public Builder arguments(Map<String, Object> arguments) {
				this.arguments = arguments;
				return this;
			}

			public Builder inputResponses(Map<String, Object> inputResponses) {
				this.inputResponses = inputResponses;
				return this;
			}

			public Builder requestState(String requestState) {
				this.requestState = requestState;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public CallToolRequest build() {
				return new CallToolRequest(name, arguments, inputResponses, requestState, meta);
			}

		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ReadResourceRequest( // @formatter:off
		@JsonProperty("uri") String uri,
		@JsonProperty("inputResponses") Map<String, Object> inputResponses,
		@JsonProperty("requestState") String requestState,
		@JsonProperty("_meta") Map<String, Object> meta) { // @formatter:on

		public ReadResourceRequest {
			Assert.notNull(uri, "uri must not be null");
		}

		public static Builder builder(String uri) {
			return new Builder(uri);
		}

		public static final class Builder {

			private final String uri;

			private Map<String, Object> inputResponses;

			private String requestState;

			private Map<String, Object> meta;

			private Builder(String uri) {
				Assert.hasText(uri, "uri must not be empty");
				this.uri = uri;
			}

			public Builder inputResponses(Map<String, Object> inputResponses) {
				this.inputResponses = inputResponses;
				return this;
			}

			public Builder requestState(String requestState) {
				this.requestState = requestState;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ReadResourceRequest build() {
				return new ReadResourceRequest(uri, inputResponses, requestState, meta);
			}

		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record GetPromptRequest( // @formatter:off
		@JsonProperty("name") String name,
		@JsonProperty("arguments") Map<String, String> arguments,
		@JsonProperty("inputResponses") Map<String, Object> inputResponses,
		@JsonProperty("requestState") String requestState,
		@JsonProperty("_meta") Map<String, Object> meta) { // @formatter:on

		public GetPromptRequest {
			Assert.notNull(name, "name must not be null");
		}

		public static Builder builder(String name) {
			return new Builder(name);
		}

		public static final class Builder {

			private final String name;

			private Map<String, String> arguments;

			private Map<String, Object> inputResponses;

			private String requestState;

			private Map<String, Object> meta;

			private Builder(String name) {
				Assert.hasText(name, "name must not be empty");
				this.name = name;
			}

			public Builder arguments(Map<String, String> arguments) {
				this.arguments = arguments;
				return this;
			}

			public Builder inputResponses(Map<String, Object> inputResponses) {
				this.inputResponses = inputResponses;
				return this;
			}

			public Builder requestState(String requestState) {
				this.requestState = requestState;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public GetPromptRequest build() {
				return new GetPromptRequest(name, arguments, inputResponses, requestState, meta);
			}

		}
	}

	/** {@code cursor}/{@code _meta} shared by every paginated list request. */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record PaginatedRequest(@JsonProperty("cursor") String cursor,
			@JsonProperty("_meta") Map<String, Object> meta) {
	}

	// ---------------------------
	// List / read / call / get results
	// ---------------------------

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ListToolsResult( // @formatter:off
		@JsonProperty("tools") List<Tool> tools,
		@JsonProperty("nextCursor") String nextCursor,
		@JsonProperty("ttlMs") Long ttlMs,
		@JsonProperty("cacheScope") CacheScope cacheScope,
		@JsonProperty("resultType") String resultType,
		@JsonProperty("_meta") Map<String, Object> meta) implements CacheableResult { // @formatter:on

		public ListToolsResult {
			Assert.notNull(tools, "tools must not be null");
			Assert.notNull(ttlMs, "ttlMs must not be null");
			Assert.notNull(cacheScope, "cacheScope must not be null");
			resultType = resultType == null ? ResultType.COMPLETE : resultType;
		}

		@JsonCreator
		static ListToolsResult fromJson(@JsonProperty("tools") List<Tool> tools,
				@JsonProperty("nextCursor") String nextCursor, @JsonProperty("ttlMs") Long ttlMs,
				@JsonProperty("cacheScope") CacheScope cacheScope, @JsonProperty("resultType") String resultType,
				@JsonProperty("_meta") Map<String, Object> meta) {
			if (tools == null || ttlMs == null || cacheScope == null) {
				logger.warn("ListToolsResult: missing required fields during deserialization; substituting defaults");
			}
			tools = tools == null ? List.of() : tools;
			ttlMs = ttlMs == null ? 0L : ttlMs;
			cacheScope = cacheScope == null ? CacheScope.PRIVATE : cacheScope;
			return new ListToolsResult(tools, nextCursor, ttlMs, cacheScope, resultType, meta);
		}

		public static Builder builder(List<Tool> tools) {
			return new Builder(tools);
		}

		public static final class Builder {

			private final List<Tool> tools;

			private String nextCursor;

			private Long ttlMs = 0L;

			private CacheScope cacheScope = CacheScope.PRIVATE;

			private Map<String, Object> meta;

			private Builder(List<Tool> tools) {
				Assert.notNull(tools, "tools must not be null");
				this.tools = tools;
			}

			public Builder nextCursor(String nextCursor) {
				this.nextCursor = nextCursor;
				return this;
			}

			public Builder ttlMs(long ttlMs) {
				Assert.isTrue(ttlMs >= 0, "ttlMs must not be negative");
				this.ttlMs = ttlMs;
				return this;
			}

			public Builder cacheScope(CacheScope cacheScope) {
				Assert.notNull(cacheScope, "cacheScope must not be null");
				this.cacheScope = cacheScope;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ListToolsResult build() {
				return new ListToolsResult(tools, nextCursor, ttlMs, cacheScope, ResultType.COMPLETE, meta);
			}

		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ListResourcesResult( // @formatter:off
		@JsonProperty("resources") List<Resource> resources,
		@JsonProperty("nextCursor") String nextCursor,
		@JsonProperty("ttlMs") Long ttlMs,
		@JsonProperty("cacheScope") CacheScope cacheScope,
		@JsonProperty("resultType") String resultType,
		@JsonProperty("_meta") Map<String, Object> meta) implements CacheableResult { // @formatter:on

		public ListResourcesResult {
			Assert.notNull(resources, "resources must not be null");
			Assert.notNull(ttlMs, "ttlMs must not be null");
			Assert.notNull(cacheScope, "cacheScope must not be null");
			resultType = resultType == null ? ResultType.COMPLETE : resultType;
		}

		@JsonCreator
		static ListResourcesResult fromJson(@JsonProperty("resources") List<Resource> resources,
				@JsonProperty("nextCursor") String nextCursor, @JsonProperty("ttlMs") Long ttlMs,
				@JsonProperty("cacheScope") CacheScope cacheScope, @JsonProperty("resultType") String resultType,
				@JsonProperty("_meta") Map<String, Object> meta) {
			resources = resources == null ? List.of() : resources;
			ttlMs = ttlMs == null ? 0L : ttlMs;
			cacheScope = cacheScope == null ? CacheScope.PRIVATE : cacheScope;
			return new ListResourcesResult(resources, nextCursor, ttlMs, cacheScope, resultType, meta);
		}

		public static Builder builder(List<Resource> resources) {
			return new Builder(resources);
		}

		public static final class Builder {

			private final List<Resource> resources;

			private String nextCursor;

			private Long ttlMs = 0L;

			private CacheScope cacheScope = CacheScope.PRIVATE;

			private Map<String, Object> meta;

			private Builder(List<Resource> resources) {
				Assert.notNull(resources, "resources must not be null");
				this.resources = resources;
			}

			public Builder nextCursor(String nextCursor) {
				this.nextCursor = nextCursor;
				return this;
			}

			public Builder ttlMs(long ttlMs) {
				Assert.isTrue(ttlMs >= 0, "ttlMs must not be negative");
				this.ttlMs = ttlMs;
				return this;
			}

			public Builder cacheScope(CacheScope cacheScope) {
				this.cacheScope = cacheScope;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ListResourcesResult build() {
				return new ListResourcesResult(resources, nextCursor, ttlMs, cacheScope, ResultType.COMPLETE, meta);
			}

		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ListResourceTemplatesResult( // @formatter:off
		@JsonProperty("resourceTemplates") List<ResourceTemplate> resourceTemplates,
		@JsonProperty("nextCursor") String nextCursor,
		@JsonProperty("ttlMs") Long ttlMs,
		@JsonProperty("cacheScope") CacheScope cacheScope,
		@JsonProperty("resultType") String resultType,
		@JsonProperty("_meta") Map<String, Object> meta) implements CacheableResult { // @formatter:on

		public ListResourceTemplatesResult {
			Assert.notNull(resourceTemplates, "resourceTemplates must not be null");
			Assert.notNull(ttlMs, "ttlMs must not be null");
			Assert.notNull(cacheScope, "cacheScope must not be null");
			resultType = resultType == null ? ResultType.COMPLETE : resultType;
		}

		@JsonCreator
		static ListResourceTemplatesResult fromJson(
				@JsonProperty("resourceTemplates") List<ResourceTemplate> resourceTemplates,
				@JsonProperty("nextCursor") String nextCursor, @JsonProperty("ttlMs") Long ttlMs,
				@JsonProperty("cacheScope") CacheScope cacheScope, @JsonProperty("resultType") String resultType,
				@JsonProperty("_meta") Map<String, Object> meta) {
			resourceTemplates = resourceTemplates == null ? List.of() : resourceTemplates;
			ttlMs = ttlMs == null ? 0L : ttlMs;
			cacheScope = cacheScope == null ? CacheScope.PRIVATE : cacheScope;
			return new ListResourceTemplatesResult(resourceTemplates, nextCursor, ttlMs, cacheScope, resultType, meta);
		}

		public static Builder builder(List<ResourceTemplate> resourceTemplates) {
			return new Builder(resourceTemplates);
		}

		public static final class Builder {

			private final List<ResourceTemplate> resourceTemplates;

			private String nextCursor;

			private Long ttlMs = 0L;

			private CacheScope cacheScope = CacheScope.PRIVATE;

			private Map<String, Object> meta;

			private Builder(List<ResourceTemplate> resourceTemplates) {
				Assert.notNull(resourceTemplates, "resourceTemplates must not be null");
				this.resourceTemplates = resourceTemplates;
			}

			public Builder nextCursor(String nextCursor) {
				this.nextCursor = nextCursor;
				return this;
			}

			public Builder ttlMs(long ttlMs) {
				Assert.isTrue(ttlMs >= 0, "ttlMs must not be negative");
				this.ttlMs = ttlMs;
				return this;
			}

			public Builder cacheScope(CacheScope cacheScope) {
				this.cacheScope = cacheScope;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ListResourceTemplatesResult build() {
				return new ListResourceTemplatesResult(resourceTemplates, nextCursor, ttlMs, cacheScope,
						ResultType.COMPLETE, meta);
			}

		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ListPromptsResult( // @formatter:off
		@JsonProperty("prompts") List<Prompt> prompts,
		@JsonProperty("nextCursor") String nextCursor,
		@JsonProperty("ttlMs") Long ttlMs,
		@JsonProperty("cacheScope") CacheScope cacheScope,
		@JsonProperty("resultType") String resultType,
		@JsonProperty("_meta") Map<String, Object> meta) implements CacheableResult { // @formatter:on

		public ListPromptsResult {
			Assert.notNull(prompts, "prompts must not be null");
			Assert.notNull(ttlMs, "ttlMs must not be null");
			Assert.notNull(cacheScope, "cacheScope must not be null");
			resultType = resultType == null ? ResultType.COMPLETE : resultType;
		}

		@JsonCreator
		static ListPromptsResult fromJson(@JsonProperty("prompts") List<Prompt> prompts,
				@JsonProperty("nextCursor") String nextCursor, @JsonProperty("ttlMs") Long ttlMs,
				@JsonProperty("cacheScope") CacheScope cacheScope, @JsonProperty("resultType") String resultType,
				@JsonProperty("_meta") Map<String, Object> meta) {
			prompts = prompts == null ? List.of() : prompts;
			ttlMs = ttlMs == null ? 0L : ttlMs;
			cacheScope = cacheScope == null ? CacheScope.PRIVATE : cacheScope;
			return new ListPromptsResult(prompts, nextCursor, ttlMs, cacheScope, resultType, meta);
		}

		public static Builder builder(List<Prompt> prompts) {
			return new Builder(prompts);
		}

		public static final class Builder {

			private final List<Prompt> prompts;

			private String nextCursor;

			private Long ttlMs = 0L;

			private CacheScope cacheScope = CacheScope.PRIVATE;

			private Map<String, Object> meta;

			private Builder(List<Prompt> prompts) {
				Assert.notNull(prompts, "prompts must not be null");
				this.prompts = prompts;
			}

			public Builder nextCursor(String nextCursor) {
				this.nextCursor = nextCursor;
				return this;
			}

			public Builder ttlMs(long ttlMs) {
				Assert.isTrue(ttlMs >= 0, "ttlMs must not be negative");
				this.ttlMs = ttlMs;
				return this;
			}

			public Builder cacheScope(CacheScope cacheScope) {
				this.cacheScope = cacheScope;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ListPromptsResult build() {
				return new ListPromptsResult(prompts, nextCursor, ttlMs, cacheScope, ResultType.COMPLETE, meta);
			}

		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ReadResourceResult( // @formatter:off
		@JsonProperty("contents") List<ResourceContents> contents,
		@JsonProperty("ttlMs") Long ttlMs,
		@JsonProperty("cacheScope") CacheScope cacheScope,
		@JsonProperty("resultType") String resultType,
		@JsonProperty("_meta") Map<String, Object> meta) implements CacheableResult, ReadResourceOutcome { // @formatter:on

		public ReadResourceResult {
			Assert.notNull(contents, "contents must not be null");
			Assert.notNull(ttlMs, "ttlMs must not be null");
			Assert.notNull(cacheScope, "cacheScope must not be null");
			resultType = resultType == null ? ResultType.COMPLETE : resultType;
		}

		@JsonCreator
		static ReadResourceResult fromJson(@JsonProperty("contents") List<ResourceContents> contents,
				@JsonProperty("ttlMs") Long ttlMs, @JsonProperty("cacheScope") CacheScope cacheScope,
				@JsonProperty("resultType") String resultType, @JsonProperty("_meta") Map<String, Object> meta) {
			contents = contents == null ? List.of() : contents;
			ttlMs = ttlMs == null ? 0L : ttlMs;
			cacheScope = cacheScope == null ? CacheScope.PRIVATE : cacheScope;
			return new ReadResourceResult(contents, ttlMs, cacheScope, resultType, meta);
		}

		public static Builder builder(List<ResourceContents> contents) {
			return new Builder(contents);
		}

		public static final class Builder {

			private final List<ResourceContents> contents;

			private Long ttlMs = 0L;

			private CacheScope cacheScope = CacheScope.PRIVATE;

			private Map<String, Object> meta;

			private Builder(List<ResourceContents> contents) {
				Assert.notNull(contents, "contents must not be null");
				this.contents = contents;
			}

			public Builder ttlMs(long ttlMs) {
				Assert.isTrue(ttlMs >= 0, "ttlMs must not be negative");
				this.ttlMs = ttlMs;
				return this;
			}

			public Builder cacheScope(CacheScope cacheScope) {
				this.cacheScope = cacheScope;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ReadResourceResult build() {
				return new ReadResourceResult(contents, ttlMs, cacheScope, ResultType.COMPLETE, meta);
			}

		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record CallToolResult( // @formatter:off
		@JsonProperty("content") List<Content> content,
		@JsonProperty("structuredContent") Object structuredContent,
		@JsonProperty("isError") Boolean isError,
		@JsonProperty("resultType") String resultType,
		@JsonProperty("_meta") Map<String, Object> meta) implements CallToolOutcome { // @formatter:on

		public CallToolResult {
			Assert.notNull(content, "content must not be null");
			resultType = resultType == null ? ResultType.COMPLETE : resultType;
		}

		@JsonCreator
		static CallToolResult fromJson(@JsonProperty("content") List<Content> content,
				@JsonProperty("structuredContent") Object structuredContent, @JsonProperty("isError") Boolean isError,
				@JsonProperty("resultType") String resultType, @JsonProperty("_meta") Map<String, Object> meta) {
			content = content == null ? List.of() : content;
			return new CallToolResult(content, structuredContent, isError, resultType, meta);
		}

		public static Builder builder() {
			return new Builder();
		}

		public static final class Builder {

			private List<Content> content = new ArrayList<>();

			private Object structuredContent;

			private Boolean isError;

			private Map<String, Object> meta;

			public Builder content(List<Content> content) {
				Assert.notNull(content, "content must not be null");
				this.content = new ArrayList<>(content);
				return this;
			}

			public Builder addContent(Content contentItem) {
				Assert.notNull(contentItem, "contentItem must not be null");
				this.content.add(contentItem);
				return this;
			}

			public Builder structuredContent(Object structuredContent) {
				this.structuredContent = structuredContent;
				return this;
			}

			public Builder isError(boolean isError) {
				this.isError = isError;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public CallToolResult build() {
				return new CallToolResult(content, structuredContent, isError, ResultType.COMPLETE, meta);
			}

		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record GetPromptResult( // @formatter:off
		@JsonProperty("description") String description,
		@JsonProperty("messages") List<PromptMessage> messages,
		@JsonProperty("resultType") String resultType,
		@JsonProperty("_meta") Map<String, Object> meta) implements GetPromptOutcome { // @formatter:on

		public GetPromptResult {
			Assert.notNull(messages, "messages must not be null");
			resultType = resultType == null ? ResultType.COMPLETE : resultType;
		}

		@JsonCreator
		static GetPromptResult fromJson(@JsonProperty("description") String description,
				@JsonProperty("messages") List<PromptMessage> messages, @JsonProperty("resultType") String resultType,
				@JsonProperty("_meta") Map<String, Object> meta) {
			messages = messages == null ? List.of() : messages;
			return new GetPromptResult(description, messages, resultType, meta);
		}

		public static Builder builder(List<PromptMessage> messages) {
			return new Builder(messages);
		}

		public static final class Builder {

			private String description;

			private final List<PromptMessage> messages;

			private Map<String, Object> meta;

			private Builder(List<PromptMessage> messages) {
				Assert.notNull(messages, "messages must not be null");
				this.messages = messages;
			}

			public Builder description(String description) {
				this.description = description;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public GetPromptResult build() {
				return new GetPromptResult(description, messages, ResultType.COMPLETE, meta);
			}

		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record CompleteResult( // @formatter:off
		@JsonProperty("completion") Completion completion,
		@JsonProperty("resultType") String resultType,
		@JsonProperty("_meta") Map<String, Object> meta) implements Result { // @formatter:on

		public CompleteResult {
			Assert.notNull(completion, "completion must not be null");
			resultType = resultType == null ? ResultType.COMPLETE : resultType;
		}

		@JsonCreator
		static CompleteResult fromJson(@JsonProperty("completion") Completion completion,
				@JsonProperty("resultType") String resultType, @JsonProperty("_meta") Map<String, Object> meta) {
			completion = completion == null ? new Completion(List.of(), null, null) : completion;
			return new CompleteResult(completion, resultType, meta);
		}

		public static CompleteResult of(Completion completion) {
			return new CompleteResult(completion, ResultType.COMPLETE, null);
		}

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record Completion( // @formatter:off
			@JsonProperty("values") List<String> values,
			@JsonProperty("total") Integer total,
			@JsonProperty("hasMore") Boolean hasMore) { // @formatter:on

			public Completion {
				Assert.notNull(values, "values must not be null");
			}

			public Completion(List<String> values) {
				this(values, null, null);
			}
		}
	}

	// ---------------------------
	// MRTR (multi round-trip requests) - input-required results and payloads
	// ---------------------------

	/**
	 * One server-requested input, keyed by a server-assigned name inside
	 * {@link InputRequiredResult#inputRequests()}. {@code params} is one of
	 * {@link ElicitFormRequest}, {@link ElicitUrlRequest}, {@link CreateMessageRequest}
	 * or a bare "list roots" marker (an empty object).
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record InputRequest(@JsonProperty("method") String method, @JsonProperty("params") Object params) {

		public InputRequest {
			Assert.hasText(method, "method must not be empty");
		}

		public static InputRequest elicit(ElicitFormRequest request) {
			return new InputRequest(McpSchema.METHOD_ELICITATION_CREATE, request);
		}

		public static InputRequest elicitUrl(String message, String url) {
			return new InputRequest(McpSchema.METHOD_ELICITATION_CREATE, new ElicitUrlRequest(message, url, null));
		}

		public static InputRequest createMessage(CreateMessageRequest request) {
			return new InputRequest(McpSchema.METHOD_SAMPLING_CREATE_MESSAGE, request);
		}

		public static InputRequest listRoots() {
			return new InputRequest(McpSchema.METHOD_ROOTS_LIST, Map.of());
		}
	}

	/**
	 * A URL-mode elicitation request. Unlike the legacy revision's
	 * {@code ElicitUrlRequest}, this one carries no {@code elicitationId}; the retry
	 * correlates through the {@link InputRequiredResult}'s server-assigned key instead.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ElicitUrlRequest( // @formatter:off
		@JsonProperty("message") String message,
		@JsonProperty("url") String url,
		@JsonProperty("_meta") Map<String, Object> meta) { // @formatter:on

		public static final String MODE = "url";

		public ElicitUrlRequest {
			Assert.notNull(message, "message must not be null");
			Assert.notNull(url, "url must not be null");
		}

		@JsonProperty("mode")
		public String mode() {
			return MODE;
		}
	}

	/**
	 * A result signalling that the server needs more input before it can complete the
	 * original request. Only {@code tools/call}, {@code resources/read} and
	 * {@code prompts/get} may return it.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record InputRequiredResult( // @formatter:off
		@JsonProperty("inputRequests") Map<String, InputRequest> inputRequests,
		@JsonProperty("requestState") String requestState,
		@JsonProperty("resultType") String resultType,
		@JsonProperty("_meta") Map<String, Object> meta)
			implements InputRequired, CallToolOutcome, GetPromptOutcome, ReadResourceOutcome { // @formatter:on

		public InputRequiredResult {
			Assert.isTrue(inputRequests != null || requestState != null,
					"at least one of inputRequests or requestState must be present");
			resultType = ResultType.INPUT_REQUIRED;
		}

		public static Builder builder() {
			return new Builder();
		}

		public static final class Builder {

			private final Map<String, InputRequest> inputRequests = new HashMap<>();

			private String requestState;

			private Map<String, Object> meta;

			public Builder elicit(String key, ElicitFormRequest request) {
				this.inputRequests.put(key, InputRequest.elicit(request));
				return this;
			}

			public Builder elicitUrl(String key, String message, String url) {
				this.inputRequests.put(key, InputRequest.elicitUrl(message, url));
				return this;
			}

			public Builder createMessage(String key, CreateMessageRequest request) {
				this.inputRequests.put(key, InputRequest.createMessage(request));
				return this;
			}

			public Builder listRoots(String key) {
				this.inputRequests.put(key, InputRequest.listRoots());
				return this;
			}

			public Builder requestState(String requestState) {
				this.requestState = requestState;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public InputRequiredResult build() {
				return new InputRequiredResult(inputRequests.isEmpty() ? null : Map.copyOf(inputRequests), requestState,
						ResultType.INPUT_REQUIRED, meta);
			}

		}
	}

	// ---------------------------
	// Subscriptions
	// ---------------------------

	/**
	 * The set of change notifications a client opts into via
	 * {@code subscriptions/listen}.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record SubscriptionFilter( // @formatter:off
		@JsonProperty("toolsListChanged") Boolean toolsListChanged,
		@JsonProperty("promptsListChanged") Boolean promptsListChanged,
		@JsonProperty("resourcesListChanged") Boolean resourcesListChanged,
		@JsonProperty("resourceSubscriptions") List<String> resourceSubscriptions) { // @formatter:on

		public static final SubscriptionFilter EMPTY = new SubscriptionFilter(null, null, null, null);

		public boolean wantsToolsListChanged() {
			return Boolean.TRUE.equals(this.toolsListChanged);
		}

		public boolean wantsPromptsListChanged() {
			return Boolean.TRUE.equals(this.promptsListChanged);
		}

		public boolean wantsResourcesListChanged() {
			return Boolean.TRUE.equals(this.resourcesListChanged);
		}

		public List<String> resourceSubscriptionsOrEmpty() {
			return this.resourceSubscriptions == null ? List.of() : this.resourceSubscriptions;
		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record SubscriptionsListenRequest(@JsonProperty("notifications") SubscriptionFilter notifications,
			@JsonProperty("_meta") Map<String, Object> meta) {

		@JsonCreator
		static SubscriptionsListenRequest fromJson(@JsonProperty("notifications") SubscriptionFilter notifications,
				@JsonProperty("_meta") Map<String, Object> meta) {
			return new SubscriptionsListenRequest(notifications == null ? SubscriptionFilter.EMPTY : notifications,
					meta);
		}
	}

	/**
	 * Params of {@code notifications/subscriptions/acknowledged}: the honoured subset.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record SubscriptionsAcknowledgedParams(@JsonProperty("notifications") SubscriptionFilter notifications,
			@JsonProperty("_meta") Map<String, Object> meta) {
	}

	/** Params of {@code notifications/resources/updated}. */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ResourceUpdatedParams(@JsonProperty("uri") String uri,
			@JsonProperty("_meta") Map<String, Object> meta) {

		public ResourceUpdatedParams {
			Assert.hasText(uri, "uri must not be empty");
		}
	}

	/** Params of {@code notifications/{tools,prompts,resources}/list_changed}. */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ListChangedParams(@JsonProperty("_meta") Map<String, Object> meta) {

		public static final ListChangedParams EMPTY = new ListChangedParams(null);

	}

	/** The terminal result of a server-closed {@code subscriptions/listen} stream. */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record SubscriptionsListenResult(@JsonProperty("resultType") String resultType,
			@JsonProperty("_meta") Map<String, Object> meta) implements Result {

		public SubscriptionsListenResult {
			resultType = resultType == null ? ResultType.COMPLETE : resultType;
		}

		public static SubscriptionsListenResult forSubscription(Object subscriptionId) {
			Map<String, Object> meta = new HashMap<>();
			meta.put(MetaKeys.SUBSCRIPTION_ID, subscriptionId);
			return new SubscriptionsListenResult(ResultType.COMPLETE, meta);
		}
	}

	// ---------------------------
	// Other notifications
	// ---------------------------

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record CancelledNotificationParams(@JsonProperty("requestId") Object requestId,
			@JsonProperty("reason") String reason) {

		public CancelledNotificationParams {
			Assert.notNull(requestId, "requestId must not be null");
		}

		@JsonCreator
		static CancelledNotificationParams fromJson(@JsonProperty("requestId") Object requestId,
				@JsonProperty("reason") String reason) {
			if (requestId == null) {
				logger.warn(
						"CancelledNotificationParams: missing required field 'requestId' during deserialization, using default ''");
				requestId = "";
			}
			return new CancelledNotificationParams(requestId, reason);
		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ProgressParams( // @formatter:off
		@JsonProperty("progressToken") Object progressToken,
		@JsonProperty("progress") Double progress,
		@JsonProperty("total") Double total,
		@JsonProperty("message") String message,
		@JsonProperty("_meta") Map<String, Object> meta) { // @formatter:on

		public ProgressParams {
			Assert.notNull(progressToken, "progressToken must not be null");
			Assert.notNull(progress, "progress must not be null");
		}
	}

	/** Params of {@code notifications/message}; {@code data} is any JSON value. */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record LoggingMessageParams(@JsonProperty("level") LoggingLevel level, @JsonProperty("logger") String logger,
			@JsonProperty("data") Object data) {

		public LoggingMessageParams {
			Assert.notNull(level, "level must not be null");
			Assert.notNull(data, "data must not be null");
		}
	}

	// ---------------------------
	// Error data payloads
	// ---------------------------

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record MissingRequiredClientCapabilityData(
			@JsonProperty("requiredCapabilities") ClientCapabilities requiredCapabilities) {
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record UnsupportedProtocolVersionData(@JsonProperty("supported") List<String> supported,
			@JsonProperty("requested") String requested) {
	}

	// ---------------------------
	// Implementation info
	// ---------------------------

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Implementation( // @formatter:off
		@JsonProperty("name") String name,
		@JsonProperty("title") String title,
		@JsonProperty("version") String version,
		@JsonProperty("description") String description,
		@JsonProperty("icons") List<Icon> icons,
		@JsonProperty("websiteUrl") String websiteUrl) { // @formatter:on

		public Implementation {
			Assert.notNull(name, "name must not be null");
			Assert.notNull(version, "version must not be null");
		}

		@JsonCreator
		static Implementation fromJson(@JsonProperty("name") String name, @JsonProperty("title") String title,
				@JsonProperty("version") String version, @JsonProperty("description") String description,
				@JsonProperty("icons") List<Icon> icons, @JsonProperty("websiteUrl") String websiteUrl) {
			if (name == null || version == null) {
				List<String> missing = new ArrayList<>();
				if (name == null) {
					missing.add("name -> ''");
					name = "";
				}
				if (version == null) {
					missing.add("version -> ''");
					version = "";
				}
				logger.warn("Implementation: missing required fields during deserialization: {}",
						String.join(", ", missing));
			}
			return new Implementation(name, title, version, description, icons, websiteUrl);
		}

		public static Builder builder(String name, String version) {
			return new Builder(name, version);
		}

		public static final class Builder {

			private final String name;

			private String title;

			private final String version;

			private String description;

			private List<Icon> icons;

			private String websiteUrl;

			private Builder(String name, String version) {
				Assert.hasText(name, "name must not be empty");
				Assert.hasText(version, "version must not be empty");
				this.name = name;
				this.version = version;
			}

			public Builder title(String title) {
				this.title = title;
				return this;
			}

			public Builder description(String description) {
				this.description = description;
				return this;
			}

			public Builder icons(List<Icon> icons) {
				this.icons = icons;
				return this;
			}

			public Builder websiteUrl(String websiteUrl) {
				this.websiteUrl = websiteUrl;
				return this;
			}

			public Implementation build() {
				return new Implementation(name, title, version, description, icons, websiteUrl);
			}

		}
	}

	/**
	 * An icon that can be displayed in a user interface.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Icon( // @formatter:off
		@JsonProperty("src") String src,
		@JsonProperty("mimeType") String mimeType,
		@JsonProperty("sizes") List<String> sizes,
		@JsonProperty("theme") String theme) { // @formatter:on

		public Icon {
			Assert.notNull(src, "Icon src must not be null");
		}

		@JsonCreator
		static Icon fromJson(@JsonProperty("src") String src, @JsonProperty("mimeType") String mimeType,
				@JsonProperty("sizes") List<String> sizes, @JsonProperty("theme") String theme) {
			if (src == null) {
				logger.warn("Icon: missing required field 'src' during deserialization, using default ''");
				src = "";
			}
			return new Icon(src, mimeType, sizes, theme);
		}

		public static Builder builder(String src) {
			return new Builder(src);
		}

		public static final class Builder {

			private final String src;

			private String mimeType;

			private List<String> sizes;

			private String theme;

			private Builder(String src) {
				Assert.hasText(src, "src must not be empty");
				this.src = src;
			}

			public Builder mimeType(String mimeType) {
				this.mimeType = mimeType;
				return this;
			}

			public Builder sizes(List<String> sizes) {
				this.sizes = sizes;
				return this;
			}

			public Builder theme(String theme) {
				this.theme = theme;
				return this;
			}

			public Icon build() {
				return new Icon(src, mimeType, sizes, theme);
			}

		}
	}

	// ---------------------------
	// Annotations
	// ---------------------------

	public enum Role {

	// @formatter:off
		@JsonProperty("user") USER,
		@JsonProperty("assistant") ASSISTANT
	} // @formatter:on

	/**
	 * Optional hints for the client on how an object is used or displayed.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Annotations( // @formatter:off
		@JsonProperty("audience") List<Role> audience,
		@JsonProperty("priority") Double priority,
		@JsonProperty("lastModified") String lastModified) { // @formatter:on

		public static Builder builder() {
			return new Builder();
		}

		public static final class Builder {

			private List<Role> audience;

			private Double priority;

			private String lastModified;

			public Builder audience(List<Role> audience) {
				this.audience = audience;
				return this;
			}

			public Builder priority(Double priority) {
				this.priority = priority;
				return this;
			}

			public Builder lastModified(String lastModified) {
				this.lastModified = lastModified;
				return this;
			}

			public Annotations build() {
				return new Annotations(audience, priority, lastModified);
			}

		}
	}

	// ---------------------------
	// Resources
	// ---------------------------

	/**
	 * A known resource that the server is capable of reading.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Resource( // @formatter:off
		@JsonProperty("uri") String uri,
		@JsonProperty("name") String name,
		@JsonProperty("title") String title,
		@JsonProperty("description") String description,
		@JsonProperty("mimeType") String mimeType,
		@JsonProperty("size") Long size,
		@JsonProperty("annotations") Annotations annotations,
		@JsonProperty("_meta") Map<String, Object> meta,
		@JsonProperty("icons") List<Icon> icons) { // @formatter:on

		public Resource {
			Assert.notNull(uri, "uri must not be null");
			Assert.notNull(name, "name must not be null");
		}

		@JsonCreator
		static Resource fromJson(@JsonProperty("uri") String uri, @JsonProperty("name") String name,
				@JsonProperty("title") String title, @JsonProperty("description") String description,
				@JsonProperty("mimeType") String mimeType, @JsonProperty("size") Long size,
				@JsonProperty("annotations") Annotations annotations, @JsonProperty("_meta") Map<String, Object> meta,
				@JsonProperty("icons") List<Icon> icons) {
			if (uri == null || name == null) {
				List<String> missing = new ArrayList<>();
				if (uri == null) {
					missing.add("uri -> ''");
					uri = "";
				}
				if (name == null) {
					missing.add("name -> ''");
					name = "";
				}
				logger.warn("Resource: missing required fields during deserialization: {}", String.join(", ", missing));
			}
			return new Resource(uri, name, title, description, mimeType, size, annotations, meta, icons);
		}

		public static Builder builder(String uri, String name) {
			return new Builder(uri, name);
		}

		public static final class Builder {

			private final String uri;

			private final String name;

			private String title;

			private String description;

			private String mimeType;

			private Long size;

			private Annotations annotations;

			private List<Icon> icons;

			private Map<String, Object> meta;

			private Builder(String uri, String name) {
				Assert.hasText(uri, "uri must not be empty");
				Assert.hasText(name, "name must not be empty");
				this.uri = uri;
				this.name = name;
			}

			public Builder title(String title) {
				this.title = title;
				return this;
			}

			public Builder description(String description) {
				this.description = description;
				return this;
			}

			public Builder mimeType(String mimeType) {
				this.mimeType = mimeType;
				return this;
			}

			public Builder size(Long size) {
				this.size = size;
				return this;
			}

			public Builder annotations(Annotations annotations) {
				this.annotations = annotations;
				return this;
			}

			public Builder icons(List<Icon> icons) {
				this.icons = icons;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public Resource build() {
				return new Resource(uri, name, title, description, mimeType, size, annotations, meta, icons);
			}

		}
	}

	/**
	 * A parameterized resource, addressed by an RFC 6570 URI template.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ResourceTemplate( // @formatter:off
		@JsonProperty("uriTemplate") String uriTemplate,
		@JsonProperty("name") String name,
		@JsonProperty("title") String title,
		@JsonProperty("description") String description,
		@JsonProperty("mimeType") String mimeType,
		@JsonProperty("annotations") Annotations annotations,
		@JsonProperty("_meta") Map<String, Object> meta,
		@JsonProperty("icons") List<Icon> icons) { // @formatter:on

		public ResourceTemplate {
			Assert.notNull(uriTemplate, "uriTemplate must not be null");
			Assert.notNull(name, "name must not be null");
		}

		@JsonCreator
		static ResourceTemplate fromJson(@JsonProperty("uriTemplate") String uriTemplate,
				@JsonProperty("name") String name, @JsonProperty("title") String title,
				@JsonProperty("description") String description, @JsonProperty("mimeType") String mimeType,
				@JsonProperty("annotations") Annotations annotations, @JsonProperty("_meta") Map<String, Object> meta,
				@JsonProperty("icons") List<Icon> icons) {
			if (uriTemplate == null || name == null) {
				List<String> missing = new ArrayList<>();
				if (uriTemplate == null) {
					missing.add("uriTemplate -> ''");
					uriTemplate = "";
				}
				if (name == null) {
					missing.add("name -> ''");
					name = "";
				}
				logger.warn("ResourceTemplate: missing required fields during deserialization: {}",
						String.join(", ", missing));
			}
			return new ResourceTemplate(uriTemplate, name, title, description, mimeType, annotations, meta, icons);
		}

		public static Builder builder(String uriTemplate, String name) {
			return new Builder(uriTemplate, name);
		}

		public static final class Builder {

			private final String uriTemplate;

			private final String name;

			private String title;

			private String description;

			private String mimeType;

			private Annotations annotations;

			private List<Icon> icons;

			private Map<String, Object> meta;

			private Builder(String uriTemplate, String name) {
				Assert.hasText(uriTemplate, "uriTemplate must not be empty");
				Assert.hasText(name, "name must not be empty");
				this.uriTemplate = uriTemplate;
				this.name = name;
			}

			public Builder title(String title) {
				this.title = title;
				return this;
			}

			public Builder description(String description) {
				this.description = description;
				return this;
			}

			public Builder mimeType(String mimeType) {
				this.mimeType = mimeType;
				return this;
			}

			public Builder annotations(Annotations annotations) {
				this.annotations = annotations;
				return this;
			}

			public Builder icons(List<Icon> icons) {
				this.icons = icons;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ResourceTemplate build() {
				return new ResourceTemplate(uriTemplate, name, title, description, mimeType, annotations, meta, icons);
			}

		}
	}

	/**
	 * The contents of a specific resource or sub-resource.
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
	@JsonSubTypes({ @JsonSubTypes.Type(value = TextResourceContents.class),
			@JsonSubTypes.Type(value = BlobResourceContents.class) })
	public interface ResourceContents {

		String uri();

		String mimeType();

		Map<String, Object> meta();

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record TextResourceContents( // @formatter:off
		@JsonProperty("uri") String uri,
		@JsonProperty("mimeType") String mimeType,
		@JsonProperty("text") String text,
		@JsonProperty("_meta") Map<String, Object> meta) implements ResourceContents { // @formatter:on

		public TextResourceContents {
			Assert.notNull(uri, "uri must not be null");
			Assert.notNull(text, "text must not be null");
		}

		@JsonCreator
		static TextResourceContents fromJson(@JsonProperty("uri") String uri, @JsonProperty("mimeType") String mimeType,
				@JsonProperty("text") String text, @JsonProperty("_meta") Map<String, Object> meta) {
			if (uri == null || text == null) {
				List<String> missing = new ArrayList<>();
				if (uri == null) {
					missing.add("uri -> ''");
					uri = "";
				}
				if (text == null) {
					missing.add("text -> ''");
					text = "";
				}
				logger.warn("TextResourceContents: missing required fields during deserialization: {}",
						String.join(", ", missing));
			}
			return new TextResourceContents(uri, mimeType, text, meta);
		}

		public static Builder builder(String uri, String text) {
			return new Builder(uri, text);
		}

		public static final class Builder {

			private final String uri;

			private String mimeType;

			private final String text;

			private Map<String, Object> meta;

			private Builder(String uri, String text) {
				Assert.hasText(uri, "uri must not be empty");
				Assert.notNull(text, "text must not be null");
				this.uri = uri;
				this.text = text;
			}

			public Builder mimeType(String mimeType) {
				this.mimeType = mimeType;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public TextResourceContents build() {
				return new TextResourceContents(uri, mimeType, text, meta);
			}

		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record BlobResourceContents( // @formatter:off
		@JsonProperty("uri") String uri,
		@JsonProperty("mimeType") String mimeType,
		@JsonProperty("blob") String blob,
		@JsonProperty("_meta") Map<String, Object> meta) implements ResourceContents { // @formatter:on

		public BlobResourceContents {
			Assert.notNull(uri, "uri must not be null");
			Assert.notNull(blob, "blob must not be null");
		}

		@JsonCreator
		static BlobResourceContents fromJson(@JsonProperty("uri") String uri, @JsonProperty("mimeType") String mimeType,
				@JsonProperty("blob") String blob, @JsonProperty("_meta") Map<String, Object> meta) {
			if (uri == null || blob == null) {
				List<String> missing = new ArrayList<>();
				if (uri == null) {
					missing.add("uri -> ''");
					uri = "";
				}
				if (blob == null) {
					missing.add("blob -> ''");
					blob = "";
				}
				logger.warn("BlobResourceContents: missing required fields during deserialization: {}",
						String.join(", ", missing));
			}
			return new BlobResourceContents(uri, mimeType, blob, meta);
		}

		public static Builder builder(String uri, String blob) {
			return new Builder(uri, blob);
		}

		public static final class Builder {

			private final String uri;

			private String mimeType;

			private final String blob;

			private Map<String, Object> meta;

			private Builder(String uri, String blob) {
				Assert.hasText(uri, "uri must not be empty");
				Assert.notNull(blob, "blob must not be null");
				this.uri = uri;
				this.blob = blob;
			}

			public Builder mimeType(String mimeType) {
				this.mimeType = mimeType;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public BlobResourceContents build() {
				return new BlobResourceContents(uri, mimeType, blob, meta);
			}

		}
	}

	// ---------------------------
	// Prompts
	// ---------------------------

	/**
	 * A prompt or prompt template that the server offers.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Prompt( // @formatter:off
		@JsonProperty("name") String name,
		@JsonProperty("title") String title,
		@JsonProperty("description") String description,
		@JsonProperty("arguments") List<PromptArgument> arguments,
		@JsonProperty("_meta") Map<String, Object> meta,
		@JsonProperty("icons") List<Icon> icons) { // @formatter:on

		public Prompt {
			Assert.notNull(name, "name must not be null");
		}

		@JsonCreator
		static Prompt fromJson(@JsonProperty("name") String name, @JsonProperty("title") String title,
				@JsonProperty("description") String description,
				@JsonProperty("arguments") List<PromptArgument> arguments,
				@JsonProperty("_meta") Map<String, Object> meta, @JsonProperty("icons") List<Icon> icons) {
			if (name == null) {
				logger.warn("Prompt: missing required field 'name' during deserialization, using default ''");
				name = "";
			}
			return new Prompt(name, title, description, arguments, meta, icons);
		}

		public static Builder builder(String name) {
			return new Builder(name);
		}

		public static final class Builder {

			private final String name;

			private String title;

			private String description;

			private List<PromptArgument> arguments;

			private List<Icon> icons;

			private Map<String, Object> meta;

			private Builder(String name) {
				Assert.hasText(name, "name must not be empty");
				this.name = name;
			}

			public Builder title(String title) {
				this.title = title;
				return this;
			}

			public Builder description(String description) {
				this.description = description;
				return this;
			}

			public Builder arguments(List<PromptArgument> arguments) {
				this.arguments = arguments;
				return this;
			}

			public Builder icons(List<Icon> icons) {
				this.icons = icons;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public Prompt build() {
				return new Prompt(name, title, description, arguments, meta, icons);
			}

		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record PromptArgument( // @formatter:off
		@JsonProperty("name") String name,
		@JsonProperty("title") String title,
		@JsonProperty("description") String description,
		@JsonProperty("required") Boolean required) { // @formatter:on

		public PromptArgument {
			Assert.notNull(name, "name must not be null");
		}

		@JsonCreator
		static PromptArgument fromJson(@JsonProperty("name") String name, @JsonProperty("title") String title,
				@JsonProperty("description") String description, @JsonProperty("required") Boolean required) {
			if (name == null) {
				logger.warn("PromptArgument: missing required field 'name' during deserialization, using default ''");
				name = "";
			}
			return new PromptArgument(name, title, description, required);
		}

		public static Builder builder(String name) {
			return new Builder(name);
		}

		public static final class Builder {

			private final String name;

			private String title;

			private String description;

			private Boolean required;

			private Builder(String name) {
				Assert.hasText(name, "name must not be empty");
				this.name = name;
			}

			public Builder title(String title) {
				this.title = title;
				return this;
			}

			public Builder description(String description) {
				this.description = description;
				return this;
			}

			public Builder required(Boolean required) {
				this.required = required;
				return this;
			}

			public PromptArgument build() {
				return new PromptArgument(name, title, description, required);
			}

		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record PromptMessage( // @formatter:off
		@JsonProperty("role") Role role,
		@JsonProperty("content") Content content) { // @formatter:on

		public PromptMessage {
			Assert.notNull(role, "role must not be null");
			Assert.notNull(content, "content must not be null");
		}

		@JsonCreator
		static PromptMessage fromJson(@JsonProperty("role") Role role, @JsonProperty("content") Content content) {
			if (role == null || content == null) {
				List<String> missing = new ArrayList<>();
				if (role == null) {
					missing.add("role -> 'user'");
					role = Role.USER;
				}
				if (content == null) {
					missing.add("content -> ''");
					content = TextContent.builder("").build();
				}
				logger.warn("PromptMessage: missing required fields during deserialization: {}",
						String.join(", ", missing));
			}
			return new PromptMessage(role, content);
		}

		public static Builder builder(Role role, Content content) {
			return new Builder(role, content);
		}

		public static final class Builder {

			private final Role role;

			private final Content content;

			private Builder(Role role, Content content) {
				Assert.notNull(role, "role must not be null");
				Assert.notNull(content, "content must not be null");
				this.role = role;
				this.content = content;
			}

			public PromptMessage build() {
				return new PromptMessage(role, content);
			}

		}
	}

	// ---------------------------
	// Tools
	// ---------------------------

	/**
	 * Hints describing a tool's behaviour. Clients must not make trust decisions based on
	 * annotations received from untrusted servers.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ToolAnnotations( // @formatter:off
		@JsonProperty("title") String title,
		@JsonProperty("readOnlyHint") Boolean readOnlyHint,
		@JsonProperty("destructiveHint") Boolean destructiveHint,
		@JsonProperty("idempotentHint") Boolean idempotentHint,
		@JsonProperty("openWorldHint") Boolean openWorldHint) { // @formatter:on

		public static Builder builder() {
			return new Builder();
		}

		public static final class Builder {

			private String title;

			private Boolean readOnlyHint;

			private Boolean destructiveHint;

			private Boolean idempotentHint;

			private Boolean openWorldHint;

			public Builder title(String title) {
				this.title = title;
				return this;
			}

			public Builder readOnlyHint(Boolean readOnlyHint) {
				this.readOnlyHint = readOnlyHint;
				return this;
			}

			public Builder destructiveHint(Boolean destructiveHint) {
				this.destructiveHint = destructiveHint;
				return this;
			}

			public Builder idempotentHint(Boolean idempotentHint) {
				this.idempotentHint = idempotentHint;
				return this;
			}

			public Builder openWorldHint(Boolean openWorldHint) {
				this.openWorldHint = openWorldHint;
				return this;
			}

			public ToolAnnotations build() {
				return new ToolAnnotations(title, readOnlyHint, destructiveHint, idempotentHint, openWorldHint);
			}

		}
	}

	/**
	 * A tool the server exposes. {@code inputSchema} and {@code outputSchema} default to
	 * the JSON Schema 2020-12 dialect unless they carry an explicit {@code $schema}.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Tool( // @formatter:off
		@JsonProperty("name") String name,
		@JsonProperty("title") String title,
		@JsonProperty("description") String description,
		@JsonProperty("inputSchema") Map<String, Object> inputSchema,
		@JsonProperty("outputSchema") Map<String, Object> outputSchema,
		@JsonProperty("annotations") ToolAnnotations annotations,
		@JsonProperty("_meta") Map<String, Object> meta,
		@JsonProperty("icons") List<Icon> icons) { // @formatter:on

		public Tool {
			Assert.notNull(name, "name must not be null");
			Assert.notNull(inputSchema, "inputSchema must not be null");
		}

		@JsonCreator
		static Tool fromJson(@JsonProperty("name") String name, @JsonProperty("title") String title,
				@JsonProperty("description") String description,
				@JsonProperty("inputSchema") Map<String, Object> inputSchema,
				@JsonProperty("outputSchema") Map<String, Object> outputSchema,
				@JsonProperty("annotations") ToolAnnotations annotations,
				@JsonProperty("_meta") Map<String, Object> meta, @JsonProperty("icons") List<Icon> icons) {
			if (name == null || inputSchema == null) {
				List<String> missing = new ArrayList<>();
				if (name == null) {
					missing.add("name -> ''");
					name = "";
				}
				if (inputSchema == null) {
					missing.add("inputSchema -> {}");
					inputSchema = Map.of();
				}
				logger.warn("Tool: missing required fields during deserialization: {}", String.join(", ", missing));
			}
			return new Tool(name, title, description, inputSchema, outputSchema, annotations, meta, icons);
		}

		public static Builder builder(String name, Map<String, Object> inputSchema) {
			return new Builder(name, inputSchema);
		}

		public static Builder builder(String name, McpJsonMapper jsonMapper, String inputSchema) {
			return new Builder(name, schemaToMap(jsonMapper, inputSchema));
		}

		public static final class Builder {

			private final String name;

			private String title;

			private String description;

			private final Map<String, Object> inputSchema;

			private Map<String, Object> outputSchema;

			private ToolAnnotations annotations;

			private List<Icon> icons;

			private Map<String, Object> meta;

			private Builder(String name, Map<String, Object> inputSchema) {
				Assert.hasText(name, "name must not be empty");
				Assert.notNull(inputSchema, "inputSchema must not be null");
				this.name = name;
				this.inputSchema = inputSchema;
			}

			public Builder title(String title) {
				this.title = title;
				return this;
			}

			public Builder description(String description) {
				this.description = description;
				return this;
			}

			public Builder outputSchema(Map<String, Object> outputSchema) {
				this.outputSchema = outputSchema;
				return this;
			}

			public Builder outputSchema(McpJsonMapper jsonMapper, String outputSchema) {
				this.outputSchema = schemaToMap(jsonMapper, outputSchema);
				return this;
			}

			public Builder annotations(ToolAnnotations annotations) {
				this.annotations = annotations;
				return this;
			}

			public Builder icons(List<Icon> icons) {
				this.icons = icons;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public Tool build() {
				return new Tool(name, title, description, inputSchema, outputSchema, annotations, meta, icons);
			}

		}
	}

	private static Map<String, Object> schemaToMap(McpJsonMapper jsonMapper, String schema) {
		try {
			return jsonMapper.readValue(schema, MAP_TYPE_REF);
		}
		catch (IOException e) {
			throw new IllegalArgumentException("Invalid schema: " + schema, e);
		}
	}

	// ---------------------------
	// Sampling (MRTR input-request payload)
	// ---------------------------

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ModelPreferences( // @formatter:off
		@JsonProperty("hints") List<ModelHint> hints,
		@JsonProperty("costPriority") Double costPriority,
		@JsonProperty("speedPriority") Double speedPriority,
		@JsonProperty("intelligencePriority") Double intelligencePriority) { // @formatter:on

		public static Builder builder() {
			return new Builder();
		}

		public static final class Builder {

			private List<ModelHint> hints;

			private Double costPriority;

			private Double speedPriority;

			private Double intelligencePriority;

			public Builder hints(List<ModelHint> hints) {
				this.hints = hints;
				return this;
			}

			public Builder addHint(String name) {
				if (this.hints == null) {
					this.hints = new ArrayList<>();
				}
				this.hints.add(new ModelHint(name));
				return this;
			}

			public Builder costPriority(Double costPriority) {
				this.costPriority = costPriority;
				return this;
			}

			public Builder speedPriority(Double speedPriority) {
				this.speedPriority = speedPriority;
				return this;
			}

			public Builder intelligencePriority(Double intelligencePriority) {
				this.intelligencePriority = intelligencePriority;
				return this;
			}

			public ModelPreferences build() {
				return new ModelPreferences(hints, costPriority, speedPriority, intelligencePriority);
			}

		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ModelHint(@JsonProperty("name") String name) {
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record SamplingMessage( // @formatter:off
		@JsonProperty("role") Role role,
		@JsonProperty("content") Content content) { // @formatter:on

		public SamplingMessage {
			Assert.notNull(role, "role must not be null");
			Assert.notNull(content, "content must not be null");
		}

		@JsonCreator
		static SamplingMessage fromJson(@JsonProperty("role") Role role, @JsonProperty("content") Content content) {
			if (role == null || content == null) {
				List<String> missing = new ArrayList<>();
				if (role == null) {
					missing.add("role -> 'user'");
					role = Role.USER;
				}
				if (content == null) {
					missing.add("content -> ''");
					content = TextContent.builder("").build();
				}
				logger.warn("SamplingMessage: missing required fields during deserialization: {}",
						String.join(", ", missing));
			}
			return new SamplingMessage(role, content);
		}

		public static Builder builder(Role role, Content content) {
			return new Builder(role, content);
		}

		public static final class Builder {

			private final Role role;

			private final Content content;

			private Builder(Role role, Content content) {
				Assert.notNull(role, "role must not be null");
				Assert.notNull(content, "content must not be null");
				this.role = role;
				this.content = content;
			}

			public SamplingMessage build() {
				return new SamplingMessage(role, content);
			}

		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record CreateMessageRequest( // @formatter:off
		@JsonProperty("messages") List<SamplingMessage> messages,
		@JsonProperty("modelPreferences") ModelPreferences modelPreferences,
		@JsonProperty("systemPrompt") String systemPrompt,
		@JsonProperty("includeContext") ContextInclusionStrategy includeContext,
		@JsonProperty("temperature") Double temperature,
		@JsonProperty("maxTokens") Integer maxTokens,
		@JsonProperty("stopSequences") List<String> stopSequences,
		@JsonProperty("metadata") Map<String, Object> metadata,
		@JsonProperty("_meta") Map<String, Object> meta) { // @formatter:on

		public CreateMessageRequest {
			Assert.notNull(messages, "messages must not be null");
			Assert.notNull(maxTokens, "maxTokens must not be null");
		}

		@JsonCreator
		static CreateMessageRequest fromJson(@JsonProperty("messages") List<SamplingMessage> messages,
				@JsonProperty("modelPreferences") ModelPreferences modelPreferences,
				@JsonProperty("systemPrompt") String systemPrompt,
				@JsonProperty("includeContext") ContextInclusionStrategy includeContext,
				@JsonProperty("temperature") Double temperature, @JsonProperty("maxTokens") Integer maxTokens,
				@JsonProperty("stopSequences") List<String> stopSequences,
				@JsonProperty("metadata") Map<String, Object> metadata,
				@JsonProperty("_meta") Map<String, Object> meta) {
			if (messages == null || maxTokens == null) {
				List<String> missing = new ArrayList<>();
				if (messages == null) {
					missing.add("messages -> []");
					messages = List.of();
				}
				if (maxTokens == null) {
					missing.add("maxTokens -> 0");
					maxTokens = 0;
				}
				logger.warn("CreateMessageRequest: missing required fields during deserialization: {}",
						String.join(", ", missing));
			}
			return new CreateMessageRequest(messages, modelPreferences, systemPrompt, includeContext, temperature,
					maxTokens, stopSequences, metadata, meta);
		}

		public enum ContextInclusionStrategy {

		// @formatter:off
			@JsonProperty("none") NONE,
			@JsonProperty("thisServer") THIS_SERVER,
			@JsonProperty("allServers") ALL_SERVERS
		} // @formatter:on

		public static Builder builder(List<SamplingMessage> messages, int maxTokens) {
			return new Builder(messages, maxTokens);
		}

		public static final class Builder {

			private final List<SamplingMessage> messages;

			private ModelPreferences modelPreferences;

			private String systemPrompt;

			private ContextInclusionStrategy includeContext;

			private Double temperature;

			private final int maxTokens;

			private List<String> stopSequences;

			private Map<String, Object> metadata;

			private Map<String, Object> meta;

			private Builder(List<SamplingMessage> messages, int maxTokens) {
				Assert.notNull(messages, "messages must not be null");
				this.messages = messages;
				this.maxTokens = maxTokens;
			}

			public Builder modelPreferences(ModelPreferences modelPreferences) {
				this.modelPreferences = modelPreferences;
				return this;
			}

			public Builder systemPrompt(String systemPrompt) {
				this.systemPrompt = systemPrompt;
				return this;
			}

			public Builder includeContext(ContextInclusionStrategy includeContext) {
				this.includeContext = includeContext;
				return this;
			}

			public Builder temperature(Double temperature) {
				this.temperature = temperature;
				return this;
			}

			public Builder stopSequences(List<String> stopSequences) {
				this.stopSequences = stopSequences;
				return this;
			}

			public Builder metadata(Map<String, Object> metadata) {
				this.metadata = metadata;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public CreateMessageRequest build() {
				return new CreateMessageRequest(messages, modelPreferences, systemPrompt, includeContext, temperature,
						maxTokens, stopSequences, metadata, meta);
			}

		}
	}

	// ---------------------------
	// Elicitation (MRTR input-request payload)
	// ---------------------------

	/**
	 * A form-mode elicitation request. {@code requestedSchema} is a restricted subset of
	 * JSON Schema: only top-level properties, no nesting.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ElicitFormRequest( // @formatter:off
		@JsonProperty("message") String message,
		@JsonProperty("requestedSchema") Map<String, Object> requestedSchema,
		@JsonProperty("_meta") Map<String, Object> meta) { // @formatter:on

		public static final String MODE = "form";

		public ElicitFormRequest {
			Assert.notNull(message, "message must not be null");
			Assert.notNull(requestedSchema, "requestedSchema must not be null");
		}

		@JsonProperty("mode")
		public String mode() {
			return MODE;
		}

		@JsonCreator
		static ElicitFormRequest fromJson(@JsonProperty("message") String message,
				@JsonProperty("requestedSchema") Map<String, Object> requestedSchema,
				@JsonProperty("_meta") Map<String, Object> meta) {
			if (message == null || requestedSchema == null) {
				List<String> missing = new ArrayList<>();
				if (message == null) {
					missing.add("message -> ''");
					message = "";
				}
				if (requestedSchema == null) {
					missing.add("requestedSchema -> {}");
					requestedSchema = Map.of();
				}
				logger.warn("ElicitFormRequest: missing required fields during deserialization: {}",
						String.join(", ", missing));
			}
			return new ElicitFormRequest(message, requestedSchema, meta);
		}

		public static Builder builder(String message, Map<String, Object> requestedSchema) {
			return new Builder(message, requestedSchema);
		}

		public static final class Builder {

			private final String message;

			private final Map<String, Object> requestedSchema;

			private Map<String, Object> meta;

			private Builder(String message, Map<String, Object> requestedSchema) {
				Assert.notNull(message, "message must not be null");
				Assert.notNull(requestedSchema, "requestedSchema must not be null");
				this.message = message;
				this.requestedSchema = requestedSchema;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ElicitFormRequest build() {
				return new ElicitFormRequest(message, requestedSchema, meta);
			}

		}
	}

	// ---------------------------
	// Logging
	// ---------------------------

	/**
	 * Log severities, least to most severe; compare via {@link #level()}. Unrecognized
	 * values deserialize to {@code null}.
	 */
	public enum LoggingLevel {

	// @formatter:off
		@JsonProperty("debug") DEBUG(0),
		@JsonProperty("info") INFO(1),
		@JsonProperty("notice") NOTICE(2),
		@JsonProperty("warning") WARNING(3),
		@JsonProperty("error") ERROR(4),
		@JsonProperty("critical") CRITICAL(5),
		@JsonProperty("alert") ALERT(6),
		@JsonProperty("emergency") EMERGENCY(7);
		// @formatter:on

		private final int level;

		private static final Map<String, LoggingLevel> BY_NAME;

		static {
			Map<String, LoggingLevel> m = new HashMap<>();
			for (LoggingLevel l : values()) {
				m.put(l.name().toLowerCase(), l);
			}
			BY_NAME = Map.copyOf(m);
		}

		LoggingLevel(int level) {
			this.level = level;
		}

		public int level() {
			return level;
		}

		@JsonCreator
		public static LoggingLevel fromValue(String value) {
			return value == null ? null : BY_NAME.get(value.toLowerCase());
		}

	}

	// ---------------------------
	// Completions
	// ---------------------------

	/**
	 * A prompt or resource-template reference for {@code completion/complete}.
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type",
			visible = true)
	@JsonSubTypes({ @JsonSubTypes.Type(value = PromptReference.class, name = PromptReference.TYPE),
			@JsonSubTypes.Type(value = ResourceReference.class, name = ResourceReference.TYPE) })
	public interface CompleteReference {

		String type();

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record PromptReference( // @formatter:off
		@JsonProperty("type") String type,
		@JsonProperty("name") String name,
		@JsonProperty("title") String title) implements CompleteReference { // @formatter:on

		public static final String TYPE = "ref/prompt";

		public PromptReference {
			Assert.notNull(name, "name must not be null");
			if (type != null && !TYPE.equals(type)) {
				logger.warn("PromptReference: 'type' argument '{}' is ignored, type is always '{}'", type, TYPE);
			}
			type = TYPE;
		}

		@JsonCreator
		static PromptReference fromJson(@JsonProperty("type") String type, @JsonProperty("name") String name,
				@JsonProperty("title") String title) {
			if (name == null) {
				logger.warn("PromptReference: missing required field 'name' during deserialization, using default ''");
				name = "";
			}
			return new PromptReference(type, name, title);
		}

		public PromptReference(String name) {
			this(TYPE, name, null);
		}

		public static Builder builder(String name) {
			return new Builder(name);
		}

		public static final class Builder {

			private final String name;

			private String title;

			private Builder(String name) {
				Assert.hasText(name, "name must not be empty");
				this.name = name;
			}

			public Builder title(String title) {
				this.title = title;
				return this;
			}

			public PromptReference build() {
				return new PromptReference(TYPE, name, title);
			}

		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ResourceReference(@JsonProperty("uri") String uri) implements CompleteReference {

		public static final String TYPE = "ref/resource";

		public ResourceReference {
			Assert.notNull(uri, "uri must not be null");
		}

		@JsonProperty("type")
		@Override
		public String type() {
			return TYPE;
		}

		@JsonCreator
		static ResourceReference fromJson(@JsonProperty("uri") String uri, @JsonProperty("type") String type) {
			if (uri == null) {
				logger.warn("ResourceReference: missing required field 'uri' during deserialization, using default ''");
				uri = "";
			}
			return new ResourceReference(uri);
		}
	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record CompleteRequest( // @formatter:off
		@JsonProperty("ref") CompleteReference ref,
		@JsonProperty("argument") CompleteArgument argument,
		@JsonProperty("_meta") Map<String, Object> meta,
		@JsonProperty("context") CompleteContext context) { // @formatter:on

		public CompleteRequest {
			Assert.notNull(ref, "ref must not be null");
			Assert.notNull(argument, "argument must not be null");
		}

		@JsonCreator
		static CompleteRequest fromJson(@JsonProperty("ref") CompleteReference ref,
				@JsonProperty("argument") CompleteArgument argument, @JsonProperty("_meta") Map<String, Object> meta,
				@JsonProperty("context") CompleteContext context) {
			// ref is what the request is dispatched on, so it gets no default (fails
			// fast).
			if (argument == null) {
				logger.warn(
						"CompleteRequest: missing required field 'argument' during deserialization, using default {name: '', value: ''}");
				argument = new CompleteArgument("", "");
			}
			return new CompleteRequest(ref, argument, meta, context);
		}

		public static Builder builder(CompleteReference ref, CompleteArgument argument) {
			return new Builder(ref, argument);
		}

		public static final class Builder {

			private final CompleteReference ref;

			private final CompleteArgument argument;

			private Map<String, Object> meta;

			private CompleteContext context;

			private Builder(CompleteReference ref, CompleteArgument argument) {
				Assert.notNull(ref, "ref must not be null");
				Assert.notNull(argument, "argument must not be null");
				this.ref = ref;
				this.argument = argument;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public Builder context(CompleteContext context) {
				this.context = context;
				return this;
			}

			public CompleteRequest build() {
				return new CompleteRequest(ref, argument, meta, context);
			}

		}

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record CompleteArgument(@JsonProperty("name") String name, @JsonProperty("value") String value) {

			public CompleteArgument {
				Assert.notNull(name, "name must not be null");
				Assert.notNull(value, "value must not be null");
			}

			@JsonCreator
			static CompleteArgument fromJson(@JsonProperty("name") String name, @JsonProperty("value") String value) {
				if (name == null || value == null) {
					List<String> missing = new ArrayList<>();
					if (name == null) {
						missing.add("name -> ''");
						name = "";
					}
					if (value == null) {
						missing.add("value -> ''");
						value = "";
					}
					logger.warn("CompleteArgument: missing required fields during deserialization: {}",
							String.join(", ", missing));
				}
				return new CompleteArgument(name, value);
			}
		}

		/** Previously-resolved variables in a URI template or prompt. */
		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record CompleteContext(@JsonProperty("arguments") Map<String, String> arguments) {
		}
	}

	// ---------------------------
	// Content
	// ---------------------------

	/**
	 * A content block in a tool result, prompt message or sampling message, discriminated
	 * by its {@code type} property.
	 */
	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
	@JsonSubTypes({ @JsonSubTypes.Type(value = TextContent.class, name = TextContent.TYPE),
			@JsonSubTypes.Type(value = ImageContent.class, name = ImageContent.TYPE),
			@JsonSubTypes.Type(value = AudioContent.class, name = AudioContent.TYPE),
			@JsonSubTypes.Type(value = EmbeddedResource.class, name = EmbeddedResource.TYPE),
			@JsonSubTypes.Type(value = ResourceLink.class, name = ResourceLink.TYPE) })
	public interface Content {

		@JsonIgnore
		String type();

		Map<String, Object> meta();

	}

	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record TextContent( // @formatter:off
		@JsonProperty("annotations") Annotations annotations,
		@JsonProperty("text") String text,
		@JsonProperty("_meta") Map<String, Object> meta) implements Content { // @formatter:on

		public static final String TYPE = "text";

		public TextContent {
			Assert.notNull(text, "text must not be null");
		}

		@JsonCreator
		static TextContent fromJson(@JsonProperty("annotations") Annotations annotations,
				@JsonProperty("text") String text, @JsonProperty("_meta") Map<String, Object> meta) {
			if (text == null) {
				logger.warn("TextContent: missing required field 'text' during deserialization, using default ''");
				text = "";
			}
			return new TextContent(annotations, text, meta);
		}

		@Override
		public String type() {
			return TYPE;
		}

		public static Builder builder(String text) {
			return new Builder(text);
		}

		public static final class Builder {

			private Annotations annotations;

			private final String text;

			private Map<String, Object> meta;

			private Builder(String text) {
				Assert.notNull(text, "text must not be null");
				this.text = text;
			}

			public Builder annotations(Annotations annotations) {
				this.annotations = annotations;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public TextContent build() {
				return new TextContent(annotations, text, meta);
			}

		}
	}

	/** Base64-encoded image data. */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ImageContent( // @formatter:off
		@JsonProperty("annotations") Annotations annotations,
		@JsonProperty("data") String data,
		@JsonProperty("mimeType") String mimeType,
		@JsonProperty("_meta") Map<String, Object> meta) implements Content { // @formatter:on

		public static final String TYPE = "image";

		public ImageContent {
			Assert.notNull(data, "data must not be null");
			Assert.notNull(mimeType, "mimeType must not be null");
		}

		@JsonCreator
		static ImageContent fromJson(@JsonProperty("annotations") Annotations annotations,
				@JsonProperty("data") String data, @JsonProperty("mimeType") String mimeType,
				@JsonProperty("_meta") Map<String, Object> meta) {
			if (data == null || mimeType == null) {
				List<String> missing = new ArrayList<>();
				if (data == null) {
					missing.add("data -> ''");
					data = "";
				}
				if (mimeType == null) {
					missing.add("mimeType -> ''");
					mimeType = "";
				}
				logger.warn("ImageContent: missing required fields during deserialization: {}",
						String.join(", ", missing));
			}
			return new ImageContent(annotations, data, mimeType, meta);
		}

		@Override
		public String type() {
			return TYPE;
		}

		public static Builder builder(String data, String mimeType) {
			return new Builder(data, mimeType);
		}

		public static final class Builder {

			private Annotations annotations;

			private final String data;

			private final String mimeType;

			private Map<String, Object> meta;

			private Builder(String data, String mimeType) {
				Assert.notNull(data, "data must not be null");
				Assert.notNull(mimeType, "mimeType must not be null");
				this.data = data;
				this.mimeType = mimeType;
			}

			public Builder annotations(Annotations annotations) {
				this.annotations = annotations;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ImageContent build() {
				return new ImageContent(annotations, data, mimeType, meta);
			}

		}
	}

	/** Base64-encoded audio data. */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record AudioContent( // @formatter:off
		@JsonProperty("annotations") Annotations annotations,
		@JsonProperty("data") String data,
		@JsonProperty("mimeType") String mimeType,
		@JsonProperty("_meta") Map<String, Object> meta) implements Content { // @formatter:on

		public static final String TYPE = "audio";

		public AudioContent {
			Assert.notNull(data, "data must not be null");
			Assert.notNull(mimeType, "mimeType must not be null");
		}

		@JsonCreator
		static AudioContent fromJson(@JsonProperty("annotations") Annotations annotations,
				@JsonProperty("data") String data, @JsonProperty("mimeType") String mimeType,
				@JsonProperty("_meta") Map<String, Object> meta) {
			if (data == null || mimeType == null) {
				List<String> missing = new ArrayList<>();
				if (data == null) {
					missing.add("data -> ''");
					data = "";
				}
				if (mimeType == null) {
					missing.add("mimeType -> ''");
					mimeType = "";
				}
				logger.warn("AudioContent: missing required fields during deserialization: {}",
						String.join(", ", missing));
			}
			return new AudioContent(annotations, data, mimeType, meta);
		}

		@Override
		public String type() {
			return TYPE;
		}

		public static Builder builder(String data, String mimeType) {
			return new Builder(data, mimeType);
		}

		public static final class Builder {

			private Annotations annotations;

			private final String data;

			private final String mimeType;

			private Map<String, Object> meta;

			private Builder(String data, String mimeType) {
				Assert.notNull(data, "data must not be null");
				Assert.notNull(mimeType, "mimeType must not be null");
				this.data = data;
				this.mimeType = mimeType;
			}

			public Builder annotations(Annotations annotations) {
				this.annotations = annotations;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public AudioContent build() {
				return new AudioContent(annotations, data, mimeType, meta);
			}

		}
	}

	/** Resource contents embedded into a prompt or tool result. */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record EmbeddedResource( // @formatter:off
		@JsonProperty("annotations") Annotations annotations,
		@JsonProperty("resource") ResourceContents resource,
		@JsonProperty("_meta") Map<String, Object> meta) implements Content { // @formatter:on

		public static final String TYPE = "resource";

		public EmbeddedResource {
			Assert.notNull(resource, "resource must not be null");
		}

		@JsonCreator
		static EmbeddedResource fromJson(@JsonProperty("annotations") Annotations annotations,
				@JsonProperty("resource") ResourceContents resource, @JsonProperty("_meta") Map<String, Object> meta) {
			if (resource == null) {
				logger.warn(
						"EmbeddedResource: missing required field 'resource' during deserialization, using empty text resource");
				resource = new TextResourceContents("", null, "", null);
			}
			return new EmbeddedResource(annotations, resource, meta);
		}

		@Override
		public String type() {
			return TYPE;
		}

		public static Builder builder(ResourceContents resource) {
			return new Builder(resource);
		}

		public static final class Builder {

			private Annotations annotations;

			private final ResourceContents resource;

			private Map<String, Object> meta;

			private Builder(ResourceContents resource) {
				Assert.notNull(resource, "resource must not be null");
				this.resource = resource;
			}

			public Builder annotations(Annotations annotations) {
				this.annotations = annotations;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public EmbeddedResource build() {
				return new EmbeddedResource(annotations, resource, meta);
			}

		}
	}

	/** A link to a resource the server can read, returned in place of its contents. */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ResourceLink( // @formatter:off
		@JsonProperty("name") String name,
		@JsonProperty("title") String title,
		@JsonProperty("uri") String uri,
		@JsonProperty("description") String description,
		@JsonProperty("mimeType") String mimeType,
		@JsonProperty("size") Long size,
		@JsonProperty("annotations") Annotations annotations,
		@JsonProperty("_meta") Map<String, Object> meta) implements Content { // @formatter:on

		public static final String TYPE = "resource_link";

		public ResourceLink {
			Assert.notNull(name, "name must not be null");
			Assert.notNull(uri, "uri must not be null");
		}

		@JsonCreator
		static ResourceLink fromJson(@JsonProperty("name") String name, @JsonProperty("title") String title,
				@JsonProperty("uri") String uri, @JsonProperty("description") String description,
				@JsonProperty("mimeType") String mimeType, @JsonProperty("size") Long size,
				@JsonProperty("annotations") Annotations annotations, @JsonProperty("_meta") Map<String, Object> meta) {
			if (name == null || uri == null) {
				List<String> missing = new ArrayList<>();
				if (name == null) {
					missing.add("name -> ''");
					name = "";
				}
				if (uri == null) {
					missing.add("uri -> ''");
					uri = "";
				}
				logger.warn("ResourceLink: missing required fields during deserialization: {}",
						String.join(", ", missing));
			}
			return new ResourceLink(name, title, uri, description, mimeType, size, annotations, meta);
		}

		@Override
		public String type() {
			return TYPE;
		}

		public static Builder builder(String uri, String name) {
			return new Builder(uri, name);
		}

		public static final class Builder {

			private final String uri;

			private final String name;

			private String title;

			private String description;

			private String mimeType;

			private Long size;

			private Annotations annotations;

			private Map<String, Object> meta;

			private Builder(String uri, String name) {
				Assert.hasText(uri, "uri must not be empty");
				Assert.hasText(name, "name must not be empty");
				this.uri = uri;
				this.name = name;
			}

			public Builder title(String title) {
				this.title = title;
				return this;
			}

			public Builder description(String description) {
				this.description = description;
				return this;
			}

			public Builder mimeType(String mimeType) {
				this.mimeType = mimeType;
				return this;
			}

			public Builder size(Long size) {
				this.size = size;
				return this;
			}

			public Builder annotations(Annotations annotations) {
				this.annotations = annotations;
				return this;
			}

			public Builder meta(Map<String, Object> meta) {
				this.meta = meta;
				return this;
			}

			public ResourceLink build() {
				return new ResourceLink(name, title, uri, description, mimeType, size, annotations, meta);
			}

		}
	}

}
