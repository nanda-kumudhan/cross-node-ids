package dev.crossnode.ids;

import java.util.Map;

public record ObservedEvent(Event event, boolean fresh, boolean knownLoss, Map<String, Object> snapshot) { }
