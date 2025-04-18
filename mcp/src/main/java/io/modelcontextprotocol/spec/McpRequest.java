package io.modelcontextprotocol.spec;

/**
 * @param params the parameters of the request.
 * @param context the request context
 * @author taobaorun
 */
public record McpRequest(Object params, RequestContext context) {
}
