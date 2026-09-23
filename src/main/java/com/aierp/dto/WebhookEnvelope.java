package com.aierp.dto;

import java.util.Map;

public record WebhookEnvelope(
    String eventId,
    String eventType,
    String source,
    String sourceAccountId,
    String sourceObjectId,
    Long sourceObjectVersion,
    String occurredAt,
    Map<String, Object> payload
) {}