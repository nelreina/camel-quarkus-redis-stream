package tech.nelreina.camel.quarkus.redis.stream.util;

import java.util.HashMap;
import java.util.Map;
import tech.nelreina.camel.quarkus.redis.stream.model.EventData;

public class HeaderFilter {
    
    private final Map<String, String> filterCriteria;
    
    public HeaderFilter(String headerFilters) {
        this.filterCriteria = parseHeaderFilters(headerFilters);
    }
    
    public HeaderFilter(String globalFilters, String routeFilters) {
        // Start with global filters
        this.filterCriteria = parseHeaderFilters(globalFilters);
        
        // Override/add route-specific filters
        Map<String, String> routeFilterMap = parseHeaderFilters(routeFilters);
        this.filterCriteria.putAll(routeFilterMap);
    }
    
    private Map<String, String> parseHeaderFilters(String headerFilters) {
        Map<String, String> filters = new HashMap<>();
        
        if (headerFilters == null || headerFilters.trim().isEmpty()) {
            return filters;
        }
        
        String[] pairs = headerFilters.split(",");
        for (String pair : pairs) {
            String trimmedPair = pair.trim();
            if (trimmedPair.isEmpty()) {
                continue;
            }
            
            String[] keyValue = trimmedPair.split("=", 2);
            if (keyValue.length == 2) {
                String key = keyValue[0].trim();
                String value = keyValue[1].trim();
                if (!key.isEmpty() && !value.isEmpty()) {
                    filters.put(key, value);
                }
            }
        }
        
        return filters;
    }
    
    public boolean matches(EventData eventData) {
        if (filterCriteria.isEmpty()) {
            return true;
        }
        
        Map<String, Object> headers = eventData.getHeaders();
        if (headers == null || headers.isEmpty()) {
            return false;
        }
        
        for (Map.Entry<String, String> filter : filterCriteria.entrySet()) {
            Object headerValue = headers.get(filter.getKey());
            if (headerValue == null || !filter.getValue().equals(String.valueOf(headerValue))) {
                return false;
            }
        }
        
        return true;
    }
    
    public Map<String, String> getFilterCriteria() {
        return new HashMap<>(filterCriteria);
    }
    
    public boolean isEmpty() {
        return filterCriteria.isEmpty();
    }
    
    /**
     * Merges global and route filters, with route filters taking precedence
     * @param globalFilters Global filter string (can be null)
     * @param routeFilters Route-specific filter string (can be null)
     * @return Merged filter string
     */
    public static String mergeFilters(String globalFilters, String routeFilters) {
        if ((globalFilters == null || globalFilters.trim().isEmpty()) && 
            (routeFilters == null || routeFilters.trim().isEmpty())) {
            return "";
        }
        
        if (globalFilters == null || globalFilters.trim().isEmpty()) {
            return routeFilters;
        }
        
        if (routeFilters == null || routeFilters.trim().isEmpty()) {
            return globalFilters;
        }
        
        // Both exist - merge them
        HeaderFilter merged = new HeaderFilter(globalFilters, routeFilters);
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : merged.getFilterCriteria().entrySet()) {
            if (result.length() > 0) {
                result.append(",");
            }
            result.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return result.toString();
    }
}