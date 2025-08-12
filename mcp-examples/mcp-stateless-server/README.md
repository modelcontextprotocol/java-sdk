# Example Stateless MCP HTTP Server

This an example of sync stateless MCP HTTP server. It shows how to set up
some simple tools, prompts, and resources as well as accessing the HTTP request
from handlers.

The actual server implementation is Jetty but any servlet container could be used.

- See [Tools](src/main/java/io/modelcontextprotocol/examples/stateless/server/Tools.java) for the test tools. 
The tool `requestHeader` shows how to access the HTTP request headers.
- See [Prompts](src/main/java/io/modelcontextprotocol/examples/stateless/server/Prompts.java) for the test prompt.
- See [Resources](src/main/java/io/modelcontextprotocol/examples/stateless/server/Resources.java) for the test resource.

## How to run

1. Build the project:

```shell
./mvnw clean install
```

2. Run this server example:

```shell
./mvnw -pl :mcp-stateless-server exec:java -Dexec.mainClass="io.modelcontextprotocol.examples.stateless.server.Main"
```

3. In a separate terminal, run the MCP inspector:

```shell
npx @modelcontextprotocol/inspector
```

A browser should open with the MCP Inspector tool:
- Set the "Transport Type" to "Streamable HTTP"
- Change the URL to `http://localhost:8080/mcp`
- Click "Connect"

Try out the tools, prompts, and resources in the MCP Inspector.
