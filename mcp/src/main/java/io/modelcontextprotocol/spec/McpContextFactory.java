package io.modelcontextprotocol.spec;

public interface McpContextFactory {

	default McpContext create(Object contextParam) {
		return new McpContext();
	}

}
