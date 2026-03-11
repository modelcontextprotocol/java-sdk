/*
* Copyright 2024 - 2024 the original author or authors.
*/

package io.modelcontextprotocol.spec.schema.resource;

import io.modelcontextprotocol.spec.McpSchema.Meta;
import io.modelcontextprotocol.spec.McpSchema.ResourceLink;

/**
 * A common interface for resource content, which includes metadata about the resource
 * such as its URI, name, description, MIME type, size, and annotations. This interface is
 * implemented by both {@link Resource} and {@link ResourceLink} to provide a consistent
 * way to access resource metadata.
 */
public interface ResourceContent extends Identifier, Annotated, Meta {

	// name & title from Identifier

	String uri();

	String description();

	String mimeType();

	Long size();

	// annotations from Annotated
	// meta from Meta

}