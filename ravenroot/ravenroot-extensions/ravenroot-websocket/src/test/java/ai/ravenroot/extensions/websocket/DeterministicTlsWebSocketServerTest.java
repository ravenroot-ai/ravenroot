package ai.ravenroot.extensions.websocket;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeterministicTlsWebSocketServerTest {
    @Test
    void scriptedFramesAndCloseAreWrittenTogetherBeforeThePeerCanAbort() throws Exception {
        var bytes = new ByteArrayOutputStream();
        int[] writes = {0};
        OutputStream abortAfterFirstWrite = new OutputStream() {
            @Override public void write(int value) throws IOException {
                write(new byte[]{(byte) value});
            }
            @Override public void write(byte[] value) throws IOException {
                if (++writes[0] != 1) throw new IOException("peer aborted after the first TLS record");
                bytes.write(value);
            }
        };
        DeterministicTlsWebSocketServer.scripted(abortAfterFirstWrite, List.of(
                new DeterministicTlsWebSocketServer.ServerFrame(0x01, new byte[]{'a'}),
                new DeterministicTlsWebSocketServer.ServerFrame(0x80, new byte[]{'b'})));
        assertEquals(1, writes[0]);
        assertArrayEquals(new byte[]{0x01, 1, 'a', (byte) 0x80, 1, 'b',
                (byte) 0x88, 2, 0x03, (byte) 0xE8}, bytes.toByteArray());
    }

    @Test
    void scriptedWriteFailuresAreNotTreatedAsExpectedClientAborts() {
        IOException failure = new IOException("transport failure");
        OutputStream broken = new OutputStream() {
            @Override public void write(int value) throws IOException { throw failure; }
        };
        assertSame(failure, assertThrows(IOException.class, () ->
                DeterministicTlsWebSocketServer.scripted(broken, List.of(
                        new DeterministicTlsWebSocketServer.ServerFrame(0x81, new byte[]{'a'})))));
    }

    @Test
    void scriptsTooLargeForOneTlsRecordFailBeforeWriting() {
        var output = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> DeterministicTlsWebSocketServer.scripted(output, List.of(
                new DeterministicTlsWebSocketServer.ServerFrame(0x82, new byte[16 * 1024]))));
        assertEquals(0, output.size());
    }

    @Test
    void completionPublishesRealTlsProtocolFailures() throws Exception {
        try (var server = DeterministicTlsWebSocketServer.scripted(List.of(List.of()));
             var peer = server.trustedClientContext().getSocketFactory().createSocket("localhost", server.port())) {
            peer.getOutputStream().write("POST / HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            peer.getOutputStream().flush();
            var failure = assertThrows(AssertionError.class, server::awaitConnections);
            assertEquals("raw TLS fixture failed", failure.getMessage());
            assertEquals("invalid upgrade request", assertInstanceOf(IOException.class, failure.getCause()).getMessage());
        }
    }
}
