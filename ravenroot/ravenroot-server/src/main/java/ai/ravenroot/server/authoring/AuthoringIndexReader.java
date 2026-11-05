package ai.ravenroot.server.authoring;

import ai.ravenroot.api.authoring.GraphAuthoringException;
import ai.ravenroot.api.authoring.GraphAuthoringRepository.Actor;
import ai.ravenroot.api.authoring.GraphAuthoringRepository.DocumentSummary;
import ai.ravenroot.api.authoring.GraphAuthoringRepository.Page;
import ai.ravenroot.api.authoring.GraphAuthoringRepository.Revision;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Provider-neutral, pinned incremental synchronization. Bodies are read only for changed
 * identities.
 */
final class AuthoringIndexReader implements AutoCloseable {
    private final ExecutorService loaders =
            new ThreadPoolExecutor(
                    16,
                    16,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(64),
                    Thread.ofVirtual().factory());
    private final long synchronizationTimeoutNanos;

    private record Prepared(String name, Metadata draft, Metadata release, String publication) {}

    record Blob(String identity, Supplier<byte[]> bytes) {}

    record Metadata(String graphId, long version, String digest) {}

    private final GraphAuthoringReadIndex index;
    private final String repository;
    private final int size;
    private final ReentrantLock[] locks = new ReentrantLock[64];
    volatile long lastValidatedMillis;
    final LongAdder synchronizations = new LongAdder(),
            synchronizationNanos = new LongAdder(),
            reused = new LongAdder();

    AuthoringIndexReader(GraphAuthoringReadIndex index, GraphAuthoringConfiguration configuration) {
        this.index = index;
        synchronizationTimeoutNanos =
                configuration
                        .requestTimeout()
                        .multipliedBy(configuration.retryLimit() + 1L)
                        .toNanos();
        this.size = configuration.pageSize();
        // Includes namespace and credential mode/reference, never the resolved credential or its
        // digest.
        this.repository =
                ReadObjects.sha256(
                        (configuration.apiBase()
                                        + "\n"
                                        + configuration.provider()
                                        + "\n"
                                        + configuration.project()
                                        + "\n"
                                        + configuration.graphDirectory()
                                        + "\n"
                                        + configuration.draftBranch()
                                        + "\n"
                                        + configuration.releaseBranch()
                                        + "\n"
                                        + configuration.credentialMode()
                                        + "\n"
                                        + configuration.credentialReference()
                                        + "\n"
                                        + configuration.appId()
                                        + "\n"
                                        + configuration.installationId()
                                        + "\n"
                                        + configuration.artifactBase()
                                        + "\n"
                                        + configuration.artifactCatalogPath()
                                        + "\n"
                                        + configuration.maxDocumentBytes()
                                        + "\n"
                                        + new TreeMap<>(configuration.tenantNamespaces()))
                                .getBytes(StandardCharsets.UTF_8));
        Arrays.setAll(locks, ignored -> new ReentrantLock());
    }

    String repository() {
        return repository;
    }

    GraphAuthoringReadIndex index() {
        return index;
    }

