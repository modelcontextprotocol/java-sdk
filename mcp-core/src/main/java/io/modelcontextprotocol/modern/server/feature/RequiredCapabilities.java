/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import java.util.LinkedHashMap;
import java.util.Map;

import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.modern.server.McpRequestContext;

final class RequiredCapabilities {

	private RequiredCapabilities() {
	}

	// A -32021 listing the required capabilities the client did not declare, or null if
	// it declared all of them.
	static McpException check(ClientCapabilities required, McpRequestContext ctx) {
		ClientCapabilities missing = missing(required, ctx.clientCapabilities());
		return missing != null ? McpException.missingClientCapability(missing) : null;
	}

	private static ClientCapabilities missing(ClientCapabilities required, ClientCapabilities declared) {
		boolean roots = required.supportsRoots() && !declared.supportsRoots();
		boolean sampling = required.supportsSampling() && !declared.supportsSampling();
		boolean form = required.supportsElicitationForm() && !declared.supportsElicitationForm();
		boolean url = required.supportsElicitationUrl() && !declared.supportsElicitationUrl();
		Map<String, Object> experimental = missingKeys(required.experimental(), declared.experimental());
		Map<String, Map<String, Object>> extensions = missingKeys(required.extensions(), declared.extensions());
		if (!roots && !sampling && !form && !url && experimental == null && extensions == null) {
			return null;
		}
		ClientCapabilities.Builder builder = ClientCapabilities.builder()
			.experimental(experimental)
			.extensions(extensions);
		if (roots) {
			builder.roots();
		}
		if (sampling) {
			builder.sampling();
		}
		if (form || url) {
			builder
				.elicitation(new ClientCapabilities.Elicitation(form ? new ClientCapabilities.Elicitation.Form() : null,
						url ? new ClientCapabilities.Elicitation.Url() : null));
		}
		return builder.build();
	}

	private static <V> Map<String, V> missingKeys(Map<String, V> required, Map<String, V> declared) {
		if (required == null) {
			return null;
		}
		Map<String, V> missing = new LinkedHashMap<>(required);
		if (declared != null) {
			missing.keySet().removeAll(declared.keySet());
		}
		return missing.isEmpty() ? null : missing;
	}

}
