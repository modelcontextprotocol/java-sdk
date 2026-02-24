package io.modelcontextprotocol.spec;

public record StoredEvent(MessageId messageId, McpSchema.JSONRPCMessage message, long timestamp) {

	public String transportId() {
		return messageId.transportId();
	}
}
