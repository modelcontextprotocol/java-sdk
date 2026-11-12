/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Framing of the stdio transport of the MCP {@code 2026-07-28} revision, shared by the
 * client and server transports: one JSON-RPC message per line.
 *
 * @author Dariusz Jędrzejczyk
 * @see <a href=
 * "https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/stdio">stdio</a>
 */
public final class McpStdio {

	private static final Logger logger = LoggerFactory.getLogger(McpStdio.class);

	/** The default maximum number of characters in one inbound message. */
	public static final int DEFAULT_INPUT_MAX_SIZE = 16 * 1024 * 1024;

	private McpStdio() {
	}

	/**
	 * Reads one line. {@code \n}, {@code \r} and {@code \r\n} all end a line. A line
	 * longer than {@code maxSize} characters is read to its end, logged and returned as
	 * an empty line, so a peer that never sends a line break can't exhaust the heap.
	 * @param reader the stream to read
	 * @param maxSize the maximum number of characters in one line
	 * @return the line, an empty string for a blank or dropped line, or {@code null} at
	 * the end of the stream
	 * @throws IOException if reading fails
	 */
	public static String readLine(Reader reader, int maxSize) throws IOException {
		StringBuilder line = new StringBuilder();
		boolean tooLong = false;
		int c;
		// Peeking past a \r would block until the peer's next message, so \r\n reads as
		// a line followed by a blank one.
		while ((c = reader.read()) != -1 && c != '\n' && c != '\r') {
			if (tooLong) {
				continue;
			}
			if (line.length() == maxSize) {
				tooLong = true;
				line = new StringBuilder();
				continue;
			}
			line.append((char) c);
		}
		if (tooLong) {
			logger.warn("Dropping inbound message longer than {} characters", maxSize);
			return "";
		}
		return c == -1 && line.isEmpty() ? null : line.toString();
	}

	/**
	 * Encodes a message as one UTF-8 line, terminated by {@code \n}, to be written with a
	 * single write so that nothing else writing to the stream lands inside it.
	 * @param jsonMapper the mapper
	 * @param message the message
	 * @return the bytes of the line
	 * @throws IOException if the message can't be serialized
	 */
	public static byte[] encodeLine(McpJsonMapper jsonMapper, JSONRPCMessage message) throws IOException {
		// JSON escapes line breaks inside strings, so raw ones are only whitespace, e.g.
		// from a pretty-printing mapper, and would split the message.
		String json = jsonMapper.writeValueAsString(message).replace("\n", "").replace("\r", "");
		return (json + '\n').getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * A key under which a request id, or a progress token, can be looked up whatever
	 * numeric type a mapper read it as: {@code 1}, {@code 1L} and {@code 1.0} have the
	 * same key, while {@code 1.5} and {@code "1"} stay distinct.
	 * @param id a request id or progress token
	 * @return its key
	 */
	public static Object idKey(Object id) {
		return id instanceof Number number && number.doubleValue() == number.longValue() ? (Object) number.longValue()
				: id;
	}

}
