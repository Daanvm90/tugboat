package org.tugboat.publisher;

import io.netty.handler.codec.http.HttpResponseStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;
import org.tugboat.events.ArtifactDownloadedEvent;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.stream.Stream;

@ApplicationScoped
public class ArtifactDownloadConsumer {

    private static final Logger LOG = Logger.getLogger(ArtifactDownloadConsumer.class);
    private static final String MAVEN_CENTRAL_URL = "https://repo.maven.apache.org/maven2/";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Inject
    PublisherHarborOrasService harborService;

    @Incoming("artifact-downloads")
    public void processDownloadEvent(ArtifactDownloadedEvent event) {
        LOG.infof("NATS Event ontvangen: OCI push orchestratie gestart voor '%s'", event.filename());

        Path tempDir = null;
        try {
            // 1. Create a temporary workspace for the download
            tempDir = Files.createTempDirectory("tugboat-publisher-");
            String originalPath = String.format("%s/%s/%s/%s", event.groupId(), event.artifactId(), event.version(), event.filename());

            // 2. Fetch the artifact payload locally
            Path downloadedFile = downloadFromMavenCentral(originalPath, event.filename(), tempDir);

            // 3. Delegate to the ORAS service to handle the Push or Update
            String digest;
            if (event.isLayerMissing()) {
                LOG.infof("Artifact bestaat reeds. Nieuwe layer '%s' toevoegen aan bestaand manifest...", event.filename());
                digest = harborService.pushArtifactNewLayerToHarbor(event.ociReference(), downloadedFile, event.filename());
            } else {
                LOG.infof("Nieuw artifact. Volledige OCI push voor '%s'...", event.filename());
                digest = harborService.pushArtifactToHarbor(event.ociReference(), downloadedFile, event.artifactId(), event.filename());
            }

            LOG.infof("Succesvol OCI push afgerond! Artifact digest: %s", digest);

        } catch (Exception e) {
            LOG.errorf("Fout tijdens verwerken van OCI push voor %s: %s", event.filename(), e.getMessage());
        } finally {
            // Cleanup the temporary directory to prevent disk leaks
            if (tempDir != null) {
                cleanupTempDir(tempDir);
            }
        }
    }

    private Path downloadFromMavenCentral(String originalPath, String filename, Path tempDir) throws Exception {
        URI sourceUri = URI.create(MAVEN_CENTRAL_URL + originalPath);
        HttpRequest request = HttpRequest.newBuilder().uri(sourceUri).GET().build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != HttpResponseStatus.OK.code()) {
            throw new Exception("Artifact niet gevonden op Maven Central (HTTP " + response.statusCode() + ")");
        }

        Path tempFile = tempDir.resolve(filename);
        Files.copy(response.body(), tempFile, StandardCopyOption.REPLACE_EXISTING);
        return tempFile;
    }

    private void cleanupTempDir(Path tempDir) {
        try (Stream<Path> walk = Files.walk(tempDir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(java.io.File::delete);
        } catch (Exception e) {
            LOG.warnf("Kon tijdelijke map %s niet verwijderen", tempDir);
        }
    }
}