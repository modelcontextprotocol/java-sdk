package io.modelcontextprotocol.client;

public class McpServerInstance {

    protected String name;

    public McpServerInstance() {
    }

    public McpServerInstance(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }

}
