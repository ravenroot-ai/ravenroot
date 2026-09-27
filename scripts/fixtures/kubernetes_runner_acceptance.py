#!/usr/bin/env python3
"""Native containerd/Calico acceptance on an isolated disposable Minikube cluster.

The host Docker CLI only provisions the ephemeral CI cluster and loads a fixture image; no
Ravenroot manager, helper or Agent receives a host path or container-engine socket. Fixed-size
ext4 volumes are operator-provisioned node infrastructure, never Agent hostPath mounts. Missing
capabilities are fatal, and exact Surefire reports must have zero skips.
"""
from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
BASE_IMAGE = "python@sha256:e81548ac35b07a3bd4805f275107592ef458b1e893c0e04d45aedaa19416cca5"
MINIKUBE = "v1.38.1"
KUBERNETES = "v1.35.1"
CLUSTER_IMAGE = "gcr.io/k8s-minikube/kicbase:v0.0.50@sha256:eb4fec00e8ad70adf8e6436f195cc429825ffb85f95afcdb5d8d9deb576f3e93"
NAMESPACE = "ravenroot-446"
CLASSES = {"ai.ravenroot.core.runner.KubernetesPodRunnerNativeTest": 1,
           "ai.ravenroot.core.runtime.KubernetesWorkspaceGraphNativeTest": 4}
spec = importlib.util.spec_from_file_location("model_protocol_endpoint", Path(__file__).with_name("model_protocol_endpoint.py"))
endpoint_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(endpoint_module)


def command(argv, *, data=None, timeout=300, quiet=False):
    result = subprocess.run(argv, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            timeout=timeout, check=False)
    if result.returncode:
        # Never include command arguments or stdout: they may contain credential material.
        diagnostic = result.stderr[-16384:].decode(errors="replace") if not quiet else "credential operation refused"
        raise RuntimeError(f"{Path(argv[0]).name} failed ({result.returncode}): {diagnostic}")
    if len(result.stdout) > 1_048_576:
        raise RuntimeError("fixture command exceeded its output ceiling")
    return result.stdout


def resources(node, count=16):
    result = [
        {"apiVersion": "v1", "kind": "Namespace", "metadata": {"name": NAMESPACE, "labels": {
            "pod-security.kubernetes.io/enforce": "restricted", "pod-security.kubernetes.io/enforce-version": "v1.35"}}},
        *({"apiVersion": "v1", "kind": "ServiceAccount", "metadata": {"name": name, "namespace": NAMESPACE},
           "automountServiceAccountToken": False} for name in ("agent", "runner-manager")),
        {"apiVersion": "rbac.authorization.k8s.io/v1", "kind": "Role", "metadata": {"name": "runner-manager", "namespace": NAMESPACE},
         "rules": [{"apiGroups": [""], "resources": ["pods", "persistentvolumeclaims"],
                    "verbs": ["create", "get", "delete", "patch"]},
                   {"apiGroups": [""], "resources": ["pods/exec"], "verbs": ["create", "get"]}]},
        {"apiVersion": "rbac.authorization.k8s.io/v1", "kind": "RoleBinding", "metadata": {"name": "runner-manager", "namespace": NAMESPACE},
         "roleRef": {"apiGroup": "rbac.authorization.k8s.io", "kind": "Role", "name": "runner-manager"},
         "subjects": [{"kind": "ServiceAccount", "name": "runner-manager", "namespace": NAMESPACE}]},
        {"apiVersion": "networking.k8s.io/v1", "kind": "NetworkPolicy", "metadata": {"name": "agent-deny-all", "namespace": NAMESPACE},
         "spec": {"podSelector": {}, "policyTypes": ["Ingress", "Egress"]}},
        {"apiVersion": "storage.k8s.io/v1", "kind": "StorageClass", "metadata": {"name": "ravenroot-fixed-ext4"},
         "provisioner": "kubernetes.io/no-provisioner", "volumeBindingMode": "WaitForFirstConsumer", "reclaimPolicy": "Retain"},
    ]
    for index in range(1, count + 1):
        result.append({"apiVersion": "v1", "kind": "PersistentVolume", "metadata": {"name": f"ravenroot-446-volume{index}"},
                       "spec": {"capacity": {"storage": "64Mi"}, "volumeMode": "Filesystem", "accessModes": ["ReadWriteOnce"],
                                "persistentVolumeReclaimPolicy": "Retain", "storageClassName": "ravenroot-fixed-ext4",
                                "local": {"path": f"/var/lib/ravenroot-446/volume{index}"},
                                "nodeAffinity": {"required": {"nodeSelectorTerms": [{"matchExpressions": [
                                    {"key": "kubernetes.io/hostname", "operator": "In", "values": [node]}]}]}}}})
    return {"apiVersion": "v1", "kind": "List", "items": result}


