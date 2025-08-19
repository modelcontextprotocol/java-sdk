/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * A stateless MCP server implementation for use with Streamable HTTP transport types. It
 * allows simple horizontal scalability since it does not maintain a session and does not
 * require initialization. Each instance of the server can be reached with no prior
 * knowledge and can serve the clients with the capabilities it supports.
 *
 * @author Dariusz Jędrzejczyk
 */
public class McpStatelessSyncServer {

	private static final Logger logger = LoggerFactory.getLogger(McpStatelessSyncServer.class);

	private final McpStatelessAsyncServer asyncServer;

	private final boolean immediateExecution;

	McpStatelessSyncServer(McpStatelessAsyncServer asyncServer, boolean immediateExecution) {
		this.asyncServer = asyncServer;
		this.immediateExecution = immediateExecution;
	}

	/**
	 * Get the server capabilities that define the supported features and functionality.
	 * @return The server capabilities
	 */
	public McpSchema.ServerCapabilities getServerCapabilities() {
		return this.asyncServer.getServerCapabilities();
	}

	/**
	 * Get the server implementation information.
	 * @return The server implementation details
	 */
	public McpSchema.Implementation getServerInfo() {
		return this.asyncServer.getServerInfo();
	}

	/**
	 * Gracefully closes the server, allowing any in-progress operations to complete.
	 * @return A Mono that completes when the server has been closed
	 */
	public Mono<Void> closeGracefully() {
		return this.asyncServer.closeGracefully();
	}

	/**
	 * Close the server immediately.
	 */
	public void close() {
		this.asyncServer.close();
	}

	/**
	 * Add a new tool specification at runtime.
	 * @param toolSpecification The tool specification to add
	 */
	public void addTool(McpStatelessServerFeatures.SyncToolSpecification toolSpecification) {
		this.asyncServer
			.addTool(McpStatelessServerFeatures.AsyncToolSpecification.fromSync(toolSpecification,
					this.immediateExecution))
			.block();
	}

	/**
	 * Remove a tool handler at runtime.
	 * @param toolName The name of the tool handler to remove
	 */
	public void removeTool(String toolName) {
		this.asyncServer.removeTool(toolName).block();
	}

	/**
	 * Add a new resource handler at runtime.
	 * @param resourceSpecification The resource handler to add
	 */
	public void addResource(McpStatelessServerFeatures.SyncResourceSpecification resourceSpecification) {
		this.asyncServer
			.addResource(McpStatelessServerFeatures.AsyncResourceSpecification.fromSync(resourceSpecification,
					this.immediateExecution))
			.block();
	}

	/**
	 * Remove a resource handler at runtime.
	 * @param resourceUri The URI of the resource handler to remove
	 */
	public void removeResource(String resourceUri) {
		this.asyncServer.removeResource(resourceUri).block();
	}

	/**
	 * Add a new prompt handler at runtime.
	 * @param promptSpecification The prompt handler to add
	 */
	public void addPrompt(McpStatelessServerFeatures.SyncPromptSpecification promptSpecification) {
		this.asyncServer
			.addPrompt(McpStatelessServerFeatures.AsyncPromptSpecification.fromSync(promptSpecification,
					this.immediateExecution))
			.block();
	}

	/**
	 * Remove a prompt handler at runtime.
	 * @param promptName The name of the prompt handler to remove
	 */
	public void removePrompt(String promptName) {
		this.asyncServer.removePrompt(promptName).block();
	}

	/**
	 * This method is package-private and used for test only. Should not be called by user
	 * code.
	 * @param protocolVersions the Client supported protocol versions.
	 */
	void setProtocolVersions(List<String> protocolVersions) {
		this.asyncServer.setProtocolVersions(protocolVersions);
	}

}
