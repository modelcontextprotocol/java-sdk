/*
 * Copyright 2026 the original author or authors.
 */

package io.modelcontextprotocol.client;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class McpClientFeaturesContentValidationTests {

	private static final McpSchema.Implementation CLIENT_INFO = McpSchema.Implementation.builder("test", "1").build();

	@ParameterizedTest
	@ValueSource(booleans = { true, false })
	void syncConversionPreservesContentValidation(boolean enabled) {
		var sync = new McpClientFeatures.Sync(CLIENT_INFO, null, null, null, null, null, null, null, null, null, null,
				null, null, true, true, enabled);
		var async = McpClientFeatures.Async.fromSync(sync);
		assertThat(async.validateCallToolResultContent()).isEqualTo(enabled);
		assertThat(async.enableCallToolSchemaCaching()).isTrue();
		assertThat(async.applyElicitationDefaults()).isTrue();
		assertThat(async.clientCapabilities()).isEqualTo(sync.clientCapabilities());
	}

	@ParameterizedTest
	@ValueSource(booleans = { true, false })
	void asyncFeaturesPreserveContentValidation(boolean enabled) {
		var features = new McpClientFeatures.Async(CLIENT_INFO, null, null, null, null, null, null, null, null, null,
				null, null, null, true, true, enabled);
		assertThat(features.validateCallToolResultContent()).isEqualTo(enabled);
		assertThat(features.enableCallToolSchemaCaching()).isTrue();
		assertThat(features.applyElicitationDefaults()).isTrue();
	}

	@Test
	void legacySyncConstructorKeepsContentValidationDisabled() {
		var sync = new McpClientFeatures.Sync(CLIENT_INFO, null, null, null, null, null, null, null, null, null, null);
		assertThat(sync.validateCallToolResultContent()).isFalse();
		assertThat(McpClientFeatures.Async.fromSync(sync).validateCallToolResultContent()).isFalse();
	}

	@Test
	void legacyAsyncConstructorKeepsContentValidationDisabled() {
		var async = new McpClientFeatures.Async(CLIENT_INFO, null, null, null, null, null, null, null, null, null);
		assertThat(async.validateCallToolResultContent()).isFalse();
	}

}
