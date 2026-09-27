package ai.ravenroot.core.runner;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KubernetesRunnerConfigurationTest {
    @Test void operatorPlacementIsBoundedAndNeverAGenericPodTemplate() {
        var placement = new KubernetesRunnerConfiguration.Placement("background", Map.of("zone", "approved"),
                List.of(new KubernetesRunnerConfiguration.Toleration("dedicated", "agents", "NoSchedule", 0)), "zone", 2);
        var spec = new LinkedHashMap<String, Object>(); placement.apply(spec);
        assertEquals(Set.of("priorityClassName", "affinity", "tolerations", "topologySpreadConstraints"), spec.keySet());
        assertThrows(IllegalArgumentException.class, () -> new KubernetesRunnerConfiguration.Toleration("", "", "NoExecute", Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> new KubernetesRunnerConfiguration.Placement("", Map.of(), List.of(), "zone", 0));
        var settings = new LinkedHashMap<String, Object>(Map.of("kubectl", "/operator/kubectl", "kubeconfig", "/operator/kubeconfig",
                "cluster", "approved", "namespace", "agents", "storageClass", "fixed", "serviceAccount", "agent", "runtimeClass", "",
                "nodeSelector", Map.of(), "creationTimeout", "PT1M", "terminationGraceSeconds", 5));
        settings.put("networkControlHost", "network-control.default.svc");
        assertEquals("approved", KubernetesRunnerConfiguration.from(settings).cluster());
        settings.put("hostPath", "/var/run/docker.sock");
        assertThrows(IllegalArgumentException.class, () -> KubernetesRunnerConfiguration.from(settings));
        assertThrows(IllegalArgumentException.class, () -> new KubernetesRunnerConfiguration(Path.of("x"), Path.of("x"), "c", "n", "s", "a", "",
                Map.of(), Duration.ZERO, 1));
    }
}
