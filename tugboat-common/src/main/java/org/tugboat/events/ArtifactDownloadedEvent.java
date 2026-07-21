package org.tugboat.events;

public record ArtifactDownloadedEvent(
        String groupId,
        String artifactId,
        String version,
        String filename,
        String ociReference,
        boolean isLayerMissing
) {}