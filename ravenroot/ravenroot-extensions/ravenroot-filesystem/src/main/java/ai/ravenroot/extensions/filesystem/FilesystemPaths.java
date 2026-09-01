package ai.ravenroot.extensions.filesystem;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Positive parser for the extension's provider-independent, slash-separated relative path syntax. */
final class FilesystemPaths {
    private static final int MAX_PATH_CODE_POINTS = 4096;
    private static final int MAX_COMPONENTS = 256;

    private FilesystemPaths() { }

    static Parsed parse(Path root, String value) {
        if (value == null || value.isEmpty() || value.codePointCount(0, value.length()) > MAX_PATH_CODE_POINTS
                || value.startsWith("/") || value.startsWith("\\") || value.indexOf('\\') >= 0
                || looksLikeDrive(value) || hasControl(value)) {
            throw FilesystemNodeException.of(FilesystemNodeException.Reason.OUTSIDE_ROOT);
        }
        String[] raw = value.split("/", -1);
        if (raw.length > MAX_COMPONENTS) throw FilesystemNodeException.of(FilesystemNodeException.Reason.OUTSIDE_ROOT);
        List<String> components = new ArrayList<>(raw.length);
        for (String component : raw) {
            if (component.isEmpty() || component.equals(".") || component.equals("..") || hasControl(component)) {
                throw FilesystemNodeException.of(FilesystemNodeException.Reason.OUTSIDE_ROOT);
            }
            if (FilesystemTempNames.isReservedComponent(component)) {
                throw FilesystemNodeException.of(FilesystemNodeException.Reason.AUTHORITY_REFUSED);
            }
            components.add(component);
        }
        try {
            Path relative = root.getFileSystem().getPath(components.getFirst(),
                    components.subList(1, components.size()).toArray(String[]::new));
            if (relative.isAbsolute()) throw FilesystemNodeException.of(FilesystemNodeException.Reason.OUTSIDE_ROOT);
            return new Parsed(value, relative, List.copyOf(components));
        } catch (InvalidPathException invalid) {
            throw FilesystemNodeException.of(FilesystemNodeException.Reason.OUTSIDE_ROOT);
        }
    }

    static void parsePattern(String pattern) {
        if (pattern == null || pattern.isEmpty() || pattern.startsWith("/") || pattern.indexOf('\\') >= 0
                || looksLikeDrive(pattern) || hasControl(pattern) || pattern.contains("//")) {
            throw new IllegalArgumentException("invalid allowed path pattern");
        }
        for (String component : pattern.split("/", -1)) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")) {
                throw new IllegalArgumentException("invalid allowed path pattern");
            }
            if (FilesystemTempNames.isReservedComponent(component)) {
                throw new IllegalArgumentException("allowed path pattern selects reserved filesystem namespace");
            }
        }
    }

    private static boolean looksLikeDrive(String value) {
        return value.length() >= 2 && Character.isLetter(value.charAt(0)) && value.charAt(1) == ':';
    }

    private static boolean hasControl(String value) {
        return value.codePoints().anyMatch(cp -> cp <= 0x1f || (cp >= 0x7f && cp <= 0x9f));
    }

    record Parsed(String display, Path relative, List<String> components) {
        Path leaf() { return relative.getFileName(); }
        List<String> parents() { return components.subList(0, components.size() - 1); }
    }
}
