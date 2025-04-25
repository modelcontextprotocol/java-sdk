package io.modelcontextprotocol.server;

import java.util.Map;

/**
 * MCP server authentication context.
 *
 * @author lambochen
 */
public class McpServerAuthParam {

    /**
     * mcp server sse endpoint
     */
    private final String sseEndpoint;
    /**
     * mcp server url
     */
    private final String uri;
    /**
     * mcp server params
     */
    private final Map<String, String> params;

    private McpServerAuthParam(String sseEndpoint, String uri, Map<String, String> params) {
        this.sseEndpoint = sseEndpoint;
        this.uri = uri;
        this.params = params;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String uri;
        private String sseEndpoint;
        private Map<String, String> params;

        public Builder uri(String uri) {
            this.uri = uri;
            return this;
        }

        public Builder sseEndpoint(String sseEndpoint) {
            this.sseEndpoint = sseEndpoint;
            return this;
        }

        public Builder params(Map<String, String> params) {
            this.params = params;
            return this;
        }

        public McpServerAuthParam build() {
            return new McpServerAuthParam(sseEndpoint, uri, params);
        }
    }
}
