package org.tugboat.events;

public record ArtifactPushedEvent(
        String ociReference,
        String digest
) {}