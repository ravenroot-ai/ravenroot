#!/usr/bin/env python3
"""Fail-closed validation for one uniquely named issue-138 Surefire report and its markers."""

from __future__ import annotations

import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


CPU_METHOD = "aSupervisorNotEnforcingCpuFailsOnlyTheCpuTest"
CPU_DESCRIPTOR = "theSupervisorEnforcesTheDeclaredCpuBudget()"
CPU_FAILURE_MESSAGE = (
    "a workload that spends roughly 4s of real CPU time against a 250ms CPU budget, "
    "well inside an 8s deadline, must not be reported as COMPLETED"
)
EXPECTED_FIXTURES = {
    "NonCompliantOnDeadline": "theSupervisorEnforcesTheDeclaredDeadline()",
    "NonCompliantOnCpu": CPU_DESCRIPTOR,
    "NonCompliantOnMemory": "theSupervisorEnforcesTheDeclaredMemoryLimit()",
    "NonCompliantOnPid": "theSupervisorEnforcesTheDeclaredPidLimit()",
    "NonCompliantOnDisk": "theSupervisorEnforcesTheDeclaredDiskLimit()",
    "NonCompliantOnCleanup": "cancellationStopsTheWorkloadAndReapsTheCompleteProcessTree()",
    "NonCompliantOnProtocol": "theSupervisorDoesNotTrustAnUnreadableWorkerResponse()",
    "NonCompliantOnOutput": "theSupervisorEnforcesTheDeclaredOutputByteLimit()",
    "NonCompliantOnFiles": "theSupervisorEnforcesTheDeclaredFileDescriptorLimit()",
}


def fail(reason: str) -> None:
    print(json.dumps({"valid": False, "reason": reason}, separators=(",", ":")))
    raise SystemExit(1)


