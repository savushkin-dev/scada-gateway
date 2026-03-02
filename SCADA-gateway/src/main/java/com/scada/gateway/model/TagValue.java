package com.scada.gateway.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TagValue {
    private String serverId;
    private String tagId;
    private String tagName;
    private Object value;
    private String dataType;
    private String quality;
    private Instant timestamp;
    private String unit;
}