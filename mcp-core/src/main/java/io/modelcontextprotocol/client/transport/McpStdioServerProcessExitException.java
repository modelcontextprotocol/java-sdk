/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import io.modelcontextprotocol.spec.McpTransportException;
import io.modelcontextprotocol.util.Assert;

/**
 * Thrown when an MCP stdio server process exits unexpectedly.
 *
 * @author Dongliang Xie
 */
public class McpStdioServerProcessExitException extends McpTransportException {

	private static final long serialVersionUID = 1L;

	private final int exitCode;

	private final String command;

	public McpStdioServerProcessExitException(int exitCode, String command) {
		super(message(exitCode, command));
		this.exitCode = exitCode;
		this.command = command;
	}

	public int getExitCode() {
		return this.exitCode;
	}

	public String getCommand() {
		return this.command;
	}

	private static String message(int exitCode, String command) {
		Assert.hasText(command, "The command can not be empty");
		return "MCP server process exited unexpectedly with code " + exitCode + " for command: " + command;
	}

}
