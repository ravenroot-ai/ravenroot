package ai.ravenroot.extensions.systemone;

import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict portable Choice/Noul/Score specification and response validator. */
final class SystemOneSpecification {
    static final String CONTRACT = "ravenroot.system-one/specification/1";
    static final String RESULT_CONTRACT = "ravenroot.system-one/result/1";
    private static final Set<String> ROOT_FIELDS = Set.of("contract", "id", "questions");
    private static final String ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}";
    private static final String SPEC_ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9._/-]{0,255}";

    private final String id;
    private final String digest;
    private final Map<String, Question> questions;
    private final Map<String, Object> wireQuestions;

    private SystemOneSpecification(String id, String digest, Map<String, Question> questions,
                                   Map<String, Object> wireQuestions) {
        this.id = id;
        this.digest = digest;
        this.questions = Map.copyOf(questions);
        this.wireQuestions = Map.copyOf(wireQuestions);
    }

    static SystemOneSpecification parse(String document, SystemOneProfile profile) {
        try {
            byte[] bytes = document.getBytes(StandardCharsets.UTF_8);
            int specificationLimit = Math.min(profile.maxRequestBytes(), 1024 * 1024);
            Object decoded = PayloadJson.read(bytes, limits(specificationLimit)).toJava();
            Map<String, Object> root = object(decoded);
            exactFields(root, ROOT_FIELDS);
            if (!CONTRACT.equals(root.get("contract"))) invalid("");
            String id = identifier(root.get("id"), SPEC_ID_PATTERN, "");
            Map<String, Object> rawQuestions = object(root.get("questions"));
            if (rawQuestions.isEmpty()) invalid("");
            if (rawQuestions.size() > profile.maxQuestions()) {
                unsupported("");
            }
            var compiled = new LinkedHashMap<String, Question>();
            var wire = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, Object> entry : rawQuestions.entrySet()) {
                String questionId = identifier(entry.getKey(), ID_PATTERN, "");
                if (compiled.containsKey(questionId)) invalid(questionId);
                Question question = question(questionId, object(entry.getValue()), profile);
                compiled.put(questionId, question);
                wire.put(questionId, question.wire());
            }
            byte[] canonical = PayloadJson.writeJava(Map.of(
                    "contract", CONTRACT, "id", id, "questions", wire), limits(specificationLimit));
            return new SystemOneSpecification(id, digest(canonical), compiled, wire);
        } catch (SystemOneException known) {
            throw known;
        } catch (RuntimeException malformed) {
            throw new SystemOneException(SystemOneException.Code.SPECIFICATION_INVALID);
        }
    }

    private static Question question(String id, Map<String, Object> raw, SystemOneProfile profile) {
        Object rawType = raw.get("type");
        if (!(rawType instanceof String) || !SystemOneProfile.PORTABLE_TYPES.contains(rawType)) invalid(id);
        String type = (String) rawType;
        if (!profile.supportedTypes().contains(type)) unsupported(id);
        Object instructions = raw.get("instructions");
        if (!structuredText(instructions)) invalid(id);
        if (canonicalSize(instructions, profile.maxInstructionBytes()) > profile.maxInstructionBytes()) {
            unsupported(id);
        }

        return switch (type) {
            case "choice" -> choice(id, raw, instructions, profile);
            case "noul" -> noul(id, raw, instructions);
            case "score" -> score(id, raw, instructions, profile);
            default -> throw new AssertionError(type);
        };
    }

    private static Question choice(String id, Map<String, Object> raw, Object instructions,
                                   SystemOneProfile profile) {
        exactFields(raw, Set.of("type", "instructions", "criteria"));
        Map<String, Object> criteria = nullableObject(raw.get("criteria"));
        if (criteria.isEmpty()) invalid(id);
        if (criteria.size() > profile.maxChoiceOptions()) unsupported(id);
        var options = new LinkedHashSet<String>();
        var normalized = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : criteria.entrySet()) {
            String option = identifier(entry.getKey(), ID_PATTERN, id);
            if (!structuredTextOrNull(entry.getValue())) invalid(id);
            options.add(option);
            normalized.put(option, immutableStructure(entry.getValue()));
        }
        Map<String, Object> safeCriteria = immutableNullableMap(normalized);
        Map<String, Object> wire = Map.of("type", "choice", "instructions",
                immutableStructure(instructions), "criteria", safeCriteria);
        return new Question("choice", List.copyOf(options), List.of(), wire);
    }

    private static Question noul(String id, Map<String, Object> raw, Object instructions) {
        if (!Set.of("type", "instructions", "criteria").containsAll(raw.keySet())) invalid(id);
        var wire = new LinkedHashMap<String, Object>();
        wire.put("type", "noul");
        wire.put("instructions", immutableStructure(instructions));
        if (raw.containsKey("criteria")) {
            Map<String, Object> criteria = nullableObject(raw.get("criteria"));
            if (!Set.of("true", "false").containsAll(criteria.keySet())) invalid(id);
            var normalized = new LinkedHashMap<String, Object>();
            criteria.forEach((key, value) -> {
                if (!structuredTextOrNull(value)) invalid(id);
                normalized.put(key, immutableStructure(value));
            });
            wire.put("criteria", immutableNullableMap(normalized));
        }
        return new Question("noul", List.of(), List.of(), Map.copyOf(wire));
    }

    private static Question score(String id, Map<String, Object> raw, Object instructions,
                                  SystemOneProfile profile) {
        exactFields(raw, Set.of("type", "instructions", "criteria"));
        if (!(raw.get("criteria") instanceof List<?>)
                || ((List<?>) raw.get("criteria")).size() < 2) invalid(id);
        List<?> criteria = (List<?>) raw.get("criteria");
        if (criteria.size() > profile.maxScoreLevels()) unsupported(id);
        var levels = new ArrayList<Object>();
        for (Object level : criteria) {
            if (!structuredText(level)) invalid(id);
            levels.add(immutableStructure(level));
        }
        Map<String, Object> wire = Map.of("type", "score", "instructions",
                immutableStructure(instructions), "criteria", List.copyOf(levels));
        return new Question("score", List.of(), List.copyOf(levels), wire);
    }

    byte[] request(Object state, SystemOneProfile profile) {
        if (!(state instanceof String || state instanceof Map<?, ?> || state instanceof List<?>)) {
            throw new SystemOneException(SystemOneException.Code.STATE_INVALID);
        }
        try {
            PayloadJson.writeJava(state, limits(profile.maxStateBytes()));
        } catch (RuntimeException invalid) {
            throw new SystemOneException(SystemOneException.Code.STATE_INVALID);
        }
        try {
            return PayloadJson.writeJava(Map.of("state", state, "model", profile.model(),
                    "questions", wireQuestions), limits(profile.maxRequestBytes()));
        } catch (RuntimeException oversized) {
            throw new SystemOneException(SystemOneException.Code.REQUEST_TOO_LARGE);
        }
    }

    ValidatedResponse validateResponse(byte[] bytes, SystemOneProfile profile) {
        try {
            Object decoded = PayloadJson.read(bytes, limits(profile.maxResponseBytes())).toJava();
            Map<String, Object> root = object(decoded);
            String reportedModel = nonBlank(root.get("model"));
            if (reportedModel.length() > 256) mismatch("");
            Map<String, Object> rawAnswers = object(root.get("answers"));
            if (!rawAnswers.keySet().equals(questions.keySet())) mismatch("");
            var answers = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, Question> entry : questions.entrySet()) {
                answers.put(entry.getKey(), validateAnswer(entry.getKey(), entry.getValue(),
                        object(rawAnswers.get(entry.getKey())), profile.probabilityTolerance()));
            }
            Map<String, Long> usage = usage(root.get("usage"));
            return new ValidatedResponse(reportedModel, Map.copyOf(answers), usage);
        } catch (SystemOneException known) {
            throw known;
        } catch (RuntimeException malformed) {
            throw new SystemOneException(SystemOneException.Code.RESPONSE_UNREADABLE);
        }
    }

    private static Map<String, Object> validateAnswer(String id, Question question,
                                                       Map<String, Object> answer, double tolerance) {
        if (!question.type().equals(answer.get("type"))) mismatch(id);
        return switch (question.type()) {
            case "noul" -> {
                exactResponseFields(answer, Set.of("type", "noul"), Set.of("type", "noul"), id);
                double value = probability(answer.get("noul"), id);
                yield Map.of("type", "noul", "noul", value);
            }
            case "choice" -> {
                exactResponseFields(answer, Set.of("type", "choice", "probabilities"),
                        Set.of("type", "choice", "probabilities", "confidence"), id);
                String selected = nonBlank(answer.get("choice"));
                if (!question.options().contains(selected)) mismatch(id);
                Map<String, Double> probabilities = probabilities(answer.get("probabilities"),
                        Set.copyOf(question.options()), tolerance, id);
                double selectedProbability = probabilities.get(selected);
                double maximumProbability = probabilities.values().stream()
                        .mapToDouble(Double::doubleValue).max().orElseThrow();
                if (Double.compare(selectedProbability, maximumProbability) != 0) mismatch(id);
                var normalized = new LinkedHashMap<String, Object>();
                normalized.put("type", "choice");
                normalized.put("choice", selected);
                normalized.put("probabilities", probabilities);
                if (answer.containsKey("confidence")) {
                    normalized.put("confidence", probability(answer.get("confidence"), id));
                }
                yield Map.copyOf(normalized);
            }
            case "score" -> {
                exactResponseFields(answer, Set.of("type", "score", "legend", "probabilities"),
                        Set.of("type", "score", "legend", "probabilities", "confidence"), id);
                double score = finite(answer.get("score"), id);
                if (score < 0 || score > question.levels().size() - 1.0) mismatch(id);
                Map<String, Object> legend = object(answer.get("legend"));
                var expectedKeys = new LinkedHashSet<String>();
                for (int index = 0; index < question.levels().size(); index++) {
                    String key = Integer.toString(index);
                    expectedKeys.add(key);
                    if (!java.util.Objects.equals(question.levels().get(index), legend.get(key))) mismatch(id);
                }
                if (!legend.keySet().equals(expectedKeys)) mismatch(id);
                Map<String, Double> probabilities = probabilities(answer.get("probabilities"),
                        expectedKeys, tolerance, id);
                var normalized = new LinkedHashMap<String, Object>();
                normalized.put("type", "score");
                normalized.put("score", score);
                normalized.put("legend", Map.copyOf(legend));
                normalized.put("probabilities", probabilities);
                if (answer.containsKey("confidence")) {
                    normalized.put("confidence", probability(answer.get("confidence"), id));
                }
                yield Map.copyOf(normalized);
            }
            default -> throw new AssertionError(question.type());
        };
    }

    private static Map<String, Double> probabilities(Object raw, Set<String> expected,
                                                      double tolerance, String id) {
        Map<String, Object> values = object(raw);
        if (!values.keySet().equals(expected)) mismatch(id);
        var result = new LinkedHashMap<String, Double>();
        double sum = 0;
        for (String key : expected) {
            double value = probability(values.get(key), id);
            sum += value;
            result.put(key, value);
        }
        if (Math.abs(sum - 1.0) > tolerance) mismatch(id);
        return Map.copyOf(result);
    }

    private static Map<String, Long> usage(Object raw) {
        if (raw == null) return Map.of();
        Map<String, Object> values = object(raw);
        var result = new LinkedHashMap<String, Long>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (!entry.getKey().matches("[A-Za-z][A-Za-z0-9_]{0,63}")
                    || !(entry.getValue() instanceof Long) || ((Long) entry.getValue()) < 0) mismatch("");
            long count = (Long) entry.getValue();
            result.put(entry.getKey(), count);
        }
        return Map.copyOf(result);
    }

    Map<String, Object> result(Object input, SystemOneProfile profile, ValidatedResponse response) {
        var model = new LinkedHashMap<String, Object>();
        model.put("requested", profile.model());
        model.put("reported", response.reportedModel());
        var result = new LinkedHashMap<String, Object>();
        result.put("contract", RESULT_CONTRACT);
        result.put("input", input);
        result.put("specification", Map.of("contract", CONTRACT, "id", id, "digest", digest));
        result.put("model", Map.copyOf(model));
        result.put("answers", response.answers());
        if (!response.usage().isEmpty()) result.put("usage", response.usage());
        return Map.copyOf(result);
    }

    String id() { return id; }
    String digest() { return digest; }

    static String stateDigest(Object state, int maximumBytes) {
        try {
            return digest(PayloadJson.writeJava(state, limits(maximumBytes)));
        } catch (RuntimeException invalid) {
            throw new SystemOneException(SystemOneException.Code.STATE_INVALID);
        }
    }

    static String digest(byte[] bytes) {
        try {
            byte[] hashed = MessageDigest.getInstance("SHA-256").digest(bytes);
            var text = new StringBuilder("sha256:");
            for (byte value : hashed) text.append(String.format("%02x", value & 0xff));
            return text.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static PayloadLimits limits(int bytes) {
        return new PayloadLimits(bytes, 32, Math.min(100_000, Math.max(1_000, bytes)),
                Math.min(500_000, Math.max(10_000, bytes * 2)), bytes, 4_096);
    }

    private static int canonicalSize(Object value, int limit) {
        try {
            return PayloadJson.writeJava(value, limits(limit)).length;
        } catch (RuntimeException invalid) {
            throw new SystemOneException(SystemOneException.Code.SPECIFICATION_INVALID);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("object");
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nullableObject(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("object");
        return (Map<String, Object>) map;
    }

    private static void exactFields(Map<String, Object> value, Set<String> fields) {
        if (!value.keySet().equals(fields)) invalid("");
    }

    private static void exactResponseFields(Map<String, Object> value, Set<String> required,
                                            Set<String> allowed, String id) {
        if (!value.keySet().containsAll(required) || !allowed.containsAll(value.keySet())) mismatch(id);
    }

    private static String identifier(Object raw, String pattern, String hint) {
        if (!(raw instanceof String) || !((String) raw).matches(pattern)) invalid(hint);
        return (String) raw;
    }

    private static String nonBlank(Object raw) {
        if (!(raw instanceof String text) || text.isBlank()) throw new IllegalArgumentException("text");
        return text;
    }

    private static double finite(Object raw, String id) {
        if (!(raw instanceof Number)) mismatch(id);
        double value = ((Number) raw).doubleValue();
        if (!Double.isFinite(value)) mismatch(id);
        return value;
    }

    private static double probability(Object raw, String id) {
        double value = finite(raw, id);
        if (value < 0 || value > 1) mismatch(id);
        return value;
    }

    private static boolean structuredText(Object value) {
        return value instanceof String || value instanceof Map<?, ?> || value instanceof List<?>;
    }

    private static boolean structuredTextOrNull(Object value) {
        return value == null || structuredText(value);
    }

    private static Object immutableStructure(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(SystemOneSpecification::immutableStructure).toList();
        }
        if (value instanceof Map<?, ?> map) {
            var copy = new LinkedHashMap<String, Object>();
            map.forEach((key, entry) -> copy.put(String.valueOf(key), immutableStructure(entry)));
            return immutableNullableMap(copy);
        }
        throw new IllegalArgumentException("structure");
    }

    private static Map<String, Object> immutableNullableMap(Map<String, Object> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static void invalid(String hint) {
        throw new SystemOneException(SystemOneException.Code.SPECIFICATION_INVALID, hint);
    }

    private static void unsupported(String hint) {
        throw new SystemOneException(SystemOneException.Code.CAPABILITY_UNSUPPORTED, hint);
    }

    private static void mismatch(String hint) {
        throw new SystemOneException(SystemOneException.Code.RESPONSE_MISMATCH, hint);
    }

    private record Question(String type, List<String> options, List<Object> levels,
                            Map<String, Object> wire) { }

    record ValidatedResponse(String reportedModel, Map<String, Object> answers,
                             Map<String, Long> usage) { }
}
