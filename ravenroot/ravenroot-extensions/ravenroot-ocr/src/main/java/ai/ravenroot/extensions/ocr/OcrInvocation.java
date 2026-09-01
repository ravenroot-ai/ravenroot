package ai.ravenroot.extensions.ocr;

import java.nio.file.Path;
import java.util.Objects;

/** Typed process intent; no payload-derived value can become an argument. */
record OcrInvocation(Path executable, Path inputFile, Path workingDirectory, Path languageData,
                     String language) {
    OcrInvocation {
        executable = Objects.requireNonNull(executable, "executable");
        inputFile = Objects.requireNonNull(inputFile, "inputFile");
        workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
        languageData = Objects.requireNonNull(languageData, "languageData");
        language = Objects.requireNonNull(language, "language");
    }
}
