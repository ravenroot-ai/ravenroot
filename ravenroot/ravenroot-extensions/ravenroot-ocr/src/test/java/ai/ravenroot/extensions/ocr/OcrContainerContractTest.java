package ai.ravenroot.extensions.ocr;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OcrContainerContractTest {
    private static final Path MODULE = Path.of(System.getProperty("basedir"));

    @Test void optionalImageRequiresDigestAndPinsEveryRequestedAptPackage() throws Exception {
        String dockerfile = Files.readString(MODULE.resolve("container/Dockerfile"));
        assertTrue(dockerfile.contains("RAVENROOT_IMAGE must be an immutable OCI digest"));
        assertTrue(dockerfile.contains("tesseract-ocr=5.3.4-1build5"));
        assertTrue(dockerfile.contains("tesseract-ocr-eng=1:4.1.0-2"));
        assertTrue(dockerfile.contains("USER 10001:10001"));
        assertTrue(dockerfile.contains("STOPSIGNAL SIGTERM"));
        assertTrue(dockerfile.contains("COPY --from=ocr-bundle"));
        assertFalse(dockerfile.contains("EXPOSE "));
    }

    @Test void smokeAppliesRuntimeHardeningAndTestsHealthReadyOcrAndSigterm() throws Exception {
        String smoke = Files.readString(MODULE.resolve("container/smoke.sh"));
        for (String required : new String[] {"--read-only", "--user 10001:10001", "--cap-drop ALL",
                "no-new-privileges:true", "--pids-limit", "--memory", "--cpus", "/ready",
                "id -u", "root-filesystem-must-remain-read-only", "must-remain-read-only",
                "type=volume", "target=/opt/ravenroot/data", "/usr/bin/tesseract", "docker stop --time 10"}) {
            assertTrue(smoke.contains(required), required);
        }
        assertFalse(smoke.contains("--publish"));
    }

    @Test void attestedBuildProducesAndVerifiesAContentAddressedLocalOciArtifact() throws Exception {
        String build = Files.readString(MODULE.resolve("container/build_attested.sh"));
        String verifier = Files.readString(MODULE.resolve("container/verify_attestations.py"));
        String verifierTest = Files.readString(MODULE.resolve("container/test_verify_attestations.py"));
        for (String required : new String[] {"--sbom=true", "--provenance=mode=max", "type=oci",
                "buildx-metadata.json", "attestation-verification.json", "RAVENROOT_IMAGE must be an immutable OCI digest"}) {
            assertTrue(build.contains(required), required);
        }
        for (String required : new String[] {"vnd.docker.reference.digest", "attestation-manifest",
                "containerimage.digest", "sha256", "IN_TOTO_STATEMENT", "IN_TOTO_LAYER", "missing", "sbom", "provenance"}) {
            assertTrue(verifier.contains(required), required);
        }
        assertTrue(verifierTest.contains("test_rejects_tampered_referenced_blob_and_wrapper_mismatch"));
    }
}
