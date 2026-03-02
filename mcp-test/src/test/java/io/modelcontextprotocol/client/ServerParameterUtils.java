package io.modelcontextprotocol.client;

import io.modelcontextprotocol.client.transport.ServerParameters;

public final class ServerParameterUtils {

	private ServerParameterUtils() {
	}

	public static ServerParameters createServerParameters() {
		if (System.getProperty("os.name").toLowerCase().contains("win")) {
			return ServerParameters.builder("cmd.exe")
				.args("/c", "npx.cmd", "-y", "@modelcontextprotocol/server-everything@2025.12.18", "stdio")
				.build();
		}
		return ServerParameters.builder("npx")
			.args("-y", "@modelcontextprotocol/server-everything@2025.12.18", "stdio")
			.build();
	}

}
