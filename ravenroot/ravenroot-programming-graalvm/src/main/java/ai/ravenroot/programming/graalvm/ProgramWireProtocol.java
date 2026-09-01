package ai.ravenroot.programming.graalvm;

import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.programming.ProgramRequest;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Small, length-bounded binary protocol; deliberately avoids Java object serialization. */
final class ProgramWireProtocol {
    private static final int MAGIC = 0x52525031;
    private static final int MAX_STRING_BYTES = 1024 * 1024;
    private static final int MAX_COLLECTION_SIZE = 10_000;
    private static final int MAX_DEPTH = 32;

    /**
     * The largest response envelope {@link #writeSuccess} will produce, and the bound on the
     * buffer it produces it in.
     *
     * <p>The value is not new and not chosen here: it is the ceiling
     * {@code GraalVmProgramRuntime} has always applied when reading a worker response, which it now
     * reads from this field instead of declaring its own copy of. Writer bound and reader ceiling
     * being the same number is the whole point -- when they were merely equal, the worker could
     * spend memory and bytes producing a response the reader would then refuse, and the caller
     * learned only that the frame did not parse. Two constants that must agree are two constants
     * that eventually will not.
     *
     * <p>Distinct from the policy's {@code maxOutputBytes}, which a supervisor enforces on a
     * child's total output and which an integrator may set lower. This one is the protocol's own
     * ceiling and is not configurable.
     */
    static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private ProgramWireProtocol() {
    }

    static void writeRequest(OutputStream output, Mode mode, GeneratedArtifact artifact, ProgramRequest request)
            throws IOException {
        var data = new DataOutputStream(output);
        data.writeInt(MAGIC);
        writeString(data, mode.name());
        writeString(data, artifact.id());
        writeString(data, artifact.language());
        writeString(data, artifact.sha256());
        writeString(data, artifact.source());
        data.writeBoolean(request != null);
        if (request != null) {
            writeString(data, request.executionId().toString());
            writeString(data, request.nodeId());
            writeValue(data, request.payload(), 0);
            writeValue(data, request.attributes(), 0);
        }
        data.flush();
    }

    static WorkerRequest readRequest(InputStream input) throws IOException {
        var data = new DataInputStream(input);
        if (data.readInt() != MAGIC) throw new IOException("Invalid worker request");
        // Mode.valueOf and UUID.fromString both throw the unchecked IllegalArgumentException on a
        // malformed string, which is not an IOException: a caller catching this method's declared
        // exception type per its own signature would not catch it, and it would escape as an
        // undeclared crash instead of the typed protocol failure every other rejection in this class
        // produces. Found by fuzzing the mode field directly (QA-07), not
        // synthesized -- this path is reachable by ordinary stream corruption (a partial write, a
        // supervisor/worker version skew) with no need for a hostile actor.
        Mode mode;
        try {
            mode = Mode.valueOf(readString(data));
        } catch (IllegalArgumentException invalidMode) {
            throw new IOException("Invalid worker request mode", invalidMode);
        }
        String id = readString(data);
        String language = readString(data);
        String sha256 = readString(data);
        String source = readString(data);
        ProgramRequest request = null;
        if (data.readBoolean()) {
            UUID executionId;
            try {
                executionId = UUID.fromString(readString(data));
            } catch (IllegalArgumentException invalidExecutionId) {
                throw new IOException("Invalid worker request execution id", invalidExecutionId);
            }
            String nodeId = readString(data);
            Object payload = readValue(data, 0);
            Object attributes = readValue(data, 0);
            if (!(attributes instanceof Map<?, ?> map)) throw new IOException("Attributes must be an object");
            var normalized = new LinkedHashMap<String, Object>();
            map.forEach((key, value) -> normalized.put(String.valueOf(key), value));
            request = new ProgramRequest(executionId, nodeId, payload, normalized);
        }
        return new WorkerRequest(mode, id, language, sha256, source, request);
    }