def provision_volumes(profile, count=16):
    # The profile was created by this invocation; only newly-created regular files are formatted.
    for index in range(1, count + 1):
        directory = f"/var/lib/ravenroot-446/volume{index}"
        program = ('set -eu; test ! -e "$1.img"; loop=$(losetup -f); '
                   'if ! test -b "$loop"; then mknod "$loop" b 7 "${loop#/dev/loop}"; fi; '
                   'mkdir -p "$1"; truncate -s 67108864 "$1.img"; '
                   'mkfs.ext4 -q -m 0 "$1.img"; mount -o loop,nosuid,nodev "$1.img" "$1"; chown 65532:65532 "$1"')
        command(["docker", "exec", profile, "sh", "-c", program, "fixture", directory])


def manager_configuration(kubectl, directory):
    context = json.loads(command(kubectl + ["config", "view", "--minify", "--raw", "-o", "json"], quiet=True))
    token = command(kubectl + ["create", "token", "runner-manager", "-n", NAMESPACE, "--duration=2h"], quiet=True).decode().strip()
    config = {"apiVersion": "v1", "kind": "Config", "clusters": [{"name": "native", "cluster": context["clusters"][0]["cluster"]}],
              "users": [{"name": "manager", "user": {"token": token}}], "contexts": [{"name": "native", "context": {
                  "cluster": "native", "user": "manager", "namespace": NAMESPACE}}], "current-context": "native"}
    path = directory / "manager.kubeconfig"
    with open(path, "x", opener=lambda name, flags: os.open(name, flags, 0o600)) as stream:
        json.dump(config, stream)
    return path


def verify_reports():
    for name, count in CLASSES.items():
        path = ROOT / "ravenroot/ravenroot-core/target/surefire-reports" / ("TEST-" + name + ".xml")
        suite = ET.parse(path).getroot()
        if suite.get("name") != name or any(suite.get(key) != value for key, value in {
                "tests": str(count), "failures": "0", "errors": "0", "skipped": "0"}.items()):
            raise RuntimeError("native Kubernetes acceptance did not execute completely without skips")


