package ai.ravenroot.core.runner;

import java.util.Map;

/** Narrow Kubernetes operations; neither graph-selected resources nor generic exec are available. */
interface KubernetesTransport {
    Map<String, Object> create(Map<String, Object> object) throws Exception;
    Map<String, Object> get(String kind, String name) throws Exception;
    Map<String, Object> patch(String kind, String name, String uid, String revision, Map<String, String> annotations) throws Exception;
    void delete(String kind, String name, String uid, String revision, int graceSeconds) throws Exception;
    Map<String, Object> attest(String pod) throws Exception;
    Map<String, Object> networkControl(String pod) throws Exception;
    Map<String, Object> isolate(String pod, String uid, String revision) throws Exception;
    Process agent(String pod, int protocolBytes) throws Exception;
}