    /**
     * Serialises the whole success envelope into a bounded buffer and copies it to
     * {@code output} only once it is complete, so that a failure partway through leaves
     * <b>no bytes at all</b> on the stream and the caller's failure path writes onto a clean one.
     *
     * <p><b>The defect this closes.</b> This method used to write straight to {@code output}. A
     * value too large to serialise fails inside {@link #writeValue}, which had by then already
     * emitted the magic, the success flag, the map header, the key and the string TAG. {@code
     * GraalVmWorkerMain}'s catch block then appended a second, well-formed failure envelope onto
     * that wreckage, and {@link #readResponse} read the second envelope's magic {@code 0x52525031}
     * as the pending string's length -- surfacing as "Invalid string length: 1381126193" while the
     * correct refusal sat in the same 78 bytes, in clear text, unreachable. Measured from a real
     * worker on both languages; identical byte for byte, which is what makes it the worker's defect
     * and not a language's.
     *
     * <p><b>Why the buffer is BOUNDED, and why that is not a detail.</b> Buffering trades stream
     * safety for memory, and this worker runs under {@code -Xmx} equal to the policy's
     * {@code memoryMiB} (64 by default). An unbounded buffer was measured to convert a legal 10 MiB
     * result at {@code -Xmx64m} from a completed write into {@code OutOfMemoryError: Java heap
     * space} -- trading one wrong answer for another, which is not a fix. The bound removes that
     * entirely: the buffer can never exceed {@link #MAX_RESPONSE_BYTES}, whatever the result costs
     * as objects, so peak cost is a constant rather than a function of the value.
     *
     * <p>{@link #MAX_RESPONSE_BYTES} is not a new limit invented here. It is the ceiling
     * {@code GraalVmProgramRuntime} already applies unconditionally when reading a worker response,
     * so an envelope above it has never been deliverable. What changes is only WHICH error the
     * caller gets for one: measured, a legal 3 MiB result used to arrive as
     * {@code SANDBOX_PROTOCOL_FAILURE} -- the supervisor truncating at {@code maxOutputBytes} and
     * the frame no longer parsing -- and now arrives as this class's own refusal, naming the
     * reason. That avoids the same illegibility on the neighbouring path.
     */
    static void writeSuccess(OutputStream output, Object value) throws IOException {
        var buffer = new BoundedBuffer();
        var data = new DataOutputStream(buffer);
        data.writeInt(MAGIC);
        data.writeBoolean(true);
        writeValue(data, value, 0);
        data.flush();
        buffer.copyTo(output);
        output.flush();
    }

    /** A failure the worker could not attribute to the artifact's source. */
    static void writeFailure(OutputStream output, Throwable error) throws IOException {
        writeFailure(output, error, false, 0, 0);
    }

    /**
     * The failure frame carries three fields beyond the type and message: whether the worker
     * attributed the failure to the artifact's <b>source</b>, and the line and column it placed it at.
     *
     * <p>The classification is written by the side that can actually make it. Only the worker knows
     * whether the throwable escaped the {@code eval}/{@code canExecute} region — everything else is
     * infrastructure (a launcher that is not there, an exhausted deadline, a response past the
     * protocol ceiling), and reporting one of those to an author as "your source does not compile" is
     * a false cause, which is worse than a generic rejection. Recovering that
     * distinction downstream would mean matching on message text; here it is a bit on the wire.
     *
     * <p>Line and column are {@code 0} when the runtime supplied none. Measured on the real worker,
     * both shipped languages supply both coordinates for a syntax error; zero is what a refusal with
     * no source location produces, such as a source that parses and is then found not to be callable.
     */
    static void writeFailure(OutputStream output, Throwable error, boolean sourceRejected, int line, int column)
            throws IOException {
        var data = new DataOutputStream(output);
        data.writeInt(MAGIC);
        data.writeBoolean(false);
        writeString(data, error.getClass().getSimpleName());
        writeString(data, safeMessage(error));
        data.writeBoolean(sourceRejected);
        data.writeInt(Math.max(0, line));
        data.writeInt(Math.max(0, column));
        data.flush();
    }

