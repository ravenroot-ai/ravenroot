package ai.ravenroot.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;

/** Local command that decodes a captured execution SSE body without starting a backend. */
final class EventStreamDecodeCommand {
    private EventStreamDecodeCommand() {
    }

    static int run(String[] args, InputStream input, PrintStream output, PrintStream errors) {
        if (args.length != 2 || !"events".equals(args[0]) || !"decode".equals(args[1])) {
            errors.println("Usage: ravenroot events decode");
            return 2;
        }
        try {
            ExecutionEventStreamDecoder.decode(input, output);
            return 0;
        } catch (ExecutionEventStreamDecoder.DecodeFailure failure) {
            errors.println("Error: " + failure.code() + " frame=" + failure.frame());
            return switch (failure.code()) {
                case STREAM_RETENTION_EXCEEDED, STREAM_CONSUMER_TOO_SLOW -> 3;
                default -> 1;
            };
        } catch (IOException failure) {
            errors.println("Error: EVENT_STREAM_IO_FAILED");
            return 1;
        }
    }
}
