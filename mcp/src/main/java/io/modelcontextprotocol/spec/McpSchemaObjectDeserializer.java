package io.modelcontextprotocol.spec;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.node.TreeTraversingParser;

import java.io.IOException;

public class McpSchemaObjectDeserializer extends StdDeserializer<McpSchema.Schema> {

	public McpSchemaObjectDeserializer() {
		this(null);
	}

	public McpSchemaObjectDeserializer(Class<?> vc) {
		super(vc);
	}

	@Override
	public McpSchema.Schema deserialize(JsonParser jp, DeserializationContext ctx) throws IOException {
		ObjectMapper mapper = (ObjectMapper) jp.getCodec();
		JsonNode root = mapper.readTree(jp);

		String type = root.path("type").asText();
		if ("string".equals(type)) {
			if (root.has("enum")) {
				return readValue(mapper, root, McpSchema.EnumSchema.class);
			}
			else {
				return readValue(mapper, root, McpSchema.StringSchema.class);
			}
		}
		else if ("boolean".equals(type)) {
			return readValue(mapper, root, McpSchema.BooleanSchema.class);
		}
		else if ("number".equals(type) || "integer".equals(type)) {
			return readValue(mapper, root, McpSchema.NumberSchema.class);
		}

		throw new RuntimeException("Unknown schema type: " + type);
	}

	private <T> T readValue(ObjectMapper mapper, JsonNode node, Class<T> clazz) throws IOException {
		ObjectReader reader = mapper.readerFor(clazz);
		TreeTraversingParser treeParser = new TreeTraversingParser(node, mapper);
		return reader.readValue(treeParser);
	}

}
