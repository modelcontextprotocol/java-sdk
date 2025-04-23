package io.modelcontextprotocol.schema;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

/**
 * @author Aliaksei Darafeyeu
 */
public class GenericEnumSerializer extends JsonSerializer<Enum<?>> {

	@Override
	public void serialize(Enum<?> value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
		final String[] parts = value.name().toLowerCase().split("_");
		final StringBuilder result = new StringBuilder(parts[0]);
		for (int i = 1; i < parts.length; i++) {
			result.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
		}
		gen.writeString(result.toString());
	}

}
