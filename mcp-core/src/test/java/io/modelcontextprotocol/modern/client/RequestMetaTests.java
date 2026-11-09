/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.util.LinkedHashMap;
import java.util.Map;

import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.modern.McpSchema.Implementation;
import io.modelcontextprotocol.modern.McpSchema.MetaKeys;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestMetaTests {

	private static final Implementation CLIENT_INFO = Implementation.builder("test-client", "1.0.0").build();

	@Test
	void carriesVersionCapabilitiesClientInfoAndProgressToken() {
		ClientCapabilities capabilities = ClientCapabilities.builder().roots().build();

		Map<String, Object> meta = RequestMeta.of(McpSchema.LATEST_PROTOCOL_VERSION, capabilities, CLIENT_INFO,
				"tok-1");

		assertThat(meta).containsExactly(Map.entry(MetaKeys.PROTOCOL_VERSION, McpSchema.LATEST_PROTOCOL_VERSION),
				Map.entry(MetaKeys.CLIENT_CAPABILITIES, capabilities), Map.entry(MetaKeys.CLIENT_INFO, CLIENT_INFO),
				Map.entry(MetaKeys.PROGRESS_TOKEN, "tok-1"));
	}

	@Test
	void absentClientInfoAndProgressTokenAreLeftOut() {
		Map<String, Object> meta = RequestMeta.of(McpSchema.LATEST_PROTOCOL_VERSION, ClientCapabilities.NONE, null,
				null);

		assertThat(meta).containsOnlyKeys(MetaKeys.PROTOCOL_VERSION, MetaKeys.CLIENT_CAPABILITIES);
	}

	@Test
	void integerProgressTokenIsAccepted() {
		Map<String, Object> meta = RequestMeta.of(McpSchema.LATEST_PROTOCOL_VERSION, ClientCapabilities.NONE, null, 7L);

		assertThat(meta).containsEntry(MetaKeys.PROGRESS_TOKEN, 7L);
	}

	@Test
	void progressTokenThatIsNeitherStringNorIntegerIsRejected() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> RequestMeta.of(McpSchema.LATEST_PROTOCOL_VERSION, ClientCapabilities.NONE, null, 1.5));
	}

	@Test
	void versionAndCapabilitiesAreRequired() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> RequestMeta.of("", ClientCapabilities.NONE, CLIENT_INFO, null));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> RequestMeta.of(McpSchema.LATEST_PROTOCOL_VERSION, null, CLIENT_INFO, null));
	}

	@Test
	void isUnmodifiable() {
		Map<String, Object> meta = RequestMeta.of(McpSchema.LATEST_PROTOCOL_VERSION, ClientCapabilities.NONE, null,
				null);

		assertThatThrownBy(() -> meta.put("other", "value")).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void applicationEntriesAreCopiedInOrder() {
		Map<String, Object> entries = new LinkedHashMap<>();
		entries.put("trace", "t-1");
		entries.put("com.example/tenant", "acme");

		Map<String, Object> copy = RequestMeta.applicationEntries(entries);
		entries.put("later", true);

		assertThat(copy).containsExactly(Map.entry("trace", "t-1"), Map.entry("com.example/tenant", "acme"));
	}

	@Test
	void applicationEntriesTheClientSetsAreRejected() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> RequestMeta.applicationEntries(Map.of(MetaKeys.PROGRESS_TOKEN, "t")));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> RequestMeta.applicationEntries(Map.of("io.modelcontextprotocol/anything", "x")));
	}

	@Test
	void clientEntriesFollowTheApplications() {
		Map<String, Object> client = RequestMeta.of(McpSchema.LATEST_PROTOCOL_VERSION, ClientCapabilities.NONE, null,
				null);

		Map<String, Object> merged = RequestMeta.merged(Map.of("trace", "t-1"), client);

		assertThat(merged.keySet()).containsExactly("trace", MetaKeys.PROTOCOL_VERSION, MetaKeys.CLIENT_CAPABILITIES);
		assertThat(RequestMeta.merged(Map.of(), client)).isSameAs(client);
	}

}
