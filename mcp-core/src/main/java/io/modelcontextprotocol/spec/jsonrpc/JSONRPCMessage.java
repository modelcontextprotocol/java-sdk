/*
* Copyright 2024 - 2024 the original author or authors.
*/

package io.modelcontextprotocol.spec.jsonrpc;

public sealed interface JSONRPCMessage permits JSONRPCRequest, JSONRPCNotification, JSONRPCResponse {

	String jsonrpc();

}