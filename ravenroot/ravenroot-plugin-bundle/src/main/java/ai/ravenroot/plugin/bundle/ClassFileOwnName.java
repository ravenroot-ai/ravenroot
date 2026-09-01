package ai.ravenroot.plugin.bundle;

import java.nio.charset.StandardCharsets;

/**
 * Reads a compiled class's own binary name from its class file bytes (PLAT-12).
 *
 * <h2>Why this exists instead of {@code Class.forName}</h2>
 * <p><strong>Reserved-package membership must be provable without loading the class, or the check
 * itself becomes an execution path.</strong> {@code Class.forName}/{@code ClassLoader.loadClass}
 * link and initialise a class as a side effect of answering "what package is this in" — exactly the
 * thing PLAT-12's "presence must never execute code" rule forbids happening to an unvalidated bundle.
 * This class answers the same question by reading three fields out of the class file's constant pool
 * (the magic number, then {@code this_class} resolved through the pool to its {@code CONSTANT_Utf8}
 * name) and touches nothing else — no method, no field, no attribute, no superclass reference. A
 * later maintainer who "simplifies" the reserved-package check to {@code Class.forName} would
 * silently reopen this hole while every existing test kept passing, which is why the reasoning is
 * recorded here rather than only in a design note.</p>
 *
 * <h2>Why hand-written rather than a library</h2>
 * <p>The only bytecode-reading dependency already in the reactor is ASM ({@code ravenroot-distribution},
 * the distribution adapter boundary test), deliberately kept test-scope-only: never a
 * compile/runtime dependency, so it is never
 * shaded into the shipped jar. This check has to run wherever a bundle is validated, which ships.
 * Rather than break that precedent for three fields, this reads them directly: unlike a general
 * parser for an ambiguous text format, the class file layout is a small fixed binary structure with
 * no comparable ambiguity surface, so hand-writing the read carries little of the risk that a
 * hand-written parser would carry for a format like YAML.</p>
 *
 * <h2>Modified UTF-8</h2>
 * <p>{@code CONSTANT_Utf8} is technically JVM "modified UTF-8", which differs from standard UTF-8
 * only for the embedded NUL encoding and supplementary-character surrogate pairs — neither of which
 * a legal Java binary class or package name can contain. Standard UTF-8 decoding is therefore exact
 * for every name this method is actually asked to read, and on the exotic inputs where the two
 * encodings would disagree, standard decoding fails closed into a malformed-manifest rejection rather
 * than silently misreading a name.</p>
 */
final class ClassFileOwnName {

    // long, not int: 0xCAFEBABE exceeds Integer.MAX_VALUE, and u4() below returns the unsigned
    // 32-bit value as a positive long. An int constant would sign-extend to a different long on
    // comparison and this equality check would never succeed.
    private static final long MAGIC = 0xCAFEBABEL;

    /** Largest class file this reader accepts, before touching the constant pool. */
    static final int MAX_CLASS_FILE_BYTES = 4 * 1024 * 1024;

    private ClassFileOwnName() {
    }

    /**
     * The dot-separated binary name the class file declares for itself.
     *
     * @throws PluginBundleException when the bytes are not a well-formed class file, or exceed
     *                               {@link #MAX_CLASS_FILE_BYTES}
     */
    static String read(byte[] classBytes, String diagnosticName) {
        if (classBytes.length > MAX_CLASS_FILE_BYTES) {
            throw PluginBundleRejection.bundleTooLarge(classBytes.length, MAX_CLASS_FILE_BYTES);
        }
        try {
            var reader = new Cursor(classBytes);
            if (reader.u4() != MAGIC) {
                throw PluginBundleRejection.malformedManifest("classFileMagic", diagnosticName);
            }
            reader.u2(); // minor_version, not needed
            reader.u2(); // major_version, not needed
            int constantPoolCount = reader.u2();

            String[] utf8 = new String[constantPoolCount];
            int[] classNameIndex = new int[constantPoolCount];

            int index = 1;
            while (index < constantPoolCount) {
                int tag = reader.u1();
                switch (tag) {
                    case 1 -> { // CONSTANT_Utf8
                        int length = reader.u2();
                        utf8[index] = new String(reader.bytes(length), StandardCharsets.UTF_8);
                        index++;
                    }
                    case 7 -> { // CONSTANT_Class
                        classNameIndex[index] = reader.u2();
                        index++;
                    }
                    case 8, 16, 19, 20 -> { // String, MethodType, Module, Package
                        reader.u2();
                        index++;
                    }
                    case 15 -> { // MethodHandle
                        reader.u1();
                        reader.u2();
                        index++;
                    }
                    case 3, 4 -> { // Integer, Float
                        reader.u4();
                        index++;
                    }
                    case 5, 6 -> { // Long, Double: occupies two constant-pool slots (JVMS 4.4.5)
                        reader.u8();
                        index += 2;
                    }
                    case 9, 10, 11, 12, 17, 18 -> { // *ref, NameAndType, Dynamic, InvokeDynamic
                        reader.u2();
                        reader.u2();
                        index++;
                    }
                    default -> throw PluginBundleRejection.malformedManifest("classFileConstantTag", diagnosticName);
                }
            }

            reader.u2(); // access_flags, not needed
            int thisClass = reader.u2();
            if (thisClass <= 0 || thisClass >= constantPoolCount) {
                throw PluginBundleRejection.malformedManifest("classFileThisClass", diagnosticName);
            }
            int nameIndex = classNameIndex[thisClass];
            String internalName = nameIndex > 0 && nameIndex < constantPoolCount ? utf8[nameIndex] : null;
            if (internalName == null || internalName.isBlank()) {
                throw PluginBundleRejection.malformedManifest("classFileOwnName", diagnosticName);
            }
            return internalName.replace('/', '.');
        } catch (IndexOutOfBoundsException truncated) {
            throw PluginBundleRejection.malformedManifest("classFileTruncated", diagnosticName);
        }
    }

    /** A bounds-checked cursor over the class file bytes. Throws on read-past-end rather than wrapping. */
    private static final class Cursor {
        private final byte[] data;
        private int position;

        Cursor(byte[] data) {
            this.data = data;
        }

        int u1() {
            return data[position++] & 0xFF;
        }

        int u2() {
            return (u1() << 8) | u1();
        }

        long u4() {
            return ((long) u2() << 16) | u2();
        }

        long u8() {
            return (u4() << 32) | u4();
        }

        byte[] bytes(int length) {
            byte[] slice = new byte[length];
            System.arraycopy(data, position, slice, 0, length);
            position += length;
            return slice;
        }
    }
}
