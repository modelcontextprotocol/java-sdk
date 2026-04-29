/*
 * Copyright 2026-2026 the original author or authors.
 */
package io.modelcontextprotocol.json.schema.jackson3;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.Error;
import com.networknt.schema.dialect.Dialects;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Default implementation of the {@link JsonSchemaValidator} interface. This class
 * provides methods to validate structured content against a JSON schema. It uses the
 * NetworkNT JSON Schema Validator library for validation.
 *
 * @author Filip Hrisafov
 */
public class DefaultJsonSchemaValidator implements JsonSchemaValidator {

	private static final Logger logger = LoggerFactory.getLogger(DefaultJsonSchemaValidator.class);

	/**
	 * Default maximum number of cached JSON schemas.
	 */
	public static final int DEFAULT_MAX_CACHE_SIZE = 1024;

	private final JsonMapper jsonMapper;

	private final SchemaRegistry schemaFactory;

	private final Map<String, Schema> schemaCache;

	public DefaultJsonSchemaValidator() {
		this(JsonMapper.shared(), DEFAULT_MAX_CACHE_SIZE);
	}

	public DefaultJsonSchemaValidator(JsonMapper jsonMapper) {
		this(jsonMapper, DEFAULT_MAX_CACHE_SIZE);
	}

	/**
	 * Creates a new {@link DefaultJsonSchemaValidator} with the given {@link JsonMapper}
	 * and maximum cache size.
	 * @param jsonMapper the JSON mapper to use for JSON processing
	 * @param maxCacheSize the maximum number of schemas to cache (LRU)
	 */
	public DefaultJsonSchemaValidator(JsonMapper jsonMapper, int maxCacheSize) {
		this.jsonMapper = jsonMapper;
		this.schemaFactory = SchemaRegistry.withDialect(Dialects.getDraft202012());
		this.schemaCache = Collections.synchronizedMap(new LinkedHashMap<String, Schema>(maxCacheSize, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, Schema> eldest) {
				return size() > maxCacheSize;
			}
		});
	}

	@Override
	public ValidationResponse validate(Map<String, Object> schema, Object structuredContent) {

		if (schema == null) {
			throw new IllegalArgumentException("Schema must not be null");
		}
		if (structuredContent == null) {
			throw new IllegalArgumentException("Structured content must not be null");
		}

		try {

			JsonNode jsonStructuredOutput = (structuredContent instanceof String)
					? this.jsonMapper.readTree((String) structuredContent)
					: this.jsonMapper.valueToTree(structuredContent);

			List<Error> validationResult = this.getOrCreateJsonSchema(schema).validate(jsonStructuredOutput);

			// Check if validation passed
			if (!validationResult.isEmpty()) {
				return ValidationResponse
					.asInvalid("Validation failed: structuredContent does not match tool outputSchema. "
							+ "Validation errors: " + validationResult);
			}

			return ValidationResponse.asValid(jsonStructuredOutput.toString());

		}
		catch (JacksonException e) {
			logger.error("Failed to validate CallToolResult: Error parsing schema: {}", e);
			return ValidationResponse.asInvalid("Error parsing tool JSON Schema: " + e.getMessage());
		}
		catch (Exception e) {
			logger.error("Failed to validate CallToolResult: Unexpected error: {}", e);
			return ValidationResponse.asInvalid("Unexpected validation error: " + e.getMessage());
		}
	}

	/**
	 * Gets a cached Schema or creates and caches a new one.
	 * @param schema the schema map to convert
	 * @return the compiled Schema
	 * @throws JacksonException if schema processing fails
	 */
	private Schema getOrCreateJsonSchema(Map<String, Object> schema) throws JacksonException {
		// Generate cache key based on schema content
		String cacheKey = this.generateCacheKey(schema);

		// Try to get from cache first
		Schema cachedSchema = this.schemaCache.get(cacheKey);
		if (cachedSchema != null) {
			return cachedSchema;
		}

		// Create new schema if not in cache
		Schema newSchema = this.createJsonSchema(schema);

		// Cache the schema
		Schema existingSchema = this.schemaCache.putIfAbsent(cacheKey, newSchema);
		return existingSchema != null ? existingSchema : newSchema;
	}

	/**
	 * Creates a new Schema from the given schema map.
	 * @param schema the schema map
	 * @return the compiled Schema
	 * @throws JacksonException if schema processing fails
	 */
	private Schema createJsonSchema(Map<String, Object> schema) throws JacksonException {
		// Convert schema map directly to JsonNode (more efficient than string
		// serialization)
		JsonNode schemaNode = this.jsonMapper.valueToTree(schema);

		// Handle case where ObjectMapper might return null (e.g., in mocked scenarios)
		if (schemaNode == null) {
			throw new JacksonException("Failed to convert schema to JsonNode") {
			};
		}

		return this.schemaFactory.getSchema(schemaNode);
	}

	/**
	 * Generates a cache key for the given schema map.
	 * @param schema the schema map
	 * @return a cache key string
	 */
	protected String generateCacheKey(Map<String, Object> schema) {
		if (schema.containsKey("$id")) {
			// Use the (optional) "$id" field as the cache key if present
			return "" + schema.get("$id");
		}
		try {
			// Use the stable JSON representation as the cache key to avoid hash
			// collisions and map order issues
			return this.jsonMapper.writeValueAsString(schema);
		}
		catch (JacksonException e) {
			// Fall back to schema's hash code if serialization fails
			return String.valueOf(schema.hashCode());
		}
	}

	/**
	 * Clears the schema cache. Useful for testing or memory management.
	 */
	public void clearCache() {
		this.schemaCache.clear();
	}

	/**
	 * Returns the current size of the schema cache.
	 * @return the number of cached schemas
	 */
	public int getCacheSize() {
		return this.schemaCache.size();
	}

}
