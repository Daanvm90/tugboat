package org.tugboat.publisher;

import jakarta.enterprise.context.ApplicationScoped;
import land.oras.*;
import land.oras.utils.Const;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class PublisherHarborOrasService {

    private static final Logger LOG = Logger.getLogger(PublisherHarborOrasService.class);

    @ConfigProperty(name = "tugboat.harbor.url")
    String harborUrl;

    @ConfigProperty(name = "tugboat.harbor.username")
    String harborUsername;

    @ConfigProperty(name = "tugboat.harbor.password")
    String harborPassword;

    private String determineMediaType(String fileExtension) {
        return switch (fileExtension) {
            case "pom" -> "application/vnd.maven.pom+xml";
            case "jar" -> "application/java-archive";
            case "sha1" -> "application/vnd.maven.sha1";
            case "md5" -> "application/vnd.maven.md5";
            case "xml" -> "application/xml";
            default -> "application/octet-stream";
        };
    }

    /**
     * Pushes a completely new Maven dependency as an OCI artifact to Harbor.
     */
    public String pushArtifactToHarbor(String ociReference, Path artifactPath, String artifactId, String filename) {
        ContainerRef ref = ContainerRef.parse(ociReference);
        Registry registry = Registry.builder().insecure(harborUrl, harborUsername, harborPassword).build();

        String fileExtension = filename.substring(filename.lastIndexOf(".") + 1);

        Annotations annotations = Annotations.ofManifest(Map.of("build-tool", "maven"))
                .withFileAnnotations(artifactId, Map.of("format", fileExtension, Const.ANNOTATION_TITLE, filename));

        ArtifactType artifactType = ArtifactType.from("application/vnd.maven.artifact");
        LocalPath localPath = LocalPath.of(artifactPath, determineMediaType(fileExtension));

        try {
            Manifest manifest = registry.pushArtifact(ref, artifactType, annotations, localPath);
            return manifest.getDigest();
        } catch (Exception e) {
            LOG.errorf("Fout tijdens het pushen van nieuw artifact: %s", e.getMessage());
            throw new RuntimeException("OCI Push gefaald", e);
        }
    }

    /**
     * Appends a new layer (e.g., fetching a SHA1 file for an existing JAR) to an existing Harbor manifest.
     */
    public String pushArtifactNewLayerToHarbor(String ociReference, Path tempFile, String filename) {
        ContainerRef ref = ContainerRef.parse(ociReference);
        Registry registry = Registry.builder().insecure(harborUrl, harborUsername, harborPassword).build();

        try {
            // 1. Fetch the existing manifest from Harbor
            Manifest existingManifest = registry.getManifest(ref);

            // 2. Push the new file as an isolated blob
            Layer newLayer = registry.pushBlob(ref, tempFile, Map.of(Const.ANNOTATION_TITLE, filename));

            // 3. Create a new layer list combining old layers and the newly pushed blob
            List<Layer> updatedLayers = new ArrayList<>(existingManifest.getLayers());
            updatedLayers.add(newLayer);

            // 4. Construct and push the updated manifest pointing to all layers
            Manifest updatedManifest = Manifest.empty()
                    .withConfig(existingManifest.getConfig())
                    .withLayers(updatedLayers);

            registry.pushManifest(ref, updatedManifest);

            return updatedManifest.getDigest();
        } catch (Exception e) {
            LOG.errorf("Fout tijdens updaten van manifest met layer '%s': %s", filename, e.getMessage());
            throw new RuntimeException("OCI Manifest update gefaald", e);
        }
    }
}