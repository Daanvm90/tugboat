package org.tugboat.gateway.service;

import io.micrometer.common.util.StringUtils;
import io.netty.handler.codec.http.HttpResponseStatus;
import jakarta.enterprise.context.ApplicationScoped;
import land.oras.*;
import land.oras.utils.Const;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.tugboat.ArtifactStatus;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.Optional;

@ApplicationScoped
public class HarborOrasService {

    public static class ArtifactEntry {
        public String blobDigest;
        public ArtifactStatus status;
        public Manifest manifest;

        public ArtifactEntry(String blobDigest, ArtifactStatus status, Manifest manifest) {
            this.blobDigest = blobDigest;
            this.status = status;
            this.manifest = manifest;
        }
    }

    private static final Logger LOG = Logger.getLogger(HarborOrasService.class);

    @ConfigProperty(name = "tugboat.harbor.url")
    String harborUrl;

    @ConfigProperty(name = "tugboat.harbor.username")
    String harborUsername;

    @ConfigProperty(name = "tugboat.harbor.password")
    String harborPassword;

    private final HttpClient httpClient = HttpClient.newBuilder().build();

    /**
     * Checks if an artifact exists in Harbor by querying ONLY the OCI Manifest.
     * Returns the blob digest if the specific layer is found.
     *
     * @param ociReference The OCI tag (e.g., "net.java.dev.jna:5.8.0")
     * @param filename     The expected filename (e.g., "jna-5.8.0-jpms.jar")
     * @return ArtifactEntry containing the status and optionally the blob digest
     */
    public ArtifactEntry checkArtifactInHarbor(String ociReference, String filename) {
        ContainerRef ref = ContainerRef.parse(ociReference);
        Registry registry = Registry.builder()
                .insecure(harborUrl, harborUsername, harborPassword)
                .build();

        Manifest manifest;
        try {
            LOG.infof("Checking Harbor manifest for artifact: %s", ociReference);
            manifest = registry.getManifest(ref);
        } catch (Exception e) {
            LOG.debugf("Cache Miss: Artifact %s not found in Harbor", ociReference);
            return new ArtifactEntry(null, ArtifactStatus.MISSING, null);
        }

        Optional<Layer> matchingLayer = manifest.getLayers().stream()
                .filter(layer -> filename.equalsIgnoreCase(layer.getAnnotations().get(Const.ANNOTATION_TITLE)))
                .findFirst();

        if (matchingLayer.isEmpty() || StringUtils.isBlank(matchingLayer.get().getDigest())) {
            LOG.infof("Cache Miss (Layer): Artifact exists, but expected file '%s' is missing.", filename);
            return new ArtifactEntry(null, ArtifactStatus.LAYER_MISSING, manifest);
        }

        LOG.infof("Cache Hit! Layer '%s' found in manifest. Digest: %s", filename, matchingLayer.get().getDigest());
        return new ArtifactEntry(matchingLayer.get().getDigest(), ArtifactStatus.OK, manifest);
    }

    /**
     * Streams the blob directly from Harbor using the standard OCI Distribution API.
     *
     * @param ociReference The full OCI reference
     * @param digest       The digest of the specific layer to stream
     * @return InputStream directly from the Harbor blob endpoint
     */
    public InputStream streamBlobFromHarbor(String ociReference, String digest) throws IOException, InterruptedException {
        ContainerRef ref = ContainerRef.parse(ociReference);

        // Standard OCI v2 API: GET /v2/<repository>/blobs/<digest>
        // Note: Change scheme to https:// if your Harbor enforces TLS
        String blobUrl = String.format("http://%s/v2/%s/blobs/%s", harborUrl, ref.getFullRepository(), digest);

        String authHeader = "Basic " + Base64.getEncoder().encodeToString((harborUsername + ":" + harborPassword).getBytes());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(blobUrl))
                .header("Authorization", authHeader)
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != HttpResponseStatus.OK.code()) {
            throw new IOException("Failed to stream blob from Harbor (HTTP " + response.statusCode() + ")");
        }

        return response.body();
    }
}