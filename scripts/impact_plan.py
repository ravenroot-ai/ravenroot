#!/usr/bin/env python3
"""Fail-closed maintenance verification plans, derived from immutable Git trees.

The conservative Maven model unions dependency, parent and plugin relationships from both trees.
It includes test dependencies and dependencyManagement on purpose: over-selection is safe. Unknown
property-based internal coordinates and topology deletion select a complete regression.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
ROOT = Path(__file__).resolve().parents[1]
LANES = {'dev', 'release/0.8'}
SUPPORT = {'ravenroot-adapter-anthropic', 'ravenroot-adapter-openai-compatible', 'ravenroot-dev-harness', 'ravenroot-sample'}
# Maven cannot describe service loading, persistence composition or browser wire contracts.
CONTRACTS = {
    'ravenroot/ravenroot-persistence-sqlite': ['ravenroot/ravenroot-server'],
    'ravenroot/ravenroot-persistence-postgresql': ['ravenroot/ravenroot-server'],
    'ravenroot/ravenroot-application-api': ['@ui', '@runtime', '@node-docs', '@api-docs', '@support'],
    'ravenroot/ravenroot-server': ['@ui', '@runtime'],
    'ravenroot/ravenroot-core': ['@runtime', '@support'],
    'ravenroot/ravenroot-pekko': ['@runtime', '@support'],
    'ravenroot/ravenroot-akka': ['@runtime', '@support'],
    'ravenroot/ravenroot-distribution': ['@runtime'],
    'ravenroot/ravenroot-plugin-bundle': ['@runtime'],
    'ravenroot/ravenroot-programming-graalvm': ['@runtime', '@native'],
}

def git(*args: str) -> str:
    return subprocess.check_output(['git', *args], text=True).strip()

def paths_between(base: str, candidate: str) -> list[str]:
    # NUL separators preserve spaces/newlines. --no-renames explicitly includes both old/new names.
    result = subprocess.check_output(['git', 'diff', '--no-renames', '--name-only', '-z', base, candidate], text=True)
    return [p for p in result.split('\0') if p]

def model(commit: str) -> tuple[dict[str, set[str]], set[str]]:
    files = git('ls-tree', '-r', '--name-only', commit).splitlines()
    poms = [p for p in files if p.endswith('/pom.xml') and (p.startswith('ravenroot/') or p.split('/')[0] in SUPPORT)]
    modules, trees, coordinates = {}, {}, {}
    for pom in poms:
        node = ET.fromstring(git('show', f'{commit}:{pom}'))
        module = pom.removesuffix('/pom.xml')
        artifact = node.findtext('m:artifactId', namespaces=NS)
        group = node.findtext('m:groupId', namespaces=NS) or node.findtext('m:parent/m:groupId', namespaces=NS)
        if not artifact or not group or '${' in artifact or '${' in group:
            raise ValueError(f'unresolved Maven project coordinate: {pom}')
        if (group, artifact) in coordinates:
            raise ValueError(f'duplicate Maven project coordinate: {group}:{artifact}')
        coordinates[(group, artifact)] = module
        modules[module] = set()
        trees[module] = node
    for module, node in trees.items():
        # Includes inherited parent, build/plugin dependencies, managed and profile dependencies.
        for dep in [*node.findall('.//m:dependency', NS), *node.findall('.//m:plugin', NS), *node.findall('m:parent', NS)]:
            group = dep.findtext('m:groupId', namespaces=NS) or ''
            artifact = dep.findtext('m:artifactId', namespaces=NS) or ''
            if '${' in group or (group == 'ai.ravenroot' and '${' in artifact):
                raise ValueError(f'unresolved internal relationship in {module}')
            target = coordinates.get((group, artifact))
            if group == 'ai.ravenroot' and not target:
                raise ValueError(f'unknown internal dependency/plugin/parent: {group}:{artifact}')
            if target and target != module:
                modules[module].add(target)
    return modules, set(files)

def closure(seeds: set[str], edges: dict[str, set[str]]) -> set[str]:
    result = set(seeds)
    while True:
        expanded = result | {d for m in result for d in edges.get(m, set())}
        if expanded == result:
            return result
        result = expanded

def make_plan(base: str, candidate: str, event: str, lane: str, force: bool = False) -> dict:
    from scripts.ci_required import GATED_JOBS
    all_jobs = set(GATED_JOBS)
    reasons, changed, selected, flags = [], [], set(), set()
    fallback = force or lane != 'release/0.8' or event == 'schedule'
    if lane not in LANES:
        raise ValueError(f'untrusted integration lane: {lane}')
    if fallback:
        reasons.append('explicit complete regression, scheduled regression, or unchanged dev policy')
    try:
        candidate = git('rev-parse', '--verify', f'{candidate}^{{commit}}')
        base = git('rev-parse', '--verify', f'{base}^{{commit}}')
        git('merge-base', base, candidate)  # unrelated histories must never become an empty plan
        old, _ = model(base)
        new, _ = model(candidate)
        changed = paths_between(base, candidate)
        graph = {m: old.get(m, set()) | new.get(m, set()) for m in old.keys() | new.keys()}
        if not changed:
            fallback = True
            reasons.append('empty comparison cannot prove impact')
        if old.keys() != new.keys():
            fallback = True
            reasons.append('module addition/deletion/rename requires complete topology validation')
        for p in changed:
            owners = sorted((m for m in graph if p.startswith(m + '/')), key=len, reverse=True)
            if p.startswith(('ravenroot/ravenroot-ui/', 'ui-server/')) or p == 'Dockerfile.ui':
                flags.add('@ui')
            elif owners and owners[0] not in {'ravenroot', 'ravenroot/ravenroot-extensions'}:
                selected.add(owners[0])
                reasons.append(f'{p}: owner {owners[0]}' + (' (dependency/build contract)' if p.endswith('pom.xml') else ' (test/resource)' if '/src/test/' in p else ' (implementation/resource)'))
            elif p.startswith(('docs/', 'adr/', '.changes/')) or p in {'README.md', 'CONTRIBUTING.md', 'AGENTS.md', 'GOVERNANCE.md', 'SECURITY.md', 'SUPPORT.md', 'CODE_OF_CONDUCT.md', 'LICENSE', 'NOTICE'}:
                flags.add('@docs')
            else:
                fallback = True
                reasons.append(f'{p}: shared build/tooling or unknown ownership requires complete verification')
        reverse = {m: {c for c, ds in graph.items() if m in ds} for m in graph}
        # Explicit integration edges participate in the same transitive fixed point as Maven.
        for source, targets in CONTRACTS.items():
            reverse.setdefault(source, set()).update(targets)
        for module in graph:
            if '/ravenroot-extensions/' in module and not module.endswith('ravenroot-extensions-all'):
                reverse[module].update({'@runtime', '@node-docs', '@extension-pack'})
        impacted = closure(selected, reverse)
        flags |= {m for m in impacted if m.startswith('@')}
        if impacted & {'ravenroot/ravenroot-application-api', 'ravenroot/ravenroot-core', 'ravenroot/ravenroot-server', 'ravenroot/ravenroot-pekko', 'ravenroot/ravenroot-akka'}:
            flags.add('@native')
        tests = sorted(m for m in impacted if m in new and m not in {'ravenroot', 'ravenroot/ravenroot-extensions'})
        if any(m.split('/')[0] in SUPPORT for m in tests):
            flags.add('@support')
        if '@runtime' in flags:
            selected.add('ravenroot/ravenroot-distribution')
        if flags & {'@docs', '@node-docs'}:
            # Runtime-backed node reference validation consumes every plugin by design.
            selected.add('ravenroot/ravenroot-extensions/ravenroot-extensions-all')
        builds = sorted(closure(set(tests) | selected, graph) & new.keys())
    except (ValueError, ET.ParseError, subprocess.CalledProcessError) as exc:
        fallback = True
        reasons.append(f'incomplete baseline/model: {type(exc).__name__}: {exc}')
        tests, builds = [], []
    jobs = {'full-source-policy', 'full-python-contracts', 'full-preflight', 'full-regression'}
    if flags & {'@docs', '@node-docs'}:
        jobs.add('docs-site')
    if '@docs' in flags:
        jobs.add('full-docs-policy')
    if '@api-docs' in flags:
        jobs.add('full-api-documentation')
    if '@runtime' in flags:
        jobs.add('full-ui-build')  # fresh current-candidate image input, not browser behavior proof
    if '@ui' in flags:
        jobs |= {'full-ui-audit', 'full-ui-unit-tests', 'full-ui-build', 'full-ui-e2e-harness', 'full-ui-e2e-shard', 'full-ui-e2e'}
    if tests or builds:
        jobs |= {'backend-build', 'full-backend-tests'}
    if '@runtime' in flags:
        jobs |= {'full-runtime-auth-smoke', 'full-runtime-jar-smoke', 'full-runtime-container-smoke', 'full-plugin-boundary', 'full-shell-contracts'}
    if '@support' in flags:
        jobs.add('backend-test')
    if fallback or any('ravenroot-object-storage' in m for m in builds):
        jobs.add('minio-fixture-preflight')
    if fallback:
        jobs, tests, builds = all_jobs, [], []
    plan = dict(schema=1, lane=lane, event=event, baseline=base, candidate=candidate,
                tree=git('rev-parse', f'{candidate}^{{tree}}'), changed=changed, components=sorted(selected),
                build=builds, test=tests, integrations=sorted(flags), full=fallback,
                native_acceptance=fallback or '@native' in flags,
                extension_pack=fallback or '@extension-pack' in flags,
                jobs=sorted(jobs), reasons=reasons,
                job_reasons={j: ('complete fallback/regression' if fallback else
                    'always-required source/CI policy or phase barrier' if j in {'full-source-policy', 'full-python-contracts', 'full-preflight', 'full-regression'} else
                    'Maven downstream tests and current-candidate prerequisite closure' if j in {'backend-build', 'full-backend-tests', 'minio-fixture-preflight'} else
                    'runtime node descriptors consumed by documentation' if j == 'docs-site' and '@node-docs' in flags else
                    'independent API Javadoc/DocLint contract' if j == 'full-api-documentation' else
                    'changed documentation content' if j in {'docs-site', 'full-docs-policy'} else
                    'current-candidate UI packaging prerequisite of OCI smoke' if j == 'full-ui-build' and '@ui' not in flags else
                    'UI implementation or declared server/API wire-contract consumer' if j.startswith('full-ui-') else
                    'out-of-reactor transitive consumer' if j == 'backend-test' else
                    'declared service-loading/runtime composition and packaging contract') for j in sorted(jobs)},
                independent_build_scopes={j: ('complete reactor' if j == 'full-api-documentation' else
                    sorted(closure({'ravenroot/ravenroot-server'} if j == 'full-ui-e2e-harness' else {'ravenroot/ravenroot-extensions/ravenroot-extensions-all'}, graph) & new.keys()))
                    for j in sorted(jobs & {'docs-site', 'full-api-documentation', 'full-ui-e2e-harness'})} if not fallback else
                    {'docs-site': 'extension pack prerequisites', 'full-api-documentation': 'complete reactor', 'full-ui-e2e-harness': 'server prerequisites'},
                omitted={j: 'no changed component or transitive integration consumer maps to this job' for j in sorted(all_jobs-jobs)},
                provenance={'model': 'union of base/candidate POM parent/dependency/plugin graph plus CONTRACTS',
                            'toolchain': 'ubuntu-24.04; temurin-21; .nvmrc; Maven repository workflow configuration',
                            'cross_event_reuse': 'disabled: exact source/base/toolchain/fresh trusted evidence not established; execute plan'})
    plan['fingerprint'] = hashlib.sha256(json.dumps(plan, sort_keys=True).encode()).hexdigest()
    return plan

def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument('--base', default='')
    p.add_argument('--candidate', default='HEAD')
    p.add_argument('--event', required=True)
    p.add_argument('--lane', default='dev')
    p.add_argument('--force-complete', action='store_true')
    p.add_argument('--output', default='impact-plan.json')
    a = p.parse_args()
    base = a.base
    if a.event == 'workflow_dispatch' and not base:
        try:
            base = git('merge-base', f'origin/{a.lane}', a.candidate)
        except subprocess.CalledProcessError:
            base = ''
    plan = make_plan(base, a.candidate, a.event, a.lane, a.force_complete)
    Path(a.output).write_text(json.dumps(plan, indent=2) + '\n')
    text = f"Impact plan {plan['baseline']} -> {plan['candidate']} ({plan['lane']})\nComplete: {plan['full']}\nJobs: {', '.join(plan['jobs'])}\nBuild: {', '.join(plan['build']) or 'complete reactor'}\nTests: {', '.join(plan['test']) or 'complete reactor'}\n" + '\n'.join(plan['reasons'])
    print(text)
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as out:
            out.write('```text\n' + text + '\n```\n')
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as out:
            out.write('jobs=' + json.dumps(plan['jobs'], separators=(',', ':')) + '\n')
            out.write('plan=' + json.dumps(plan, separators=(',', ':')) + '\n')

if __name__ == '__main__':
    # Support script execution as well as import from repository contract tests.
    import sys
    sys.path.insert(0, str(ROOT))
    main()
