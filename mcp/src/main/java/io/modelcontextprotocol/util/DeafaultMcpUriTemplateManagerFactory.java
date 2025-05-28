/*
* Copyright 2025 - 2025 the original author or authors.
*/
package io.modelcontextprotocol.util;

/**
 * @deprecated Use {@link DefaultMcpUriTemplateManagerFactory} instead. This
 * class will be removed in future versions.
 * @author Christian Tzolov
 */
@Deprecated
public class DeafaultMcpUriTemplateManagerFactory implements McpUriTemplateManagerFactory {

    /**
     * Creates a new instance of {@link McpUriTemplateManager} with the specified URI
     * template.
     *
     * @param uriTemplate The URI template to be used for variable extraction
     * @return A new instance of {@link McpUriTemplateManager}
     * @throws IllegalArgumentException if the URI template is null or empty
     */
    @Override
    public McpUriTemplateManager create(String uriTemplate) {
        return new DefaultMcpUriTemplateManager(uriTemplate);
    }
}
