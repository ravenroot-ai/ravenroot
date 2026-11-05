package ai.ravenroot.server.authoring;

import ai.ravenroot.api.authoring.GraphAuthoringException;
import ai.ravenroot.api.authoring.GraphAuthoringRepository.DocumentSummary;
import ai.ravenroot.api.authoring.GraphAuthoringRepository.Page;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Bounded, nonpersistent index for explicitly constructed adapters and deterministic fixtures. */
final class VolatileGraphAuthoringReadIndex implements GraphAuthoringReadIndex {
    private final LinkedHashMap<List<String>, Generation> generations = new LinkedHashMap<>();
    private final Map<List<String>, List<Entry>> entries = new HashMap<>();

    @Override
    public synchronized Optional<Generation> latest(String repository, String tenant) {
        return generations.entrySet().stream()
                .filter(
                        entry ->
                                entry.getKey().get(0).equals(repository)
                                        && entry.getKey().get(1).equals(tenant))
                .map(Map.Entry::getValue)
                .reduce((left, right) -> right);
    }

    @Override
    public synchronized List<Entry> entries(String repository, String tenant, String generation) {
        return entries.getOrDefault(List.of(repository, tenant, generation), List.of());
    }

    @Override
    public synchronized Page<DocumentSummary> page(
            String repository, String tenant, String generation, int page, int size) {
        var key = List.of(repository, tenant, generation);
        if (!generations.containsKey(key))
            throw new GraphAuthoringException(GraphAuthoringException.Failure.INVALID_DOCUMENT);
        List<Entry> values = entries.get(key);
        int start = (int) Math.min((long) page * size, values.size()),
                end = Math.min(start + size, values.size());
        return new Page<>(
                values.subList(start, end).stream().map(Entry::summary).toList(),
                end < values.size() ? "g1." + generation + "." + (page + 1) : "");
    }

    @Override
    public synchronized void publish(
            String repository, String tenant, Generation generation, List<Entry> values) {
        var key = List.of(repository, tenant, generation.id());
        generations.put(key, generation);
        entries.put(key, List.copyOf(values));
        while (generations.size() > 16) {
            var first = generations.keySet().iterator().next();
            generations.remove(first);
            entries.remove(first);
        }
    }

    @Override
    public Optional<byte[]> content(String repository, String identity) {
        return Optional.empty();
    }

    @Override
    public void retainContent(String repository, String identity, byte[] bytes) {}

    @Override
    public long retainedBytes() {
        return 0;
    }
}