def load_markers(path: Path) -> list[dict[str, object]]:
    markers: list[dict[str, object]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line:
            try:
                value = json.loads(line)
            except json.JSONDecodeError as error:
                fail(f"invalid marker JSON: {error}")
            if not isinstance(value, dict):
                fail("marker is not an object")
            markers.append(value)
    return markers


def main() -> None:
    if len(sys.argv) != 7:
        fail("expected MODE REPORT MARKERS OUTER_EXIT SAMPLE CONDITION")
    mode, report_name, markers_name, outer_exit, sample, condition = sys.argv[1:]
    report = Path(report_name)
    markers = load_markers(Path(markers_name))
    root = ET.parse(report).getroot()
    tests = int(root.attrib.get("tests", "-1"))
    failures = int(root.attrib.get("failures", "-1"))
    errors = int(root.attrib.get("errors", "-1"))
    skipped = int(root.attrib.get("skipped", "-1"))
    cases = root.findall("testcase")
    reruns = root.findall(".//rerunFailure") + root.findall(".//rerunError")
    flaky = root.findall(".//flakyFailure") + root.findall(".//flakyError")
    exit_code = int(outer_exit)

    summary: dict[str, object] = {
        "valid": True,
        "tests": tests,
        "failures": failures,
        "errors": errors,
        "skipped": skipped,
        "exit": exit_code,
        "reruns": len(reruns),
        "flaky": len(flaky),
        "sample": sample,
        "condition": condition,
    }
    if reruns or flaky:
        fail("Surefire report contained a rerun or flaky result")

    if mode == "base":
        case_name = cases[0].attrib.get("name", "").removesuffix("()") if len(cases) == 1 else ""
        if tests != 1 or len(cases) != 1 or case_name != CPU_METHOD or skipped != 0:
            fail("base report did not contain exactly the selected CPU outer method")
        if exit_code == 0 and failures == 0 and errors == 0:
            summary["category"] = "OUTER_PASS"
        elif exit_code == 1 and failures == 1 and errors == 0:
            failure = cases[0].find("failure")
            if failure is None or failure.attrib.get("type") != "org.opentest4j.MultipleFailuresError":
                fail("base outer failure was not the expected EventStatistics MultipleFailuresError")
            detail = (failure.attrib.get("message", "") + " " + (failure.text or "")).lower()
            if "failed" not in detail or "succeeded" not in detail:
                fail("base outer failure did not identify failed/succeeded statistics")
            summary["category"] = "OUTER_STATISTICS_FAILURE"
            summary["failureType"] = failure.attrib.get("type")
        else:
            fail("base outer exit/report shape was neither pass nor expected statistics failure")
    elif mode == "candidate":
        if (exit_code, tests, failures, errors, skipped, len(cases)) != (0, 1, 0, 0, 0, 1):
            fail("candidate CPU outer did not pass exactly once")
        if cases[0].attrib.get("name", "").removesuffix("()") != CPU_METHOD or len(markers) != 1:
            fail("candidate CPU report/marker did not identify exactly the selected outer method")
        marker = markers[0]
        required = {
            "sample": sample,
            "condition": condition,
            "nestedStarted": 11,
            "nestedSucceeded": 10,
            "nestedFailed": 1,
            "descriptor": CPU_DESCRIPTOR,
            "throwable": "org.opentest4j.AssertionFailedError",
            "expected": "any non-COMPLETED SandboxOutcome",
            "actual": "COMPLETED",
        }
        if any(marker.get(key) != value for key, value in required.items()):
            fail("candidate CPU marker did not prove the exact cause/value contract")
        summary["category"] = "INTENDED_CPU_ASSERTION"
        summary["marker"] = marker
    elif mode == "candidate-siblings":
        if (exit_code, tests, failures, errors, skipped, len(cases)) != (0, 9, 0, 0, 0, 9):
            fail("candidate sibling class did not pass all nine outer controls")
        if len(markers) != 9:
            fail("candidate sibling run did not emit exactly nine nested-result markers")
        observed = {marker.get("fixture"): marker for marker in markers}
        if set(observed) != set(EXPECTED_FIXTURES):
            fail("candidate sibling markers did not name the exact nine fixtures")
        for fixture, descriptor in EXPECTED_FIXTURES.items():
            marker = observed[fixture]
            expected = {
                "nestedStarted": 11,
                "nestedSucceeded": 10,
                "nestedFailed": 1,
                "descriptor": descriptor,
                "throwable": "org.opentest4j.AssertionFailedError",
            }
            if any(marker.get(key) != value for key, value in expected.items()):
                fail(f"candidate sibling marker was invalid for {fixture}")
        summary["category"] = "ALL_NINE_INTENDED_ASSERTIONS"
        summary["markers"] = markers
    elif mode == "auxiliary":
        if (exit_code, tests, failures, errors, skipped, len(cases)) != (0, 1, 0, 0, 0, 1):
            fail("auxiliary observer did not complete exactly once")
        if len(markers) != 1:
            fail("auxiliary observer did not emit exactly one record")
        marker = markers[0]
        if marker.get("sample") != sample or marker.get("condition") != condition:
            fail("auxiliary marker identity did not match its scheduled index")
        if marker.get("nestedStarted") != "11" or marker.get("observedRecipe") != "BUSY 4000\n":
            fail("auxiliary did not observe the exact real CPU contract recipe")
        outcome = marker.get("observedOutcome")
        if outcome not in {
            "COMPLETED", "DEADLINE_EXCEEDED", "POLICY_REJECTED", "SETUP_FAILURE",
            "SECCOMP_DENIED", "OUT_OF_MEMORY", "CANCELLED", "REAP_FAILED", "PROTOCOL_FAILURE",
        }:
            fail("auxiliary did not record a supervisor outcome")
        if outcome == "COMPLETED":
            completed_shape = {
                "category": "INTENDED_CPU_ASSERTION",
                "nestedSucceeded": "10",
                "nestedFailed": "1",
                "descriptor": CPU_DESCRIPTOR,
                "throwable": "org.opentest4j.AssertionFailedError",
                "actual": "COMPLETED",
            }
            if any(marker.get(key) != value for key, value in completed_shape.items()):
                fail("auxiliary COMPLETED outcome lacked the intended CPU assertion shape")
            if CPU_FAILURE_MESSAGE not in str(marker.get("message", "")):
                fail("auxiliary COMPLETED assertion lacked the distinctive CPU message")
        else:
            expected_category = (
                "FALSE_CLEAN_DEADLINE"
                if outcome == "DEADLINE_EXCEEDED"
                else "FALSE_CLEAN_OTHER_OUTCOME"
            )
            clean_shape = {
                "category": expected_category,
                "nestedSucceeded": "11",
                "nestedFailed": "0",
                "descriptor": None,
                "throwable": None,
            }
            if any(marker.get(key) != value for key, value in clean_shape.items()):
                fail("auxiliary non-COMPLETED outcome lacked a coherent clean nested result")
        summary["category"] = "AUXILIARY_REAL_PROCESS_OBSERVATION"
        summary["marker"] = marker
    else:
        fail(f"unknown validation mode: {mode}")

    print(json.dumps(summary, separators=(",", ":")))


if __name__ == "__main__":
    main()
