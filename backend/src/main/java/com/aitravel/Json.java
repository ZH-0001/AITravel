package com.aitravel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class Json {
    public final ObjectMapper mapper;
    public Json(ObjectMapper mapper) { this.mapper = mapper; }
    public String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("JSON serialization failed"); }
    }
    public JsonNode read(String value) {
        try { return mapper.readTree(value); }
        catch (JsonProcessingException e) { throw ApiError.bad("JSON 格式无效"); }
    }
}
