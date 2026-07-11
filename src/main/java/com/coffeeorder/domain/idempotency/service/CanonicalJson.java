package com.coffeeorder.domain.idempotency.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class CanonicalJson {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private CanonicalJson() {}

    static String normalize(String json) {
        return write(read(json));
    }

    static JsonNode read(String json) {
        try {
            if (json == null) {
                throw new IllegalArgumentException("JSON must not be null");
            }
            return OBJECT_MAPPER.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("value must be valid JSON", exception);
        }
    }

    static String write(JsonNode json) {
        try {
            return OBJECT_MAPPER.writeValueAsString(sort(json));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("JSON cannot be serialized", exception);
        }
    }

    private static JsonNode sort(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = OBJECT_MAPPER.createObjectNode();
            List<Map.Entry<String, JsonNode>> fields = new ArrayList<>(node.properties());
            fields.sort(Comparator.comparing(Map.Entry::getKey));
            fields.forEach(entry -> sorted.set(entry.getKey(), sort(entry.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode sorted = OBJECT_MAPPER.createArrayNode();
            node.forEach(element -> sorted.add(sort(element)));
            return sorted;
        }
        return node;
    }
}
