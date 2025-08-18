/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.util;

import reactor.util.annotation.Nullable;

import java.net.URI;
import java.util.Collection;
import java.util.Map;

/**
 * Miscellaneous utility methods.
 *
 * @author Christian Tzolov
 */

public final class Utils {

	/**
	 * Check whether the given {@code String} contains actual <em>text</em>.
	 * <p>
	 * More specifically, this method returns {@code true} if the {@code String} is not
	 * {@code null}, its length is greater than 0, and it contains at least one
	 * non-whitespace character.
	 * @param str the {@code String} to check (may be {@code null})
	 * @return {@code true} if the {@code String} is not {@code null}, its length is
	 * greater than 0, and it does not contain whitespace only
	 * @see Character#isWhitespace
	 */
	public static boolean hasText(@Nullable String str) {
		return (str != null && !str.isBlank());
	}

	/**
	 * Return {@code true} if the supplied Collection is {@code null} or empty. Otherwise,
	 * return {@code false}.
	 * @param collection the Collection to check
	 * @return whether the given Collection is empty
	 */
	public static boolean isEmpty(@Nullable Collection<?> collection) {
		return (collection == null || collection.isEmpty());
	}

	/**
	 * Return {@code true} if the supplied Map is {@code null} or empty. Otherwise, return
	 * {@code false}.
	 * @param map the Map to check
	 * @return whether the given Map is empty
	 */
	public static boolean isEmpty(@Nullable Map<?, ?> map) {
		return (map == null || map.isEmpty());
	}

	/**
	 * Resolves the given endpoint URL against the base URL.
	 * <ul>
	 * <li>If the endpoint URL is relative, it will be resolved against the base URL.</li>
	 * <li>If the endpoint URL is absolute, it will be validated to ensure it matches the
	 * base URL's scheme, authority, and path prefix.</li>
	 * <li>If validation fails for an absolute URL, an {@link IllegalArgumentException} is
	 * thrown.</li>
	 * </ul>
	 * @param baseUrl The base URL (must be absolute)
	 * @param endpointUrl The endpoint URL (can be relative or absolute)
	 * @return The resolved endpoint URI
	 * @throws IllegalArgumentException If the absolute endpoint URL does not match the
	 * base URL or URI is malformed
	 */
	public static URI resolveUri(URI baseUrl, String endpointUrl) {
		if (!Utils.hasText(endpointUrl)) {
			return baseUrl;
		}
		URI endpointUri = URI.create(endpointUrl);
		if (endpointUri.isAbsolute() && !isUnderBaseUri(baseUrl, endpointUri)) {
			throw new IllegalArgumentException("Absolute endpoint URL does not match the base URL.");
		}
		else {
			return baseUrl.resolve(endpointUri);
		}
	}

	/**
	 * Checks if the given absolute endpoint URI falls under the base URI. It validates
	 * the scheme, authority (host and port), and ensures that the base path is a prefix
	 * of the endpoint path.
	 * @param baseUri The base URI
	 * @param endpointUri The endpoint URI to check
	 * @return true if endpointUri is within baseUri's hierarchy, false otherwise
	 */
	private static boolean isUnderBaseUri(URI baseUri, URI endpointUri) {
		if (!baseUri.getScheme().equals(endpointUri.getScheme())
				|| !baseUri.getAuthority().equals(endpointUri.getAuthority())) {
			return false;
		}

		URI normalizedBase = baseUri.normalize();
		URI normalizedEndpoint = endpointUri.normalize();

		String basePath = normalizedBase.getPath();
		String endpointPath = normalizedEndpoint.getPath();

		if (basePath.endsWith("/")) {
			basePath = basePath.substring(0, basePath.length() - 1);
		}
		return endpointPath.startsWith(basePath);
	}

}