def run():
    for tool in ("docker", "minikube", "kubectl", "helm", "mvn"):
        if not shutil.which(tool):
            raise RuntimeError(f"native Kubernetes preflight: required tool unavailable: {tool}")
    if os.environ.get("RAVENROOT_RUNNER_ACCEPTANCE_CONFIG"):
        raise RuntimeError("native CI acceptance is secretless; provider configuration is forbidden")
    if not json.loads(command(["docker", "info", "--format", "{{json .}}"]))["OSType"] == "linux":
        raise RuntimeError("native Kubernetes acceptance requires a Linux container host")
    if command(["minikube", "version", "--short"]).decode().strip() != MINIKUBE:
        raise RuntimeError("native acceptance requires the repository-pinned Minikube " + MINIKUBE)
    # Exercise the actual default-bridge attachment used by Minikube's helper containers,
    # not just Docker's stale network metadata. Never repair/restart the host daemon here.
    command(["docker", "run", "--rm", "--network=bridge", "--entrypoint=/bin/true", CLUSTER_IMAGE])
    profile = "rr-native-" + uuid.uuid4().hex[:12]
    image_tag = "ravenroot-native:" + profile
    with tempfile.TemporaryDirectory(prefix="ravenroot-kubernetes-") as temporary:
        directory = Path(temporary).resolve()
        try:
            print("Native Kubernetes preflight: creating isolated containerd/Calico cluster", flush=True)
            command(["minikube", "start", "--profile=" + profile, "--driver=docker", "--container-runtime=containerd",
                     "--kubernetes-version=" + KUBERNETES, "--base-image=" + CLUSTER_IMAGE,
                     "--cni=calico", "--cpus=2", "--memory=2048",
                     "--extra-config=kubelet.pod-max-pids=256", "--keep-context", "--embed-certs", "--install-addons=false"], timeout=600)
            kubectl = ["kubectl", "--context=" + profile]
            # Pin one policy backend. Calico Auto can change its choice during initial kube-proxy
            # startup, leaving stale rules in the other table family on nested Linux hosts.
            command(kubectl + ["set", "env", "daemonset/calico-node", "-n", "kube-system", "FELIX_IPTABLESBACKEND=NFT"])
            command(kubectl + ["rollout", "status", "daemonset/calico-node", "-n", "kube-system", "--timeout=180s"])
            # Only the newly-created test node is eligible. Preserve every non-Calico legacy rule;
            # current policy remains enforced by the pinned nft backend and is positively tested.
            command(["docker", "exec", profile, "sh", "-c", "iptables-legacy-save | awk '!/^:cali-/ && !/-A cali-/ && !/-j cali-/ && !/cali:/' | iptables-legacy-restore"])
            provision_volumes(profile)
            command(kubectl + ["apply", "-f", "-"], data=json.dumps(resources(profile)).encode())
            credential = manager_configuration(kubectl, directory)
            print("Native Kubernetes preflight passed; building immutable Agent fixture", flush=True)
            command(["docker", "build", "-f", str(ROOT / "docs/examples/governed-runner/Agent.Dockerfile"),
                     "--build-arg", "BASE_IMAGE=" + BASE_IMAGE, "-t", image_tag,
                     str(ROOT / "docs/examples/governed-runner")])
            command(["minikube", "image", "load", "--profile=" + profile, image_tag])
            lines = command(["docker", "exec", profile, "ctr", "-n", "k8s.io", "images", "ls"]).decode().splitlines()
            matching = [line.split()[2] for line in lines if line.split()[0] == "docker.io/library/" + image_tag]
            if len(matching) != 1 or not re.fullmatch(r"sha256:[0-9a-f]{64}", matching[0]):
                raise RuntimeError("loaded containerd image digest could not be established")
            image = "docker.io/library/ravenroot-native@" + matching[0]
            command(["docker", "exec", profile, "ctr", "-n", "k8s.io", "images", "tag", "docker.io/library/" + image_tag, image])
            rendered = command(["helm", "template", "native", str(ROOT / "deploy/helm/ravenroot"), "-f",
                str(ROOT / "docs/examples/governed-runner/kubernetes-values.yaml"), "--show-only", "templates/kubernetes-runner.yaml",
                "--set", "runnerPlane.workerPools[0].kubernetes.namespace=" + NAMESPACE,
                "--set", "runnerPlane.workerPools[0].kubernetes.agentServiceAccount=agent",
                "--set", "runnerPlane.workerPools[0].kubernetes.runtimeImages[0]=" + image])
            command(kubectl + ["apply", "-f", "-"], data=rendered)
            command(kubectl + ["rollout", "status", "deployment/native-ravenroot-agent-native-network-control", "--timeout=90s"])
            control = command(kubectl + ["get", "service/native-ravenroot-agent-native-network-control", "-o", "jsonpath={.spec.clusterIP}"]).decode()
            policies = json.loads(command(kubectl + ["get", "validatingadmissionpolicies", "-o", "json"]))
            if any(policy.get("status", {}).get("typeChecking", {}).get("expressionWarnings") for policy in policies["items"]):
                raise RuntimeError("native admission policy has a type-checking warning")
            for name in CLASSES:
                (ROOT / "ravenroot/ravenroot-core/target/surefire-reports" / ("TEST-" + name + ".xml")).unlink(missing_ok=True)
            with endpoint_module.ModelProtocolEndpoint() as endpoint:
                configuration = directory / "model.json"
                configuration.write_text(json.dumps(endpoint.configuration()))
                result = subprocess.run(["mvn", "-B", "--no-transfer-progress", "-f", str(ROOT / "ravenroot/pom.xml"),
                    "-pl", "ravenroot-core", "-am", "-Dtest=" + ",".join(name.rsplit(".", 1)[1] for name in CLASSES),
                    "-Dsurefire.failIfNoSpecifiedTests=false", "-Dravenroot.kubernetes.kubeconfig=" + str(credential),
                    "-Dravenroot.kubernetes.kubectl=" + shutil.which("kubectl"), "-Dravenroot.kubernetes.image=" + image,
                    "-Dravenroot.kubernetes.networkControlHost=" + control,
                    "-Dravenroot.kubernetes.modelConfiguration=" + str(configuration), "test"], cwd=ROOT, timeout=900)
                if result.returncode:
                    # These are our secretless fixture Pods, before the profile is destroyed. Only
                    # the immutable attester is executable; never dump assignments, env or tokens.
                    inventory = json.loads(command(kubectl + ["get", "pods", "-n", NAMESPACE, "-o", "json"]))
                    for pod in inventory["items"][:16]:
                        print("Owned Pod diagnostic: " + json.dumps({"name": pod["metadata"]["name"],
                            "phase": pod.get("status", {}).get("phase"), "reason": pod.get("status", {}).get("reason"),
                            "containers": [{"state": c.get("state")} for c in pod.get("status", {}).get("containerStatuses", [])]}), flush=True)
                        if pod.get("status", {}).get("phase") == "Running":
                            diagnostic = subprocess.run(kubectl + ["exec", "-n", NAMESPACE, pod["metadata"]["name"], "-c", "agent", "--",
                                "python3", "/opt/kubernetes_attestation.py"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=45)
                            print("Immutable attester diagnostic: " + str(diagnostic.returncode) + " "
                                  + diagnostic.stdout[-4096:].decode(errors="replace") + " "
                                  + diagnostic.stderr[-4096:].decode(errors="replace"), flush=True)
                    raise RuntimeError("native Kubernetes production acceptance failed")
                verify_reports()
                if endpoint.requests < 40:
                    raise RuntimeError("native acceptance did not exercise the real production model protocol")
                remaining = json.loads(command(kubectl + ["get", "pods,pvc", "-n", NAMESPACE, "-o", "json"]))
                if remaining["items"]:
                    raise RuntimeError("native acceptance left owned Pods or PVCs without cleanup proof")
                print("KUBERNETES_RUNNER_ACCEPTANCE=" + json.dumps({"kubernetes": KUBERNETES, "runtime": "containerd",
                    "cni": "calico", "storage": "fixed-ext4-local-pv", "tests": sum(CLASSES.values()),
                    "skipped": 0, "modelProtocolRequests": endpoint.requests, "cleanup": "verified"}), flush=True)
        finally:
            # Only this invocation's unpredictable dedicated profile is eligible for deletion.
            command(["minikube", "delete", "--profile=" + profile], timeout=180)


if __name__ == "__main__":
    run()
