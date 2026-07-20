package org.tugboat.gateway.service;

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
     * Checks if an artifact already exists in Harbor and pulls it locally if it does.
     *
     * @param ociReference The OCI tag (e.g., "net.java.dev.jna:5.8.0")
     * @param filename     The expected filename (e.g., "jna-5.8.0-jpms.jar")
     * @return The downloaded File, or null if it does not exist in Harbor (Cache Miss)
     */
    public ArtifactEntry pullArtifactFromHarbor(String ociReference, String filename) throws IOException {

        // 1. Create a temporary directory to extract the pulled OCI layers into
        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("tugboat-");

        // 2. Build the full reference
        ContainerRef ref = ContainerRef.parse(ociReference);

        // 3. Configure the Registry client
        Registry registry = Registry.builder()
                .insecure(harborUrl, harborUsername, harborPassword)
                .build();
        try {
            LOG.infof("Checking Harbor for artifact: %s", ociReference);

            // 4. Pull the artifact from Harbor using the ORAS SDK
            registry.pullArtifact(ref, tempDir, OCI.PullOptions.defaults());

            // 5. Verify if the file we expect is actually part of the pulled artifact
            try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.list(tempDir)) {
                java.util.Optional<java.nio.file.Path> pulledFile = stream.filter(file -> file.toFile().getName().equalsIgnoreCase(filename)).findFirst();
                if (pulledFile.isPresent()) {
                    LOG.infof("Cache Hit! Successfully pulled %s from Harbor.", pulledFile.get().getFileName());
                    return new ArtifactEntry(pulledFile.get().toFile(), ArtifactStatus.OK, registry.getManifest(ref), tempDir);
                } else {
                    LOG.warnf("Artifact pulled, but expected file '%s' was missing inside the OCI manifest.", filename);
                    return new ArtifactEntry(null, ArtifactStatus.LAYER_MISSING, registry.getManifest(ref), tempDir);
                }
            }
        } catch (Exception e) {
            // A cache miss (artifact not found in Harbor) will throw an exception here.
            // We log it as DEBUG because a cache miss is a normal operational event, not an application error.
            LOG.debugf("Cache Miss: Artifact %s not found in Harbor (or pull failed: %s)", ociReference, e.getMessage());
        }
        LOG.warnf("Artifact pulled, but expected file '%s' was missing inside the OCI manifest.", filename);
        return new ArtifactEntry(null, ArtifactStatus.MISSING, null, tempDir);
    }
}