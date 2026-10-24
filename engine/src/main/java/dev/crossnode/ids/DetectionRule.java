package dev.crossnode.ids;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public record DetectionRule(String id, String title, String description, String kind, String severity,
                            Map<String, Object> spec) {
    public long seconds(String field) { return ((Number) spec.get(field)).longValue(); }
    public List<String> prefixes() {
        return ((List<?>)spec.get("executable_prefixes")).stream().map(String.class::cast).toList();
    }

    public static List<DetectionRule> load(Path directory) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setCodePointLimit(128 * 1024);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        List<DetectionRule> rules = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(file -> file.getFileName().toString().endsWith(".yml")).sorted().toList()) {
                Object document = yaml.load(Files.readString(path));
                if (!(document instanceof Map<?, ?> map)) throw new IllegalArgumentException("Rule must be an object");
                if (!map.keySet().equals(Set.of("id", "title", "description", "kind", "severity", "enabled", "attack", "spec")))
                    throw new IllegalArgumentException("Missing or unknown rule fields: " + path);
                String id = required(map, "id");
                if (!id.matches("[a-z][a-z0-9-]+") || !path.getFileName().toString().equals(id + ".yml") || !ids.add(id))
                    throw new IllegalArgumentException("Invalid/duplicate rule ID: " + id);
                String kind = required(map, "kind");
                String severity = required(map, "severity");
                if (!Set.of("low", "medium", "high", "critical").contains(severity))
                    throw new IllegalArgumentException("Invalid severity");
                if (!(map.get("enabled") instanceof Boolean enabled) || !(map.get("spec") instanceof Map<?, ?>)
                        || !(map.get("attack") instanceof List<?> attack)
                        || attack.stream().anyMatch(item -> !(item instanceof String s) || !s.matches("T[0-9]{4}(\\.[0-9]{3})?")))
                    throw new IllegalArgumentException("Invalid enabled/spec/attack fields");
                if (!enabled) continue; // Future SSH sketches have no executable implementation.
                Map<String, Object> spec = new LinkedHashMap<>();
                ((Map<?, ?>)map.get("spec")).forEach((key, value) -> spec.put((String)key, value));
                if (kind.equals("single")) {
                    if (!spec.keySet().equals(Set.of("event_type", "executable_prefixes")) || !"exec".equals(spec.get("event_type"))
                            || !(spec.get("executable_prefixes") instanceof List<?> prefixes) || prefixes.isEmpty()
                            || prefixes.stream().anyMatch(prefix -> !(prefix instanceof String p) || !p.matches("/.+/")))
                        throw new IllegalArgumentException("Invalid single-event specification");
                } else if (kind.equals("connection")) {
                    if (!spec.keySet().equals(Set.of("protocol", "address_family", "match", "within_seconds", "grace_seconds"))
                            || !"tcp".equals(spec.get("protocol")) || !"ipv4".equals(spec.get("address_family"))
                            || !"tcp_4tuple".equals(spec.get("match")))
                        throw new IllegalArgumentException("Invalid connection specification");
                    integer(spec, "within_seconds", 1, 60);
                    integer(spec, "grace_seconds", 0, 600);
                } else throw new IllegalArgumentException("Enabled unsupported rule kind: " + kind);
                rules.add(new DetectionRule(id, required(map, "title"), required(map, "description"), kind, severity, spec));
            }
        } catch (java.io.IOException exception) { throw new IllegalArgumentException("Cannot read rules: " + directory, exception); }
        if (rules.isEmpty()) throw new IllegalArgumentException("No enabled supported rules");
        return List.copyOf(rules);
    }

    private static String required(Map<?, ?> map, String field) {
        if (!(map.get(field) instanceof String value) || value.isBlank()) throw new IllegalArgumentException("Missing " + field);
        return value;
    }

    private static void integer(Map<String, Object> spec, String field, int min, int max) {
        Object value = spec.get(field);
        if (!(value instanceof Integer || value instanceof Long) || ((Number)value).longValue() < min
                || ((Number)value).longValue() > max) throw new IllegalArgumentException("Invalid " + field);
    }
}
