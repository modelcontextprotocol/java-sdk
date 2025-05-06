package io.modelcontextprotocol.schema;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

/**
 * @author Aliaksei Darafeyeu
 */
public class GenericEnumDeserializer<T extends Enum<T>> extends JsonDeserializer<T> {

	private final Class<T> enumType;

	public GenericEnumDeserializer(Class<T> enumType) {
		this.enumType = enumType;
	}

	@Override
	public T deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
		String camel = p.getText();
		// Convert camelCase or kebab-case to UPPER_SNAKE_CASE
		String snake = camel.replaceAll("([a-z])([A-Z])", "$1_$2").replace("-", "_").toUpperCase();

		try {
			return Enum.valueOf(enumType, snake);
		}
		catch (IllegalArgumentException ex) {
			throw new IOException("Unknown enum value: " + camel + " for enum " + enumType.getSimpleName());
		}
	}

}
