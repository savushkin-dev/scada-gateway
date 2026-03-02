package com.scada.gateway.opcua;

import com.scada.gateway.config.OpcUaConfig;
import com.scada.gateway.model.TagValue;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.api.config.OpcUaClientConfig;
import org.eclipse.milo.opcua.stack.client.DiscoveryClient;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class OpcUaClientService {
    
    private final OpcUaConfig opcUaConfig;
    private OpcUaClient client;
    private ExecutorService executorService;
    private volatile boolean running = false;
    
    public OpcUaClientService(OpcUaConfig opcUaConfig) {
        this.opcUaConfig = opcUaConfig;
        this.executorService = Executors.newSingleThreadExecutor();
    }
    
    @PostConstruct
    public void init() {
        OpcUaConfig.OpcUaServerConfig serverConfig = opcUaConfig.getServers().stream()
                .filter(OpcUaConfig.OpcUaServerConfig::isEnabled)
                .findFirst()
                .orElse(null);
        
        if (serverConfig == null) {
            log.warn("No enabled OPC UA server found");
            return;
        }
        
        connectToServer(serverConfig);
    }
    
    private void connectToServer(OpcUaConfig.OpcUaServerConfig serverConfig) {
        try {
            log.info("Connecting to OPC UA server: {} at {}", 
                    serverConfig.getName(), serverConfig.getEndpoint());
            
            List<EndpointDescription> endpoints = DiscoveryClient.getEndpoints(
                    serverConfig.getEndpoint()).get();
            
            EndpointDescription endpoint = endpoints.stream()
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("No endpoints found"));
            
            OpcUaClientConfig config = OpcUaClientConfig.builder()
                    .setApplicationName(LocalizedText.english("SCADA Gateway"))
                    .setApplicationUri("urn:scada:gateway")
                    .setEndpoint(endpoint)
                    .build();
            
            client = OpcUaClient.create(config);
            client.connect().get();
            
            log.info("✅ Connected to OPC UA server: {}", serverConfig.getName());
            
            running = true;
            startPolling(serverConfig);
            
        } catch (Exception e) {
            log.error("Failed to connect to OPC UA server: {}", e.getMessage());
        }
    }
    
    private void startPolling(OpcUaConfig.OpcUaServerConfig serverConfig) {
        executorService.submit(() -> {
            while (running) {
                try {
                    for (OpcUaConfig.TagConfig tagConfig : serverConfig.getTags()) {
                        if (!tagConfig.isEnabled()) continue;
                        
                        readTag(serverConfig.getId(), tagConfig);
                        Thread.sleep(tagConfig.getPollingRate());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Error in polling loop: {}", e.getMessage());
                }
            }
        });
    }
    
    private void readTag(String serverId, OpcUaConfig.TagConfig tagConfig) {
        try {
            NodeId nodeId = NodeId.parse(tagConfig.getNodeId());
            DataValue dataValue = client.readValue(0, TimestampsToReturn.Both, nodeId).get();
            
            TagValue tagValue = TagValue.builder()
                    .serverId(serverId)
                    .tagId(tagConfig.getNodeId())
                    .tagName(tagConfig.getName())
                    .value(extractValue(dataValue.getValue()))
                    .dataType(tagConfig.getDataType())
                    .quality(dataValue.getStatusCode().isGood() ? "GOOD" : "BAD")
                    .timestamp(Instant.now())
                    .unit(tagConfig.getUnit())
                    .build();
            
            log.info("📊 {} = {} {}", 
                    tagConfig.getName(), 
                    tagValue.getValue(),
                    tagConfig.getUnit() != null ? tagConfig.getUnit() : "");
            
        } catch (Exception e) {
            log.error("Error reading tag {}: {}", tagConfig.getNodeId(), e.getMessage());
        }
    }
    
    private Object extractValue(Variant variant) {
        if (variant == null || variant.isNull()) {
            return null;
        }
        
        Object value = variant.getValue();
        if (value instanceof UInteger) {
            return ((UInteger) value).longValue();
        }
        return value;
    }
    
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down OPC UA client...");
        running = false;
        
        if (executorService != null) {
            executorService.shutdown();
            try {
                executorService.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        if (client != null) {
            try {
                client.disconnect().get();
                log.info("Disconnected from OPC UA server");
            } catch (Exception e) {
                log.error("Error disconnecting: {}", e.getMessage());
            }
        }
    }
}
