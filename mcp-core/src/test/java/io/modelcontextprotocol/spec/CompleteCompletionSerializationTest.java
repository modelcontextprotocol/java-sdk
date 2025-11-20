package io.modelcontextprotocol.spec;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.internal.DefaultMcpJsonMapperSupplier;

class CompleteCompletionSerializationTest {

	@Test
	void codeCompletionSerialization() throws IOException {
		McpJsonMapper jsonMapper = DefaultMcpJsonMapperSupplier.getDefaultMcpJsonMapper();
		McpSchema.CompleteResult.CompleteCompletion codeComplete = new McpSchema.CompleteResult.CompleteCompletion(
				Collections.emptyList(), 0, false);
		String json = jsonMapper.writeValueAsString(codeComplete);
		String expected = """
				{"values":[],"total":0,"hasMore":false}""";
		assertEquals(expected, json, json);

		McpSchema.CompleteResult completeResult = new McpSchema.CompleteResult(codeComplete);
		json = jsonMapper.writeValueAsString(completeResult);
		expected = """
				{"completion":{"values":[],"total":0,"hasMore":false}}""";
		assertEquals(expected, json, json);
	}

}
