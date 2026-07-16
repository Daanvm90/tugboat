package org.tugboat.gateway.service;

import jakarta.enterprise.context.ApplicationScoped;
import land.oras.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class HarborOrasService {

    private static final Logger LOG = Logger.getLogger(HarborOrasService.class);

    @ConfigProperty(name = "tugboat.harbor.url")
    String harborUrl;

    @ConfigProperty(name = "tugboat.harbor.project")
    String harborProject;

    @ConfigProperty(name = "tugboat.harbor.username")
    String harborUsername;

    @ConfigProperty(name = "tugboat.harbor.password")
    String harborPassword;

    /**
     * Responsible for transmitting the Maven dependency (like jna-5.8.0-jpms.jar)
     * as an OCI artifact to Harbor.
     *
     * @param ociReference The OCI tag (e.g., "net.java.dev.jna:5.8.0")
     * @param artifactPath The path to the locally (temporarily) stored JAR file
     * @param filename The original filename (e.g., "jna-5.8.0-jpms.jar")
     * @return The resulting OCI Manifest digest
     */
    public String pushArtifactToHarbor(String ociReference, Path artifactPath, String filename) {

        // 1. Constructing the full OCI reference
        // Example: "harbor.yourdomain.com/maven-proxy/net.java.dev.jna:5.8.0"
        String domain = harborUrl.replace("https://", "").replace("http://", "");
        String fullReference = String.format("%s/%s/%s", domain, harborProject, ociReference);
        ContainerRef ref = ContainerRef.parse(fullReference);

        // 2. Configure the Registry client with the client credentials
        Registry registry = Registry.builder()
                .insecure(domain, harborUsername, harborPassword)
                .build();

        // 3. Constructing annotations (metadata) associated with the artifact
        Annotations annotations = Annotations.ofManifest(Map.of("build-tool", "maven"))
                .withFileAnnotations(filename, Map.of("format", "jar"));

        // 4. Define the type artifact and the local path to the artifact
        ArtifactType artifactType = ArtifactType.from("application/vnd.maven.artifact");
        LocalPath localPath = LocalPath.of(artifactPath, "application/java-archive");

        try {
            LOG.infof("Start push van %s naar OCI registry...", fullReference);

            // 5. Push the artifact through ORAS on Harbor
            Manifest manifest = registry.pushArtifact(ref, artifactType, annotations, localPath);

            LOG.infof("Succesvol gepusht! Manifest digest: %s", manifest.getDigest());
            return manifest.getDigest();

        } catch (Exception e) {
            LOG.errorf("Fout tijdens het pushen van artifact naar Harbor: %s", e.getMessage());
            throw new RuntimeException("OCI Push gefaald", e);
        }
    }

    /**
     * Checks if an artifact already exists in Harbor and pulls it locally if it does.
     *
     * @param ociReference The OCI tag (e.g., "net.java.dev.jna:5.8.0")
     * @param filename     The expected filename (e.g., "jna-5.8.0-jpms.jar")
     * @return The downloaded File, or null if it does not exist in Harbor (Cache Miss)
     */
    public Optional<File> pullArtifactFromHarbor(String ociReference, String filename) {

        // 1. Build the full reference
        String domain = harborUrl.replace("https://", "").replace("http://", "");
        String fullReference = String.format("%s/%s/%s", domain, harborProject, ociReference);
        ContainerRef ref = ContainerRef.parse(fullReference);

        // 2. Configure the Registry client
        Registry registry = Registry.builder()
                .insecure(domain, harborUsername, harborPassword)
                .build();

        try {
            LOG.infof("Checking Harbor for artifact: %s", fullReference);

            // 3. Create a temporary directory to extract the pulled OCI layers into
            java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("tugboat-pull-");

            // 4. Pull the artifact from Harbor using the ORAS SDK
            registry.pullArtifact(ref, tempDir, OCI.PullOptions.defaults());

            // 5. Verify if the file we expect is actually part of the pulled artifact
            try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.list(tempDir)) {
                java.util.Optional<java.nio.file.Path> pulledFile = stream.filter(java.nio.file.Files::isRegularFile).findFirst();
                if (pulledFile.isPresent()) {
                    LOG.infof("Cache Hit! Successfully pulled %s from Harbor.", pulledFile.get().getFileName());
                    return Optional.of(pulledFile.get().toFile());
                }
            }
        } catch (Exception e) {
            // A cache miss (artifact not found in Harbor) will throw an exception here.
            // We log it as DEBUG because a cache miss is a normal operational event, not an application error.
            LOG.debugf("Cache Miss: Artifact %s not found in Harbor (or pull failed: %s)", fullReference, e.getMessage());
        }
        LOG.warnf("Artifact pulled, but expected file '%s' was missing inside the OCI manifest.", filename);
        return Optional.empty();
    }
}