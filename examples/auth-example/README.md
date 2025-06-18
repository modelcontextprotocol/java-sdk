# MCP Authentication Example

This example demonstrates how to implement OAuth 2.0 authentication with the Model Context Protocol (MCP) Java SDK.

## Overview

The example consists of:

1. A simple MCP server with OAuth authentication
2. A simple MCP client that authenticates using OAuth
3. A tool that requires authentication to access

## Running the Example

### 1. Build the Project

```bash
cd examples/auth-example
mvn clean package
```

### 2. Run the Server

In one terminal window:

```bash
cd examples/auth-example
mvn exec:java -Dexec.mainClass="io.modelcontextprotocol.examples.auth.server.SimpleAuthServer"
```

### 3. Run the Client

In another terminal window:

```bash
cd examples/auth-example
mvn exec:java -Dexec.mainClass="io.modelcontextprotocol.examples.auth.client.SimpleAuthClient"
```

## Using the Client

Once the client is running, you can use these commands:
- `list` - List available tools
- `call get_user_profile` - Call the user profile tool
- `quit` - Exit the client

## Authentication Flow

1. Client initiates the OAuth flow
2. Server redirects to the authorization page
3. User approves the authorization
4. Server redirects back to the client with an authorization code
5. Client exchanges the code for access and refresh tokens
6. Client uses the access token for authenticated MCP requests

## Implementation Details

### Server

The server implements the `OAuthAuthorizationServerProvider` interface to provide OAuth authentication. It uses in-memory storage for clients, tokens, and authorization codes.

### Client

The client uses the `OAuthClientProvider` class to handle OAuth authentication. It opens a browser for the authorization flow and starts a local server to receive the OAuth callback.

## Code Structure

- `SimpleAuthServer.java` - Server implementation
- `SimpleAuthClient.java` - Client implementation
- `Constants.java` - Shared constants