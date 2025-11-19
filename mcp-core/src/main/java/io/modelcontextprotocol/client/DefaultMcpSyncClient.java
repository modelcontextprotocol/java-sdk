/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.time.Duration;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.ListPromptsResult;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Mono;

/**
 * A synchronous client implementation for the Model Context Protocol (MCP) that wraps an
 * {@link McpAsyncClient} to provide blocking operations.
 *
 * <p>
 * This client implements the MCP specification by delegating to an asynchronous client
 * and blocking on the results. Key features include:
 * <ul>
 * <li>Synchronous, blocking API for simpler integration in non-reactive applications
 * <li>Tool discovery and invocation for server-provided functionality
 * <li>Resource access and management with URI-based addressing
 * <li>Prompt template handling for standardized AI interactions
 * <li>Real-time notifications for tools, resources, and prompts changes
 * <li>Structured logging with configurable severity levels
 * </ul>
 *
 * <p>
 * The client follows the same lifecycle as its async counterpart:
 * <ol>
 * <li>Initialization - Establishes connection and negotiates capabilities
 * <li>Normal Operation - Handles requests and notifications
 * <li>Graceful Shutdown - Ensures clean connection termination
 * </ol>
 *
 * <p>
 * This implementation implements {@link AutoCloseable} for resource cleanup and provides
 * both immediate and graceful shutdown options. All operations block until completion or
 * timeout, making it suitable for traditional synchronous programming models.
 *
 * @author Dariusz Jędrzejczyk
 * @author Christian Tzolov
 * @author Jihoon Kim
 * @see McpClient
 * @see McpAsyncClient
 * @see McpSchema
 */
public class DefaultMcpSyncClient implements McpSyncClient {

	private static final Logger logger = LoggerFactory.getLogger(DefaultMcpSyncClient.class);

	// TODO: Consider providing a client config to set this properly
	// this is currently a concern only because AutoCloseable is used - perhaps it
	// is not a requirement?
	private static final long DEFAULT_CLOSE_TIMEOUT_MS = 10_000L;

	private final McpAsyncClient delegate;

	private final Supplier<McpTransportContext> contextProvider;

	/**
	 * Create a new McpSyncClient with the given delegate.
	 * @param delegate the asynchronous kernel on top of which this synchronous client
	 * provides a blocking API.
	 * @param contextProvider the supplier of context before calling any non-blocking
	 * operation on underlying delegate
	 */
	DefaultMcpSyncClient(McpAsyncClient delegate, Supplier<McpTransportContext> contextProvider) {
		Assert.notNull(delegate, "The delegate can not be null");
		Assert.notNull(contextProvider, "The contextProvider can not be null");
		this.delegate = delegate;
		this.contextProvider = contextProvider;
	}

	@Override
	public McpSchema.InitializeResult getCurrentInitializationResult() {
		return this.delegate.getCurrentInitializationResult();
	}

	@Override
	public McpSchema.ServerCapabilities getServerCapabilities() {
		return this.delegate.getServerCapabilities();
	}

	@Override
	public String getServerInstructions() {
		return this.delegate.getServerInstructions();
	}

	@Override
	public McpSchema.Implementation getServerInfo() {
		return this.delegate.getServerInfo();
	}

	@Override
	public boolean isInitialized() {
		return this.delegate.isInitialized();
	}

	@Override
	public ClientCapabilities getClientCapabilities() {
		return this.delegate.getClientCapabilities();
	}

	@Override
	public McpSchema.Implementation getClientInfo() {
		return this.delegate.getClientInfo();
	}

	@Override
	public void close() {
		this.delegate.close();
	}

	@Override
	public boolean closeGracefully() {
		try {
			this.delegate.closeGracefully().block(Duration.ofMillis(DEFAULT_CLOSE_TIMEOUT_MS));
		}
		catch (RuntimeException e) {
			logger.warn("Client didn't close within timeout of {} ms.", DEFAULT_CLOSE_TIMEOUT_MS, e);
			return false;
		}
		return true;
	}

