package io.modelcontextprotocol.client;

public class SseServerInstance extends McpServerInstance {
    
    private String url;

    public SseServerInstance() {
        super();
    }

    public SseServerInstance(String name, String url) {
        super(name);
        this.url = url;
    }
    
    public String getUrl() {
        return url;
    }
    
    public void setUrl(String url) {
        this.url = url;
    }
}
