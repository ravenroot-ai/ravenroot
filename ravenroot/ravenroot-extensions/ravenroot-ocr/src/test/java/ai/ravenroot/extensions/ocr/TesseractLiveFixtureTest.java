package ai.ravenroot.extensions.ocr;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TesseractLiveFixtureTest {
    @TempDir Path root;

    @Test void extractsTextFromFixedFixtureWhenLocalCapabilityIsInstalled() throws Exception {
        Path executable = firstExecutable(
                System.getenv("RAVENROOT_OCR_TEST_TESSERACT"),
                "/opt/homebrew/bin/tesseract", "/usr/bin/tesseract", "/usr/local/bin/tesseract");
        Path tessdata = firstDirectory(
                System.getenv("RAVENROOT_OCR_TEST_TESSDATA"),
                "/opt/homebrew/share/tessdata", "/usr/share/tesseract-ocr/5/tessdata",
                "/usr/share/tesseract-ocr/4.00/tessdata", "/usr/local/share/tessdata");
        Assumptions.assumeTrue(executable != null && tessdata != null && Files.exists(tessdata.resolve("eng.traineddata")),
                "local Tesseract with English language data is not installed");
        Path temporary = Files.createDirectory(root.resolve("ocr-tmp"));
        OcrProfile profile = new OcrProfile(OcrTestSupport.TENANT, OcrTestSupport.PROFILE,
                executable, tessdata, Set.of("eng"), temporary, Duration.ofSeconds(10),
                1024 * 1024, 1024 * 1024, 1, Duration.ofSeconds(1));
        String encoded = new String(java.util.Objects.requireNonNull(getClass().getResourceAsStream(
                "/fixtures/ravenroot-text.png.base64")).readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII).strip();
        var action = new OcrExtractNodeBehavior((tenant, name) -> Optional.of(profile),
                new OcrRuntimeControls(1), new JdkTesseractProcessFactory(), System::nanoTime)
                .create(new ai.ravenroot.api.node.NodeConfiguration("ocr", OcrExtractNodeBehavior.BEHAVIOR,
                        Map.of("ocrProfile", OcrTestSupport.PROFILE, "language", "eng")));

        Map<String, Object> output = OcrTestSupport.output(action, OcrTestSupport.message(OcrTestSupport.TENANT,
                Map.of("version", OcrExtractNodeBehavior.CONTRACT, "imageBase64", encoded)));

        assertEquals("EXTRACTED", output.get("status"), output::toString);
        String text = String.valueOf(output.get("text")).replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        assertTrue(text.contains("RAVENROOT42"), output::toString);
        assertTrue(Files.list(temporary).findAny().isEmpty(), "live invocation directory must be removed");
        assertEquals(400, Base64.getDecoder().decode(encoded).length > 0 ? output.get("width") : -1);
    }

    private static Path firstExecutable(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                Path path = Path.of(candidate);
                if (path.isAbsolute() && Files.isRegularFile(path) && Files.isExecutable(path)) {
                    try { return path.toRealPath(); } catch (java.io.IOException ignored) { }
                }
            }
        }
        return null;
    }

    private static Path firstDirectory(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                Path path = Path.of(candidate);
                if (path.isAbsolute() && Files.isDirectory(path)) {
                    try { return path.toRealPath(); } catch (java.io.IOException ignored) { }
                }
            }
        }
        return null;
    }
}
