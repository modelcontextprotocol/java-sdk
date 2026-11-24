package io.modelcontextprotocol.client;

import java.util.List;
import java.util.Map;

public class StdioServerInstance extends McpServerInstance {
    
    private String command;
	
    private List<String> args;
    
    private Map<String, String> env;

    public StdioServerInstance() {
        super();
    }

    public StdioServerInstance(String name, String command, List<String> args, Map<String, String> env) {
        super(name);
        this.command = command;
        this.args = args;
        this.env = env;
    }

    public String getCommand() {
        return command;
    }   
    
    public void setCommand(String command) {
        this.command = command;
    }

    public List<String> getArgs() {
        return args;
    }

    public Map<String, String> getEnv() {   
        return env;
    }   
}