    Page<DocumentSummary> list(
            Actor actor,
            String cursor,
            String draft,
            String release,
            PublicationEvidence publications,
            Supplier<Map<String, Blob>> drafts,
            Supplier<Map<String, Blob>> releases) {
        int page = 0;
        String generation = null;
        if (cursor != null && cursor.startsWith("g1.")) {
            String[] parts = cursor.split("\\.", -1);
            if (parts.length != 3 || !parts[1].matches("[0-9a-f-]{36}")) throw invalid();
            generation = parts[1];
            page = page(parts[2]);
        } else page = page(cursor);
        if (generation != null)
            return index.page(repository, actor.tenantId(), generation, page, size);
        var lock = locks[Math.floorMod(actor.tenantId().hashCode(), locks.length)];
        try {
            lock.lockInterruptibly();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
        try {
            long start = System.nanoTime();
            PublicationEvidence observation = publications.snapshot();
            String publicationVersion = observation.version();
            var latest = index.latest(repository, actor.tenantId());
            String draftHead = draft == null ? "" : draft,
                    releaseHead = release == null ? "" : release;
            if (latest.isPresent()
                    && publicationVersion != null
                    && latest.get().draftHead().equals(draftHead)
                    && latest.get().releaseHead().equals(releaseHead)
                    && latest.get().publicationVersion().equals(publicationVersion)) {
                lastValidatedMillis = System.currentTimeMillis();
                reused.increment();
                return index.page(repository, actor.tenantId(), latest.get().id(), page, size);
            }
            var previous = new HashMap<String, GraphAuthoringReadIndex.Entry>();
            latest.ifPresent(
                    value ->
                            index.entries(repository, actor.tenantId(), value.id())
                                    .forEach(
                                            entry ->
                                                    previous.put(
                                                            entry.summary().documentId(), entry)));
            Map<String, Blob> draftBlobs = drafts.get(), releaseBlobs = releases.get();
            var names = new TreeSet<String>();
            names.addAll(draftBlobs.keySet());
            names.addAll(releaseBlobs.keySet());
            if (names.size() > 100_000)
                throw new GraphAuthoringException(GraphAuthoringException.Failure.LIMIT_EXCEEDED);
            var result = new ArrayList<GraphAuthoringReadIndex.Entry>();
            var prepared =
                    prepare(
                            actor,
                            names,
                            draftBlobs,
                            releaseBlobs,
                            previous,
                            releaseHead,
                            observation);
            for (String name : names) {
                Blob d = draftBlobs.get(name), r = releaseBlobs.get(name);
                Prepared projected = prepared.get(name);
                Metadata dm = projected.draft(), rm = projected.release();
                Metadata selected = dm != null ? dm : rm;
                String publication = projected.publication();
                var summary =
                        new DocumentSummary(
                                name,
                                name,
                                selected.graphId(),
                                selected.version(),
                                new Revision(
                                        d == null ? "absent" : draftHead + ":" + d.identity(),
                                        r == null ? "absent" : releaseHead + ":" + r.identity(),
                                        publication),
                                r != null,
                                !"absent".equals(publication),
                                d == null && r != null,
                                rm == null ? "" : rm.graphId(),
                                rm == null ? 0 : rm.version());
                result.add(
                        new GraphAuthoringReadIndex.Entry(
                                summary,
                                d == null ? "" : d.identity(),
                                r == null ? "" : r.identity(),
                                dm == null ? "" : dm.digest(),
                                rm == null ? "" : rm.digest()));
            }
            var completed =
                    new GraphAuthoringReadIndex.Generation(
                            UUID.randomUUID().toString(),
                            draftHead,
                            releaseHead,
                            publicationVersion == null ? "" : publicationVersion,
                            System.currentTimeMillis());
            index.publish(repository, actor.tenantId(), completed, result);
            lastValidatedMillis = System.currentTimeMillis();
            synchronizations.increment();
            synchronizationNanos.add(System.nanoTime() - start);
            return index.page(repository, actor.tenantId(), completed.id(), page, size);
        } finally {
            lock.unlock();
        }
    }

    private Map<String, Prepared> prepare(
            Actor actor,
            SortedSet<String> names,
            Map<String, Blob> drafts,
            Map<String, Blob> releases,
            Map<String, GraphAuthoringReadIndex.Entry> previous,
            String releaseHead,
            PublicationEvidence publications) {
        var completion = new ExecutorCompletionService<Prepared>(loaders);
        var outstanding = new HashSet<Future<Prepared>>();
        var result = new HashMap<String, Prepared>();
        var iterator = names.iterator();
        long deadline = System.nanoTime() + synchronizationTimeoutNanos;
        try {
            while (iterator.hasNext() || !outstanding.isEmpty()) {
                while (iterator.hasNext() && outstanding.size() < 16) {
                    String name = iterator.next();
                    outstanding.add(
                            completion.submit(
                                    () -> {
                                        Blob draft = drafts.get(name), release = releases.get(name);
                                        var old = previous.get(name);
                                        Metadata dm = metadata(draft, old),
                                                rm = metadata(release, old);
                                        if (dm != null
                                                && rm != null
                                                && !dm.graphId().equals(rm.graphId()))
                                            throw invalid();
                                        String publication =
                                                rm == null
                                                        ? "absent"
                                                        : publications.tokenByDigest(
                                                                actor.tenantId(),
                                                                rm.graphId(),
                                                                rm.version(),
                                                                releaseHead,
                                                                rm.digest(),
                                                                release.bytes());
                                        return new Prepared(name, dm, rm, publication);
                                    }));
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw unavailable();
                var completed = completion.poll(remaining, TimeUnit.NANOSECONDS);
                if (completed == null) throw unavailable();
                outstanding.remove(completed);
                Prepared projected = completed.get();
                result.put(projected.name(), projected);
            }
            return result;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException classified) throw classified;
            throw unavailable();
        } finally {
            outstanding.forEach(future -> future.cancel(true));
        }
    }

    private static GraphAuthoringException unavailable() {
        return new GraphAuthoringException(GraphAuthoringException.Failure.UNAVAILABLE);
    }

    @Override
    public void close() {
        try {
            loaders.close();
        } finally {
            index.close();
        }
    }

    private Metadata metadata(Blob blob, GraphAuthoringReadIndex.Entry old) {
        if (blob == null) return null;
        if (old != null) {
            var summary = old.summary();
            if (blob.identity().equals(old.draftBlob()))
                return new Metadata(summary.graphId(), summary.releaseVersion(), old.draftDigest());
            if (blob.identity().equals(old.releaseBlob()))
                return new Metadata(
                        summary.releasedGraphId(), summary.releasedVersion(), old.releaseDigest());
        }
        byte[] bytes = blob.bytes().get();
        var metadata = GraphReleaseMetadata.read(bytes);
        if (metadata == null) throw invalid();
        return new Metadata(
                metadata.graphId(), metadata.releaseVersion(), ReadObjects.sha256(bytes));
    }

    private static int page(String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            int page = Integer.parseInt(cursor);
            if (page < 0 || page > 100_000) throw invalid();
            return page;
        } catch (NumberFormatException failure) {
            throw invalid();
        }
    }

    private static GraphAuthoringException invalid() {
        return new GraphAuthoringException(GraphAuthoringException.Failure.INVALID_DOCUMENT);
    }
}
