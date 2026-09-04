package ai.ravenroot.extensions.storage;

import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.node.service.OutboundHttpResponse;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StorageBehaviorTest {
    @Test void listPaginatesAcrossRestartWithTenantAndScopeBoundOpaqueCursor() {
        StorageProfile profile = StorageTestSupport.profile(Set.of(StorageProfile.Operation.LIST), 2, 10);
        StorageTestSupport.HttpDouble firstHttp = new StorageTestSupport.HttpDouble();
        firstHttp.response = CompletableFuture.completedFuture(xml("""
                <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <IsTruncated>true</IsTruncated>
                  <NextContinuationToken>opaque+/=token</NextContinuationToken>
                  <Contents><Key>tenant-data/folder/a.txt</Key><Size>4</Size><ETag>"a"</ETag>
                    <Owner><DisplayName>secret-owner</DisplayName></Owner></Contents>
                  <Contents><Key>tenant-data/folder/b.txt</Key><Size>5</Size><ETag>"b"</ETag></Contents>
                </ListBucketResult>
                """));
        Map<String, Object> properties = Map.of("storageProfile", "assets", "prefix", "folder", "maxResults", "2",
                "projection", "size,etag");
        NodeAction first = new ObjectListNodeBehavior(new StorageRuntime(name -> java.util.Optional.of(profile)))
                .create(new ai.ravenroot.api.node.NodeConfiguration("list", ObjectListNodeBehavior.BEHAVIOR,
                        properties), firstHttp);

        Map<?, ?> pageOne = (Map<?, ?>) first.handle(StorageTestSupport.message("tenant-a",
                Map.of("version", "object.list.v1"))).toCompletableFuture().join().payload();
        assertEquals("object.list.result.v1", pageOne.get("version"));
        assertEquals(2, ((List<?>) pageOne.get("objects")).size());
        Map<?, ?> firstObject = (Map<?, ?>) ((List<?>) pageOne.get("objects")).getFirst();
        assertEquals(Map.of("key", "folder/a.txt", "size", 4L, "etag", "\"a\""), firstObject);
        assertFalse(pageOne.toString().contains("secret-owner"));
        String cursor = (String) pageOne.get("cursor");
        assertFalse(cursor.contains("opaque"));
        assertEquals("list-type=2&max-keys=2&prefix=tenant-data%2Ffolder",
                firstHttp.request.get().destination().getRawQuery());

        StorageTestSupport.HttpDouble secondHttp = new StorageTestSupport.HttpDouble();
        secondHttp.response = CompletableFuture.completedFuture(xml("""
                <ListBucketResult><IsTruncated>false</IsTruncated>
                  <Contents><Key>tenant-data/folder/c.txt</Key><Size>6</Size><ETag>"c"</ETag></Contents>
                </ListBucketResult>
                """));
        NodeAction restarted = new ObjectListNodeBehavior(new StorageRuntime(name -> java.util.Optional.of(profile)))
                .create(new ai.ravenroot.api.node.NodeConfiguration("list", ObjectListNodeBehavior.BEHAVIOR,
                        properties), secondHttp);
        Map<?, ?> pageTwo = (Map<?, ?>) restarted.handle(StorageTestSupport.message("tenant-a",
                Map.of("version", "object.list.v1", "cursor", cursor))).toCompletableFuture().join().payload();
        assertEquals(false, pageTwo.get("truncated"));
        assertFalse(pageTwo.containsKey("cursor"));
        assertTrue(secondHttp.request.get().destination().getRawQuery()
                .contains("continuation-token=opaque%2B%2F%3Dtoken"));

        int calls = secondHttp.calls.get();
        assertEquals(StorageException.Code.INVALID_INPUT, failure(restarted.handle(
                StorageTestSupport.message("tenant-b", Map.of("version", "object.list.v1", "cursor", cursor)))).code());
        assertEquals(calls, secondHttp.calls.get());

        StorageProfile rotated = new StorageProfile("assets", profile.origin(), profile.region(), profile.bucket(),
                profile.keyPrefix(), profile.addressingStyle(), "rotated-signing-binding", profile.allowedOperations(),
                profile.allowedContentTypes(), profile.allowIfMatch(), profile.allowIfNoneMatch(),
                profile.maxObjectBytes(), profile.timeoutMs(), profile.maxConcurrency(),
                profile.maxRequestsPerSecond());
        StorageTestSupport.HttpDouble rotatedHttp = new StorageTestSupport.HttpDouble();
        NodeAction rotatedAction = new ObjectListNodeBehavior(new StorageRuntime(name -> java.util.Optional.of(rotated)))
                .create(new ai.ravenroot.api.node.NodeConfiguration("list", ObjectListNodeBehavior.BEHAVIOR,
                        properties), rotatedHttp);
        assertEquals(StorageException.Code.INVALID_INPUT, failure(rotatedAction.handle(
                StorageTestSupport.message("tenant-a", Map.of("version", "object.list.v1", "cursor", cursor)))).code());
        assertEquals(0, rotatedHttp.calls.get());

        String[] cursorParts = cursor.split("\\.", -1);
        String substituted = cursorParts[0] + "." + cursorParts[1] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(
                "hostile-provider-token".getBytes(StandardCharsets.UTF_8));
        StorageTestSupport.HttpDouble substitutedHttp = new StorageTestSupport.HttpDouble();
        substitutedHttp.response = CompletableFuture.completedFuture(xml("""
                <ListBucketResult><IsTruncated>false</IsTruncated>
                  <Contents><Key>tenant-data/folder/confined.txt</Key><Size>7</Size><ETag>"safe"</ETag></Contents>
                </ListBucketResult>
                """));
        NodeAction substitutedAction = new ObjectListNodeBehavior(new StorageRuntime(name ->
                java.util.Optional.of(profile))).create(new ai.ravenroot.api.node.NodeConfiguration(
                        "list", ObjectListNodeBehavior.BEHAVIOR, properties), substitutedHttp);
        Map<?, ?> confined = (Map<?, ?>) substitutedAction.handle(StorageTestSupport.message("tenant-a",
                Map.of("version", "object.list.v1", "cursor", substituted))).toCompletableFuture().join().payload();
        assertEquals("folder/confined.txt", ((Map<?, ?>) ((List<?>) confined.get("objects")).getFirst()).get("key"));
        assertTrue(substitutedHttp.request.get().destination().getRawQuery()
                .contains("prefix=tenant-data%2Ffolder"));
        assertTrue(substitutedHttp.request.get().destination().getRawQuery()
                .contains("continuation-token=hostile-provider-token"));
        assertEquals("s3.example.test", substitutedHttp.request.get().destination().getHost());
    }

    @Test void listRetriesOnlyTransientReadsWithinOneAdmissionAndRejectsUnsafeXml() {
        StorageProfile profile = StorageTestSupport.profile(Set.of(StorageProfile.Operation.LIST), 1, 3);
        SequenceHttp http = new SequenceHttp(List.of(
                new OutboundHttpResponse(503, Map.of(), new byte[0]),
                xml("<ListBucketResult><IsTruncated>false</IsTruncated></ListBucketResult>")));
        NodeAction action = new ObjectListNodeBehavior(new StorageRuntime(name -> java.util.Optional.of(profile)))
                .create(new ai.ravenroot.api.node.NodeConfiguration("list", ObjectListNodeBehavior.BEHAVIOR,
                        Map.of("storageProfile", "assets", "retries", "1")), http);
        assertDoesNotThrow(() -> action.handle(StorageTestSupport.message(
                Map.of("version", "object.list.v1"))).toCompletableFuture().join());
        assertEquals(2, http.calls.get());
        assertTrue(http.requests.get(1).deadline().compareTo(http.requests.getFirst().deadline()) <= 0);

        StorageProfile oneRequest = StorageTestSupport.profile(Set.of(StorageProfile.Operation.LIST), 1, 1);
        SequenceHttp rateBound = new SequenceHttp(List.of(
                new OutboundHttpResponse(503, Map.of(), new byte[0]),
                xml("<ListBucketResult><IsTruncated>false</IsTruncated></ListBucketResult>")));
        NodeAction rateLimitedRetry = new ObjectListNodeBehavior(new StorageRuntime(name ->
                java.util.Optional.of(oneRequest))).create(new ai.ravenroot.api.node.NodeConfiguration(
                        "list", ObjectListNodeBehavior.BEHAVIOR,
                        Map.of("storageProfile", "assets", "retries", "1")), rateBound);
        assertEquals(StorageException.Code.RATE_LIMITED, failure(rateLimitedRetry.handle(
                StorageTestSupport.message(Map.of("version", "object.list.v1")))).code());
        assertEquals(1, rateBound.calls.get(), "a retry consumes a fresh profile rate token before transport");

        SequenceHttp oversized = new SequenceHttp(List.of(
                new OutboundHttpResponse(503, Map.of(), new byte[1025]),
                xml("<ListBucketResult><IsTruncated>false</IsTruncated></ListBucketResult>")));
        NodeAction responseBound = new ObjectListNodeBehavior(new StorageRuntime(name ->
                java.util.Optional.of(profile))).create(new ai.ravenroot.api.node.NodeConfiguration(
                        "list", ObjectListNodeBehavior.BEHAVIOR,
                        Map.of("storageProfile", "assets", "retries", "1")), oversized);
        assertEquals(StorageException.Code.RESPONSE_TOO_LARGE, failure(responseBound.handle(
                StorageTestSupport.message(Map.of("version", "object.list.v1")))).code());
        assertEquals(1, oversized.calls.get(), "oversized retryable responses are rejected before retry");

        StorageTestSupport.HttpDouble hostile = new StorageTestSupport.HttpDouble();
        hostile.response = CompletableFuture.completedFuture(xml("""
                <!DOCTYPE ListBucketResult [<!ENTITY xxe SYSTEM "file:///private/secret">]>
                <ListBucketResult><IsTruncated>false</IsTruncated><Contents>
                  <Key>&xxe;</Key></Contents></ListBucketResult>
                """));
        NodeAction strict = new ObjectListNodeBehavior(new StorageRuntime(name -> java.util.Optional.of(profile)))
                .create(new ai.ravenroot.api.node.NodeConfiguration("list", ObjectListNodeBehavior.BEHAVIOR,
                        Map.of("storageProfile", "assets")), hostile);
        StorageException rejected = failure(strict.handle(StorageTestSupport.message(
                Map.of("version", "object.list.v1"))));
        assertEquals(StorageException.Code.RESPONSE_INVALID, rejected.code());
        assertFalse(rejected.getMessage().contains("private"));
    }

    @Test void listCannotEscapeProfilePrefixOrConfiguredBounds() {
        StorageProfile profile = StorageTestSupport.profile(Set.of(StorageProfile.Operation.LIST), 2, 10);
        StorageTestSupport.HttpDouble http = new StorageTestSupport.HttpDouble();
        http.response = CompletableFuture.completedFuture(xml("""
                <ListBucketResult><IsTruncated>false</IsTruncated>
                  <Contents><Key>other-tenant/secret</Key></Contents></ListBucketResult>
                """));
        NodeAction action = new ObjectListNodeBehavior(new StorageRuntime(name -> java.util.Optional.of(profile)))
                .create(new ai.ravenroot.api.node.NodeConfiguration("list", ObjectListNodeBehavior.BEHAVIOR,
                        Map.of("storageProfile", "assets", "prefix", "folder", "maxResults", "1",
                                "endpoint", "https://attacker.invalid", "bucket", "other")), http);
        assertEquals(StorageException.Code.RESPONSE_INVALID, failure(action.handle(StorageTestSupport.message(
                Map.of("version", "object.list.v1")))).code());
        assertEquals("s3.example.test", http.request.get().destination().getHost());
        assertTrue(http.request.get().destination().getRawQuery().contains("prefix=tenant-data%2Ffolder"));

        http.response = CompletableFuture.completedFuture(xml("""
                <ListBucketResult><IsTruncated>false</IsTruncated>
                  <Contents><Key>tenant-data/folder/a</Key></Contents>
                  <Contents><Key>tenant-data/folder/b</Key></Contents></ListBucketResult>
                """));
        assertEquals(StorageException.Code.RESPONSE_INVALID, failure(action.handle(StorageTestSupport.message(
                Map.of("version", "object.list.v1")))).code());
    }

    @Test void cancellingListStopsTheActiveCallAndPreventsConfiguredRetry() {
        StorageProfile profile = StorageTestSupport.profile(Set.of(StorageProfile.Operation.LIST), 1, 10);
        StorageTestSupport.HttpDouble http = new StorageTestSupport.HttpDouble();
        http.response = new CompletableFuture<>();
        NodeAction action = new ObjectListNodeBehavior(new StorageRuntime(name -> java.util.Optional.of(profile)))
                .create(new ai.ravenroot.api.node.NodeConfiguration("list", ObjectListNodeBehavior.BEHAVIOR,
                        Map.of("storageProfile", "assets", "retries", "3")), http);
        CompletableFuture<NodeResult> result = action.handle(StorageTestSupport.message(
                Map.of("version", "object.list.v1"))).toCompletableFuture();
        assertTrue(result.cancel(true));
        assertEquals(StorageException.Code.DEADLINE_EXCEEDED, failure(result).code());
        assertEquals(1, http.calls.get());
        assertEquals(1, http.cancellations.get());
    }

    @Test void listXmlRejectsInternalEntitiesDepthAndElementFloodsWithSanitizedFailures() {
        StorageProfile profile = new StorageProfile("assets", java.net.URI.create("https://s3.example.test"),
                "eu-west-1", "bucket-a", "tenant-data", StorageProfile.AddressingStyle.PATH, "assets-s3",
                Set.of(StorageProfile.Operation.LIST), Set.of(), false, false,
                1024 * 1024, 2000, 1, 100);
        assertInvalidListXml(profile, """
                <!DOCTYPE ListBucketResult [<!ENTITY internal "tenant-data/folder/secret">]>
                <ListBucketResult><IsTruncated>false</IsTruncated>
                  <Contents><Key>&internal;</Key></Contents></ListBucketResult>
                """);
        assertInvalidListXml(profile, "<ListBucketResult><a><b><c><d><e><f><g><h/></g></f></e></d></c></b></a>"
                + "<IsTruncated>false</IsTruncated></ListBucketResult>");
        assertInvalidListXml(profile, "<ListBucketResult><IsTruncated>false</IsTruncated>"
                + "<Ignored/>".repeat(16_385) + "</ListBucketResult>");
    }

    @Test void unavailableManagedServiceFailsListAndDeleteBeforeAnyProviderTransport() {
        StorageProfile profile = StorageTestSupport.profile(Set.of(StorageProfile.Operation.LIST,
                StorageProfile.Operation.DELETE), 2, 10);
        StorageRuntime runtime = new StorageRuntime(name -> java.util.Optional.of(profile));
        NodeAction list = new ObjectListNodeBehavior(runtime).create(new ai.ravenroot.api.node.NodeConfiguration(
                "list", ObjectListNodeBehavior.BEHAVIOR, Map.of("storageProfile", "assets")),
                NodePackageServices.unavailable());
        NodeAction delete = new ObjectDeleteNodeBehavior(runtime).create(StorageTestSupport.configuration(
                ObjectDeleteNodeBehavior.BEHAVIOR), NodePackageServices.unavailable());
        assertEquals(StorageException.Code.CAPACITY_UNAVAILABLE, failure(list.handle(StorageTestSupport.message(
                Map.of("version", "object.list.v1")))).code());
        assertEquals(StorageException.Code.CAPACITY_UNAVAILABLE, failure(delete.handle(StorageTestSupport.message(
                Map.of("version", "object.delete.v1")))).code());
    }

    @Test void deleteIsSingleAttemptVersionScopedIdempotentAndSanitized() {
        StorageProfile profile = StorageTestSupport.profile(Set.of(StorageProfile.Operation.DELETE,
                StorageProfile.Operation.DELETE_VERSION), 2, 10);
        StorageTestSupport.HttpDouble http = new StorageTestSupport.HttpDouble();
        http.response = CompletableFuture.completedFuture(new OutboundHttpResponse(204, Map.of(), new byte[0]));
        NodeAction action = new ObjectDeleteNodeBehavior(new StorageRuntime(name -> java.util.Optional.of(profile)))
                .create(StorageTestSupport.configuration(ObjectDeleteNodeBehavior.BEHAVIOR), http);
        Map<?, ?> deleted = (Map<?, ?>) action.handle(StorageTestSupport.message(Map.of(
                "version", "object.delete.v1", "versionId", "v+/=1"))).toCompletableFuture().join().payload();
        assertEquals(Map.of("version", "object.delete.result.v1", "status", "DELETED", "deleted", true), deleted);
        assertEquals("DELETE", http.request.get().method());
        assertEquals("versionId=v%2B%2F%3D1", http.request.get().destination().getRawQuery());
        assertEquals(1, http.calls.get(), "delete performs no HEAD or automatic retry");

        http.response = CompletableFuture.completedFuture(new OutboundHttpResponse(404, Map.of(),
                "<Error><Message>sensitive remote detail</Message></Error>".getBytes(StandardCharsets.UTF_8)));
        Map<?, ?> missing = (Map<?, ?>) action.handle(StorageTestSupport.message(
                Map.of("version", "object.delete.v1"))).toCompletableFuture().join().payload();
        assertEquals("NOT_FOUND", missing.get("status"));
        assertEquals(false, missing.get("deleted"));
        assertFalse(missing.toString().contains("sensitive"));
    }

    @Test void deleteVersionRequiresSeparateAuthorityAndUncertaintyNeverRetries() {
        StorageProfile profile = StorageTestSupport.profile(Set.of(StorageProfile.Operation.DELETE), 2, 10);
        StorageTestSupport.HttpDouble http = new StorageTestSupport.HttpDouble();
        NodeAction action = new ObjectDeleteNodeBehavior(new StorageRuntime(name -> java.util.Optional.of(profile)))
                .create(StorageTestSupport.configuration(ObjectDeleteNodeBehavior.BEHAVIOR), http);
        assertEquals(StorageException.Code.INVALID_INPUT, failure(action.handle(StorageTestSupport.message(Map.of(
                "version", "object.delete.v1", "versionId", "not-authorized")))).code());
        assertEquals(0, http.calls.get());

        http.response = CompletableFuture.failedFuture(new NodePackageServiceException(
                NodePackageServiceException.Reason.TRANSPORT_FAILED));
        assertEquals(StorageException.Code.AMBIGUOUS, failure(action.handle(StorageTestSupport.message(
                Map.of("version", "object.delete.v1")))).code());
        assertEquals(1, http.calls.get());
    }

    @Test void getUsesManagedS3SigningAndProjectsBoundedTextMetadata() {
        StorageTestSupport.HttpDouble http = new StorageTestSupport.HttpDouble();
        ObjectGetNodeBehavior behavior = new ObjectGetNodeBehavior(new StorageRuntime(name ->
                java.util.Optional.of(StorageTestSupport.profile(Set.of(StorageProfile.Operation.GET), 2, 10))));
        NodeAction action = behavior.create(StorageTestSupport.configuration(ObjectGetNodeBehavior.BEHAVIOR), http);

        NodeResult result = action.handle(StorageTestSupport.message(Map.of("version", "object.get.v1")))
                .toCompletableFuture().join();

        assertEquals("https://s3.example.test/bucket-a/tenant-data/folder/object.txt",
                http.request.get().destination().toASCIIString());
        assertEquals("GET", http.request.get().method());
        assertEquals("assets-s3", http.request.get().signing().orElseThrow().bindingId());
        assertTrue(http.request.get().credential().isEmpty());
        assertFalse(http.request.get().headers().containsKey("authorization"));
        assertEquals("tenant-a", http.message.get().tenantId());
        Map<?, ?> output = (Map<?, ?>) result.payload();
        assertEquals("object.get.result.v1", output.get("version"));
        assertEquals(Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8)), output.get("base64"));
        assertEquals(5L, output.get("bytes"));
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", output.get("sha256"));
        assertEquals("\"etag-1\"", output.get("etag"));
        assertEquals("version-1", output.get("versionId"));
    }

    @Test void putSendsOneBodyAndOnlyProfileAuthorizedConditionals() {
        StorageTestSupport.HttpDouble http = new StorageTestSupport.HttpDouble();
        http.response = CompletableFuture.completedFuture(StorageTestSupport.response(201, new byte[0]));
        ObjectPutNodeBehavior behavior = new ObjectPutNodeBehavior(new StorageRuntime(name ->
                java.util.Optional.of(StorageTestSupport.profile(Set.of(StorageProfile.Operation.PUT), 2, 10))));
        NodeAction action = behavior.create(StorageTestSupport.configuration(ObjectPutNodeBehavior.BEHAVIOR), http);
        NodeResult result = action.handle(StorageTestSupport.message(Map.of("version", "object.put.v1",
                        "text", "hello", "contentType", "text/plain", "ifMatch", "\"old\"",
                        "ifNoneMatch", "*"))).toCompletableFuture().join();

        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), http.request.get().body());
        assertEquals(List.of("text/plain"), http.request.get().headers().get("content-type"));
        assertEquals(List.of("\"old\""), http.request.get().headers().get("if-match"));
        assertEquals(List.of("*"), http.request.get().headers().get("if-none-match"));
        Map<?, ?> output = (Map<?, ?>) result.payload();
        assertEquals("object.put.result.v1", output.get("version"));
        assertEquals(5L, output.get("bytes"));
    }

    @Test void inputAndResponseCeilingsRefuseWithoutLeakingRemoteData() {
        StorageProfile profile = new StorageProfile("assets", java.net.URI.create("https://s3.example.test"),
                "eu-west-1", "bucket-a", "", StorageProfile.AddressingStyle.PATH, "sign",
                Set.of(StorageProfile.Operation.GET, StorageProfile.Operation.PUT), Set.of(), false, false,
                4, 1000, 2, 10);
        StorageRuntime runtime = new StorageRuntime(name -> java.util.Optional.of(profile));
        StorageTestSupport.HttpDouble http = new StorageTestSupport.HttpDouble();
        NodeAction put = new ObjectPutNodeBehavior(runtime).create(
                new ai.ravenroot.api.node.NodeConfiguration("put", ObjectPutNodeBehavior.BEHAVIOR,
                        Map.of("storageProfile", "assets", "key", "key")), http);
        http.response = CompletableFuture.completedFuture(StorageTestSupport.response(200, new byte[0]));
        assertDoesNotThrow(() -> put.handle(StorageTestSupport.message(
                Map.of("version", "object.put.v1", "base64", "MTIzNA=="))).toCompletableFuture().join());
        assertEquals(StorageException.Code.REQUEST_TOO_LARGE, failure(put.handle(StorageTestSupport.message(
                Map.of("version", "object.put.v1", "text", "12345")))).code());
        assertEquals(1, http.calls.get());

        http.response = CompletableFuture.completedFuture(StorageTestSupport.response(200, "12345".getBytes(StandardCharsets.UTF_8)));
        NodeAction get = new ObjectGetNodeBehavior(runtime).create(
                new ai.ravenroot.api.node.NodeConfiguration("get", ObjectGetNodeBehavior.BEHAVIOR,
                        Map.of("storageProfile", "assets", "key", "key")), http);
        StorageException response = failure(get.handle(StorageTestSupport.message(Map.of("version", "object.get.v1"))));
        assertEquals(StorageException.Code.RESPONSE_TOO_LARGE, response.code());
        assertFalse(response.getMessage().contains("12345"));
    }

    @Test void managedSecurityFailuresRemainStableAndSanitized() {
        StorageRuntime runtime = new StorageRuntime(name -> java.util.Optional.of(
                StorageTestSupport.profile(Set.of(StorageProfile.Operation.GET), 2, 10)));
        StorageTestSupport.HttpDouble http = new StorageTestSupport.HttpDouble();
        http.synchronousFailure = new NodePackageServiceException(NodePackageServiceException.Reason.TLS_REFUSED);
        NodeAction action = new ObjectGetNodeBehavior(runtime).create(
                StorageTestSupport.configuration(ObjectGetNodeBehavior.BEHAVIOR), http);
        StorageException failure = failure(action.handle(StorageTestSupport.message(
                Map.of("version", "object.get.v1"))));
        assertEquals(StorageException.Code.TLS_REFUSED, failure.code());
        assertEquals("Object storage failed: TLS_REFUSED", failure.getMessage());
    }

    @Test void redirectAndMalformedMetadataAreRejected() {
        StorageRuntime runtime = new StorageRuntime(name -> java.util.Optional.of(
                StorageTestSupport.profile(Set.of(StorageProfile.Operation.GET), 2, 10)));
        NodeAction action;
        StorageTestSupport.HttpDouble http = new StorageTestSupport.HttpDouble();
        action = new ObjectGetNodeBehavior(runtime).create(StorageTestSupport.configuration(ObjectGetNodeBehavior.BEHAVIOR), http);
        http.response = CompletableFuture.completedFuture(StorageTestSupport.response(307, new byte[0]));
        assertEquals(StorageException.Code.REDIRECT_REFUSED, failure(action.handle(
                StorageTestSupport.message(Map.of("version", "object.get.v1")))).code());
        http.response = CompletableFuture.completedFuture(new OutboundHttpResponse(200, Map.of(), new byte[0]));
        assertEquals(StorageException.Code.RESPONSE_INVALID, failure(action.handle(
                StorageTestSupport.message(Map.of("version", "object.get.v1")))).code());
    }

    @Test void putTransportUncertaintyAndDirectCancellationAreAlwaysAmbiguous() {
        StorageRuntime runtime = new StorageRuntime(name -> java.util.Optional.of(
                StorageTestSupport.profile(Set.of(StorageProfile.Operation.PUT), 2, 10)));
        StorageTestSupport.HttpDouble http = new StorageTestSupport.HttpDouble();
        NodeAction action = new ObjectPutNodeBehavior(runtime).create(StorageTestSupport.configuration(
                ObjectPutNodeBehavior.BEHAVIOR), http);
        http.response = CompletableFuture.failedFuture(new NodePackageServiceException(
                NodePackageServiceException.Reason.TRANSPORT_FAILED));
        assertEquals(StorageException.Code.AMBIGUOUS, failure(action.handle(StorageTestSupport.message(
                Map.of("version", "object.put.v1", "text", "value")))).code());

        http.response = new CompletableFuture<>();
        CompletableFuture<NodeResult> result = action.handle(StorageTestSupport.message(
                Map.of("version", "object.put.v1", "text", "value"))).toCompletableFuture();
        assertTrue(result.cancel(true));
        assertEquals(StorageException.Code.AMBIGUOUS, failure(result).code());
        assertEquals(1, http.cancellations.get());
    }

    @Test void sharedProfileConcurrencyCoversGetAndPutAndReleasesAfterSettlement() {
        StorageProfile profile = StorageTestSupport.profile(Set.of(StorageProfile.Operation.GET,
                StorageProfile.Operation.PUT), 1, 10);
        StorageNodePackage nodePackage = new StorageNodePackage(name -> java.util.Optional.of(profile));
        StorageTestSupport.HttpDouble firstHttp = new StorageTestSupport.HttpDouble();
        firstHttp.response = new CompletableFuture<>();
        StorageTestSupport.HttpDouble secondHttp = new StorageTestSupport.HttpDouble();
        secondHttp.response = CompletableFuture.completedFuture(StorageTestSupport.response(200, new byte[0]));
        NodeAction get = behavior(nodePackage, ObjectGetNodeBehavior.BEHAVIOR)
                .create(StorageTestSupport.configuration(ObjectGetNodeBehavior.BEHAVIOR), firstHttp);
        NodeAction put = behavior(nodePackage, ObjectPutNodeBehavior.BEHAVIOR)
                .create(StorageTestSupport.configuration(ObjectPutNodeBehavior.BEHAVIOR), secondHttp);

        CompletionStage<NodeResult> pending = get.handle(StorageTestSupport.message(Map.of("version", "object.get.v1")));
        assertEquals(StorageException.Code.CAPACITY_UNAVAILABLE, failure(put.handle(StorageTestSupport.message(
                Map.of("version", "object.put.v1", "text", "value")))).code());
        assertEquals(0, secondHttp.calls.get());
        firstHttp.response.complete(StorageTestSupport.response(200, "ok".getBytes(StandardCharsets.UTF_8)));
        pending.toCompletableFuture().join();
        assertDoesNotThrow(() -> put.handle(StorageTestSupport.message(
                Map.of("version", "object.put.v1", "text", "value"))).toCompletableFuture().join());
    }

    @Test void profileRateLimitAppliesAcrossSequentialOperations() {
        StorageProfile profile = StorageTestSupport.profile(Set.of(StorageProfile.Operation.GET), 2, 1);
        ObjectGetNodeBehavior behavior = new ObjectGetNodeBehavior(new StorageRuntime(name -> java.util.Optional.of(profile)));
        StorageTestSupport.HttpDouble http = new StorageTestSupport.HttpDouble();
        NodeAction action = behavior.create(StorageTestSupport.configuration(ObjectGetNodeBehavior.BEHAVIOR), http);
        action.handle(StorageTestSupport.message(Map.of("version", "object.get.v1"))).toCompletableFuture().join();
        assertEquals(StorageException.Code.RATE_LIMITED, failure(action.handle(StorageTestSupport.message(
                Map.of("version", "object.get.v1")))).code());
        assertEquals(1, http.calls.get());
    }

    @Test void disallowedOperationAndConditionalsFailBeforeTransport() {
        StorageProfile getOnly = StorageTestSupport.profile(Set.of(StorageProfile.Operation.GET), 2, 10);
        assertEquals(StorageException.Code.CONFIGURATION, assertThrows(StorageException.class, () ->
                new ObjectPutNodeBehavior(new StorageRuntime(name -> java.util.Optional.of(getOnly)))
                        .create(StorageTestSupport.configuration(ObjectPutNodeBehavior.BEHAVIOR),
                                new StorageTestSupport.HttpDouble())).code());

        StorageProfile noConditions = new StorageProfile("assets", java.net.URI.create("https://s3.example.test"),
                "eu-west-1", "bucket-a", "", StorageProfile.AddressingStyle.PATH, "sign",
                Set.of(StorageProfile.Operation.PUT), Set.of(), false, false, 1024, 1000, 2, 10);
        StorageTestSupport.HttpDouble http = new StorageTestSupport.HttpDouble();
        NodeAction action = new ObjectPutNodeBehavior(new StorageRuntime(name -> java.util.Optional.of(noConditions)))
                .create(new ai.ravenroot.api.node.NodeConfiguration("put", ObjectPutNodeBehavior.BEHAVIOR,
                        Map.of("storageProfile", "assets", "key", "key")), http);
        assertEquals(StorageException.Code.INVALID_INPUT, failure(action.handle(StorageTestSupport.message(
                Map.of("version", "object.put.v1", "text", "x", "ifMatch", "*")))).code());
        assertEquals(0, http.calls.get());
    }

    private static NodeBehavior behavior(StorageNodePackage nodePackage, String name) {
        return nodePackage.behaviors().stream().filter(item -> item.descriptor().behavior().equals(name)).findFirst().orElseThrow();
    }

    private static StorageException failure(CompletionStage<?> stage) {
        try { stage.toCompletableFuture().join(); throw new AssertionError("expected failure"); }
        catch (CompletionException failure) { return (StorageException) failure.getCause(); }
    }

    private static OutboundHttpResponse xml(String xml) {
        return new OutboundHttpResponse(200, Map.of(), xml.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertInvalidListXml(StorageProfile profile, String xml) {
        StorageTestSupport.HttpDouble http = new StorageTestSupport.HttpDouble();
        http.response = CompletableFuture.completedFuture(xml(xml));
        NodeAction action = new ObjectListNodeBehavior(new StorageRuntime(name -> java.util.Optional.of(profile)))
                .create(new ai.ravenroot.api.node.NodeConfiguration("list", ObjectListNodeBehavior.BEHAVIOR,
                        Map.of("storageProfile", "assets")), http);
        StorageException failure = failure(action.handle(StorageTestSupport.message(
                Map.of("version", "object.list.v1"))));
        assertEquals(StorageException.Code.RESPONSE_INVALID, failure.code());
        assertEquals("Object storage failed: RESPONSE_INVALID", failure.getMessage());
    }

    private static final class SequenceHttp implements ai.ravenroot.api.node.service.NodePackageServices {
        private final List<OutboundHttpResponse> responses;
        private final List<ai.ravenroot.api.node.service.OutboundHttpRequest> requests = new java.util.ArrayList<>();
        private final AtomicInteger calls = new AtomicInteger();
        SequenceHttp(List<OutboundHttpResponse> responses) { this.responses = List.copyOf(responses); }
        @Override public Set<ai.ravenroot.api.node.service.NodePackageCapability> capabilities() {
            return Set.of(ai.ravenroot.api.node.service.NodePackageCapability.OUTBOUND_HTTP);
        }
        @Override public ai.ravenroot.api.node.service.NodeCredentialService credentials() {
            return ai.ravenroot.api.node.service.NodePackageServices.unavailable().credentials();
        }
        @Override public ai.ravenroot.api.node.service.OutboundHttpService outboundHttp() {
            return (message, request) -> {
                int index = calls.getAndIncrement();
                requests.add(request);
                return ai.ravenroot.api.node.service.OutboundCall.completed(responses.get(index));
            };
        }
        @Override public ai.ravenroot.api.node.service.OutboundWebSocketService outboundWebSocket() {
            return ai.ravenroot.api.node.service.NodePackageServices.unavailable().outboundWebSocket();
        }
    }
}
