
package io.modelcontextprotocol.spec;

import java.util.Collections;
import java.util.Map;

/**
 * Represents the context for MCP request execution.
 *
 * <p>
 * The context map can contain any information that is relevant to the request.
 * </p>
 *
 * @author taobaorun
 */
public class RequestContext {

	/**
	 * the original request object
	 */
	private Object request;

	private final Map<String, Object> context;

	public RequestContext(Map<String, Object> context) {
		this.context = Collections.unmodifiableMap(context);
	}

	public Map<String, Object> getContext() {
		return context;
	}

	public Object getRequest() {
		return request;
	}

	public void setRequest(Object request) {
		this.request = request;
	}

}
