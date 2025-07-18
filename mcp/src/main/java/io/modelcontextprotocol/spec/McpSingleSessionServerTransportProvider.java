package io.modelcontextprotocol.spec;

public interface McpSingleSessionServerTransportProvider extends McpServerTransportProvider {
    /**
     * Sets the session factory that will be used to create sessions for new clients. An
     * implementation of the MCP server MUST call this method before any MCP interactions
     * take place.
     *
     * @param sessionFactory the session factory to be used for initiating client sessions
     */
    void setSessionFactory(McpServerSession.Factory sessionFactory);
}
