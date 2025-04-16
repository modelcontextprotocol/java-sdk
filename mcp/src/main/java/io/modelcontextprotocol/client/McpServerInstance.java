package io.modelcontextprotocol.client;

import java.util.HashMap;

public class McpServerInstance {
        private String name;
        private String url;

        private HashMap<String, String> headers;

        public String getName() {
            return name;
        }
        
        public void setName(String name) {
            this.name = name;
        }
        
        public String getUrl() {
            return url;
        }
        
        public void setUrl(String url) {
            this.url = url;
        }

        public HashMap<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(HashMap<String, String> headers) {
            this.headers = headers;
        }

}
