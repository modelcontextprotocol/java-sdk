/*
* Copyright 2024 - 2024 the original author or authors.
*/

package io.modelcontextprotocol.spec.schema.resource;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.modelcontextprotocol.spec.McpSchema.Meta;

/**
 * The contents of a specific resource or sub-resource.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
@JsonSubTypes({ @JsonSubTypes.Type(value = TextResourceContents.class),
		@JsonSubTypes.Type(value = BlobResourceContents.class) })
public interface ResourceContents extends Meta {

	/**
	 * The URI of this resource.
	 * @return the URI of this resource.
	 */
	String uri();

	/**
	 * The MIME type of this resource.
	 * @return the MIME type of this resource.
	 */
	String mimeType();

}