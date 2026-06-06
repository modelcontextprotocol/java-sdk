package io.modelcontextprotocol.spec;

import java.util.Comparator;
import java.util.List;

public interface ProtocolVersions {

	/**
	 * MCP protocol version for 2024-11-05.
	 * https://modelcontextprotocol.io/specification/2024-11-05
	 */
	String MCP_2024_11_05 = "2024-11-05";

	/**
	 * MCP protocol version for 2025-03-26.
	 * https://modelcontextprotocol.io/specification/2025-03-26
	 */
	String MCP_2025_03_26 = "2025-03-26";

	/**
	 * MCP protocol version for 2025-06-18.
	 * https://modelcontextprotocol.io/specification/2025-06-18
	 */
	String MCP_2025_06_18 = "2025-06-18";

	/**
	 * MCP protocol version for 2025-11-25.
	 * https://modelcontextprotocol.io/specification/2025-11-25
	 */
	String MCP_2025_11_25 = "2025-11-25";

	List<String> ALL = List.of(MCP_2024_11_05, MCP_2025_03_26, MCP_2025_06_18, MCP_2025_11_25);

	String LATEST = ALL.stream().max(Comparator.naturalOrder()).orElseThrow();

}
