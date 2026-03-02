package com.scada.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "opcua")
@Data
public class OpcUaConfig {
    private List<OpcUaServerConfig> servers;

    @Data
    public static class OpcUaServerConfig {
        private String id;
        private String name;
        private String endpoint;
        private String security;
        private String username;
        private String password;
        private boolean enabled;
        private List<TagConfig> tags;
    }

    @Data
    public static class TagConfig {
        private String nodeId;
        private String name;
        private String dataType;
        private long pollingRate;
        private boolean enabled;
        private String unit;
    }
}