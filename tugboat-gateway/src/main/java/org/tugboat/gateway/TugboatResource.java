package org.tugboat.gateway;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestResponse;
import org.tugboat.ArtifactStatus;
import org.tugboat.exceptions.ResourceNotFoundException;
import org.tugboat.gateway.service.HarborOrasService;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;

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

        String ociReference = String.format("%s/%s/%s/%s:%s", harborUrl, harborProject, groupId.replace("/", "."), artifactId, version);
        // harbor.local/maven-proxy/net.java.dev.jna/jna:5.8.0

        return Uni.createFrom().item(() -> {
            try {
                java.nio.file.Path tempFile;


                // Check if available in Harbor (cache hit)
                 HarborOrasService.ArtifactEntry cachedFile = harborService.pullArtifactFromHarbor(ociReference, filename);
                 if (ArtifactStatus.OK.equals(cachedFile.status)) {
                     return RestResponse.ResponseBuilder
                             .ok(cachedFile.file)
                             .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                             .build();
                 }

                 // Cache miss
                LOG.infof("Artifact niet gevonden in Harbor. Downloaden van Maven Central...");
                 try {
                     tempFile = getArtifactFromMavenCentral(originalPath, filename);
                 } catch (ResourceNotFoundException rnfe) {
                     return RestResponse.status(RestResponse.Status.NOT_FOUND);
                 }


                 String digest;
                // Distribute to Harbor as OCI-artifact
                if (ArtifactStatus.LAYER_MISSING.equals(cachedFile.status)) {
                    digest  = harborService.pushArtifactNewLayerToHarbor(ociReference, tempFile, artifactId, filename, cachedFile);
                } else {
                    digest = harborService.pushArtifactToHarbor(ociReference, tempFile, artifactId, filename);
                }

                LOG.infof("Succesvol vertaald en opgeslagen in Harbor met digest: %s", digest);

                return RestResponse.ResponseBuilder
                        .ok(tempFile.toFile())
                        .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                        .header("X-Tugboat-Oci-Digest", digest) // Leuke bonus: stuur de OCI digest mee in de headers
                        .build();

            } catch (Exception e) {
                LOG.error("Fout tijdens ophalen en proxien van artifact", e);
                return RestResponse.status(RestResponse.Status.INTERNAL_SERVER_ERROR);
            }
        });
    }

    private java.nio.file.Path getArtifactFromMavenCentral(String originalPath, String filename) throws ResourceNotFoundException, IOException, InterruptedException {
        URI sourceUri = URI.create(MAVEN_CENTRAL_URL + originalPath);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(sourceUri)
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            LOG.warnf("Artifact niet gevonden op Maven Central (HTTP %d)", response.statusCode());
            throw new ResourceNotFoundException("Artifact niet gevonden op Maven Central");
        }

        java.nio.file.Path tempDirectory = Files.createTempDirectory("tugboat-cache-");
        java.nio.file.Path tempFile = Files.createFile(tempDirectory.resolve(filename));
        Files.copy(response.body(), tempFile, StandardCopyOption.REPLACE_EXISTING);
        return tempFile;
    }
}