/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal stdio MCP server that never answers the first {@code initialize} request, like
 * a server that is too slow to start, and answers every later one.
 */
public final class FirstInitializeIgnoringStdioServer {

	private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(\"[^\"]*\"|-?\\d+)");

	private FirstInitializeIgnoringStdioServer() {
	}

	public static void main(String[] args) throws IOException {
		BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
		PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
		boolean ignoredFirstInitialize = false;
		String line;
		while ((line = in.readLine()) != null) {
			if (!line.contains("\"initialize\"")) {
				continue;
			}
			if (!ignoredFirstInitialize) {
				ignoredFirstInitialize = true;
				continue;
			}
			Matcher id = ID.matcher(line);
			if (id.find()) {
				out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id.group(1)
						+ ",\"result\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
						+ "\"serverInfo\":{\"name\":\"test-server\",\"version\":\"1.0.0\"}}}");
			}
		}
	}

}
