package org.tugboat.gateway;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestResponse;
import org.tugboat.ArtifactStatus;
import org.tugboat.events.ArtifactDownloadedEvent;
import org.tugboat.gateway.service.HarborOrasService;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Path("/repository/maven")
public class TugboatResource {

    private static final Logger LOG = Logger.getLogger(TugboatResource.class);
    private static final String MAVEN_CENTRAL_URL = "https://repo.maven.apache.org/maven2/";

    @ConfigProperty(name = "tugboat.harbor.url")
    String harborUrl;

    @ConfigProperty(name = "tugboat.harbor.project")
    String harborProject;

    @Inject
    HarborOrasService harborService;

    @Inject
    @Channel("artifact-downloads")
    Emitter<ArtifactDownloadedEvent> downloadEmitter;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @GET
    @Path("/{groupId: .+}/{artifactId}/{version}/{filename}")
    public Uni<RestResponse<?>> fetchArtifact(
            @PathParam("groupId") String groupId,
            @PathParam("artifactId") String artifactId,
            @PathParam("version") String version,
            @PathParam("filename") String filename) {

        String originalPath = String.format("%s/%s/%s/%s", groupId, artifactId, version, filename);
        LOG.infof("Maven request ontvangen voor: %s", originalPath);

        String ociReference = String.format("%s/%s/%s/%s:%s", harborUrl, harborProject, groupId.replace("/", "."), artifactId, version).toLowerCase();

        return Uni.createFrom().item(() -> {
            try {
                // 1. Fast metadata check via OCI Manifest
                HarborOrasService.ArtifactEntry cachedFile = harborService.checkArtifactInHarbor(ociReference, filename);

                // 2. Cache Hit: Stream directly from Harbor to the Client
                if (ArtifactStatus.OK.equals(cachedFile.status)) {
                    LOG.infof("Streaming artifact %s directly from Harbor...", filename);
                    InputStream harborStream = harborService.streamBlobFromHarbor(ociReference, cachedFile.blobDigest);

                    return RestResponse.ResponseBuilder
                            .ok(harborStream)
                            .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                            .header("X-Tugboat-Cache", "HIT")
                            .build();
                }

                // 3. Cache Miss: Stream directly from Maven Central to the Client
                LOG.infof("Artifact niet gevonden in Harbor. Streamen van Maven Central...");

                URI sourceUri = URI.create(MAVEN_CENTRAL_URL + originalPath);
                HttpRequest request = HttpRequest.newBuilder().uri(sourceUri).GET().build();
                HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200) {
                    LOG.warnf("Artifact niet gevonden op Maven Central (HTTP %d)", response.statusCode());
                    return RestResponse.status(RestResponse.Status.NOT_FOUND);
                }

                // 4. Emit event for the publisher module to handle the OCI Push asynchronously
                boolean isMissingLayer = ArtifactStatus.LAYER_MISSING.equals(cachedFile.status);
                ArtifactDownloadedEvent event = new ArtifactDownloadedEvent(groupId, artifactId, version, filename, ociReference, isMissingLayer);
                downloadEmitter.send(event);
                LOG.infof("Download event gedistribueerd naar NATS voor bestand: %s", filename);

                // 5. Return InputStream directly for streaming chunks to the client
                return RestResponse.ResponseBuilder
                        .ok(response.body())
                        .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                        .header("X-Tugboat-Cache", "MISS")
                        .build();

            } catch (Exception e) {
                LOG.error("Fout tijdens ophalen en proxien van artifact", e);
                return RestResponse.status(RestResponse.Status.INTERNAL_SERVER_ERROR);
            }
        });
    }
}