    static Object readResponse(InputStream input) throws IOException {
        var data = new DataInputStream(input);
        if (data.readInt() != MAGIC) throw new IOException("Invalid worker response");
        if (data.readBoolean()) return readValue(data, 0);
        String type = readString(data);
        String detail = readString(data);
        throw new ProgramWorkerException(type, detail, data.readBoolean(), data.readInt(), data.readInt());
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return "Program worker rejected the artifact";
        return message.length() <= 2_000 ? message : message.substring(0, 2_000);
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = String.valueOf(value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) throw new IOException("Value exceeds worker protocol limit");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) throw new IOException("Invalid string length: " + length);
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new IOException("Unexpected end of worker message");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeValue(DataOutputStream output, Object value, int depth) throws IOException {
        checkDepth(depth);
        if (value == null) {
            output.writeByte(0);
        } else if (value instanceof Boolean booleanValue) {
            output.writeByte(booleanValue ? 2 : 1);
        } else if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long) {
            output.writeByte(3);
            output.writeLong(((Number) value).longValue());
        } else if (value instanceof Number number) {
            output.writeByte(4);
            output.writeDouble(number.doubleValue());
        } else if (value instanceof CharSequence || value instanceof Character || value instanceof UUID) {
            output.writeByte(5);
            writeString(output, value.toString());
        } else if (value instanceof Map<?, ?> map) {
            if (map.size() > MAX_COLLECTION_SIZE) throw new IOException("Object is too large");
            output.writeByte(6);
            output.writeInt(map.size());
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IOException("Object keys must be strings");
                writeString(output, key);
                writeValue(output, entry.getValue(), depth + 1);
            }
        } else if (value instanceof Iterable<?> iterable) {
            var list = new ArrayList<>();
            iterable.forEach(list::add);
            if (list.size() > MAX_COLLECTION_SIZE) throw new IOException("Array is too large");
            output.writeByte(7);
            output.writeInt(list.size());
            for (Object item : list) writeValue(output, item, depth + 1);
        } else {
            throw new IOException("Unsupported program value type: " + value.getClass().getName());
        }
    }

    private static Object readValue(DataInputStream input, int depth) throws IOException {
        checkDepth(depth);
        return switch (input.readUnsignedByte()) {
            case 0 -> null;
            case 1 -> false;
            case 2 -> true;
            case 3 -> input.readLong();
            case 4 -> input.readDouble();
            case 5 -> readString(input);
            case 6 -> readMap(input, depth + 1);
            case 7 -> readList(input, depth + 1);
            default -> throw new IOException("Unsupported value tag");
        };
    }

    private static Map<String, Object> readMap(DataInputStream input, int depth) throws IOException {
        int size = readCollectionSize(input);
        var result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < size; index++) result.put(readString(input), readValue(input, depth));
        return Collections.unmodifiableMap(result);
    }

    private static List<Object> readList(DataInputStream input, int depth) throws IOException {
        int size = readCollectionSize(input);
        var result = new ArrayList<Object>(size);
        for (int index = 0; index < size; index++) result.add(readValue(input, depth));
        return Collections.unmodifiableList(result);
    }

    private static int readCollectionSize(DataInputStream input) throws IOException {
        int size = input.readInt();
        if (size < 0 || size > MAX_COLLECTION_SIZE) throw new IOException("Invalid collection size: " + size);
        return size;
    }

    private static void checkDepth(int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("Program value nesting exceeds " + MAX_DEPTH);
    }

    /**
     * An in-memory sink that refuses the write which would take it past
     * {@link #MAX_RESPONSE_BYTES}, rather than growing to hold it.
     *
     * <p>The refusal is raised on RESERVATION, before the underlying buffer is asked to grow, so the
     * ceiling bounds the allocation and not merely the reported size. A check performed after the
     * write would let the array double past the limit first, which is the allocation this class
     * exists to prevent.
     */
    private static final class BoundedBuffer extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        @Override
        public void write(int value) throws IOException {
            reserve(1);
            bytes.write(value);
        }

        @Override
        public void write(byte[] source, int offset, int length) throws IOException {
            reserve(length);
            bytes.write(source, offset, length);
        }

        private void reserve(int additional) throws IOException {
            if (bytes.size() + (long) additional > MAX_RESPONSE_BYTES) {
                throw new IOException("Response exceeds worker protocol limit");
            }
        }

        void copyTo(OutputStream output) throws IOException {
            bytes.writeTo(output);
        }
    }

    /**
     * Makes "this stream has already been written to" an explicit, checkable fact rather than
     * something a failure path has to infer.
     *
     * <p>{@link #writeSuccess}'s buffer is what actually closes the defect; this is the guard that
     * stops it being reopened. A future writer added to the success path could reintroduce a partial
     * envelope, and the failure path's own correctness must not depend on every such writer being
     * careful. {@link #dirty()} lets {@code GraalVmWorkerMain} refuse to append a second envelope
     * instead of producing an unreadable concatenation.
     */
    static final class GuardedOutput extends OutputStream {
        private final OutputStream delegate;
        private boolean dirty;

        GuardedOutput(OutputStream delegate) {
            this.delegate = delegate;
        }

        /** True once any byte has reached the underlying stream. */
        boolean dirty() {
            return dirty;
        }

        @Override
        public void write(int value) throws IOException {
            dirty = true;
            delegate.write(value);
        }

        @Override
        public void write(byte[] source, int offset, int length) throws IOException {
            if (length > 0) dirty = true;
            delegate.write(source, offset, length);
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }
    }

    enum Mode { VALIDATE, TEST, EXECUTE }

    record WorkerRequest(Mode mode, String artifactId, String language, String sha256, String source,
                         ProgramRequest request) {
    }

    static final class ProgramWorkerException extends IOException {
        private final String detail;
        private final boolean sourceRejected;
        private final int line;
        private final int column;

        ProgramWorkerException(String type, String detail, boolean sourceRejected, int line, int column) {
            // Keeps "<type>: <detail>" as getMessage() deliberately: several tests and one
            // caller assert on that exact composition, and widening the frame must not change what a
            // reader that ignores the new fields observes.
            super(type + ": " + detail);
            this.detail = detail;
            this.sourceRejected = sourceRejected;
            this.line = Math.max(0, line);
            this.column = Math.max(0, column);
        }

        /**
         * The worker's message without the exception-type prefix {@link #getMessage()} carries.
         *
         * <p>This is what an author is shown, and {@code PolyglotException:} in front of
         * {@code IndentationError: …} is the adapter's own plumbing leaking into their diagnosis.</p>
         */
        String detail() {
            return detail;
        }

        /** Whether the worker attributed this failure to the artifact's source. */
        boolean sourceRejected() {
            return sourceRejected;
        }

        int line() {
            return line;
        }

        int column() {
            return column;
        }
    }
}
