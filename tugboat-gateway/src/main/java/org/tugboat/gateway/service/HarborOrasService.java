package org.tugboat.gateway.service;

import io.micrometer.common.util.StringUtils;
import jakarta.enterprise.context.ApplicationScoped;
import land.oras.*;
import land.oras.utils.Const;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.tugboat.ArtifactStatus;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@ApplicationScoped
public class HarborOrasService {
    public static class ArtifactEntry {

        public File file;
        public ArtifactStatus status;
        public Manifest manifest;
        public Path tempDir;
        public ArtifactEntry(File file, ArtifactStatus status, Manifest manifest, Path tempDir) {
            this.file = file;
            this.status = status;
            this.manifest = manifest;
            this.tempDir = tempDir;
        }
    }

    private static final Logger LOG = Logger.getLogger(HarborOrasService.class);

    @ConfigProperty(name = "tugboat.harbor.url")
    String harborUrl;

    @ConfigProperty(name = "tugboat.harbor.project")
    String harborProject;

    @ConfigProperty(name = "tugboat.harbor.username")
    String harborUsername;

    @ConfigProperty(name = "tugboat.harbor.password")
    String harborPassword;

    private String determineMediaType(String fileExtension) {
        switch (fileExtension) {
            case "pom":
                return "application/vnd.maven.pom+xml";
            case "jar":
                return "application/java-archive";
            case "sha1":
                return "application/vnd.maven.sha1";
            case "md5":
                return "application/vnd.maven.md5";
            case "xml":
                return "application/xml";
            default:
                return "application/octet-stream";
        }
    }

    /**
     * Responsible for transmitting the Maven dependency (like jna-5.8.0-jpms.jar)
     * as an OCI artifact to Harbor.
     *
     * @param ociReference The OCI tag (e.g., "net.java.dev.jna:5.8.0")
     * @param artifactPath The path to the locally (temporarily) stored JAR file
     * @param filename The original filename (e.g., "jna-5.8.0-jpms.jar")
     * @return The resulting OCI Manifest digest
     */
    public String pushArtifactToHarbor(String ociReference, Path artifactPath, String artifactId, String filename) {

        // 1. Constructing the full OCI reference
        // Example: "harbor.yourdomain.com/maven-proxy/net.java.dev.jna:5.8.0"
        ContainerRef ref = ContainerRef.parse(ociReference);

        // 2. Configure the Registry client with the client credentials
        Registry registry = Registry.builder()
                .insecure(harborUrl, harborUsername, harborPassword)
                .build();

        String fileExtension = filename.substring(filename.lastIndexOf(".") + 1);

        // 3. Constructing annotations (metadata) associated with the artifact
        Annotations annotations = Annotations.ofManifest(Map.of("build-tool", "maven"))
                .withFileAnnotations(artifactId, Map.of("format", fileExtension));

        // 4. Define the type artifact and the local path to the artifact
        ArtifactType artifactType = ArtifactType.from("application/vnd.maven.artifact");
        LocalPath localPath = LocalPath.of(artifactPath, determineMediaType(fileExtension));

        try {
            LOG.infof("Start push van %s naar OCI registry...", ociReference);

            // 5. Push the artifact through ORAS on Harbor
            Manifest manifest = registry.pushArtifact(ref, artifactType, annotations, localPath);

            LOG.infof("Succesvol gepusht! Manifest digest: %s", manifest.getDigest());
            return manifest.getDigest();

        } catch (Exception e) {
            LOG.errorf("Fout tijdens het pushen van artifact naar Harbor: %s", e.getMessage());
            throw new RuntimeException("OCI Push gefaald", e);
        }
    }

    public String pushArtifactNewLayerToHarbor(String ociReference, Path tempFile, String artifactId, String filename, ArtifactEntry cachedFile) throws IOException {
        ContainerRef ref = ContainerRef.parse(ociReference);

        Registry registry = Registry.builder()
                .insecure(harborUrl, harborUsername, harborPassword)
                .build();

        try (Stream<Path> layerStream = Files.list(cachedFile.tempDir)) {
            List<Layer> layers = layerStream.map(file -> registry.pushBlob(ref, file, Map.of(Const.ANNOTATION_TITLE, file.toFile().getName()))).toList();

            Config config = registry.pushConfig(ref, Config.empty().withMediaType("application/vnd.maven.artifact"));

            Manifest manifest = Manifest.empty()
                    .withConfig(config)
                    .withLayers(layers);
            registry.pushManifest(ref, manifest);

            return manifest.getDigest();

        } catch (Exception e) {
            LOG.errorf("Fout tijdens toevoegen van nieuwe layer '%s': %s", filename, e.getMessage());
            throw new RuntimeException("Kan OCI layer niet updaten", e);
        }
    }

    /**
     * Checks if an artifact already exists in Harbor by querying the OCI Manifest.
     * If the required layer exists, it selectively pulls only that blob.
     *
     * @param ociReference The OCI tag (e.g., "net.java.dev.jna:5.8.0")
     * @param filename     The expected filename (e.g., "jna-5.8.0-jpms.jar")
     * @return The downloaded File, or the appropriate ArtifactStatus on Cache Miss
     */
    public ArtifactEntry pullArtifactFromHarbor(String ociReference, String filename) throws IOException {

        ContainerRef ref = ContainerRef.parse(ociReference);
        Registry registry = Registry.builder()
                .insecure(harborUrl, harborUsername, harborPassword)
                .build();

        Manifest manifest;
        try {
            LOG.infof("Checking Harbor manifest for artifact: %s", ociReference);
            // 1. Fetch ONLY the manifest (lightweight JSON), not the layers
            manifest = registry.getManifest(ref);
        } catch (Exception e) {
            // Artifact does not exist at all in Harbor
            LOG.debugf("Cache Miss: Artifact %s not found in Harbor", ociReference);
            return new ArtifactEntry(null, ArtifactStatus.MISSING, null, null);
        }

        // 2. Inspect the manifest layers to see if one matches our target filename
        java.util.Optional<Layer> matchingLayer = manifest.getLayers().stream()
                .filter(layer -> {
                    // We set this annotation during the push phase
                    String title = layer.getAnnotations().get(Const.ANNOTATION_TITLE);
                    return filename.equalsIgnoreCase(title);
                })
                .findFirst();

        if (matchingLayer.isEmpty() || StringUtils.isBlank(matchingLayer.get().getDigest())) {
            LOG.infof("Cache Miss (Layer): Artifact exists, but expected file '%s' is missing.", filename);
            // We pass the tempDir as null since we haven't created one yet
            return new ArtifactEntry(null, ArtifactStatus.LAYER_MISSING, manifest, null);
        }

        LOG.infof("Cache Hit! Layer '%s' found in manifest. Downloading specific blob...", filename);

        // 3. Create temp directory ONLY because we know we need to download something
        Path tempDir = Files.createTempDirectory("tugboat-");
        Path downloadedFile = tempDir.resolve(filename);

        try {
            // 4. Download ONLY the specific blob we need using its digest
            registry.fetchBlob(ref.withDigest(matchingLayer.get().getDigest()), downloadedFile);

            return new ArtifactEntry(downloadedFile.toFile(), ArtifactStatus.OK, manifest, tempDir);
        } catch (Exception e) {
            LOG.errorf("Fout tijdens downloaden van blob %s: %s", matchingLayer.get().getDigest(), e.getMessage());
            throw new IOException("Failed to pull targeted blob from Harbor", e);
        }
    }
}