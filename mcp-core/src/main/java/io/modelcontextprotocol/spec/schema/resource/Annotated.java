/*
* Copyright 2024 - 2024 the original author or authors.
*/

package io.modelcontextprotocol.spec.schema.resource;

import io.modelcontextprotocol.spec.McpSchema.Annotations;

/**
 * Base for objects that include optional annotations for the client. The client can use
 * annotations to inform how objects are used or displayed
 */
public interface Annotated {

	Annotations annotations();

}