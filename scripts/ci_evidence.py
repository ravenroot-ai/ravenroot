"""Necessary equivalence checks for future cross-event evidence reuse.

This is deliberately not wired to skip functional jobs: hosted runner image identity and trusted
complete-run receipts are not yet established. All predicates are required, never PR position.
"""
from datetime import datetime, timezone


def equivalent(receipt, request, now=None):
    now = now or datetime.now(timezone.utc)
    if receipt.get('repository') != 'ravenroot-ai/ravenroot':
        return False
    if receipt.get('workflow') != '.github/workflows/ci.yml' or receipt.get('conclusion') != 'success':
        return False
    if receipt.get('event') not in {'workflow_dispatch', 'pull_request', 'merge_group'}:
        return False
    # SHA of the complete Git tree covers resources/tests/config, not just src/main. Base remains
    # part of the proof even when textual merging creates no source conflict.
    for key in ('tree', 'base', 'plan', 'toolchain', 'runner_image', 'dependency_lock'):
        if not receipt.get(key) or receipt.get(key) != request.get(key):
            return False
    if receipt.get('trusted_api_receipt') is not True or receipt.get('complete') is not True:
        return False
    try:
        age = (now - datetime.fromisoformat(receipt['completed_at'])).total_seconds()
    except (KeyError, TypeError, ValueError):
        return False
    return 0 <= age <= 24 * 60 * 60