	@Override
	public McpSchema.InitializeResult initialize() {
		// TODO: block takes no argument here as we assume the async client is
		// configured with a requestTimeout at all times
		return withProvidedContext(this.delegate.initialize()).block();
	}

	@Override
	public void rootsListChangedNotification() {
		withProvidedContext(this.delegate.rootsListChangedNotification()).block();
	}

	@Override
	public void addRoot(McpSchema.Root root) {
		this.delegate.addRoot(root).block();
	}

	@Override
	public void removeRoot(String rootUri) {
		this.delegate.removeRoot(rootUri).block();
	}

	@Override
	public Object ping() {
		return withProvidedContext(this.delegate.ping()).block();
	}

	// --------------------------
	// Tools
	// --------------------------
	@Override
	public McpSchema.CallToolResult callTool(McpSchema.CallToolRequest callToolRequest) {
		return withProvidedContext(this.delegate.callTool(callToolRequest)).block();

	}

	@Override
	public McpSchema.ListToolsResult listTools() {
		return withProvidedContext(this.delegate.listTools()).block();
	}

	@Override
	public McpSchema.ListToolsResult listTools(String cursor) {
		return withProvidedContext(this.delegate.listTools(cursor)).block();

	}

	// --------------------------
	// Resources
	// --------------------------

	@Override
	public McpSchema.ListResourcesResult listResources() {
		return withProvidedContext(this.delegate.listResources()).block();

	}

	@Override
	public McpSchema.ListResourcesResult listResources(String cursor) {
		return withProvidedContext(this.delegate.listResources(cursor)).block();

	}

	@Override
	public McpSchema.ReadResourceResult readResource(McpSchema.Resource resource) {
		return withProvidedContext(this.delegate.readResource(resource)).block();

	}

	@Override
	public McpSchema.ReadResourceResult readResource(McpSchema.ReadResourceRequest readResourceRequest) {
		return withProvidedContext(this.delegate.readResource(readResourceRequest)).block();

	}

	@Override
	public McpSchema.ListResourceTemplatesResult listResourceTemplates() {
		return withProvidedContext(this.delegate.listResourceTemplates()).block();

	}

	@Override
	public McpSchema.ListResourceTemplatesResult listResourceTemplates(String cursor) {
		return withProvidedContext(this.delegate.listResourceTemplates(cursor)).block();

	}

	@Override
	public void subscribeResource(McpSchema.SubscribeRequest subscribeRequest) {
		withProvidedContext(this.delegate.subscribeResource(subscribeRequest)).block();

	}

	@Override
	public void unsubscribeResource(McpSchema.UnsubscribeRequest unsubscribeRequest) {
		withProvidedContext(this.delegate.unsubscribeResource(unsubscribeRequest)).block();

	}

	// --------------------------
	// Prompts
	// --------------------------

	@Override
	public ListPromptsResult listPrompts() {
		return withProvidedContext(this.delegate.listPrompts()).block();
	}

	@Override
	public ListPromptsResult listPrompts(String cursor) {
		return withProvidedContext(this.delegate.listPrompts(cursor)).block();

	}

	@Override
	public GetPromptResult getPrompt(GetPromptRequest getPromptRequest) {
		return withProvidedContext(this.delegate.getPrompt(getPromptRequest)).block();
	}

	@Override
	public void setLoggingLevel(McpSchema.LoggingLevel loggingLevel) {
		withProvidedContext(this.delegate.setLoggingLevel(loggingLevel)).block();

	}

	@Override
	public McpSchema.CompleteResult completeCompletion(McpSchema.CompleteRequest completeRequest) {
		return withProvidedContext(this.delegate.completeCompletion(completeRequest)).block();

	}

	/**
	 * For a given action, on assembly, capture the "context" via the
	 * {@link #contextProvider} and store it in the Reactor context.
	 * @param action the action to perform
	 * @return the result of the action
	 */
	private <T> Mono<T> withProvidedContext(Mono<T> action) {
		return action.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, this.contextProvider.get()));
	}

}
