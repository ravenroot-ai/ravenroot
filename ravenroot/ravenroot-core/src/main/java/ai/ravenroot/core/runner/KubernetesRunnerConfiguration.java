package ai.ravenroot.core.runner;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** Closed, operator-owned Kubernetes placement and transport configuration. */
public record KubernetesRunnerConfiguration(Path kubectl, Path kubeconfig, String cluster,
        String namespace, String storageClass, String serviceAccount, String runtimeClass,
        Map<String, String> nodeSelector, Duration creationTimeout, int terminationGraceSeconds, String networkControlHost,
        Placement placement) {
    public KubernetesRunnerConfiguration(Path kubectl, Path kubeconfig, String cluster, String namespace,
            String storageClass, String serviceAccount, String runtimeClass, Map<String, String> nodeSelector,
            Duration creationTimeout, int terminationGraceSeconds) {
        this(kubectl, kubeconfig, cluster, namespace, storageClass, serviceAccount, runtimeClass, nodeSelector,
                creationTimeout, terminationGraceSeconds, "127.0.0.1", Placement.NONE);
    }
    public KubernetesRunnerConfiguration(Path kubectl, Path kubeconfig, String cluster, String namespace,
            String storageClass, String serviceAccount, String runtimeClass, Map<String, String> nodeSelector,
            Duration creationTimeout, int terminationGraceSeconds, String networkControlHost) {
        this(kubectl, kubeconfig, cluster, namespace, storageClass, serviceAccount, runtimeClass, nodeSelector,
                creationTimeout, terminationGraceSeconds, networkControlHost, Placement.NONE);
    }
    public KubernetesRunnerConfiguration {
        java.util.Objects.requireNonNull(kubectl); java.util.Objects.requireNonNull(kubeconfig);
        java.util.Objects.requireNonNull(placement);
        cluster = KubernetesApi.dnsLabel(cluster); namespace = KubernetesApi.dnsLabel(namespace);
        if (networkControlHost == null || !networkControlHost.matches("[a-zA-Z0-9][a-zA-Z0-9.-]{0,252}"))
            throw new IllegalArgumentException("operator network-control host is required");
        storageClass = KubernetesApi.dnsLabel(storageClass); serviceAccount = KubernetesApi.dnsLabel(serviceAccount);
        if (!runtimeClass.isEmpty()) runtimeClass = KubernetesApi.dnsLabel(runtimeClass);
        nodeSelector = Map.copyOf(nodeSelector);
        if (nodeSelector.size() > 16 || nodeSelector.entrySet().stream().anyMatch(entry ->
                !entry.getKey().matches("[a-zA-Z0-9][a-zA-Z0-9./_-]{0,252}")
                || !entry.getValue().matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,62}")))
            throw new IllegalArgumentException("invalid bounded operator node selector");
        if (creationTimeout.isNegative() || creationTimeout.isZero() || creationTimeout.compareTo(Duration.ofMinutes(30)) > 0
                || terminationGraceSeconds < 1 || terminationGraceSeconds > 300)
            throw new IllegalArgumentException("invalid Kubernetes lifecycle timeout");
    }
    public static KubernetesRunnerConfiguration from(Map<String, Object> value) {
        var fields = new java.util.HashSet<>(value.keySet()); fields.remove("placement");
        if (!fields.equals(Set.of("kubectl", "kubeconfig", "cluster", "namespace", "storageClass",
                "serviceAccount", "runtimeClass", "nodeSelector", "creationTimeout", "terminationGraceSeconds", "networkControlHost")))
            throw new IllegalArgumentException("unknown or missing Kubernetes driver setting");
        var selector = new java.util.LinkedHashMap<String, String>();
        RunnerJson.map(value.get("nodeSelector")).forEach((key, raw) -> {
            if (!(raw instanceof String text)) throw new IllegalArgumentException("node selector values must be strings");
            selector.put(key, text);
        });
        return new KubernetesRunnerConfiguration(Path.of(RunnerJson.text(value, "kubectl")),
                Path.of(RunnerJson.text(value, "kubeconfig")), RunnerJson.text(value, "cluster"),
                RunnerJson.text(value, "namespace"), RunnerJson.text(value, "storageClass"),
                RunnerJson.text(value, "serviceAccount"), RunnerJson.text(value, "runtimeClass"), selector,
                Duration.parse(RunnerJson.text(value, "creationTimeout")), Math.toIntExact(RunnerJson.number(value, "terminationGraceSeconds")),
                RunnerJson.text(value, "networkControlHost"), value.containsKey("placement") ? Placement.from(RunnerJson.map(value.get("placement"))) : Placement.NONE);
    }
    public String networkControlAddress() {
        try { return java.net.InetAddress.getByName(networkControlHost).getHostAddress(); }
        catch (java.net.UnknownHostException failure) { throw new IllegalStateException("operator network-control Service is not resolvable", failure); }
    }
    /** Deliberately smaller than PodSpec: operator-only hard affinity, equal tolerations and spread. */
    public record Placement(String priorityClass, Map<String, String> requiredAffinity,
            java.util.List<Toleration> tolerations, String topologyKey, int maxSkew) {
        public static final Placement NONE = new Placement("", Map.of(), java.util.List.of(), "", 1);
        public Placement {
            if (!priorityClass.isEmpty()) priorityClass = KubernetesApi.dnsLabel(priorityClass);
            requiredAffinity = Map.copyOf(requiredAffinity); tolerations = java.util.List.copyOf(tolerations);
            if (requiredAffinity.size() > 16 || tolerations.size() > 16 || maxSkew < 1 || maxSkew > 100
                    || !topologyKey.isEmpty() && !labelKey(topologyKey)
                    || requiredAffinity.entrySet().stream().anyMatch(e -> !labelKey(e.getKey()) || !labelValue(e.getValue())))
                throw new IllegalArgumentException("invalid bounded Kubernetes placement");
        }
        static Placement from(Map<String, Object> value) {
            if (!value.keySet().equals(Set.of("priorityClass", "requiredAffinity", "tolerations", "topologyKey", "maxSkew")))
                throw new IllegalArgumentException("unknown placement setting");
            var affinity = new java.util.LinkedHashMap<String, String>();
            RunnerJson.map(value.get("requiredAffinity")).forEach((key, raw) -> {
                if (!(raw instanceof String text)) throw new IllegalArgumentException("invalid affinity value"); affinity.put(key, text);
            });
            if (!(value.get("tolerations") instanceof java.util.List<?> raw)) throw new IllegalArgumentException("tolerations required");
            var tolerations = raw.stream().map(item -> {
                var t = RunnerJson.map(item);
                if (!t.keySet().equals(Set.of("key", "value", "effect", "seconds"))) throw new IllegalArgumentException("unknown toleration setting");
                return new Toleration(RunnerJson.text(t, "key"), RunnerJson.text(t, "value"), RunnerJson.text(t, "effect"), RunnerJson.number(t, "seconds"));
            }).toList();
            return new Placement(RunnerJson.text(value, "priorityClass"), affinity, tolerations, RunnerJson.text(value, "topologyKey"), Math.toIntExact(RunnerJson.number(value, "maxSkew")));
        }
        public void apply(java.util.Map<String, Object> spec) {
            if (!priorityClass.isEmpty()) spec.put("priorityClassName", priorityClass);
            if (!requiredAffinity.isEmpty()) spec.put("affinity", Map.of("nodeAffinity", Map.of("requiredDuringSchedulingIgnoredDuringExecution",
                    Map.of("nodeSelectorTerms", java.util.List.of(Map.of("matchExpressions", requiredAffinity.entrySet().stream().map(e ->
                            Map.of("key", e.getKey(), "operator", "In", "values", java.util.List.of(e.getValue()))).toList()))))));
            if (!tolerations.isEmpty()) spec.put("tolerations", tolerations.stream().map(Toleration::json).toList());
            if (!topologyKey.isEmpty()) spec.put("topologySpreadConstraints", java.util.List.of(Map.of("maxSkew", maxSkew,
                    "topologyKey", topologyKey, "whenUnsatisfiable", "DoNotSchedule", "labelSelector", Map.of("matchLabels", Map.of("app.kubernetes.io/component", "governed-agent")))));
        }
    }
    public record Toleration(String key, String value, String effect, long seconds) {
        public Toleration {
            if (!labelKey(key) || !labelValue(value) || !Set.of("NoSchedule", "PreferNoSchedule", "NoExecute").contains(effect)
                    || seconds < 0 || seconds > 86400 || !effect.equals("NoExecute") && seconds != 0)
                throw new IllegalArgumentException("invalid bounded toleration");
        }
        Map<String, Object> json() {
            var result = new java.util.LinkedHashMap<String, Object>(Map.of("key", key, "value", value, "operator", "Equal", "effect", effect));
            if (effect.equals("NoExecute")) result.put("tolerationSeconds", seconds); return result;
        }
    }
    private static boolean labelKey(String value) { return value != null && value.matches("[a-zA-Z0-9][a-zA-Z0-9./_-]{0,252}"); }
    private static boolean labelValue(String value) { return value != null && value.matches("[a-zA-Z0-9._-]{0,63}"); }
}
