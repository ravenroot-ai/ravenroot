package ai.ravenroot.core.security.nodepackage;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AwsSigV4SignerTest {
    private static final String EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final Instant AWS_S3_EXAMPLE_TIME = Instant.parse("2013-05-24T00:00:00Z");
    private static final char[] AWS_S3_EXAMPLE_CREDENTIAL = ("AKIAIOSFODNN7EXAMPLE\n"
            + "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY").toCharArray();

    @Test
    void matchesAwsPublishedS3GetObjectVector() {
        AwsSigV4Signer.Signed signed = sign(
                URI.create("https://examplebucket.s3.amazonaws.com/test.txt"),
                Map.of("range", List.of("bytes=0-9")), new byte[0]);

        assertTrue(signed.stringToSign().endsWith(
                "7344ae5b7ee6c3e7e6b0fe0640412a37625d1fbfff95c48bbb2dc43964946972"));
        assertEquals("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, "
                        + "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, Signature="
                        + "f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
                signed.headers().get("authorization").getFirst());
    }

    @Test
    void fixedS3PathProfilePreservesDotSegmentsRepeatedSeparatorsAndSingleEncoding() {
        // AWS S3's canonical-request specification explicitly forbids path normalization. This
        // fixed vector uses AWS's published example credentials/date and applies its documented
        // UriEncode rule exactly once to already-percent-encoded object-key data:
        // https://docs.aws.amazon.com/AmazonS3/latest/developerguide/sig-v4-header-based-auth.html
        AwsSigV4Signer.Signed signed = sign(URI.create(
                "https://examplebucket.s3.amazonaws.com/a/../b//c%2Fd/%252Fe"), Map.of(), new byte[0]);
        String canonicalRequest = "GET\n"
                + "/a/../b//c%2Fd/%252Fe\n\n"
                + "host:examplebucket.s3.amazonaws.com\n"
                + "x-amz-content-sha256:" + EMPTY_SHA256 + "\n"
                + "x-amz-date:20130524T000000Z\n\n"
                + "host;x-amz-content-sha256;x-amz-date\n" + EMPTY_SHA256;

        assertEquals(canonicalRequest, signed.canonicalRequest());
        assertTrue(signed.stringToSign().endsWith(
                "1ec226e80e3591e56ae8aa13802e342d000d7cde39c4c775a9855be59a54a2e4"));
        assertTrue(authorization(signed).endsWith(
                "Signature=51d47fb17e78910594195d2bc10a1c07b948bef4e1ee92687ee80fa513e49c7b"));
    }

    @Test
    void pathQueryHeaderAndBodyMutationsAllChangeSignature() {
        URI base = URI.create("https://examplebucket.s3.amazonaws.com/items/a%20b?b=2&a=1");
        Map<String, List<String>> headers = Map.of("x-test", List.of("one  two"));
        byte[] body = "body".getBytes(StandardCharsets.UTF_8);
        String signature = authorization(sign(base, headers, body));

        assertNotEquals(signature, authorization(sign(
                URI.create("https://examplebucket.s3.amazonaws.com/items/a%20c?b=2&a=1"), headers, body)));
        assertNotEquals(authorization(sign(
                        URI.create("https://examplebucket.s3.amazonaws.com/items/a/b"), headers, body)),
                authorization(sign(
                        URI.create("https://examplebucket.s3.amazonaws.com/items/a%2Fb"), headers, body)),
                "an encoded slash is request data, not a path separator");
        assertNotEquals(signature, authorization(sign(
                URI.create("https://examplebucket.s3.amazonaws.com/items/a%20b?b=3&a=1"), headers, body)));
        assertNotEquals(signature, authorization(sign(base, Map.of("x-test", List.of("changed")), body)));
        assertNotEquals(signature, authorization(sign(base, headers,
                "changed".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void temporaryCredentialAddsAndSignsSecurityToken() {
        char[] credential = ("AKIDEXAMPLE\nsecret\nsession-token").toCharArray();
        AwsSigV4Signer.Signed signed = AwsSigV4Signer.sign("GET",
                URI.create("https://s3.us-east-1.amazonaws.com/bucket/key"), Map.of(), new byte[0],
                AWS_S3_EXAMPLE_TIME, credential, "us-east-1", "s3");

        assertEquals(List.of("session-token"), signed.headers().get("x-amz-security-token"));
        assertTrue(signed.headers().get("authorization").getFirst()
                .contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date;x-amz-security-token"));
    }

    private static AwsSigV4Signer.Signed sign(URI uri, Map<String, List<String>> headers, byte[] body) {
        return AwsSigV4Signer.sign("GET", uri, headers, body, AWS_S3_EXAMPLE_TIME,
                AWS_S3_EXAMPLE_CREDENTIAL.clone(), "us-east-1", "s3");
    }

    private static String authorization(AwsSigV4Signer.Signed signed) {
        return signed.headers().get("authorization").getFirst();
    }
}
