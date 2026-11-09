/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import io.modelcontextprotocol.modern.McpSchema.ElicitInputRequest;
import io.modelcontextprotocol.modern.McpSchema.InputRequest;
import io.modelcontextprotocol.util.Assert;

/**
 * The server asked for input that no handler of the call covers. The client declares only
 * the capabilities its handlers cover, so the server asked for input it may not request.
 *
 * @author Dariusz Jędrzejczyk
 */
public class McpInputHandlerMissingException extends RuntimeException {

	private final String inputKey;

	private final transient InputRequest inputRequest;

	public McpInputHandlerMissingException(String inputKey, InputRequest inputRequest) {
		super("No handler for input request '" + inputKey + "' (" + describe(requireRequest(inputRequest)) + ")");
		this.inputKey = inputKey;
		this.inputRequest = inputRequest;
	}

	/** The key the server gave the input request. */
	public String inputKey() {
		return this.inputKey;
	}

	/** The input request no handler covers. */
	public InputRequest inputRequest() {
		return this.inputRequest;
	}

	private static String describe(InputRequest request) {
		if (request instanceof ElicitInputRequest elicit) {
			return request.method() + ", mode " + elicit.params().mode();
		}
		return request.method();
	}

	private static InputRequest requireRequest(InputRequest inputRequest) {
		Assert.notNull(inputRequest, "inputRequest must not be null");
		return inputRequest;
	}

}
