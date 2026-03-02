package io.modelcontextprotocol.spec.json.gson;

import java.lang.reflect.Field;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.gson.FieldNamingStrategy;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;

/**
 * Test-only {@link McpJsonMapperSupplier} backed by Gson. Registered via
 * {@code META-INF/services} so that {@code McpJsonDefaults.getMapper()} works in unit
 * tests without requiring a Jackson module on the classpath.
 * <p>
 * The Gson instance is configured with a {@link FieldNamingStrategy} that reads Jackson's
 * {@link JsonProperty} annotation so that snake_case JSON fields (e.g.
 * {@code token_endpoint}) map correctly to camelCase Java fields annotated with
 * {@code @JsonProperty("token_endpoint")}.
 */
public class GsonMcpJsonMapperSupplier implements McpJsonMapperSupplier {

	@Override
	public McpJsonMapper get() {
		var gson = new GsonBuilder().serializeNulls()
			.setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
			.setNumberToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
			.setFieldNamingStrategy(new JacksonPropertyFieldNamingStrategy())
			.create();
		return new GsonMcpJsonMapper(gson);
	}

	/**
	 * Resolves a field name using the value of a {@link JsonProperty} annotation if
	 * present, otherwise falls back to the Java field name.
	 */
	private static final class JacksonPropertyFieldNamingStrategy implements FieldNamingStrategy {

		@Override
		public String translateName(Field field) {
			JsonProperty annotation = field.getAnnotation(JsonProperty.class);
			if (annotation != null && annotation.value() != null && !annotation.value().isEmpty()) {
				return annotation.value();
			}
			return field.getName();
		}

	}

}
