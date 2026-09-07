#!/usr/bin/env python3
"""Inventory fixed operational candidates and reject unreviewed source drift.

The scanner is deliberately lexical. It finds a stable, reviewable set of candidates
within its documented patterns and leaves each semantic decision in
``scripts/operational-configuration-inventory.json``. The inventory and generated
report are the source of the issue's counts.
"""

from __future__ import annotations

import argparse
import ast
from bisect import bisect_right
import hashlib
import io
import json
import re
import subprocess
import sys
import tokenize
from collections import Counter
from dataclasses import dataclass, replace
from functools import lru_cache
from pathlib import Path
from typing import Iterable


ROOT = Path(__file__).resolve().parents[1]
INVENTORY = ROOT / "scripts" / "operational-configuration-inventory.json"
REPORT = ROOT / "docs" / "architecture" / "operational-configuration-audit.md"

SCHEMA_VERSION = 4
CLASSIFICATIONS = {
    "operator-configurable",
    "security-ceiling-or-default",
    "protocol-or-format-invariant",
    "published-contract-description",
    "presentation-text",
    "derived",
    "test-fixture",
}
STATUSES = {
    "pending-review",
    "already-centralized",
    "confirmed-hardcoded",
    "converted",
    "retained",
    "deferred",
    "duplicate-removed",
}
CLASSIFICATION_STATUSES = {
    "operator-configurable": {"already-centralized", "confirmed-hardcoded", "converted", "deferred"},
    "security-ceiling-or-default": {"retained", "deferred"},
    "protocol-or-format-invariant": {"retained", "deferred"},
    "published-contract-description": {"retained", "deferred"},
    "presentation-text": {"retained", "deferred"},
    "derived": {"retained", "deferred"},
    "test-fixture": {"retained"},
}
TESTKIT_MODULES = {
    "ravenroot-api-testkit",
    "ravenroot-engine-testkit",
    "ravenroot-persistence-testkit",
    "ravenroot-sandbox-supervisor-testkit",
}
EXCLUDED_PARTS = {"target", "node_modules", "dist", ".git"}
SOURCE_SUFFIXES = {".java", ".js", ".mjs", ".ts", ".py", ".sh", ".yaml", ".yml", ".json"}

VERIFICATION_SCRIPT_FIXTURE_FAMILY_ID = "audited-verification-script-fixtures-v1"
VERIFICATION_SCRIPT_FIXTURE_REVISION = "63661709c127bdc206857343382d3a1d4d47274a"
VERIFICATION_SCRIPT_FIXTURES = {
    "scripts/measure-e2e-stability.sh": (
        "e2e-stability-measurement", "ed25097ed031b3fb048d563cf47f03481768f5e716afb0890a3e14fc35a6f8a8", 57,
        {"binding-default": 1, "environment-binding": 4, "inline-script-operational": 41, "script-default": 11}),
    "scripts/verify-empty-plugins-parity-ci.sh": (
        "published-image-empty-plugin-parity", "ecc85e4b1efbe50d63c0f878aaa4dae701ed5361f83a879cb54655444f2d700d", 49,
        {"inline-script-operational": 36, "script-default": 13}),
    "scripts/verify-empty-plugins-parity.py": (
        "image-filesystem-comparator", "564e3e3ac7108646c460eef817951421fda0ef46a9706782b06398ff723b5ccd", 29,
        {"inline-script-operational": 28, "script-default": 1}),
    "scripts/verify-empty-plugins-parity.sh": (
        "local-image-empty-plugin-parity", "72001195f773a80f9fcf1142035dc3d7e6a1786359cfd714b935e55670a30446", 30,
        {"inline-script-operational": 22, "script-default": 8}),
    "scripts/verify-extension-pack-consumer.sh": (
        "extension-pack-consumer-contract", "b0d2f47f494c5c11daad85ddf66699451dcf2dd070aa327c10e3864b00b82f18", 16,
        {"inline-script-operational": 9, "script-default": 7}),
    "scripts/verify-mail-imap-consumer-container.sh": (
        "mail-consumer-container-contract", "e747acddda06b1816f9c99e129d71cbdaa29e327875a5d5816de90f04c01168a", 27,
        {"binding-default": 2, "environment-binding": 5, "inline-script-operational": 11, "script-default": 9}),
    "scripts/verify-mail-imap-mutations-container.sh": (
        "mail-mutation-container-contract", "cb833155dc8c083add03904afdf10ab572b537a1236f541efc342c27a946f11a", 24,
        {"binding-default": 3, "environment-binding": 3, "inline-script-operational": 10, "script-default": 8}),
    "scripts/verify-plugin-activation-on-compose.sh": (
        "compose-plugin-activation", "97ffc49f1f58c4adc39ed89fbc4677cf45c84a9ae48de6d5352391541cc50089", 89,
        {"binding-default": 8, "environment-binding": 11, "inline-script-operational": 46, "script-default": 24}),
    "scripts/verify-plugin-activation-on-image.sh": (
        "published-image-plugin-activation", "9edfe04b31f7a3883dd8d769c1a47650ee688c00b35131fdc0d7734e516d5a47", 85,
        {"binding-default": 2, "environment-binding": 6, "inline-script-operational": 52, "script-default": 25}),
    "scripts/verify-plugin-palette-ui.sh": (
        "plugin-palette-integration", "b1bfa6e192e3aeae5ade4fb2d438769b9b05ece18ce7c550bfcfb25d71abfa55", 46,
        {"binding-default": 2, "environment-binding": 10, "inline-script-operational": 18, "script-default": 16}),
    "scripts/verify-plugins-dir-confinement.sh": (
        "plugin-build-context-confinement", "0869b6787ce7588535086f71ab7e214b2340c5ef29b2980a5baf1342c38eb965", 61,
        {"environment-binding": 5, "inline-script-operational": 45, "script-default": 11}),
}
VERIFICATION_SCRIPT_INBOUND_GUARDS = ("dev.sh", "service.sh", "plugin.sh")
VERIFICATION_SCRIPT_CALLERS = (
    {
        "caller": ".github/workflows/ci.yml",
        "callee": "scripts/verify-extension-pack-consumer.sh",
        "invocation": "run: ./scripts/verify-extension-pack-consumer.sh",
    },
    {
        "caller": "scripts/verify-empty-plugins-parity-ci.sh",
        "callee": "scripts/verify-empty-plugins-parity.py",
        "invocation": 'python3 "$PROJECT_DIR/scripts/verify-empty-plugins-parity.py" '
                      '"$BASELINE_IMAGE" "$CANDIDATE_IMAGE"',
    },
    {
        "caller": "scripts/verify-empty-plugins-parity.sh",
        "callee": "scripts/verify-empty-plugins-parity.py",
        "invocation": 'python3 "$PROJECT_DIR/scripts/verify-empty-plugins-parity.py" '
                      '"$BASELINE_IMAGE" "$CANDIDATE_IMAGE"',
    },
)
VERIFICATION_SCRIPT_SHEBANGS = {
    path: ("#!/usr/bin/env python3" if path.endswith(".py")
           else "#!/usr/bin/env bash" if path == "scripts/verify-extension-pack-consumer.sh"
           else "#!/usr/bin/env sh")
    for path in VERIFICATION_SCRIPT_FIXTURES
}

UI_TEXT_FAMILY_ID = "ui-text-english-catalog-v1"
UI_TEXT_CATALOG_PATH = Path("ravenroot/ravenroot-ui/src/ui-text.js")
UI_TEXT_TEST_PATH = Path("ravenroot/ravenroot-ui/test/ui-text.test.js")
UI_TEXT_APP_COMMANDS_PATH = Path("ravenroot/ravenroot-ui/src/app-commands.js")
UI_TEXT_APP_PATH = Path("ravenroot/ravenroot-ui/src/app.js")
UI_TEXT_REVIEWED_REVISION = "63661709c127bdc206857343382d3a1d4d47274a"
UI_TEXT_CATALOG_SHA256 = "d10e9a1544beaa7b70f77e661d8588cc298d7f9788766f127757a6f34dfc8b8a"
UI_TEXT_TEST_SHA256 = "e6ca57f84c2e06ec6f2bec86d233e33532e885b41b2ecd176b10d3f53d659b6e"
UI_TEXT_LOCALIZE_DIGEST = "112ebafda98ef66415e7d4560b8f396bc3f65449835f1d1f303e96c22c52a383"
UI_TEXT_APP_SINK_DIGEST = "4c8e22f34d9f70a82e7fbdf21f56b3f5d12b220d55fb00b739987e13afe7e76e"
UI_TEXT_SCHEMA_V1_INVENTORY_REVISION = "fcd928ebb7786ea1cf1cf4f7080a7b18a1a48047"
UI_TEXT_SCHEMA_V1_SOURCE_SHA256 = "72bfb30b905f67ae5fc70a17eaae67e3663ec99ebd25a6bcd373b0cee5cc596b"
UI_TEXT_SCHEMA_V1_IDS = (
    "oc-12f6a427111d040bd4aa", "oc-8416e8b851cce3329b1d",
)
UI_TEXT_SCHEMA_V4_SOURCE_NEW_IDS = frozenset({
    "oc-ce9850da7630d6ea1749", "oc-2b32b53ea0f7822612ca",
    "oc-9e874d946f8a79b73530", "oc-5873f014f9ea8d7d9d03",
    "oc-49d55d48af24d04c9b73", "oc-47f34f542bba68d1c400",
    "oc-81ef8e3dfab6a0643768", "oc-63fe3715fe8bd63180a3",
    "oc-9df1108c1a74357909a7", "oc-a19936bc2054e0f6bd62",
    "oc-146806df89282eb9ca15", "oc-a1986315a895c02bddc9",
    "oc-c95227fa97e491b36828", "oc-673cf3bf45ae267157f3",
    "oc-93998a417671476b45c5", "oc-9caa334287f5b6915ecd",
})
UI_TEXT_PRESENTATION_IDS = frozenset({
    'oc-0778a2efb5d8708bd646', 'oc-088e92c26628d0adad80', 'oc-0a5f0ed674086ddb2a03', 'oc-0dae250cd9621cd2f078',
    'oc-0e9d49471b4d33ec20d7', 'oc-10409c736ba775d117a0', 'oc-127aadfd00e04a9bdcac', 'oc-143b93a3f6be93b7aacb',
    'oc-146806df89282eb9ca15', 'oc-1693e1104b56860c24ef', 'oc-16f0bc5f5c9a5f64ddf0', 'oc-19db1ca6030468cd33f6',
    'oc-1d1c293f4d3812952dbf', 'oc-1ec5bfe7ddda0f9a9531', 'oc-1f3aaec970ae4353deca', 'oc-1f991e30871b8144c15e',
    'oc-2088e23ec2b5c82b6975', 'oc-211965fa170d58817599', 'oc-212b2d5da98d5df3839c', 'oc-2297ef2d047fc75b4736',
    'oc-232a016775345ef87eb2', 'oc-235f0ab166fbbd80eaa7', 'oc-23e1114860fab3b06c71', 'oc-27e517a8445bc7e0cc28',
    'oc-283c1ba9a7afb4762cf0', 'oc-2bbfe90cc60fb23c76c5', 'oc-2ce85f3c6c0e2590d72f', 'oc-2efc3c45ea011522ecfd',
    'oc-303da9331161cc07ddb9', 'oc-3a57c578367bbbc78c57', 'oc-3a71146ce353adc1a9ba', 'oc-3edeaf0885840907d21a',
    'oc-3fc94ec398ef106d9d2e', 'oc-426862d0e196dec85172', 'oc-42fdd72bb206e94e335e', 'oc-4305f020c62737a57720',
    'oc-432a6d4d782319f505d1', 'oc-48ec2c553a9c3e7c49de', 'oc-49d55d48af24d04c9b73', 'oc-4b34227edda242ed157a',
    'oc-4b76a1a6918a24c3877b', 'oc-5178a796c0c9daf841a0', 'oc-52935e1fa959a61f471d', 'oc-55f7e1b896ef59bd38f2',
    'oc-5863b2a659fd37fb755c', 'oc-58af37ce7a00624298ea', 'oc-597724da5399e10c9c7e', 'oc-5a6bc3be4e7a3ce3b34e',
    'oc-5b7f8bca15ac34d37862', 'oc-5f4abc94973ef462fa68', 'oc-60a324066a93967bba51', 'oc-61fc921b9f38867524ae',
    'oc-62ccc8e18216706c04f8', 'oc-65aefc7e777e43aa8475', 'oc-67cd9e297cf2e4b680c4', 'oc-68b6b526715d964be7df',
    'oc-6f786cadeadfd97b615b', 'oc-701fa31babbcf1b48f14', 'oc-70ab8d1dfe2cb6fe5495', 'oc-712f6659e1b53ab5c778',
    'oc-7675415b516f25739652', 'oc-76c9f37f7260c3bc8076', 'oc-7aecc4290a9fc48e0047', 'oc-7b76376ff1c391e4a1c1',
    'oc-7d92117979cc5a7b7fd8', 'oc-7ebd8a5ba39c7361a118', 'oc-81ef8e3dfab6a0643768', 'oc-879323805ad6d69cd14b',
    'oc-8afc9cd3bd60f8ded5c5', 'oc-8c8b815d2d931fbbacc1', 'oc-91919c995435b5aaa778', 'oc-93998a417671476b45c5',
    'oc-9ab27d08765248606f12', 'oc-9df1108c1a74357909a7', 'oc-9e874d946f8a79b73530', 'oc-9eb3772523d4450f467a',
    'oc-9f4b7258ae0ce244e93b', 'oc-a2579b787ff2a311e439', 'oc-a2a0f0fe291dcf77cefe', 'oc-a3c5e7d725c88040f40f',
    'oc-a523251b537aa0a53cb4', 'oc-a7077ac961ac25158d03', 'oc-a7f91b5723cf67680859', 'oc-a82bd81f37cb13a3e9eb',
    'oc-a86eda883f318b1380ea', 'oc-aa5f3a4c71c18690b7fd', 'oc-ac067908bede1dd7c4b5', 'oc-ad11fc25502ea196a213',
    'oc-ae8e6aaa6ef3373dfa9f', 'oc-af1c179aef54ee007b2d', 'oc-b1f7edb0fc1f89c183fe', 'oc-b391c81ac924ce8e2295',
    'oc-b4b04ab74cfd9a6e8374', 'oc-b5b8b9e035b2600b45a2', 'oc-b6640abc9d839a5c6319', 'oc-b6fc2ed2012e23320746',
    'oc-b78e88a1559c230c86e1', 'oc-b799a34f94130eb1fa7c', 'oc-b86291021cc115415aba', 'oc-b8c2bfd8436182c15aaf',
    'oc-b96f5a3985aedbd36aa3', 'oc-b9844bc4e4043cd4b6c4', 'oc-bda7bf14f5950239b56b', 'oc-c0136d34409eef3f6e0d',
    'oc-c05079581e5b29f71da0', 'oc-c09da8cc019889050507', 'oc-c37d6e29dba8200551ce', 'oc-c95227fa97e491b36828',
    'oc-cb043b104b2707e47644', 'oc-ce9850da7630d6ea1749', 'oc-ceb56bb8d0887eed9073', 'oc-cf0d7b941854e4f878d1',
    'oc-d9157b3995aae94daeb2', 'oc-d997450675c193f636e1', 'oc-d9b6131ac8e2483dbbbb', 'oc-db2479072f9565cb19f6',
    'oc-dd3b747538e5570b2233', 'oc-ddc8a544bcd72ccc924b', 'oc-e0d31122bd3ba3add911', 'oc-e15b90401e0ce341e155',
    'oc-e171acb561ac8095f611', 'oc-e3868604b019ff1f5a58', 'oc-e61c49c08dc65aedae1f', 'oc-e737206f8ae5bd630310',
    'oc-e74d16a7c8d7a2f0168c', 'oc-e96659fe19c3c6f2c9fd', 'oc-ec0561175c8ad3838d43', 'oc-ec7ffd01fffec8a969f1',
    'oc-f19f409192b0f2be984b', 'oc-f556019b3bec8d89b2d4', 'oc-f732c40605a558ae4536', 'oc-fb0baa9094fc554d2954',
    'oc-ff76b70c5fd54fe72608',
})
UI_TEXT_LOCALE_IDS = frozenset({
    "oc-e992a480aea3ee3919a4", "oc-f29c51f16adaa75b7f33",
})
UI_TEXT_PRESENTATION_RATIONALE = (
    "Canonical user-facing English copy is owned by the closed UI text catalog and is available "
    "through its verified locale fallback and renderer seams."
)
UI_TEXT_PROTOCOL_RATIONALE = (
    "Stable UI catalog lookup key or locale fallback atom is part of the closed text lookup format."
)

HELM_SCHEMA_CLOSED_FAMILY_ID = "helm-json-schema-keyword-and-type-vocabulary-v1"
HELM_SCHEMA_PATH = Path("deploy/helm/ravenroot/values.schema.json")
HELM_SCHEMA_REVIEWED_REVISION = "c356a76db83ca28d29b93e118e848a0e049e6147"
HELM_SCHEMA_REVIEWED_SOURCE_SHA256 = (
    "fdf22df3b0431837429ad66b536b3c7add37950080f0d9f553defcd8a0c085af"
)
HELM_SCHEMA_REVIEWED_SPEC_SHA256 = (
    "4e88bfe5caafe72549bbd1cd03994a128edf728e852eb5b591aea753cb6a825d"
)
HELM_SCHEMA_KEYWORDS = frozenset({
    "type", "minimum", "maximum", "oneOf", "$ref", "required", "enum", "pattern",
    "minLength", "minItems",
})
HELM_SCHEMA_TYPE_VOCABULARY = frozenset({"object", "array", "string", "integer"})
HELM_SCHEMA_CLOSED_IDS = frozenset({
    "oc-005d262e99a349f9fed3",
    "oc-00c22f9f6e830608b39d",
    "oc-01b0fedf9c78137ba759",
    "oc-01d9c3913aff25c37e4e",
    "oc-020e28da91abe48cea5c",
    "oc-031ead3eaac98c5fc3bb",
    "oc-039055a3e1aa62a1a74e",
    "oc-03ed74b2b68f5d6ee92a",
    "oc-049b20d8350412e9c17d",
    "oc-04ad878bdbdb414ba55b",
    "oc-04ef83b3ffb1d985a193",
    "oc-05bb0729aa1f7f1203c2",
    "oc-077174e5d73fb916828e",
    "oc-08dd81e0fb8e018e9cee",
    "oc-095f8b370a3e7a357772",
    "oc-0a42e45cd3949c8261c3",
    "oc-0b73f99fbcd171823db6",
    "oc-0bff50e9ea62f75aeb70",
    "oc-0c3eb1d4c4017ab3bbb0",
    "oc-0c8b324117cd1d1a87a6",
    "oc-0d7c73f2e16371008564",
    "oc-0eedd7d631f0c9780ebe",
    "oc-1016b4847442919a9f27",
    "oc-1252e1a07a37359f8206",
    "oc-128bafb10a0964a71820",
    "oc-152c613a3a8fab8231b5",
    "oc-15c97a042ed498958650",
    "oc-15e4edd63a86148fc2b7",
    "oc-161b64c2b049621bf513",
    "oc-166a7ad264d06d0cac61",
    "oc-18524422f2f110513d7f",
    "oc-193e5b5d409c2edc14e7",
    "oc-198fbf7ca5076ea067af",
    "oc-1a3dd607a43c9a6fc1c2",
    "oc-1a62642ef6dd51b9be99",
    "oc-1bf914ec657d6ba2e082",
    "oc-1c693d338c00988f68ec",
    "oc-1cc65baebdd7036e55bd",
    "oc-1cf6c8d6ba6114127863",
    "oc-1e8960f3fd10199d72e7",
    "oc-1f18e295fb2814d50dfc",
    "oc-20b4a92798e15a967ba0",
    "oc-2130fbcd77f4c3fe4071",
    "oc-22cc0f0447700d74a129",
    "oc-22d343a44a57a51e9e63",
    "oc-2452f8c401be462d8b10",
    "oc-26a30147b4a341cd97c3",
    "oc-26b597f6eadc38a3e09d",
    "oc-27187178ceae2ed4251d",
    "oc-28241b2da4de09f55dc8",
    "oc-2a2544ec7c7d40a77352",
    "oc-2a34adf6f2ef4fd7a454",
    "oc-2a40e4b3d9edafe0b67b",
    "oc-2acd68d49e7d57b7508c",
    "oc-2b1ee7e6fa8648cdd3ec",
    "oc-2c911880c68d2fce8cc8",
    "oc-2cca3de935c941edc413",
    "oc-2d03672b683978563b91",
    "oc-2d2d5299b46008a1634c",
    "oc-2db8e4434ae796a68a05",
    "oc-2e85c815fb32a35729fd",
    "oc-2e98baf2a34da37aa046",
    "oc-2ed96391e0bafec34f12",
    "oc-2f5a7cae2e87ddb618a8",
    "oc-3020738a54d1014c8d7b",
    "oc-30549b1e99dc10292d3c",
    "oc-30df78063f137a06825f",
    "oc-30ed9bf6fbe0609c7b4b",
    "oc-31186b403650944288a6",
    "oc-31d142043072dba081c6",
    "oc-3217a82495ca615c75dd",
    "oc-3323109b00985c217aec",
    "oc-33388ea100f6b34a6177",
    "oc-33748656bff6d322ed85",
    "oc-34547fef5b593d6ab463",
    "oc-3528ef0bf3b7fa3c2ae9",
    "oc-3631f645e061383b1fe1",
    "oc-364e04bba7822657b2b9",
    "oc-37523fe8f85e08212531",
    "oc-379496ead6d6928a71fe",
    "oc-37bb70969eed74f5143e",
    "oc-3847895f4336426236c0",
    "oc-3874f3b422018841a48c",
    "oc-38ca48666e022bbe8755",
    "oc-3acb1759ccacf7aa3497",
    "oc-3b13ac2cbda79ba11631",
    "oc-3ba6103dbbcf8283192e",
    "oc-3bde49b49644280ff550",
    "oc-3bfcd57252b82438737d",
    "oc-3c85531917465c7e1ae2",
    "oc-3c98ef56025f9337c8d2",
    "oc-3deeb93660d7064b340f",
    "oc-3dfc86eb4ee3b5d51bc5",
    "oc-3dfcbd16f7ee7648f599",
    "oc-3e225992460a2f6b60df",
    "oc-4057f048577da2328a60",
    "oc-4082c427622bf86224b1",
    "oc-422e44bfc51582197f29",
    "oc-4239d41eb4687518a2c3",
    "oc-42404732f92e60dfdce4",
    "oc-429dbc64bea5897a5362",
    "oc-42ae47d502feadafc85d",
    "oc-433d3952899a37d2c454",
    "oc-447f5fed915f78010191",
    "oc-44ba9f5d6006a3db350c",
    "oc-44cd6149899fdf3c61ee",
    "oc-45004995268035ca0ecb",
    "oc-45b9083d18a8a2158290",
    "oc-46707882d8362a3badec",
    "oc-46ef287b2fa4a928dfbb",
    "oc-47922f85b26b647c427f",
    "oc-491369ce2531749c2cfb",
    "oc-496e6b006bd91be5104c",
    "oc-499cf91995dcac655b8b",
    "oc-49fba4c487895c2d92e1",
    "oc-4afc10197afee35ca07b",
    "oc-4bd721ed2d1424657927",
    "oc-4be2fb6f52d5740194a0",
    "oc-4c3bd90861afd03c64b1",
    "oc-4c4a6606b7f5b230148a",
    "oc-4d5c4f87100985c136b7",
    "oc-4d6c9284fdaa12e5f257",
    "oc-4e9b64ebed5b7a186ce2",
    "oc-4ee0746187cab14caf05",
    "oc-4f0320b08bd5b1124563",
    "oc-4f0a85b71f82e09ea796",
    "oc-4f43bd2c908c73a8ea9f",
    "oc-502eb33be2737c17c1e7",
    "oc-505d880adc41dbbacf7b",
    "oc-506e64fb132137eba3cd",
    "oc-512656983610ecbfef7b",
    "oc-5171de7012c21068925a",
    "oc-51e44c4df1102af00f5c",
    "oc-52036caded17fa8eae88",
    "oc-52807759f505e23376d6",
    "oc-528657ce46d2a0ec4194",
    "oc-5456afbecb8bf00587e3",
    "oc-54adef26af329dee80db",
    "oc-54f9f6bb889c0fc86e3b",
    "oc-551b8faa3d7cedeb8dfa",
    "oc-5586e107cf989c8fd917",
    "oc-5622056d4409ee8063a2",
    "oc-566794e0dd89158a32c0",
    "oc-57aa8b5abec6aaf07a0b",
    "oc-5968d0b3c7c3bf3865d8",
    "oc-596f505c6c9d4e3e602a",
    "oc-59daae45696732a1205d",
    "oc-59fe8188512ef92b2942",
    "oc-5a5a0025d9e70cccf179",
    "oc-5a7f5d1d8c9a28f5aecb",
    "oc-5acc9ba4db37400497c5",
    "oc-5b54854d2e93adfcdbcd",
    "oc-5bf9272376b8ab6b026c",
    "oc-5cb83d53266174efebcc",
    "oc-5d5f66d1692ce700d042",
    "oc-5d7962c7de8fda6db92c",
    "oc-5eac3140da87c6c25ff2",
    "oc-5f7f759a61bdc5bd78c1",
    "oc-6014eb8cbff12fb9f485",
    "oc-61958019414b69027ed3",
    "oc-627dc85485eac04359e4",
    "oc-638ad4e089a8de722f99",
    "oc-63cc754e53fa3eefd3f0",
    "oc-647b5c1bb95fbee06575",
    "oc-65e9250e7cd59c34f4b6",
    "oc-68d966e44cbd84f1c0fe",
    "oc-6a4ca67b1a29d5ba24fd",
    "oc-6b1cd22c98505eb5970d",
    "oc-6cca20618ac72a597d33",
    "oc-6ced1e6e9e33779d8190",
    "oc-6d4b645a574ffa372068",
    "oc-6d5135580b4cff195b65",
    "oc-6f330d852d7cbb912087",
    "oc-6f8d880ce8954f0f14c5",
    "oc-6fc2ebc11d73dd595a82",
    "oc-716982ebe7b9be8dada1",
    "oc-71c972c672a6da1670f7",
    "oc-73033e96d582be4dbeb9",
    "oc-730538cd2784e018ed30",
    "oc-736271ca5a9ef4c5bf78",
    "oc-739a776bc4866287cb76",
    "oc-7661adac1c1c23b800cc",
    "oc-76cb7481e3a1afdaa606",
    "oc-77662deed80cd6da1ead",
    "oc-7810f1dc277d37750672",
    "oc-7856f72d393b3c9ab82e",
    "oc-78730e87092644831935",
    "oc-788a4be2a106e3b7b780",
    "oc-78b27c5e22f8de0c26e2",
    "oc-79b24c08988b2f6ebaeb",
    "oc-79beca25802c9ddcdeb8",
    "oc-79d4ea7b0ab673fc9e7d",
    "oc-79e45c619de0c2c12c9e",
    "oc-7a217196adbb98df980d",
    "oc-7aa6d2602bbbd6ca6305",
    "oc-7ac0549c5a67a593ad3c",
    "oc-7b3d20fc7611bb948f04",
    "oc-7b4dff1ba681c3b97577",
    "oc-7bbf452a3fd4394e3ac8",
    "oc-7c9222db214428927172",
    "oc-7e97e07ff585769cf0ae",
    "oc-7ebf65b7dcddb52bef3c",
    "oc-8004ee61f171a9743efe",
    "oc-8129f06600c617c1129c",
    "oc-852b520801cb1427e807",
    "oc-859d9b7fe9efd0e8990e",
    "oc-85e4007da0deddbf9772",
    "oc-870d04a158745403dc65",
    "oc-87411de0ac91f68b24b3",
    "oc-87479d4549c1db6ebf3e",
    "oc-879dd163cb22396e0f20",
    "oc-88407e9372f4c5426586",
    "oc-8a2339a90e772a6f13e3",
    "oc-8a40bf3a2bab089204c7",
    "oc-8db8470b786cf1122be8",
    "oc-8dd7b61548420d4fc707",
    "oc-8dda0e2493e1a4a6b63d",
    "oc-8e437da10d6cb6409db5",
    "oc-8e5e76189f7b9e926dd3",
    "oc-8ea623b59f81da36aa46",
    "oc-8f2e1bb69e9295250224",
    "oc-8faabf9257090256b82a",
    "oc-916ee9828386ed9f88c6",
    "oc-9175057082f1dc78c78e",
    "oc-91f71536e5b56f9eb09e",
    "oc-927a2e05f35d939bdda7",
    "oc-930e16e8a1f6db5e22a7",
    "oc-933b4bf41fd93d5461a0",
    "oc-93905982dfc19946bacb",
    "oc-93ab7dfbd619c25577c6",
    "oc-93ae66d3450dd770d8b8",
    "oc-93c9463f47e18d394b74",
    "oc-940ed2df55f40e7c6be4",
    "oc-9458548adbd8f5239c40",
    "oc-945a3419669963484ef2",
    "oc-9468b7fe1369c625994f",
    "oc-94e1568755d067b136a8",
    "oc-954ea1bfc6f3ef06703c",
    "oc-95530901d79301c1521a",
    "oc-95c2119ee61129139398",
    "oc-960653da1f7b1966a252",
    "oc-9640d4212084ebd54a1f",
    "oc-9804971ed85f8fc6a435",
    "oc-995782aa6dd75f7a12f5",
    "oc-9a0c4cb68e3fa9bde2ae",
    "oc-9a1cd2aeeca34f8f27cb",
    "oc-9a5302b82f3a80106fc3",
    "oc-9c5dfb0926b2a2d54f03",
    "oc-9ce557d59316276902a2",
    "oc-9e9923c25de0b2002c8b",
    "oc-9ed2666fc0f4dd148cb6",
    "oc-9eee228d1abd55d3209a",
    "oc-9ff016253223c51ce864",
    "oc-a0d198368cd5d3e40df9",
    "oc-a121496e4e06bb686bbc",
    "oc-a1446400a2a74f785412",
    "oc-a19f76d0196e277d45bd",
    "oc-a20d135f53c3780392a3",
    "oc-a28d778dcda1d67156a6",
    "oc-a2961f331b8653abf56a",
    "oc-a4ea05a24147f563c7d8",
    "oc-a5222af4aa7e3cd10009",
    "oc-a5930ac8b1cbc38d514b",
    "oc-a5ed67b0e9bf9307ea26",
    "oc-a5f12d86b96ea189a0ca",
    "oc-a645709442aa5bae9c9a",
    "oc-a7156b331bb1ffd940fa",
    "oc-a80c42f5a399ca406c8a",
    "oc-a91142d37c3d897714ea",
    "oc-a93fd244445bbb8fa284",
    "oc-a94b6d75264dfebec8c9",
    "oc-a97d15eb6f1a60db1469",
    "oc-a9b004118e373bff95e4",
    "oc-aa89abb5acc0000fad96",
    "oc-aaa89b4feb5b6a5c87f9",
    "oc-aaada88f540a7e62b410",
    "oc-ab20dc6d4c023e48d4c1",
    "oc-ab47a6248e2627059a6c",
    "oc-ab94f01a1a6f66583729",
    "oc-abb94e8bd6ce1630380f",
    "oc-ac35e6cd345080536916",
    "oc-ac70d8ae3337b731bef7",
    "oc-ad16f6064eeeadfc8822",
    "oc-ad20e7c429a9c505f571",
    "oc-ad75ae49c4c5b24d787a",
    "oc-ad9debe84107afd66ec1",
    "oc-ae8145ef2f6f2854691f",
    "oc-aea7a972dca135fd6089",
    "oc-af30ca06e1f9fb1b7b76",
    "oc-af30e29bbea3fac81727",
    "oc-af372c96f0173b283548",
    "oc-b0850ccbb0bff0d17612",
    "oc-b0d3927fda40877b5218",
    "oc-b0f3f1b49ab762aa9862",
    "oc-b1b2ae6663453ff3d725",
    "oc-b2174943836bc74ed9c7",
    "oc-b32348c94bd2b1f4e66e",
    "oc-b36642197a17897d4f05",
    "oc-b37e0d1f762a59eb324a",
    "oc-b41d5d9014f56b7284e1",
    "oc-b49d01826f0c793706b9",
    "oc-b4b5d48dd18ca164001e",
    "oc-b4d5fcd11e13a211018f",
    "oc-b567b06eda5fb8c59534",
    "oc-b56b28d0481ea0f0a6a3",
    "oc-b589853b95f7ba742664",
    "oc-b5c5511be8b856c5efc8",
    "oc-b693c59d341188a5ac38",
    "oc-b6f5eae61239ad5bff1e",
    "oc-b78267a8650bc8acf25e",
    "oc-b7b43c5354caba560d97",
    "oc-b8385995ea3ce2736dfe",
    "oc-b8e64d56c9be94c73aee",
    "oc-b99ee7a08fd85b166a62",
    "oc-ba2fc586f7fb53c0622d",
    "oc-ba5deb5e5e75260dadfe",
    "oc-bac85fd68e8ab6b07af1",
    "oc-bb6f057a0c4adb081bdd",
    "oc-bbc78deecc478f30602e",
    "oc-bbd3927d5ada46da5e03",
    "oc-bc677458dfe6092421fe",
    "oc-bc7e985990b54af5137b",
    "oc-bc9d76add707ac1854ba",
    "oc-bcad6d281df168bc3874",
    "oc-bcd9c4f183b2ffc5f8fd",
    "oc-bd207591f1d0dabd0d4e",
    "oc-bf80e100afd4eaea27fc",
    "oc-bff1b5d21f29ab35613d",
    "oc-c0698b781978eb4ba495",
    "oc-c0e636f680b46b943e84",
    "oc-c198f608c474ee54511d",
    "oc-c1e24ba0a2f88b3ca9db",
    "oc-c24d75f1544b1bff8be4",
    "oc-c29a0f9ba74e289fe924",
    "oc-c2a1126a0e635062948a",
    "oc-c31a69207444fd541be9",
    "oc-c3215ef6c3a7a05fc74c",
    "oc-c3717e169dd915f40bda",
    "oc-c447b8f0fcf1539c0aaa",
    "oc-c448ba5371ce63016b5f",
    "oc-c4d7f0e311a805ae2626",
    "oc-c51c6f7fef6b1b45254b",
    "oc-c52cb8933be7965c2901",
    "oc-c6d4cab110ea27ddef83",
    "oc-c865590d347425e6abfc",
    "oc-ca1ca886cd25dbd9a18b",
    "oc-ca4a272f164c4c39524e",
    "oc-cab1213917f9c64fcf77",
    "oc-cb62732882dfa43b46fb",
    "oc-cbc0697f006c5e07ead2",
    "oc-cbc97c88bff4ca66b0ba",
    "oc-cbcb0509fda414759466",
    "oc-cbe053cd58eb30ffcc14",
    "oc-cc2a814be4a79b9fdd8d",
    "oc-cc564dbd7d60f1c979ac",
    "oc-cd5e15f722b793345427",
    "oc-ce1db7518d907ad6a437",
    "oc-cfe3af34d1f343c4ce90",
    "oc-d0a5f93a299c7fadaa4a",
    "oc-d0bd6c610032a6cc008a",
    "oc-d15ce383fd787255da9a",
    "oc-d25d998242a8b97b1eb1",
    "oc-d2f3645749d272cf7685",
    "oc-d421d7ae392b4cd4d779",
    "oc-d440f65010c6102e17bb",
    "oc-d4c1af3b9c0b0735bbf0",
    "oc-d5c402db7f7386eec6fa",
    "oc-d5c7dcf9c55f79b75193",
    "oc-d60de2a9fbf1f3b17e64",
    "oc-d657891f6e1fa4a2b437",
    "oc-d6614adc9995605ea79d",
    "oc-d6b12a7e38989a2ca43c",
    "oc-d6e9797c376ebea819ca",
    "oc-d75a50db66d05a9ef2d5",
    "oc-d7a60baa1e87dd172e05",
    "oc-d821e6352f5ded8cb5ba",
    "oc-d8f54d96e4ff364bfc0e",
    "oc-d92e0de11c8d987056b1",
    "oc-dbebd1be3404cd147a71",
    "oc-dc2aed1d09e7ef99e583",
    "oc-dc57cd828d22e0020e33",
    "oc-dd96d402045e027c49b3",
    "oc-dda093898bb0fe4cddb1",
    "oc-decb555e34198855338f",
    "oc-e05c5b574323e1300913",
    "oc-e1eb0d0278c82c6710c2",
    "oc-e27f71cbc54820d67801",
    "oc-e299fec2cb108477bc77",
    "oc-e350501d73e86e902286",
    "oc-e3b3be9073823a848917",
    "oc-e4a24526f9804c0f6007",
    "oc-e4a53a690b69097246b6",
    "oc-e50be5d06107c05a3720",
    "oc-e515912a18c55222dfa2",
    "oc-e5d99bcff26506fb9433",
    "oc-e601a52eefc6800843d6",
    "oc-e68c75b8d1dbfda79d84",
    "oc-e6bda13a2698cca2b9df",
    "oc-e7d5544ba5c8374b4510",
    "oc-e8b361e82ee5e9da28b2",
    "oc-e92a3a724ea6d78aac87",
    "oc-e93e0c751f9f53c9dfdc",
    "oc-e9b9a2cbb46eb45d72ad",
    "oc-ea30df14cb449a51ea5f",
    "oc-eb41cd534f819c4ede2d",
    "oc-eb94b51f2e4103215a8e",
    "oc-ecfd86203dc07ae3c192",
    "oc-ed2f6eaa0c1cc30a5a62",
    "oc-eedd6cfbae8d40ec1150",
    "oc-ef05f5dc74a4e66daac3",
    "oc-ef39078c2d97dc4ea4d8",
    "oc-ef9920d1820a7f141f80",
    "oc-effdd85d30556dc1e678",
    "oc-f1e4019aebd8cf41bc17",
    "oc-f242e648183331baaff6",
    "oc-f275f259ee27a4304fa5",
    "oc-f4224a643d014192d183",
    "oc-f4400d7999dd5b092840",
    "oc-f4497becdcc8df0f9250",
    "oc-f54c5bdd66854c6b8a89",
    "oc-f5601e9238968b9057bf",
    "oc-f5b6ce5ebef7c0637cdf",
    "oc-f605faf29e6b838fa2e5",
    "oc-f65bcafac2044afc1488",
    "oc-f694a591000d074ea307",
    "oc-f6f8a7dfffc3f3ef809b",
    "oc-f74a3981955dba987a0d",
    "oc-f7e82d29bd10e3faf2ac",
    "oc-f9da0dae771120fed986",
    "oc-fa302cfbfef0d6b4dcf3",
    "oc-fa66900dd02f9b169c89",
    "oc-fa982e268f1a231d1b57",
    "oc-faa2ef16136f93054d8d",
    "oc-faddde6c20001bb92d7b",
    "oc-fb97f77507d614421264",
    "oc-fbc68ebe2f53ed4ee470",
    "oc-fbe21ddbfe5ee637ccaa",
    "oc-fc402950c7c4c604bbf8",
    "oc-fc6800e4b61150a0d89d",
    "oc-fca913b7bff9c240490f",
    "oc-fd96d4ef78b2a2abe5ec",
    "oc-fdbb78436f97fd2be63c",
    "oc-ff24f5ce9570de4d1d7c",
    "oc-ffd149c0cfa6ad0daec9",
})
HELM_SCHEMA_KEYWORD_RATIONALE = (
    "This exact key is JSON Schema vocabulary at one reviewed schema-node position. "
    "Its spelling is a format invariant; its operand and runtime authority remain independently "
    "reviewed."
)
HELM_SCHEMA_TYPE_RATIONALE = (
    "This exact /type value is frozen JSON Schema vocabulary at one reviewed schema-node position. "
    "Changing the selected type changes the deployment contract and requires renewed review."
)

GITHUB_SCHEMA_FAMILY_ID = "github-versioned-action-payload-schemas-v1"
GITHUB_SCHEMA_REVIEWED_REVISION = "681568e938aa59f8480e1fd28ed5fa87555559be"
GITHUB_SCHEMA_PARTITION_SHA256 = "005495754796cae214b88e5556056c18bd0190da626df245945e93c8173b1564"
GITHUB_SCHEMA_PATHS = {
    "ravenroot/ravenroot-extensions/ravenroot-github/src/main/resources/META-INF/ravenroot/github/schemas/project-transition.v1.schema.json":
        ("fd25ae8677ee22eda0d49fa7ce401f06b9da34b1e0cdf9ddc71ede77113f81d9", 172,
         {"configuration-scalar": 170, "schema-reference-binding": 2}),
    "ravenroot/ravenroot-extensions/ravenroot-github/src/main/resources/META-INF/ravenroot/github/schemas/github-workflow-watch.v1.schema.json":
        ("d9a669ccffd113fa8f840745448645dbd9028ab8ea470b4cac48c4816c75578d", 166,
         {"configuration-scalar": 163, "schema-reference-binding": 3}),
    "ravenroot/ravenroot-extensions/ravenroot-github/src/main/resources/META-INF/ravenroot/github/schemas/release-prepare.v1.schema.json":
        ("5c23145d4bf1505a75b7cd959e7f11312eebdba76aea4099734362ee3a8e2cd6", 166,
         {"configuration-scalar": 163, "schema-reference-binding": 3}),
    "ravenroot/ravenroot-extensions/ravenroot-github/src/main/resources/META-INF/ravenroot/github/schemas/github-app-review.v1.schema.json":
        ("33bbf245382b749e5770a6e4c11f0c31595eab13790c34480453cb948bc556f8", 132,
         {"configuration-scalar": 130, "schema-reference-binding": 2}),
}
GITHUB_SCHEMA_INDEX_PATH = Path(
    "ravenroot/ravenroot-extensions/ravenroot-github/src/main/resources/META-INF/ravenroot/github/schema-index.json")
GITHUB_SCHEMA_INDEX_SHA256 = "d548dba5a4a97fda0a1134bc39c1697c4ce8f023e71f114d5c1a4a6c017cd291"
GITHUB_SCHEMA_TEST_PATH = Path(
    "ravenroot/ravenroot-extensions/ravenroot-github/src/test/java/ai/ravenroot/extensions/github/GithubBoundaryTest.java")
GITHUB_SCHEMA_TEST_SHA256 = "ffbdc521fb732859de3b394f4b482bdf9f29c03dd647aeba5e69169a8aa5037b"
GITHUB_SECURITY_OWNER_SYMBOLS = {
    "oc-03a7f22eb6ac51c6b6fc": "GithubWorkflowWatchBehavior.Input.parse",
    "oc-1a236d34af58b3524646": "ProjectTransitionBehavior.Input.parse",
    "oc-56ce39b5be04561eba93": "ProjectTransitionBehavior.Input.parse",
    "oc-6d58112a5a4d5fb914c3": "ProjectTransitionBehavior.Input.parse",
    "oc-7151deed6e0817d4dec6": "ProjectTransitionBehavior.TransitionComment.parse",
    "oc-7c0f5f9063a71a2cc545": "ReleasePrepareBehavior.Input.parse",
    "oc-88dba22febfba716b8e0": "GithubAppReviewBehavior.Input.parse",
    "oc-89f463cfd891b43aea0e": "ProjectTransitionBehavior.Input.parse",
    "oc-d6ee8b5d15f088cea90b": "GithubAppReviewBehavior.Input.text",
}
GITHUB_SECURITY_GUARD_METHODS = {
    "GithubAppReviewBehavior.Input.parse": (
        "ravenroot/ravenroot-extensions/ravenroot-github/src/main/java/ai/ravenroot/extensions/github/GithubAppReviewBehavior.java",
        "Input", "parse", "ccd3391eaf0d85954424e0fbb03303b849adbbed13780ea7578adf5a0e417194"),
    "GithubAppReviewBehavior.Input.text": (
        "ravenroot/ravenroot-extensions/ravenroot-github/src/main/java/ai/ravenroot/extensions/github/GithubAppReviewBehavior.java",
        "Input", "text", "20597dbdb419cdcd03edf6849b67d267ba856e2756003c582fa86ed083ac0d7b"),
    "GithubWorkflowWatchBehavior.Input.parse": (
        "ravenroot/ravenroot-extensions/ravenroot-github/src/main/java/ai/ravenroot/extensions/github/GithubWorkflowWatchBehavior.java",
        "Input", "parse", "663abb9a3159631272d3945bce96f4709eaebff7987a3ec7214c622045a58760"),
    "ProjectTransitionBehavior.Input.parse": (
        "ravenroot/ravenroot-extensions/ravenroot-github/src/main/java/ai/ravenroot/extensions/github/ProjectTransitionBehavior.java",
        "Input", "parse", "20ae2776072f99a09f9349537def92e26fa26ab84dd87351fa4ac00a83374d33"),
    "ProjectTransitionBehavior.TransitionComment.parse": (
        "ravenroot/ravenroot-extensions/ravenroot-github/src/main/java/ai/ravenroot/extensions/github/ProjectTransitionBehavior.java",
        "TransitionComment", "parse", "f26cbfc631acaa6d5a98c0c40d821b42e8bad7cccebd57b7a529265e21e1e4f2"),
    "ReleasePrepareBehavior.Input.parse": (
        "ravenroot/ravenroot-extensions/ravenroot-github/src/main/java/ai/ravenroot/extensions/github/ReleasePrepareBehavior.java",
        "Input", "parse", "15e9f25986417c4aa326b6a277623f5c4a514f176cbf11c4fdfe375177ab6980"),
}

VERIFICATION_SCRIPT_FIXTURE_RATIONALE = (
    "Fixed value belongs to an explicitly audited runnable verification fixture and cannot be "
    "reached from the three production launcher scripts."
)

OPERATIONAL_WORD = re.compile(
    r"(?i)(timeout|deadline|interval|poll|retry|attempt|capacity|queue|limit|max|min|retention|ttl|"
    r"lifetime|grace|delay|period|expiry|expire|workers|threads|batch|burst|rate|bytes|size|count|"
    r"lease|drain|age|port|path|directory|dir|memory|heap|cpu|replica|probe|health|cumulative)"
)
NUMBER = re.compile(r"(?<![\w.])(?:0[xX][0-9a-fA-F_]+|\d[\d_]*(?:\.\d+)?)(?:[lLdDfF])?(?![\w.])")
QUOTED = re.compile(r'''(?s)(?:"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')''')
FIXED = re.compile(
    r"(?s)(?<![\w.])(?:0[xX][0-9a-fA-F_]+|\d[\d_]*(?:\.\d+)?)(?:[lLdDfF])?(?![\w.])|"
    r'''"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|Duration\.of(?:Nanos|Millis|Seconds|Minutes|Hours|Days)\s*\('''
)
FIXED_ATOM = re.compile(
    r'''(?<![\w.])(?:0[xX][0-9a-fA-F_]+|\d[\d_]*(?:\.\d+)?)(?:[lLdDfF])?(?![\w.])|'''
    r'''"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*' '''.rstrip()
)
KNOWN_OPERATIONAL_CALL = re.compile(
    r"(?i)(Duration\.of(?:Nanos|Millis|Seconds|Minutes|Hours|Days)|new\s+(?:PayloadLimits|GraphExecutionLimits|GraphMlLimits|Limits|RetryPolicy|RetryBackoff|AgentBudgetVector|Semaphore|"
    r"ArrayBlockingQueue|ThreadPoolExecutor)|newScheduledThreadPool|newFixedThreadPool|withStash|"
    r"orTimeout|completeOnTimeout|readNBytes|sleep|setTimeout|setInterval|getOrDefault)\s*\("
)
# This is intentionally a bounded lexical contract rather than Java receiver-type inference. The
# explicit TimeUnit argument separates timed Future/latch/process/executor/lock/semaphore overloads
# from ordinary get()/await()/tryAcquire() calls. Dynamic and static-imported units are outside the
# supported pattern and remain visible only through their own fixed declarations, when present.
TIME_UNIT_OPERATIONAL_CALL = re.compile(
    r"\.(get|await|tryAcquire|tryLock|waitFor|awaitTermination)\s*\("
)
TIME_UNIT_ARGUMENT = re.compile(
    r"(?:java\.util\.concurrent\.)?TimeUnit\."
    r"(?:NANOSECONDS|MICROSECONDS|MILLISECONDS|SECONDS|MINUTES|HOURS|DAYS)"
)
ASSIGNMENT_NAME = re.compile(r"(?s)\b([A-Za-z_$][\w$]*)\s*=\s*[^=]")
STATIC_FINAL = re.compile(r"\bstatic\s+final\b")
JS_DECLARATION = re.compile(r"\b(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=")
PY_ASSIGNMENT = re.compile(r"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(?![=])")
YAML_SCALAR = re.compile(r'''^\s*["']?([A-Za-z_][A-Za-z0-9_.-]*)["']?\s*:\s*(\S.*)$''')
ENVIRONMENT_BINDING = re.compile(r"RAVENROOT_[A-Z0-9_]+")
PROPERTY_BINDING = re.compile(r'''"(ravenroot\.[A-Za-z0-9_.-]*)"''')
SYSTEM_PROPERTY_READ = re.compile(r"\bSystem\.getProperty\s*\(\s*([^,)]+)")
RESOLVER_TEST_ROLES = {
    "propertyPrecedence",
    "blankPropertyEnvironmentFallback",
    "blankSourcesTypedDefault",
    "malformedNonblankRefusal",
}
RESOLVER_COMPOSITION_METHOD_ROLES = {
    "typedDefaultsFactory",
    "nestedDefaultsFactory",
}
RESOLVER_COMPOSITION_LINK_ROLES = {
    "typedDefaultsInitializer",
    "nestedDefaultsAccessor",
}
ENVIRONMENT_RESOLVER_TEST_ROLES = {
    "bindingEnumeration",
    "blankTypedDefault",
    "asciiTrimContract",
    "malformedOverflowRefusal",
    "nonPositiveRefusal",
    "documentedBoundaryAcceptance",
    "relationalConstraintRefusal",
}
DEPLOYMENT_ENVIRONMENT_CARRIER_PATHS = {
    "compose": frozenset({"compose.yaml"}),
    "helm": frozenset({
        "deploy/helm/ravenroot/values.yaml",
        "deploy/helm/ravenroot/values.schema.json",
        "deploy/helm/ravenroot/templates/deployment.yaml",
    }),
    "rawKubernetes": frozenset({"deploy/kubernetes/ravenroot.yaml"}),
}
GRAPH_LIMIT_FAMILY_ID = "graph-execution-environment-v1"
GRAPH_EXECUTION_LIMITS_PATH = Path(
    "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/GraphExecutionLimits.java")
GRAPH_ML_LIMITS_PATH = Path(
    "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/graph/GraphMlLimits.java")
PAYLOAD_LIMITS_PATH = Path(
    "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/payload/PayloadLimits.java")
GRAPH_DEFINITION_STORE_PATH = Path(
    "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/persistence/GraphDefinitionStore.java")
GRAPH_LIMIT_TYPED_AUTHORITIES = frozenset({
    ("graph.execution.max-amplified-deliveries", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/GraphExecutionLimits.java#GraphExecutionLimits", "maxAmplifiedDeliveries", "RAVENROOT_GRAPH_MAX_AMPLIFIED_DELIVERIES"),
    ("graph.execution.max-cumulative-payload-bytes", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/GraphExecutionLimits.java#GraphExecutionLimits", "maxCumulativePayloadBytes", "RAVENROOT_GRAPH_MAX_CUMULATIVE_PAYLOAD_BYTES"),
    ("graph.execution.max-fan-out", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/GraphExecutionLimits.java#GraphExecutionLimits", "maxFanOut", "RAVENROOT_GRAPH_MAX_FAN_OUT"),
    ("graph.execution.max-in-flight-hops-per-traversal", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/GraphExecutionLimits.java#GraphExecutionLimits", "maxInFlightHopsPerTraversal", "RAVENROOT_GRAPH_MAX_IN_FLIGHT_HOPS"),
    ("graph.execution.max-live-actors-per-traversal", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/GraphExecutionLimits.java#GraphExecutionLimits", "maxLiveActorsPerTraversal", "RAVENROOT_GRAPH_MAX_LIVE_ACTORS_PER_TRAVERSAL"),
    ("graph.execution.max-queued-admissions-per-node", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/GraphExecutionLimits.java#GraphExecutionLimits", "maxQueuedAdmissionsPerNode", "RAVENROOT_GRAPH_MAX_QUEUED_ADMISSIONS_PER_NODE"),
    ("graph.execution.max-recovery-deliveries-per-attempt", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/GraphExecutionLimits.java#GraphExecutionLimits", "maxRecoveryDeliveriesPerAttempt", "RAVENROOT_GRAPH_MAX_RECOVERY_DELIVERIES_PER_ATTEMPT"),
    ("graph.execution.max-resident-actors", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/GraphExecutionLimits.java#GraphExecutionLimits", "maxResidentActors", "RAVENROOT_GRAPH_MAX_RESIDENT_ACTORS"),
    ("graph.execution.max-traversal-steps", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/GraphExecutionLimits.java#GraphExecutionLimits", "maxTraversalSteps", "RAVENROOT_GRAPH_MAX_TRAVERSAL_STEPS"),
    ("graph.graphml.max-attributes", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/graph/GraphMlLimits.java#GraphMlLimits", "maxAttributes", "RAVENROOT_GRAPHML_MAX_ATTRIBUTES"),
    ("graph.graphml.max-bytes", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/graph/GraphMlLimits.java#GraphMlLimits", "maxBytes", "RAVENROOT_GRAPHML_MAX_BYTES"),
    ("graph.graphml.max-depth", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/graph/GraphMlLimits.java#GraphMlLimits", "maxDepth", "RAVENROOT_GRAPHML_MAX_DEPTH"),
    ("graph.graphml.max-edges", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/graph/GraphMlLimits.java#GraphMlLimits", "maxEdges", "RAVENROOT_GRAPH_MAX_EDGES"),
    ("graph.graphml.max-elements", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/graph/GraphMlLimits.java#GraphMlLimits", "maxElements", "RAVENROOT_GRAPHML_MAX_ELEMENTS"),
    ("graph.graphml.max-keys", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/graph/GraphMlLimits.java#GraphMlLimits", "maxKeys", "RAVENROOT_GRAPHML_MAX_KEYS"),
    ("graph.graphml.max-namespace-declarations", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/graph/GraphMlLimits.java#GraphMlLimits", "maxNamespaceDeclarations", "RAVENROOT_GRAPHML_MAX_NAMESPACE_DECLARATIONS"),
    ("graph.graphml.max-nodes", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/graph/GraphMlLimits.java#GraphMlLimits", "maxNodes", "RAVENROOT_GRAPH_MAX_NODES"),
    ("graph.graphml.max-properties", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/graph/GraphMlLimits.java#GraphMlLimits", "maxProperties", "RAVENROOT_GRAPH_MAX_PROPERTIES"),
    ("graph.graphml.max-string-length", "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/graph/GraphMlLimits.java#GraphMlLimits", "maxStringLength", "RAVENROOT_GRAPHML_MAX_STRING_LENGTH"),
    ("graph.payload.max-collection-size", "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/payload/PayloadLimits.java#PayloadLimits", "maxCollectionSize", "RAVENROOT_GRAPH_MAX_PAYLOAD_COLLECTION_SIZE"),
    ("graph.payload.max-depth", "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/payload/PayloadLimits.java#PayloadLimits", "maxDepth", "RAVENROOT_GRAPH_MAX_PAYLOAD_DEPTH"),
    ("graph.payload.max-encoded-bytes", "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/payload/PayloadLimits.java#PayloadLimits", "maxEncodedBytes", "RAVENROOT_GRAPH_MAX_PAYLOAD_BYTES"),
    ("graph.payload.max-key-length", "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/payload/PayloadLimits.java#PayloadLimits", "maxKeyLength", "RAVENROOT_GRAPH_MAX_PAYLOAD_KEY_LENGTH"),
    ("graph.payload.max-text-length", "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/payload/PayloadLimits.java#PayloadLimits", "maxTextLength", "RAVENROOT_GRAPH_MAX_PAYLOAD_TEXT_LENGTH"),
    ("graph.payload.max-value-count", "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/payload/PayloadLimits.java#PayloadLimits", "maxValueCount", "RAVENROOT_GRAPH_MAX_PAYLOAD_VALUE_COUNT"),
})

GRAPH_LIMIT_SOURCE_CONTRACTS = (
    # setting, target constructor, root component, environment symbol, helper, fallback, ceiling
    ("graph.graphml.max-bytes", "GraphMlLimits", "graphMl", "MAX_GRAPHML_BYTES_VARIABLE",
     "integer", "graphMl.maxBytes()", "GraphDefinitionStore.HARD_MAX_DEFINITION_BYTES"),
    ("graph.graphml.max-nodes", "GraphMlLimits", "graphMl", "MAX_NODES_VARIABLE",
     "integer", "graphMl.maxNodes()", "GraphMlLimits.HARD_MAX_NODES"),
    ("graph.graphml.max-edges", "GraphMlLimits", "graphMl", "MAX_EDGES_VARIABLE",
     "integer", "graphMl.maxEdges()", "GraphMlLimits.HARD_MAX_EDGES"),
    ("graph.graphml.max-properties", "GraphMlLimits", "graphMl", "MAX_PROPERTIES_VARIABLE",
     "integer", "graphMl.maxProperties()", "GraphMlLimits.HARD_MAX_PROPERTIES"),
    ("graph.graphml.max-depth", "GraphMlLimits", "graphMl", "MAX_GRAPHML_DEPTH_VARIABLE",
     "integer", "graphMl.maxDepth()", "GraphMlLimits.HARD_MAX_DEPTH"),
    ("graph.graphml.max-string-length", "GraphMlLimits", "graphMl",
     "MAX_GRAPHML_STRING_LENGTH_VARIABLE", "integer", "graphMl.maxStringLength()",
     "GraphMlLimits.HARD_MAX_STRING_LENGTH"),
    ("graph.graphml.max-keys", "GraphMlLimits", "graphMl", "MAX_GRAPHML_KEYS_VARIABLE",
     "integer", "graphMl.maxKeys()", "GraphMlLimits.HARD_MAX_KEYS"),
    ("graph.graphml.max-elements", "GraphMlLimits", "graphMl", "MAX_GRAPHML_ELEMENTS_VARIABLE",
     "integer", "graphMl.maxElements()", "GraphMlLimits.HARD_MAX_ELEMENTS"),
    ("graph.graphml.max-attributes", "GraphMlLimits", "graphMl",
     "MAX_GRAPHML_ATTRIBUTES_VARIABLE", "integer", "graphMl.maxAttributes()",
     "GraphMlLimits.HARD_MAX_ATTRIBUTES"),
    ("graph.graphml.max-namespace-declarations", "GraphMlLimits", "graphMl",
     "MAX_GRAPHML_NAMESPACE_DECLARATIONS_VARIABLE", "integer",
     "graphMl.maxNamespaceDeclarations()", "GraphMlLimits.HARD_MAX_NAMESPACE_DECLARATIONS"),
    ("graph.payload.max-encoded-bytes", "PayloadLimits", "payload", "MAX_PAYLOAD_BYTES_VARIABLE",
     "integer", "payload.maxEncodedBytes()", "PayloadLimits.HARD_MAX_ENCODED_BYTES"),
    ("graph.payload.max-depth", "PayloadLimits", "payload", "MAX_PAYLOAD_DEPTH_VARIABLE",
     "integer", "payload.maxDepth()", "PayloadLimits.HARD_MAX_DEPTH"),
    ("graph.payload.max-collection-size", "PayloadLimits", "payload",
     "MAX_PAYLOAD_COLLECTION_SIZE_VARIABLE", "integer", "payload.maxCollectionSize()",
     "PayloadLimits.HARD_MAX_COLLECTION_SIZE"),
    ("graph.payload.max-value-count", "PayloadLimits", "payload",
     "MAX_PAYLOAD_VALUE_COUNT_VARIABLE", "integer", "payload.maxValueCount()",
     "PayloadLimits.HARD_MAX_VALUE_COUNT"),
    ("graph.payload.max-text-length", "PayloadLimits", "payload",
     "MAX_PAYLOAD_TEXT_LENGTH_VARIABLE", "integer", "payload.maxTextLength()",
     "PayloadLimits.HARD_MAX_TEXT_LENGTH"),
    ("graph.payload.max-key-length", "PayloadLimits", "payload",
     "MAX_PAYLOAD_KEY_LENGTH_VARIABLE", "integer", "payload.maxKeyLength()",
     "PayloadLimits.HARD_MAX_KEY_LENGTH"),
    ("graph.execution.max-fan-out", "GraphExecutionLimits", "maxFanOut",
     "MAX_FAN_OUT_VARIABLE", "integer", "defaults.maxFanOut", "HARD_MAX_FAN_OUT"),
    ("graph.execution.max-resident-actors", "GraphExecutionLimits", "maxResidentActors",
     "MAX_RESIDENT_ACTORS_VARIABLE", "integer", "defaults.maxResidentActors",
     "HARD_MAX_RESIDENT_ACTORS"),
    ("graph.execution.max-live-actors-per-traversal", "GraphExecutionLimits",
     "maxLiveActorsPerTraversal", "MAX_LIVE_ACTORS_VARIABLE", "integer",
     "defaults.maxLiveActorsPerTraversal", "HARD_MAX_LIVE_ACTORS"),
    ("graph.execution.max-in-flight-hops-per-traversal", "GraphExecutionLimits",
     "maxInFlightHopsPerTraversal", "MAX_IN_FLIGHT_HOPS_VARIABLE", "integer",
     "defaults.maxInFlightHopsPerTraversal", "HARD_MAX_IN_FLIGHT_HOPS"),
    ("graph.execution.max-queued-admissions-per-node", "GraphExecutionLimits",
     "maxQueuedAdmissionsPerNode", "MAX_QUEUED_ADMISSIONS_VARIABLE", "integer",
     "defaults.maxQueuedAdmissionsPerNode", "HARD_MAX_QUEUED_ADMISSIONS"),
    ("graph.execution.max-traversal-steps", "GraphExecutionLimits", "maxTraversalSteps",
     "MAX_TRAVERSAL_STEPS_VARIABLE", "longInteger", "defaults.maxTraversalSteps",
     "HARD_MAX_TRAVERSAL_STEPS"),
    ("graph.execution.max-amplified-deliveries", "GraphExecutionLimits",
     "maxAmplifiedDeliveries", "MAX_AMPLIFIED_DELIVERIES_VARIABLE", "longInteger",
     "defaults.maxAmplifiedDeliveries", "HARD_MAX_AMPLIFIED_DELIVERIES"),
    ("graph.execution.max-cumulative-payload-bytes", "GraphExecutionLimits",
     "maxCumulativePayloadBytes", "MAX_CUMULATIVE_PAYLOAD_BYTES_VARIABLE", "longInteger",
     "defaults.maxCumulativePayloadBytes", "HARD_MAX_CUMULATIVE_PAYLOAD_BYTES"),
    ("graph.execution.max-recovery-deliveries-per-attempt", "GraphExecutionLimits",
     "maxRecoveryDeliveriesPerAttempt", "MAX_RECOVERY_DELIVERIES_VARIABLE", "integer",
     "defaults.maxRecoveryDeliveriesPerAttempt", "HARD_MAX_RECOVERY_DELIVERIES"),
)
GRAPH_LIMIT_AUTHORITY_BY_SETTING = {
    setting: {"typedOwner": owner, "field": field, "environment": environment}
    for setting, owner, field, environment in GRAPH_LIMIT_TYPED_AUTHORITIES
}
GRAPH_LIMIT_SOURCE_BY_SETTING = {
    setting: {
        "targetConstructor": target, "rootComponent": root_component,
        "environmentSymbol": environment_symbol, "helper": helper,
        "fallbackAccessor": fallback, "ceilingAccessor": ceiling,
    }
    for setting, target, root_component, environment_symbol, helper, fallback, ceiling
    in GRAPH_LIMIT_SOURCE_CONTRACTS
}
GRAPH_LIMIT_DEFAULT_CONTRACTS = {
    "graph.graphml.max-bytes": ("GraphDefinitionStore.DEFAULT_MAX_DEFINITION_BYTES", "bytes"),
    "graph.graphml.max-nodes": ("10_000", "nodes"),
    "graph.graphml.max-edges": ("25_000", "edges"),
    "graph.graphml.max-properties": ("100_000", "properties"),
    "graph.graphml.max-depth": ("64", "nesting levels"),
    "graph.graphml.max-string-length": ("1024 * 1024", "UTF-16 code units"),
    "graph.graphml.max-keys": ("4_096", "distinct keys"),
    "graph.graphml.max-elements": ("250_000", "XML elements"),
    "graph.graphml.max-attributes": ("500_000", "XML attributes"),
    "graph.graphml.max-namespace-declarations": ("10_000", "namespace declarations"),
    "graph.payload.max-encoded-bytes": ("256 * 1024", "bytes"),
    "graph.payload.max-depth": ("32", "nesting levels"),
    "graph.payload.max-collection-size": ("1_000", "members per collection"),
    "graph.payload.max-value-count": ("10_000", "values"),
    "graph.payload.max-text-length": ("32 * 1024", "UTF-16 code units"),
    "graph.payload.max-key-length": ("256", "UTF-16 code units"),
    "graph.execution.max-fan-out": ("64", "targets"),
    "graph.execution.max-resident-actors": ("256", "actors"),
    "graph.execution.max-live-actors-per-traversal": ("256", "actors"),
    "graph.execution.max-in-flight-hops-per-traversal": ("1_024", "messages"),
    "graph.execution.max-queued-admissions-per-node": ("1_024", "messages"),
    "graph.execution.max-traversal-steps": ("100_000", "deliveries"),
    "graph.execution.max-amplified-deliveries": ("100_000", "deliveries"),
    "graph.execution.max-cumulative-payload-bytes": ("64L * 1024 * 1024", "bytes"),
    "graph.execution.max-recovery-deliveries-per-attempt": ("8", "delivery claims"),
}
GRAPH_NUMERIC_CONSTANT_EXPRESSIONS = {
    "GraphDefinitionStore.DEFAULT_MAX_DEFINITION_BYTES": "10 * 1024 * 1024",
    "GraphDefinitionStore.HARD_MAX_DEFINITION_BYTES": "256 * 1024 * 1024",
    "GraphMlLimits.HARD_MAX_NODES": "1_000_000",
    "GraphMlLimits.HARD_MAX_EDGES": "5_000_000",
    "GraphMlLimits.HARD_MAX_PROPERTIES": "10_000_000",
    "GraphMlLimits.HARD_MAX_DEPTH": "1_024",
    "GraphMlLimits.HARD_MAX_STRING_LENGTH": "64 * 1024 * 1024",
    "GraphMlLimits.HARD_MAX_KEYS": "100_000",
    "GraphMlLimits.HARD_MAX_ELEMENTS": "10_000_000",
    "GraphMlLimits.HARD_MAX_ATTRIBUTES": "20_000_000",
    "GraphMlLimits.HARD_MAX_NAMESPACE_DECLARATIONS": "1_000_000",
    "PayloadLimits.HARD_MAX_ENCODED_BYTES": "64 * 1024 * 1024",
    "PayloadLimits.HARD_MAX_DEPTH": "256",
    "PayloadLimits.HARD_MAX_COLLECTION_SIZE": "1_000_000",
    "PayloadLimits.HARD_MAX_VALUE_COUNT": "5_000_000",
    "PayloadLimits.HARD_MAX_TEXT_LENGTH": "64 * 1024 * 1024",
    "PayloadLimits.HARD_MAX_KEY_LENGTH": "4_096",
    "GraphExecutionLimits.HARD_MAX_FAN_OUT": "256",
    "GraphExecutionLimits.HARD_MAX_RESIDENT_ACTORS": "4_096",
    "GraphExecutionLimits.HARD_MAX_LIVE_ACTORS": "1_024",
    "GraphExecutionLimits.HARD_MAX_IN_FLIGHT_HOPS": "4_096",
    "GraphExecutionLimits.HARD_MAX_QUEUED_ADMISSIONS": "4_096",
    "GraphExecutionLimits.HARD_MAX_TRAVERSAL_STEPS": "1_000_000L",
    "GraphExecutionLimits.HARD_MAX_AMPLIFIED_DELIVERIES": "1_000_000L",
    "GraphExecutionLimits.HARD_MAX_CUMULATIVE_PAYLOAD_BYTES": "256L * 1024 * 1024",
    "GraphExecutionLimits.HARD_MAX_RECOVERY_DELIVERIES": "64",
}


@dataclass(frozen=True)
class Candidate:
    id: str
    path: str
    line: int
    symbol: str
    kind: str
    role: str
    expression: str
    expression_digest: str
    evidence: str
    evidence_digest: str
    surface: str
    fixture: bool = False

    def inventory_entry(self) -> dict[str, object]:
        if self.fixture:
            return {
                **self.source_fields(),
                "status": "retained",
                "classification": "test-fixture",
                "rationale": "The candidate is under an explicitly recognized test-fixture surface; it is not production runtime configuration.",
            }
        return {
            **self.source_fields(),
            "status": "pending-review",
            "classification": None,
        }

    def source_fields(self) -> dict[str, object]:
        return {
            "id": self.id,
            "path": self.path,
            "line": self.line,
            "symbol": self.symbol,
            "kind": self.kind,
            "role": self.role,
            "expression": self.expression,
            "expressionDigest": self.expression_digest,
            "evidenceDigest": self.evidence_digest,
            "surface": self.surface,
        }


def tracked_files(root: Path) -> tuple[Path, ...]:
    listing = subprocess.run(
        ["git", "ls-files", "-co", "--exclude-standard", "-z"],
        cwd=root,
        check=True,
        capture_output=True,
    ).stdout.decode("utf-8").split("\0")
    return tuple(sorted(Path(item) for item in listing if item and (root / item).is_file()))


def tracked_git_mode(root: Path, relative: Path) -> str | None:
    listing = subprocess.run(
        ["git", "ls-files", "-s", "--", relative.as_posix()], cwd=root,
        capture_output=True, text=True,
    )
    if listing.returncode != 0:
        return None
    match = re.fullmatch(r"([0-9]{6}) [0-9a-f]+ [0-9]+\t[^\n]+\n?", listing.stdout)
    return match.group(1) if match else None


def verification_script_inbound_errors(root: Path) -> list[str]:
    """Check the three reviewed live call edges and reject every other literal inbound edge."""
    errors: list[str] = []
    allowed = {(str(item["caller"]), str(item["callee"])): str(item["invocation"])
               for item in VERIFICATION_SCRIPT_CALLERS}
    seen: Counter[tuple[str, str]] = Counter()
    listing = subprocess.run(
        ["git", "ls-files", "-z"], cwd=root, check=True, capture_output=True,
    ).stdout.decode("utf-8").split("\0")
    ignored = {
        "scripts/audit_operational_configuration.py",
        "scripts/tests/test_audit_operational_configuration.py",
        "scripts/operational-configuration-inventory.json",
        "docs/architecture/operational-configuration-audit.md",
    }
    text_suffixes = SOURCE_SUFFIXES | {".xml"}
    for name in sorted(item for item in listing if item):
        relative = Path(name)
        if name in ignored or name.startswith("docs/") \
                or (relative.suffix not in text_suffixes
                    and relative.name not in {"Dockerfile", "Dockerfile.ci", "package.json"}):
            continue
        target = root / relative
        if not target.is_file() or target.is_symlink():
            continue
        source = target.read_text(encoding="utf-8", errors="strict")
        if relative.suffix in {".java", ".js", ".mjs", ".ts"}:
            executable = strip_c_comments(source)
        elif relative.suffix == ".py":
            try:
                tree = ast.parse(source)
                docstrings = {
                    (node.body[0].lineno, node.body[0].end_lineno)
                    for node in ast.walk(tree)
                    if isinstance(node, (ast.Module, ast.ClassDef, ast.FunctionDef,
                                         ast.AsyncFunctionDef))
                    and node.body and isinstance(node.body[0], ast.Expr)
                    and isinstance(node.body[0].value, ast.Constant)
                    and isinstance(node.body[0].value.value, str)
                }
                tokens = tokenize.generate_tokens(io.StringIO(source).readline)
                executable = " ".join(
                    token.string for token in tokens
                    if token.type != tokenize.COMMENT
                    and not (token.type == tokenize.STRING
                             and any(first <= token.start[0] <= last
                                     for first, last in docstrings))
                )
            except (SyntaxError, tokenize.TokenError):
                errors.append(f"unsupported Python caller syntax while scanning: {name}")
                continue
        else:
            executable = "\n".join(
                line for line in source.splitlines() if not line.lstrip().startswith("#"))
        for callee in VERIFICATION_SCRIPT_FIXTURES:
            basename = Path(callee).name
            if basename not in executable and callee not in executable:
                continue
            if relative.suffix in {".java", ".js", ".mjs", ".ts"} and not re.search(
                    rf"(?:new\s+ProcessBuilder|Runtime\.getRuntime\(\)\.exec|"
                    rf"\b(?:spawn|spawnSync|exec|execFile|fork)\s*)\([^;]*"
                    rf"{re.escape(basename)}", executable, re.DOTALL):
                # Literal guidance and assertion text are not process-launch edges.
                continue
            edge = (name, callee)
            invocation = allowed.get(edge)
            if invocation is None:
                # References inside the classified script itself describe its own usage and are
                # not inbound. Calls between listed scripts remain directional and must be listed.
                if name == callee:
                    continue
                errors.append(f"unreviewed executable caller of verification fixture: {name} -> {callee}")
                continue
            live_lines = [line.strip() for line in executable.splitlines()
                          if basename in line or callee in line]
            if live_lines.count(invocation) != 1 or len(live_lines) != 1:
                errors.append(f"reviewed verification-script invocation has drifted: {name} -> {callee}")
            else:
                seen[edge] += 1
    if seen != Counter({edge: 1 for edge in allowed}):
        errors.append("verification-script caller closure lost one of its three exact live edges")
    return errors


def surface(relative: Path) -> str | None:
    parts = relative.parts
    text = relative.as_posix()
    if any(part in EXCLUDED_PARTS for part in parts):
        return None
    if relative.name in {"package-lock.json", "npm-shrinkwrap.json", "pnpm-lock.yaml", "yarn.lock"}:
        return None
    if text in {
        "scripts/audit_operational_configuration.py",
        "scripts/operational-configuration-inventory.json",
    }:
        # The audit implementation and inventory describe the scan. Including either would make
        # checker maintenance create candidates about the checker rather than runtime policy.
        return None
    if relative.name in {"Dockerfile", "Dockerfile.ci"}:
        return "deployment"
    if text in VERIFICATION_SCRIPT_FIXTURES:
        return "test-fixture"
    if text == "compose.yaml" or text.startswith("deploy/"):
        return "deployment"
    if relative.suffix == ".sh":
        if text.startswith("scripts/tests/") or "/e2e/" in text:
            return "test-fixture"
        return "script"
    # Documented runnable configuration is a deployment surface, not ordinary prose.
    if text in {
        "docs/examples/assistant/compose.override.yaml",
        "docs/examples/plugins/compose.openapi-client.override.yaml",
    }:
        return "deployment-example"
    if text.startswith("ravenroot/ravenroot-ui/src/") or text.startswith("ravenroot/ravenroot-ui/public/"):
        return "ui"
    if text.startswith("scripts/"):
        if text.startswith("scripts/tests/") or text.startswith("scripts/fixtures/"):
            return "test-fixture"
        return "script"
    if "/src/test/" in text or "/e2e/" in text or "/test/" in text:
        return None
    if "/src/main/" in text:
        module = next((part for part in parts if part.startswith("ravenroot-") and part != "ravenroot"), "")
        return "test-fixture" if module in TESTKIT_MODULES else "java"
    return None


def strip_c_comments(text: str) -> str:
    """Remove // and /* */ comments while preserving strings and newlines."""
    out: list[str] = []
    index = 0
    state = "code"
    quote = ""
    while index < len(text):
        char = text[index]
        following = text[index + 1] if index + 1 < len(text) else ""
        if state == "line":
            if char == "\n":
                state = "code"
                out.append(char)
            else:
                out.append(" ")
        elif state == "block":
            if char == "*" and following == "/":
                out.extend((" ", " "))
                index += 1
                state = "code"
            else:
                out.append("\n" if char == "\n" else " ")
        elif state == "textblock":
            if text.startswith('"""', index):
                out.extend(('"', '"', '"'))
                index += 2
                state = "code"
            elif char == "\\" and following:
                out.extend((char, following))
                index += 1
            else:
                out.append(char)
        elif state == "string":
            out.append(char)
            if char == "\\" and following:
                out.append(following)
                index += 1
            elif char == quote:
                state = "code"
        elif char == "/" and following == "/":
            out.extend((" ", " "))
            index += 1
            state = "line"
        elif char == "/" and following == "*":
            out.extend((" ", " "))
            index += 1
            state = "block"
        elif text.startswith('"""', index):
            state = "textblock"
            out.extend(('"', '"', '"'))
            index += 2
        elif char in {'"', "'", "`"}:
            quote = char
            state = "string"
            out.append(char)
        else:
            out.append(char)
        index += 1
    return "".join(out)


def strip_c_comments_and_literals(text: str) -> str:
    """Mask comments and quoted literals while preserving offsets and newlines."""
    without_comments = strip_c_comments(text)
    out: list[str] = []
    index = 0
    quote = ""
    textblock = False
    while index < len(without_comments):
        char = without_comments[index]
        following = without_comments[index + 1] if index + 1 < len(without_comments) else ""
        if textblock:
            if without_comments.startswith('"""', index):
                out.extend((" ", " ", " "))
                index += 2
                textblock = False
            elif char == "\\" and following:
                out.extend((" ", " "))
                index += 1
            else:
                out.append("\n" if char == "\n" else " ")
        elif quote:
            if char == "\\" and following:
                out.extend((" ", " "))
                index += 1
            elif char == quote:
                out.append(" ")
                quote = ""
            else:
                out.append("\n" if char == "\n" else " ")
        elif without_comments.startswith('"""', index):
            textblock = True
            out.extend((" ", " ", " "))
            index += 2
        elif char in {'"', "'", "`"}:
            quote = char
            out.append(" ")
        else:
            out.append(char)
        index += 1
    return "".join(out)


def javascript_static_imports(source: str) -> tuple[str, ...] | None:
    """Read the leading static-import block accepted by the bounded UI proof."""
    code = strip_c_comments(source)
    imports: list[str] = []
    position = 0
    while True:
        while position < len(code) and code[position].isspace():
            position += 1
        if not re.match(r"import\b", code[position:]):
            return tuple(imports)
        terminator = code.find(";", position)
        if terminator < 0:
            return None
        statement = code[position:terminator + 1]
        if not re.fullmatch(
                r"import\s+(?:[A-Za-z_$][\w$]*\s+from\s+|\*\s+as\s+"
                r"[A-Za-z_$][\w$]*\s+from\s+|\{.*?\}\s+from\s+)"
                r"(?:\"(?:\\.|[^\"\\])*\"|'(?:\\.|[^'\\])*')\s*;",
                statement, re.DOTALL):
            return None
        imports.append(normalized(statement))
        position = terminator + 1


def java_type_span(source: str, symbol: str) -> tuple[int, int] | None:
    """Return one Java type declaration span, ignoring declaration-shaped text in literals/comments."""
    code = strip_c_comments_and_literals(source)
    declaration = re.search(
        rf"\b(?:class|record|interface|enum|@interface)\s+{re.escape(symbol)}\b", code,
    )
    if declaration is None:
        return None
    opening = code.find("{", declaration.end())
    if opening < 0:
        return None
    depth = 0
    for offset in range(opening, len(code)):
        if code[offset] == "{":
            depth += 1
        elif code[offset] == "}":
            depth -= 1
            if depth == 0:
                return declaration.start(), offset + 1
    return None


def java_exact_top_level_type_header(source: str, symbol: str, expected: str) -> bool:
    """Require one unannotated top-level type declaration with an exact supported header."""
    code = strip_c_comments_and_literals(source)
    depths = java_brace_depths(code)
    declarations = [match for match in re.finditer(
        rf"\b(?:class|record|interface|enum|@interface)\s+{re.escape(symbol)}\b", code)
        if depths[match.start()] == 0
    ]
    if len(declarations) != 1:
        return False
    declaration = declarations[0]
    start = code.rfind("\n", 0, declaration.start()) + 1
    opening = code.find("{", declaration.end())
    if opening < 0 or normalized(code[start:opening]) != normalized(expected):
        return False
    boundary = 0
    for offset, char in enumerate(code[:start]):
        if depths[offset] == 0 and char in ";}":
            boundary = offset + 1
    return not code[boundary:start].strip()


def java_type_declares_field(source: str, symbol: str, field: str) -> bool:
    """Check an exact record component or direct member declared by the named Java type."""
    span = java_type_span(source, symbol)
    if span is None:
        return False
    code = strip_c_comments_and_literals(source)[slice(*span)]
    opening = code.find("{")
    parts = field.split(".")
    if len(parts) != 1:
        return False
    identifier = parts[-1]

    type_match = re.search(
        rf"\b(?P<kind>class|record|interface|enum|@interface)\s+{re.escape(symbol)}\b", code[:opening],
    )
    if type_match is None:
        return False
    declared: set[str] = set()
    if type_match.group("kind") == "record":
        parenthesis = code.find("(", type_match.end(), opening)
        if parenthesis >= 0:
            depth = 0
            component_start = parenthesis + 1
            for offset in range(parenthesis + 1, opening):
                char = code[offset]
                if char in "(<[":
                    depth += 1
                elif char in ")>]":
                    if char == ")" and depth == 0:
                        component = code[component_start:offset]
                        names = re.findall(r"\b[A-Za-z_$][\w$]*\b", component)
                        if names:
                            declared.add(names[-1])
                        break
                    depth -= 1
                elif char == "," and depth == 0:
                    component = code[component_start:offset]
                    names = re.findall(r"\b[A-Za-z_$][\w$]*\b", component)
                    if names:
                        declared.add(names[-1])
                    component_start = offset + 1

    body = code[opening + 1:-1]
    depth = 1
    statement: list[str] = []
    for char in body:
        if char == "{":
            if depth == 1:
                statement.clear()
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 1:
                statement.clear()
        elif depth == 1:
            statement.append(char)
            if char == ";":
                unit = "".join(statement)
                statement.clear()
                declaration = re.match(
                    r"\s*(?:(?:public|protected|private|static|final|volatile|transient)\s+)*"
                    r"[A-Za-z_$][\w$<>,.?\[\] @]*\s+([A-Za-z_$][\w$]*)\s*(?:=|;|,)",
                    unit,
                )
                if declaration is not None:
                    declared.add(declaration.group(1))
    return identifier in declared


def normalized(value: str) -> str:
    return " ".join(value.split())


def line_number(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def symbol_markers(code: str, suffix: str) -> tuple[tuple[int, str], ...]:
    """Index declaration starts once; candidate lookup must stay linearithmic on large files."""
    markers: list[tuple[int, str]] = [(0, "module")]
    offset = 0
    for line in code.splitlines(keepends=True):
        match = None
        if suffix == ".java":
            match = re.search(r"\b(?:class|record|interface|enum)\s+([A-Za-z_$][\w$]*)", line)
            if match is None and "(" in line and line.rstrip().endswith("{"):
                match = re.search(r"([A-Za-z_$][\w$]*)\s*\([^;{}]*\)\s*(?:throws\s+[^{}]+)?\{\s*$", line)
        elif suffix in {".js", ".mjs", ".ts"}:
            match = re.search(r"\b(?:function\s+)?([A-Za-z_$][\w$]*)\s*\([^;{}]*\)\s*\{\s*$", line)
        elif suffix == ".py":
            match = re.match(r"\s*(?:async\s+)?def\s+([A-Za-z_][\w]*)\s*\(", line)
        if match is not None:
            markers.append((offset + match.start(), match.group(1)))
        offset += len(line)
    return tuple(markers)


def containing_symbol(markers: tuple[tuple[int, str], ...], offset: int) -> str:
    position = bisect_right(markers, (offset, chr(0x10FFFF))) - 1
    return markers[position][1]


def statement_spans(code: str) -> Iterable[tuple[int, int, str]]:
    """Yield semicolon or newline units without splitting inside quoted strings."""
    start = 0
    quote = ""
    escaped = False
    for index, char in enumerate(code):
        if quote:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = ""
            continue
        if char in {'"', "'", "`"}:
            quote = char
        elif char == ";":
            yield start, index + 1, code[start:index + 1]
            start = index + 1
    if start < len(code):
        for match in re.finditer(r"[^\n]+", code[start:]):
            yield start + match.start(), start + match.end(), match.group(0)


def code_candidates(relative: Path, text: str, surface_name: str) -> list[tuple[int, str, str, str, str, str]]:
    suffix = relative.suffix
    code = strip_c_comments(text)
    markers = symbol_markers(code, suffix)
    rows: list[tuple[int, str, str, str, str, str]] = []
    for start, _end, raw in statement_spans(code):
        evidence = normalized(raw)
        if not evidence:
            continue

        for binding in ENVIRONMENT_BINDING.finditer(raw):
            candidate_offset = start + binding.start()
            token = binding.group(0)
            rows.append((candidate_offset, containing_symbol(markers, candidate_offset),
                         "environment-binding", token, token, evidence))
        for binding in PROPERTY_BINDING.finditer(raw):
            candidate_offset = start + binding.start()
            token = binding.group(1)
            rows.append((candidate_offset, containing_symbol(markers, candidate_offset),
                         "property-binding", token, token, evidence))
        for binding in SYSTEM_PROPERTY_READ.finditer(raw):
            argument = normalized(binding.group(1))
            if PROPERTY_BINDING.fullmatch(argument):
                continue
            candidate_offset = start + binding.start(1)
            rows.append((candidate_offset, containing_symbol(markers, candidate_offset),
                         "property-binding", "System.getProperty", argument.strip('"'), evidence))

        kind: str | None = None
        label = "literal"
        assignment = JS_DECLARATION.search(evidence) if suffix in {".js", ".mjs", ".ts"} \
            else ASSIGNMENT_NAME.search(evidence)
        if STATIC_FINAL.search(evidence) or (suffix in {".js", ".mjs", ".ts"}
                                              and evidence.lstrip().startswith("const ")):
            kind = "fixed-declaration"
            if assignment:
                label = assignment.group(1)
        elif assignment and OPERATIONAL_WORD.search(assignment.group(1)):
            kind = "operational-declaration"
            label = assignment.group(1)
        elif KNOWN_OPERATIONAL_CALL.search(evidence):
            kind = "inline-operational-call"
            call = KNOWN_OPERATIONAL_CALL.search(evidence)
            assert call is not None
            label = normalized(call.group(1)).replace(" ", "-")
        if kind:
            for atom in FIXED_ATOM.finditer(raw):
                candidate_offset = start + atom.start()
                rows.append((candidate_offset, containing_symbol(markers, candidate_offset), kind,
                             label, normalized(atom.group(0)), evidence))
    if suffix == ".java":
        # Parse only the supported timeout argument. Feeding these method names into the broad
        # KNOWN_OPERATIONAL_CALL branch would incorrectly collect permit counts and unrelated
        # literals elsewhere in the containing statement.
        occupied_offsets = {row[0] for row in rows}
        masked = strip_c_comments_and_literals(text)
        for start, end, raw in statement_spans(code):
            evidence = normalized(raw)
            for call in TIME_UNIT_OPERATIONAL_CALL.finditer(masked, start, end):
                opening = masked.find("(", call.start(), end)
                if opening < 0:
                    continue
                parsed = split_java_arguments(text, masked, opening)
                if parsed is None:
                    continue
                arguments, closing = parsed
                if closing >= end:
                    continue
                method = call.group(1)
                if method == "tryAcquire":
                    if len(arguments) not in {2, 3}:
                        continue
                elif len(arguments) != 2:
                    continue
                unit = arguments[-1]
                unit_expression = re.sub(r"\s+", "", masked[unit[1]:unit[2]])
                if TIME_UNIT_ARGUMENT.fullmatch(unit_expression) is None:
                    continue
                timeout = arguments[-2]
                for atom in NUMBER.finditer(masked, timeout[1], timeout[2]):
                    candidate_offset = atom.start()
                    if candidate_offset in occupied_offsets:
                        continue
                    occupied_offsets.add(candidate_offset)
                    rows.append((candidate_offset, containing_symbol(markers, candidate_offset),
                                 "inline-operational-call", f"timeunit-{method}",
                                 normalized(text[atom.start():atom.end()]), evidence))
    return rows


def line_candidates(relative: Path, text: str, surface_name: str) -> list[tuple[int, str, str, str, str, str]]:
    rows: list[tuple[int, str, str, str, str, str]] = []
    suffix = relative.suffix
    markers = symbol_markers(text, suffix)
    offset = 0
    for index, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith(("#", "//")):
            offset += len(raw) + 1
            continue
        evidence = normalized(raw)
        for binding in ENVIRONMENT_BINDING.finditer(raw):
            candidate_offset = offset + binding.start()
            token = binding.group(0)
            rows.append((candidate_offset, containing_symbol(markers, candidate_offset),
                         "environment-binding", token, token, evidence))
        label: str | None = None
        kind: str | None = None
        if suffix in {".yaml", ".yml", ".json"}:
            match = YAML_SCALAR.match(raw)
            if match and FIXED.search(match.group(2)):
                label = match.group(1)
                kind = "configuration-scalar"
        elif suffix in {".py", ".sh"} or relative.name.startswith("Dockerfile"):
            docker = re.match(r"^\s*(USER|EXPOSE)\s+(.+)$", raw, re.IGNORECASE)
            match = PY_ASSIGNMENT.match(raw)
            shell = re.match(r"^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$", raw)
            name = match.group(1) if match else shell.group(1) if shell else None
            if docker and FIXED.search(docker.group(2)):
                label = docker.group(1).upper()
                kind = "container-directive"
            elif name and (name.isupper() or OPERATIONAL_WORD.search(name)) and FIXED.search(raw):
                label = name
                kind = "script-default"
            elif (re.search(r"\b(?:sleep|timeout)\s+[0-9]", raw)
                  or (OPERATIONAL_WORD.search(raw) and FIXED.search(raw))):
                label = "inline"
                kind = "inline-script-operational"
            elif ENVIRONMENT_BINDING.search(raw) and FIXED.search(raw):
                label = "binding-value"
                kind = "binding-default"
        if kind and label:
            for atom in FIXED_ATOM.finditer(raw):
                candidate_offset = offset + atom.start()
                rows.append((candidate_offset, containing_symbol(markers, candidate_offset), kind,
                             label, normalized(atom.group(0)), evidence))
        offset += len(raw) + 1
    return rows


def json_pointer(document: object, reference: str) -> object:
    """Resolve one same-document JSON Pointer, rejecting external and malformed references."""
    if not reference.startswith("#/"):
        raise ValueError("only same-document JSON Pointer references are supported")
    value = document
    for encoded in reference[2:].split("/"):
        token = encoded.replace("~1", "/").replace("~0", "~")
        if isinstance(value, dict) and token in value:
            value = value[token]
        elif isinstance(value, list) and token.isdigit() and int(token) < len(value):
            value = value[int(token)]
        else:
            raise ValueError("JSON Pointer target is absent")
    return value


def strict_json_document(text: str) -> object:
    """Parse JSON while rejecting duplicate object members."""
    def unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
        result: dict[str, object] = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"duplicate JSON member: {key}")
            result[key] = value
        return result

    try:
        return json.loads(text, object_pairs_hook=unique_object)
    except json.JSONDecodeError as invalid:
        raise ValueError("invalid JSON") from invalid


def resolved_json_schema_value(document: object, value: object,
                               references: tuple[str, ...] = ()) -> object:
    """Return a canonicalizable local-ref expansion and fail closed on cycles."""
    if isinstance(value, dict):
        if "$ref" in value:
            if set(value) != {"$ref"} or not isinstance(value["$ref"], str):
                raise ValueError("unsupported sibling or value next to $ref")
            reference = value["$ref"]
            if reference in references:
                raise ValueError("cyclic local JSON reference")
            target = json_pointer(document, reference)
            return {"$ref": reference,
                    "resolved": resolved_json_schema_value(document, target, references + (reference,))}
        return {key: resolved_json_schema_value(document, child, references)
                for key, child in sorted(value.items())}
    if isinstance(value, list):
        return [resolved_json_schema_value(document, child, references) for child in value]
    return value


def json_schema_reference_candidates(text: str) -> list[tuple[int, str, str, str, str, str]]:
    """Discover per-property local `$ref` edges with their resolved constraint evidence."""
    try:
        document = json.loads(text)
    except json.JSONDecodeError:
        return []
    rows: list[tuple[int, str, str, str, str, str]] = []
    cursor = 0

    def visit(value: object, pointer: str, required_property: bool = False) -> None:
        nonlocal cursor
        if isinstance(value, dict):
            reference = value.get("$ref")
            if isinstance(reference, str):
                offset = text.find('"$ref"', cursor)
                if offset < 0:
                    offset = 0
                else:
                    cursor = offset + len('"$ref"')
                try:
                    if set(value) != {"$ref"}:
                        raise ValueError("unsupported sibling next to $ref")
                    target = json_pointer(document, reference)
                    resolved = resolved_json_schema_value(document, target, (reference,))
                    resolution_error = None
                except ValueError as invalid:
                    resolved = None
                    resolution_error = str(invalid)
                reference_pointer = f"{pointer}/$ref"
                evidence = {
                    "pointer": reference_pointer,
                    "reference": reference,
                    "required": required_property,
                    "resolved": resolved,
                    "resolutionError": resolution_error,
                }
                rows.append((offset, pointer, "schema-reference-binding", reference_pointer,
                             reference, json.dumps(evidence, sort_keys=True, separators=(",", ":"))))
            for key, child in value.items():
                escaped = str(key).replace("~", "~0").replace("/", "~1")
                if key == "properties" and isinstance(child, dict):
                    required = set(value.get("required", [])) if isinstance(value.get("required"), list) else set()
                    for property_name, property_schema in child.items():
                        property_token = str(property_name).replace("~", "~0").replace("/", "~1")
                        visit(property_schema, f"{pointer}/{escaped}/{property_token}",
                              property_name in required)
                else:
                    visit(child, f"{pointer}/{escaped}", required_property)
        elif isinstance(value, list):
            for index, child in enumerate(value):
                visit(child, f"{pointer}/{index}", required_property)

    visit(document, "")
    return rows


def discover_source_texts(sources: dict[Path, str]) -> tuple[Candidate, ...]:
    """Run the production candidate scanner over explicit in-memory source texts."""
    provisional: list[tuple[str, int, str, str, str, str, str, str, bool]] = []
    for relative, text in sorted(sources.items()):
        surface_name = surface(relative)
        if surface_name is None or (relative.suffix not in SOURCE_SUFFIXES
                                    and not relative.name.startswith("Dockerfile")):
            continue
        if relative.suffix in {".java", ".js", ".mjs", ".ts"}:
            found = code_candidates(relative, text, surface_name)
        else:
            found = line_candidates(relative, text, surface_name)
            if relative.suffix == ".json":
                found.extend(json_schema_reference_candidates(text))
        for offset, symbol_name, kind, role, expression, evidence in found:
            provisional.append((relative.as_posix(), line_number(text, offset), symbol_name,
                                kind, role, expression, evidence, surface_name,
                                surface_name == "test-fixture"))

    occurrences: Counter[tuple[str, str, str, str, str]] = Counter()
    candidates: list[Candidate] = []
    for path, line, symbol_name, kind, role, expression, evidence, surface_name, fixture in sorted(provisional):
        key = (path, symbol_name, kind, role, expression)
        occurrence = occurrences[key]
        occurrences[key] += 1
        evidence_digest = hashlib.sha256(evidence.encode("utf-8")).hexdigest()
        material = "\0".join((path, symbol_name, kind, role, expression, evidence_digest,
                               str(occurrence)))
        digest = hashlib.sha256(expression.encode("utf-8")).hexdigest()
        identifier = "oc-" + hashlib.sha256(material.encode("utf-8")).hexdigest()[:20]
        candidates.append(Candidate(identifier, path, line, symbol_name, kind, role, expression, digest,
                                    evidence, evidence_digest, surface_name, fixture))
    return tuple(candidates)


def discover_paths(root: Path, relative_paths: Iterable[Path]) -> tuple[Candidate, ...]:
    """Run the production candidate scanner over an explicit bounded path set."""
    sources: dict[Path, str] = {}
    for relative in sorted(set(relative_paths)):
        surface_name = surface(relative)
        if surface_name is None or (relative.suffix not in SOURCE_SUFFIXES
                                    and not relative.name.startswith("Dockerfile")):
            continue
        sources[relative] = (root / relative).read_text(encoding="utf-8", errors="strict")
    return discover_source_texts(sources)


def discover(root: Path) -> tuple[Candidate, ...]:
    return discover_paths(root, tracked_files(root))


def load_inventory(path: Path = INVENTORY, *, allow_previous_schema: bool = False) -> dict[str, object]:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        raise ValueError(f"missing inventory: {path.relative_to(ROOT)}") from None
    accepted_versions = {SCHEMA_VERSION, SCHEMA_VERSION - 1} if allow_previous_schema else {SCHEMA_VERSION}
    if document.get("schemaVersion") not in accepted_versions:
        raise ValueError(f"inventory schemaVersion must be {SCHEMA_VERSION}")
    entries = document.get("entries")
    if not isinstance(entries, list):
        raise ValueError("inventory entries must be an array")
    retired = document.get("retiredEntries", [])
    if not isinstance(retired, list):
        raise ValueError("inventory retiredEntries must be an array")
    if document.get("schemaVersion") == SCHEMA_VERSION and not isinstance(document.get("evidenceRecords"), dict):
        raise ValueError("inventory evidenceRecords must be an object")
    if not isinstance(document.get("migrationHistory", []), list):
        raise ValueError("inventory migrationHistory must be an array")
    return document


@lru_cache(maxsize=None)
def current_source_owner(root: Path, owner: str) -> tuple[Path, str] | None:
    """Resolve a tracked in-repository ``path#symbol`` authority without following escapes."""
    if "#" not in owner:
        return None
    owner_path, owner_symbol = owner.rsplit("#", 1)
    relative = Path(owner_path)
    if not owner_symbol or relative.is_absolute() or ".." in relative.parts:
        return None
    root_resolved = root.resolve()
    authority = (root / relative).resolve()
    try:
        authority.relative_to(root_resolved)
    except ValueError:
        return None
    tracked = subprocess.run(["git", "ls-files", "--error-unmatch", "--", relative.as_posix()], cwd=root,
                             capture_output=True)
    if tracked.returncode != 0 or not authority.is_file():
        return None
    source = authority.read_text(encoding="utf-8")
    suffix = relative.suffix
    symbol = re.escape(owner_symbol)
    if suffix == ".java":
        return (relative, owner_symbol) if java_type_span(source, owner_symbol) is not None else None
    declarations = {
        ".py": (rf"(?m)^\s*(?:class|def|async\s+def)\s+{symbol}\b",),
        ".js": (rf"\b(?:class|function)\s+{symbol}\b", rf"\b(?:const|let|var)\s+{symbol}\s*="),
        ".mjs": (rf"\b(?:class|function)\s+{symbol}\b", rf"\b(?:const|let|var)\s+{symbol}\s*="),
        ".ts": (rf"\b(?:class|interface|type|enum|function)\s+{symbol}\b",
                rf"\b(?:const|let|var)\s+{symbol}\s*="),
        ".sh": (rf"(?m)^\s*(?:function\s+)?{symbol}\s*(?:\(\s*\))?\s*\{{",
                rf"(?m)^\s*{symbol}="),
        ".yaml": (rf"(?m)^\s*{symbol}\s*:",),
        ".yml": (rf"(?m)^\s*{symbol}\s*:",),
    }.get(suffix, ())
    if not declarations or not any(re.search(pattern, source) for pattern in declarations):
        return None
    return relative, owner_symbol


def current_source_field(root: Path, owner: str, field: str) -> bool:
    """Verify a Java operator field is declared by its typed current-source owner."""
    resolved = current_source_owner(root, owner)
    if resolved is None:
        return False
    relative, owner_symbol = resolved
    if relative.suffix != ".java":
        return False
    return java_type_declares_field((root / relative).read_text(encoding="utf-8"), owner_symbol, field)


def java_record_components(source: str, symbol: str) -> tuple[str, ...]:
    span = java_type_span(source, symbol)
    if span is None:
        return ()
    code = strip_c_comments_and_literals(source)[slice(*span)]
    declaration = re.search(rf"\brecord\s+{re.escape(symbol)}\b", code)
    opening_brace = code.find("{")
    if declaration is None or opening_brace < 0:
        return ()
    opening = code.find("(", declaration.end(), opening_brace)
    if opening < 0:
        return ()
    components: list[str] = []
    start = opening + 1
    depth = 0
    for offset in range(start, opening_brace):
        char = code[offset]
        if char in "(<[":
            depth += 1
        elif char in ")>]":
            if char == ")" and depth == 0:
                names = re.findall(r"\b[A-Za-z_$][\w$]*\b", code[start:offset])
                if names:
                    components.append(names[-1])
                return tuple(components)
            depth -= 1
        elif char == "," and depth == 0:
            names = re.findall(r"\b[A-Za-z_$][\w$]*\b", code[start:offset])
            if names:
                components.append(names[-1])
            start = offset + 1
    return ()


def java_record_default_expression_span(source: str, symbol: str, instance_symbol: str,
                                        field: str) -> tuple[str, int, int] | None:
    """Read one positional component expression and span from a unique direct record default."""
    components = java_record_components(source, symbol)
    if field not in components:
        return None
    span = java_type_span(source, symbol)
    assert span is not None
    base, limit = span
    actual = source[base:limit]
    code = strip_c_comments_and_literals(source)[base:limit]
    initializers = list(re.finditer(
        rf"\b{re.escape(instance_symbol)}\b\s*=\s*new\s+{re.escape(symbol)}\s*\(", code,
    ))
    if len(initializers) != 1:
        return None
    opening = code.find("(", initializers[0].start())
    parsed = split_java_arguments(actual, code, opening)
    if parsed is None or len(parsed[0]) != len(components):
        return None
    expression, start, end = parsed[0][components.index(field)]
    return expression, base + start, base + end


def java_record_default_expression(source: str, symbol: str, instance_symbol: str,
                                   field: str) -> str | None:
    result = java_record_default_expression_span(source, symbol, instance_symbol, field)
    return result[0] if result is not None else None


def java_static_final_initializer(source: str, symbol: str,
                                  field: str) -> tuple[str, int, int] | None:
    """Return one direct static-final field initializer in a named Java type."""
    span = java_type_span(source, symbol)
    if span is None:
        return None
    base, limit = span
    actual = source[base:limit]
    code = strip_c_comments_and_literals(source)[base:limit]
    depths = java_brace_depths(code)
    matches: list[tuple[str, int, int]] = []
    for name in re.finditer(rf"\b{re.escape(field)}\b\s*=", code):
        if depths[name.start()] != 1:
            continue
        statement_start = max(code.rfind(";", 0, name.start()), code.rfind("{", 0, name.start())) + 1
        prefix = code[statement_start:name.start()]
        if re.search(r"\bstatic\s+final\b|\bfinal\s+static\b", prefix) is None:
            continue
        equals = code.find("=", name.start(), name.end())
        end = equals + 1
        round_depth = square_depth = brace_depth = 0
        while end < len(code):
            char = code[end]
            if char == "(": round_depth += 1
            elif char == ")": round_depth -= 1
            elif char == "[": square_depth += 1
            elif char == "]": square_depth -= 1
            elif char == "{": brace_depth += 1
            elif char == "}": brace_depth -= 1
            elif char == ";" and round_depth == square_depth == brace_depth == 0:
                start = equals + 1
                matches.append((normalized(actual[start:end]), base + start, base + end))
                break
            end += 1
    return matches[0] if len(matches) == 1 else None


def candidate_ids_in_source_span(relative: Path, source: str, start: int, end: int,
                                 kind: str, role: str,
                                 discovered: dict[str, Candidate]) -> list[str]:
    """Reconcile lexical offsets with stable candidate occurrences without storing offsets publicly."""
    grouped: dict[tuple[str, str, str, str, str], list[str]] = {}
    for candidate in discovered.values():
        if candidate.path != relative.as_posix():
            continue
        key = (candidate.symbol, candidate.kind, candidate.role, candidate.expression,
               candidate.evidence_digest)
        grouped.setdefault(key, []).append(candidate.id)
    occurrences: Counter[tuple[str, str, str, str, str]] = Counter()
    selected: list[str] = []
    for offset, symbol_name, candidate_kind, candidate_role, expression, evidence in code_candidates(
            relative, source, surface(relative) or "java"):
        evidence_digest = hashlib.sha256(evidence.encode("utf-8")).hexdigest()
        key = (symbol_name, candidate_kind, candidate_role, expression, evidence_digest)
        occurrence = occurrences[key]
        occurrences[key] += 1
        ids = grouped.get(key, [])
        if start <= offset < end and candidate_kind == kind and candidate_role == role \
                and occurrence < len(ids):
            selected.append(ids[occurrence])
    return selected


def java_package(source: str) -> str:
    code = strip_c_comments_and_literals(source)
    match = re.search(r"\bpackage\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;", code)
    return match.group(1) if match is not None else ""


def java_constant_reference_matches(source: str, source_owner: str, expression: str,
                                    target_owner: str, target_field: str,
                                    target_source: str) -> bool:
    """Match one bounded unqualified, imported/simple, or fully qualified Java field reference."""
    normalized_expression = normalized(expression)
    source_path, source_type = source_owner.rsplit("#", 1)
    target_path, target_type = target_owner.rsplit("#", 1)
    if normalized_expression == target_field:
        return source_path == target_path and source_type == target_type
    if normalized_expression == f"{target_type}.{target_field}":
        target_package = java_package(target_source)
        return java_package(source) == target_package or re.search(
            rf"\bimport\s+{re.escape(target_package + '.' + target_type)}\s*;",
            strip_c_comments_and_literals(source),
        ) is not None
    target_package = java_package(target_source)
    return bool(target_package) and normalized_expression == \
        f"{target_package}.{target_type}.{target_field}"

def matching_delimiter(code: str, opening: int, left: str, right: str) -> int | None:
    depth = 0
    for offset in range(opening, len(code)):
        if code[offset] == left:
            depth += 1
        elif code[offset] == right:
            depth -= 1
            if depth == 0:
                return offset
    return None


def java_brace_depths(code: str) -> list[int]:
    """Return the brace depth immediately before each character in masked Java source."""
    depths: list[int] = []
    depth = 0
    for char in code:
        depths.append(depth)
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
    return depths


def java_method_span(source: str, type_symbol: str, method: str) -> tuple[int, int] | None:
    """Resolve exactly one direct Java method body in a named type."""
    type_span = java_type_span(source, type_symbol)
    if type_span is None:
        return None
    base, limit = type_span
    code = strip_c_comments_and_literals(source)[base:limit]
    depths = java_brace_depths(code)
    matches: list[tuple[int, int]] = []
    for name in re.finditer(rf"\b{re.escape(method)}\s*\(", code):
        if depths[name.start()] != 1 or (name.start() and code[name.start() - 1] == "."):
            continue
        opening = code.find("(", name.start())
        closing = matching_delimiter(code, opening, "(", ")")
        if closing is None:
            continue
        suffix = re.match(r"\s*(?:throws\s+[^{};]+)?\s*\{", code[closing + 1:])
        if suffix is None:
            continue
        opening_brace = closing + 1 + suffix.end() - 1
        closing_brace = matching_delimiter(code, opening_brace, "{", "}")
        if closing_brace is not None:
            matches.append((base + name.start(), base + closing_brace + 1))
    return matches[0] if len(matches) == 1 else None


def split_java_arguments(actual: str, code: str, opening: int) -> tuple[list[tuple[str, int, int]], int] | None:
    closing = matching_delimiter(code, opening, "(", ")")
    if closing is None:
        return None
    arguments: list[tuple[str, int, int]] = []
    start = opening + 1
    round_depth = square_depth = brace_depth = 0
    for offset in range(start, closing):
        char = code[offset]
        if char == "(": round_depth += 1
        elif char == ")": round_depth -= 1
        elif char == "[": square_depth += 1
        elif char == "]": square_depth -= 1
        elif char == "{": brace_depth += 1
        elif char == "}": brace_depth -= 1
        elif char == "," and round_depth == square_depth == brace_depth == 0:
            arguments.append((normalized(actual[start:offset]), start, offset))
            start = offset + 1
    arguments.append((normalized(actual[start:closing]), start, closing))
    return arguments, closing


def java_constructor_component_call(source: str, type_symbol: str, method: str,
                                    constructor_type: str, components: tuple[str, ...],
                                    component: str) -> tuple[str, int, int] | None:
    """Return the exact direct constructor argument occupying one record-component position."""
    method_span = java_method_span(source, type_symbol, method)
    if method_span is None or component not in components:
        return None
    base, limit = method_span
    actual = source[base:limit]
    code = strip_c_comments_and_literals(source)[base:limit]
    constructor = re.compile(rf"\bnew\s+{re.escape(constructor_type)}\s*\(")
    found: list[tuple[list[tuple[str, int, int]], int, int]] = []
    for match in constructor.finditer(code):
        opening = code.find("(", match.start())
        parsed = split_java_arguments(actual, code, opening)
        if parsed is not None and len(parsed[0]) == len(components):
            found.append((parsed[0], opening, parsed[1]))
    if len(found) != 1:
        return None
    arguments, opening, closing = found[0]
    argument, start, end = arguments[components.index(component)]
    return argument, base + start, base + end


def java_method_digest(source: str, type_symbol: str, method: str) -> str | None:
    span = java_method_span(source, type_symbol, method)
    if span is None:
        return None
    body = normalized(strip_c_comments(source[slice(*span)]))
    return hashlib.sha256(body.encode("utf-8")).hexdigest()


def java_method_header(source: str, type_symbol: str, method: str) -> str | None:
    """Return the normalized declaration header for one supported direct method."""
    span = java_method_span(source, type_symbol, method)
    if span is None:
        return None
    start = source.rfind("\n", 0, span[0]) + 1
    code = strip_c_comments_and_literals(source)
    opening = code.find("{", span[0], span[1])
    if opening < 0:
        return None
    return normalized(source[start:opening])


def java_method_annotations(source: str, type_symbol: str, method: str) -> tuple[str, ...] | None:
    """Return the contiguous, one-line annotations on one supported direct Java method."""
    span = java_method_span(source, type_symbol, method)
    if span is None:
        return None
    declaration_line = source.rfind("\n", 0, span[0]) + 1
    preceding = source[:declaration_line].splitlines()
    annotations: list[str] = []
    while preceding:
        line = preceding.pop().strip()
        if not line.startswith("@"):
            break
        annotations.append(normalized(line))
    annotations.reverse()
    return tuple(annotations)


def java_test_type_is_directly_runnable(source: str, type_symbol: str) -> bool:
    """Accept one unannotated top-level test class with no abstract/inherited execution shape."""
    code = strip_c_comments_and_literals(source)
    depths = java_brace_depths(code)
    declarations = [
        match for match in re.finditer(rf"\bclass\s+{re.escape(type_symbol)}\b", code)
        if depths[match.start()] == 0
    ]
    if len(declarations) != 1:
        return False
    declaration = declarations[0]
    opening = code.find("{", declaration.end())
    if opening < 0 or re.fullmatch(r"\s*\{", code[declaration.end():opening + 1]) is None:
        return False
    boundary = 0
    for offset, char in enumerate(code[:declaration.start()]):
        if depths[offset] == 0 and char in ";}":
            boundary = offset + 1
    prefix = code[boundary:declaration.start()]
    return re.fullmatch(r"\s*(?:final\s+)?", prefix) is not None


RATE_TEST_IMPORTS = {
    "Test": "org.junit.jupiter.api.Test",
    "ParameterizedTest": "org.junit.jupiter.params.ParameterizedTest",
    "MethodSource": "org.junit.jupiter.params.provider.MethodSource",
    "Stream": "java.util.stream.Stream",
}


def java_has_exact_rate_test_imports(source: str, type_symbol: str) -> bool:
    """Bind the supported short annotation/factory names to their exact library types."""
    code = strip_c_comments_and_literals(source)
    if re.search(
            r"(?m)^\s*import\s+static\s+[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*"
            r"\.(?:Stream|\*)\s*;", code,
    ) is not None:
        return False
    imports = re.findall(
        r"(?m)^\s*import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)\s*;", code,
    )
    for simple, qualified in RATE_TEST_IMPORTS.items():
        matching = [imported for imported in imports if imported.rsplit(".", 1)[-1] == simple]
        if matching != [qualified]:
            return False
        if re.search(
                rf"\b(?:class|record|enum|interface)\s+{re.escape(simple)}\b"
                rf"|@interface\s+{re.escape(simple)}\b", code,
        ) is not None:
            return False
    if java_type_declares_field(source, type_symbol, "Stream"):
        return False
    return True


def java_direct_stream_string_return(source: str, type_symbol: str,
                                     method: str) -> tuple[str, ...] | None:
    """Parse one direct `return Stream.of("...")` body with quoted literals only."""
    span = java_method_span(source, type_symbol, method)
    if span is None:
        return None
    actual = strip_c_comments(source[slice(*span)])
    code = strip_c_comments_and_literals(source[slice(*span)])
    body = code.find("{")
    if body < 0:
        return None
    direct = re.match(r"\s*return\s+Stream\s*\.\s*of\s*\(", code[body + 1:])
    if direct is None:
        return None
    opening = body + 1 + direct.end() - 1
    parsed = split_java_arguments(actual, code, opening)
    if parsed is None:
        return None
    arguments, closing = parsed
    if re.fullmatch(r"\s*;\s*}", code[closing + 1:]) is None:
        return None
    values: list[str] = []
    for argument, _start, _end in arguments:
        literal = re.fullmatch(r'"(RAVENROOT_[A-Z0-9_]+)"', argument)
        if literal is None:
            return None
        values.append(literal.group(1))
    return tuple(values)


def java_compact_constructor_span(source: str, type_symbol: str) -> tuple[int, int] | None:
    """Resolve one direct compact record constructor, excluding methods and nested types."""
    type_span = java_type_span(source, type_symbol)
    if type_span is None:
        return None
    base, limit = type_span
    code = strip_c_comments_and_literals(source)[base:limit]
    depths = java_brace_depths(code)
    matches: list[tuple[int, int]] = []
    for name in re.finditer(rf"\b{re.escape(type_symbol)}\s*\{{", code):
        if depths[name.start()] != 1:
            continue
        opening = code.find("{", name.start())
        closing = matching_delimiter(code, opening, "{", "}")
        if closing is not None:
            matches.append((base + name.start(), base + closing + 1))
    return matches[0] if len(matches) == 1 else None


def java_span_digest(source: str, span: tuple[int, int] | None) -> str | None:
    if span is None:
        return None
    body = normalized(strip_c_comments(source[slice(*span)]))
    return hashlib.sha256(body.encode("utf-8")).hexdigest()


def java_reachable_helpers_from_span(source: str, type_symbol: str,
                                    span: tuple[int, int] | None) -> set[str] | None:
    """Close same-type calls beginning in a direct constructor or initializer span."""
    if span is None:
        return None
    declared = java_declared_method_names(source, type_symbol)
    code = strip_c_comments_and_literals(source)[slice(*span)]
    roots = {name for name in declared if re.search(rf"\b{re.escape(name)}\s*\(", code)}
    reachable = set(roots)
    pending = list(roots)
    while pending:
        method = pending.pop()
        calls = java_method_calls(source, type_symbol, method, declared)
        if calls is None:
            return None
        for called in calls - reachable:
            reachable.add(called)
            pending.append(called)
    return reachable


JAVA_DECIMAL_INTEGER = re.compile(r"(?:0|[1-9](?:_?[0-9])*)")
JAVA_INT_MAX = (1 << 31) - 1
JAVA_LONG_MAX = (1 << 63) - 1


def java_positive_decimal(value: str, maximum: int) -> int | None:
    if JAVA_DECIMAL_INTEGER.fullmatch(value) is None:
        return None
    parsed = int(value.replace("_", ""))
    return parsed if 0 < parsed <= maximum else None


def evaluated_java_default(expression: str, component_is_duration: bool) -> dict[str, object] | None:
    """Evaluate the closed positive int/default-duration forms used by rate configuration."""
    expression = normalized(expression)
    if component_is_duration:
        matched = re.fullmatch(
            r"Duration\.of(Seconds|Minutes|Hours|Days)\(((?:0|[1-9](?:_?[0-9])*))\)",
            expression,
        )
        if matched is None:
            return None
        value = java_positive_decimal(matched.group(2), JAVA_LONG_MAX)
        multipliers = {"Seconds": 1, "Minutes": 60, "Hours": 3600, "Days": 86400}
        if value is None or value > JAVA_LONG_MAX // multipliers[matched.group(1)]:
            return None
        seconds = value * multipliers[matched.group(1)]
        if seconds > JAVA_INT_MAX:
            return None
        return {"kind": "duration-seconds", "value": seconds}
    direct = java_positive_decimal(expression, JAVA_INT_MAX)
    if direct is not None:
        return {"kind": "integer", "value": direct}
    multiplied = re.fullmatch(
        r"((?:0|[1-9](?:_?[0-9])*))\s*\*\s*((?:0|[1-9](?:_?[0-9])*))",
        expression,
    )
    if multiplied is None:
        return None
    left = java_positive_decimal(multiplied.group(1), JAVA_INT_MAX)
    right = java_positive_decimal(multiplied.group(2), JAVA_INT_MAX)
    if left is None or right is None or left > JAVA_INT_MAX // right:
        return None
    return {"kind": "integer", "value": left * right}


def java_declared_method_names(source: str, type_symbol: str) -> set[str]:
    """Return unambiguous method names declared directly by one Java type."""
    type_span = java_type_span(source, type_symbol)
    if type_span is None:
        return set()
    base, limit = type_span
    code = strip_c_comments_and_literals(source)[base:limit]
    depths = java_brace_depths(code)
    names: Counter[str] = Counter()
    for match in re.finditer(r"\b([A-Za-z_$][\w$]*)\s*\(", code):
        if depths[match.start()] != 1:
            continue
        name = match.group(1)
        if name in {"if", "for", "while", "switch", "catch", "synchronized", "try", "do"}:
            continue
        opening = code.find("(", match.start())
        closing = matching_delimiter(code, opening, "(", ")")
        if closing is None:
            continue
        suffix = re.match(r"\s*(?:throws\s+[^{};]+)?\s*\{", code[closing + 1:])
        if suffix is not None:
            names[name] += 1
    return {name for name, count in names.items() if count == 1}


def java_direct_method_declaration_count(source: str, type_symbol: str, method: str) -> int:
    """Count every supported direct declaration, including ambiguous overloads."""
    type_span = java_type_span(source, type_symbol)
    if type_span is None:
        return 0
    base, limit = type_span
    code = strip_c_comments_and_literals(source)[base:limit]
    depths = java_brace_depths(code)
    count = 0
    for match in re.finditer(rf"\b{re.escape(method)}\s*\(", code):
        if depths[match.start()] != 1:
            continue
        opening = code.find("(", match.start())
        closing = matching_delimiter(code, opening, "(", ")")
        if closing is None:
            continue
        suffix = re.match(r"\s*(?:throws\s+[^{};]+)?\s*\{", code[closing + 1:])
        if suffix is not None:
            count += 1
    return count


def java_method_calls(source: str, type_symbol: str, method: str,
                      declared_methods: set[str]) -> set[str] | None:
    """Find same-type helper names called from one supported, unambiguous method body."""
    span = java_method_span(source, type_symbol, method)
    if span is None:
        return None
    code = strip_c_comments_and_literals(source)[slice(*span)]
    opening = code.find("{")
    if opening < 0:
        return None
    body = code[opening + 1:-1]
    return {name for name in declared_methods
            if re.search(rf"\b{re.escape(name)}\s*\(", body)}


def java_reachable_helper_methods(source: str, type_symbol: str,
                                  roots: tuple[str, ...]) -> set[str] | None:
    """Close direct same-type calls from resolver roots; overloads fail closed."""
    declared = java_declared_method_names(source, type_symbol)
    if any(root not in declared for root in roots):
        return None
    reachable = set(roots)
    pending = list(roots)
    while pending:
        method = pending.pop()
        calls = java_method_calls(source, type_symbol, method, declared)
        if calls is None:
            return None
        for called in calls - reachable:
            reachable.add(called)
            pending.append(called)
    return reachable - set(roots)


@lru_cache(maxsize=None)
def committed_source(root: Path, revision: str, path: str) -> str | None:
    if not historical_source_locator_is_safe(revision, path):
        return None
    relative = Path(path)
    result = subprocess.run(["git", "show", f"{revision}:{relative.as_posix()}"], cwd=root,
                            capture_output=True, text=True)
    return result.stdout if result.returncode == 0 else None


def historical_source_locator_is_safe(revision: object, path: object) -> bool:
    """Accept only a full lowercase commit id and one normalized repository-relative path."""
    if not isinstance(revision, str) or re.fullmatch(r"[0-9a-f]{40}", revision) is None \
            or not isinstance(path, str) or not path or "\0" in path or "\\" in path:
        return False
    relative = Path(path)
    return not relative.is_absolute() and ".." not in relative.parts \
        and "." not in relative.parts and relative.as_posix() == path


@lru_cache(maxsize=None)
def commit_exists(root: Path, revision: str) -> bool:
    if re.fullmatch(r"[0-9a-f]{40}", revision) is None:
        return False
    return subprocess.run(["git", "cat-file", "-e", f"{revision}^{{commit}}"], cwd=root,
                          capture_output=True).returncode == 0


@lru_cache(maxsize=None)
def revision_is_ancestor(root: Path, before: str, after: str) -> bool:
    if re.fullmatch(r"[0-9a-f]{40}", before) is None \
            or re.fullmatch(r"[0-9a-f]{40}", after) is None:
        return False
    return subprocess.run(["git", "merge-base", "--is-ancestor", before, after], cwd=root,
                          capture_output=True).returncode == 0


def revision_transition_errors(root: Path, identifier: str, provenance: dict[str, object],
                               *, path: str, symbol: str, label: str) -> tuple[list[str], str | None, str | None]:
    errors: list[str] = []
    before = str(provenance["beforeRevision"])
    after = str(provenance["afterRevision"])
    if before == after:
        errors.append(f"{identifier}: {label} revisions must be distinct")
    for revision_field, revision in (("beforeRevision", before), ("afterRevision", after)):
        if not commit_exists(root, revision):
            errors.append(f"{identifier}: {label} {revision_field} is not a local commit")
    if not errors and not revision_is_ancestor(root, before, after):
        errors.append(f"{identifier}: {label} beforeRevision is not an ancestor of afterRevision")
    before_source = committed_source(root, before, path)
    after_source = committed_source(root, after, path)
    if before_source is None or not re.search(rf"\b{re.escape(symbol)}\b", before_source):
        errors.append(f"{identifier}: {label} path/symbol is absent from beforeRevision")
    if after_source is None or not re.search(rf"\b{re.escape(symbol)}\b", after_source):
        errors.append(f"{identifier}: {label} path/symbol is absent from afterRevision")
    if before_source is not None and after_source is not None and before_source == after_source:
        errors.append(f"{identifier}: {label} source is unchanged between revisions")
    return errors, before_source, after_source


def yaml_scalar_rows(source: str) -> list[tuple[int, str, str]]:
    """Return line, path, and value for mapping-only YAML scalars used by deployment values."""
    stack: list[tuple[int, str]] = []
    rows: list[tuple[int, str, str]] = []
    for line, raw in enumerate(source.splitlines(), start=1):
        if not raw.strip() or raw.lstrip().startswith(("#", "-")):
            continue
        match = re.match(r'^([ ]*)([A-Za-z_][A-Za-z0-9_.-]*)\s*:\s*(.*?)\s*$', raw)
        if match is None:
            continue
        indent = len(match.group(1))
        while stack and stack[-1][0] >= indent:
            stack.pop()
        key = match.group(2)
        value = match.group(3)
        path = tuple(item[1] for item in stack) + (key,)
        if value:
            rows.append((line, ".".join(path), normalized(value)))
        else:
            stack.append((indent, key))
    return rows


def yaml_scalar_at_path(source: str, dotted_path: str) -> str | None:
    """Return one scalar at an exact mapping-only YAML path used by deployment values."""
    found = [value for _line, path, value in yaml_scalar_rows(source) if path == dotted_path]
    return found[0] if len(found) == 1 else None


def yaml_default_removal_errors(root: Path, identifier: str, entry: dict[str, object],
                                removal: dict[str, object],
                                active_entries: dict[str, dict[str, object]]) -> list[str]:
    """Verify removal of one exact YAML default in favor of a typed Java authority."""
    required = ("yamlPath", "beforeValue", "afterValue", "replacementOwner", "replacementField",
                "replacementInstanceSymbol", "replacementDefaultExpression")
    if any(not isinstance(removal.get(field), str) or not str(removal[field]).strip()
           for field in required):
        return [f"{identifier}: YAML default removal requires {', '.join(required)}"]
    errors: list[str] = []
    before = str(removal.get("beforeRevision", ""))
    after = str(removal.get("afterRevision", ""))
    path = str(entry.get("path", ""))
    if before == after:
        errors.append(f"{identifier}: removal revisions must be distinct")
    for field, revision in (("beforeRevision", before), ("afterRevision", after)):
        if not commit_exists(root, revision):
            errors.append(f"{identifier}: removal {field} is not a local commit")
    if not errors and not revision_is_ancestor(root, before, after):
        errors.append(f"{identifier}: removal beforeRevision is not an ancestor of afterRevision")
    before_source = committed_source(root, before, path)
    after_source = committed_source(root, after, path)
    if before_source is None or after_source is None:
        errors.append(f"{identifier}: removal YAML path is absent from a revision")
        return errors
    yaml_path = str(removal["yamlPath"])
    before_value = yaml_scalar_at_path(before_source, yaml_path)
    after_value = yaml_scalar_at_path(after_source, yaml_path)
    exact_before = [(candidate_path, value) for line, candidate_path, value
                    in yaml_scalar_rows(before_source) if line == entry.get("line")]
    if exact_before != [(yaml_path, before_value)]:
        errors.append(f"{identifier}: YAML default removal path does not identify the retired source line")
    if before_value != normalized(str(removal["beforeValue"])) \
            or before_value != normalized(str(entry.get("expression", ""))):
        errors.append(f"{identifier}: YAML default removal beforeValue does not match the exact path")
    if after_value != normalized(str(removal["afterValue"])):
        errors.append(f"{identifier}: YAML default removal afterValue does not match the exact path")
    if str(entry.get("role", "")) != yaml_path.rsplit(".", 1)[-1]:
        errors.append(f"{identifier}: YAML default removal path does not match the candidate role")

    setting = str(entry.get("setting", ""))
    replacement_owner = str(removal["replacementOwner"])
    replacement_field = str(removal["replacementField"])
    representatives = [candidate for candidate in active_entries.values()
                       if candidate.get("setting") == setting
                       and candidate.get("classification") == "operator-configurable"
                       and candidate.get("status") != "pending-review"
                       and candidate.get("owner") == replacement_owner
                       and candidate.get("field") == replacement_field]
    if not setting or not representatives:
        errors.append(f"{identifier}: YAML default removal has no active typed replacement setting")
        return errors
    authority = representatives[0].get("defaultAuthority")
    if not isinstance(authority, dict) \
            or authority.get("owner") != replacement_owner \
            or authority.get("field") != replacement_field \
            or authority.get("instanceSymbol") != removal["replacementInstanceSymbol"] \
            or normalized(str(authority.get("sourceExpression", ""))) \
            != normalized(str(removal["replacementDefaultExpression"])):
        errors.append(f"{identifier}: YAML default removal does not match active defaultAuthority")

    if "#" not in replacement_owner:
        errors.append(f"{identifier}: replacementOwner must be path#symbol")
        return errors
    owner_path, owner_symbol = replacement_owner.rsplit("#", 1)
    replacement_source = committed_source(root, after, owner_path)
    if replacement_source is None or java_type_span(replacement_source, owner_symbol) is None \
            or not java_type_declares_field(replacement_source, owner_symbol, replacement_field):
        errors.append(f"{identifier}: typed replacement owner/field is absent from afterRevision")
        return errors
    actual_default = java_record_default_expression(
        replacement_source, owner_symbol, str(removal["replacementInstanceSymbol"]), replacement_field)
    if actual_default is None or normalized(actual_default) \
            != normalized(str(removal["replacementDefaultExpression"])):
        errors.append(f"{identifier}: typed replacement default has drifted in afterRevision")
    return errors


def conversion_evidence_errors(identifier: str, entry: dict[str, object],
                               conversion: dict[str, object], before_source: str | None,
                               after_source: str | None) -> list[str]:
    """Verify that a converted setting's declared binding transition occurred in executable source."""
    if before_source is None or after_source is None:
        return []
    binding = str(conversion["binding"])
    binding_symbol = str(conversion["bindingSymbol"])
    field = str(conversion["field"])
    before_expression = normalized(str(conversion["beforeExpression"]))
    after_expression = normalized(str(conversion["afterExpression"]))
    before_code = normalized(strip_c_comments_and_literals(before_source))
    after_code = normalized(strip_c_comments_and_literals(after_source))
    errors: list[str] = []
    bindings = entry.get("bindings", [])
    if not isinstance(bindings, list) or binding not in bindings:
        errors.append(f"{identifier}: conversion binding is not declared by the setting")
    if field not in before_expression or field not in after_expression:
        errors.append(f"{identifier}: conversion expressions must both identify the setting field")
    if binding_symbol not in after_expression:
        errors.append(f"{identifier}: conversion afterExpression must use bindingSymbol")
    if before_expression == after_expression:
        errors.append(f"{identifier}: conversion expressions must be distinct")
    if before_expression not in before_code or before_expression in after_code:
        errors.append(f"{identifier}: conversion beforeExpression does not identify the replaced source")
    if after_expression in before_code or after_expression not in after_code:
        errors.append(f"{identifier}: conversion afterExpression does not identify the added source")
    if re.search(rf"\b{re.escape(binding)}\b", strip_c_comments(before_source)):
        errors.append(f"{identifier}: conversion binding already exists in beforeRevision")
    if not re.search(rf"\b{re.escape(binding)}\b", strip_c_comments(after_source)):
        errors.append(f"{identifier}: conversion binding is absent from afterRevision")
    declaration = re.compile(rf"\b{re.escape(binding_symbol)}\b\s*=")
    executable_after = strip_c_comments_and_literals(after_source)
    declared_binding = any(
        re.match(rf"\s*\"{re.escape(binding)}\"", after_source[match.end():])
        for match in declaration.finditer(executable_after)
    )
    if not declared_binding:
        errors.append(f"{identifier}: conversion bindingSymbol does not declare the named binding")
    return errors


def environment_resolver_authority_errors(root: Path, identifier: str,
                                          authority: dict[str, object]) -> list[str]:
    required = {
        "kind", "path", "type", "factoryMethod", "factoryBodyDigest", "integerMethod",
        "integerBodyDigest", "dependencyBodyDigests", "validationBodyDigest",
        "validationDependencyBodyDigests", "testPath", "testType", "testMethods",
        "testMethodDigests",
    }
    if set(authority) != required:
        return [f"resolver authority {identifier} requires exactly {', '.join(sorted(required))}"]
    relative = Path(str(authority["path"]))
    type_symbol = str(authority["type"])
    if current_source_owner(root, f"{relative.as_posix()}#{type_symbol}") is None:
        return [f"resolver authority {identifier} has no tracked Java type"]
    source = (root / relative).read_text(encoding="utf-8")
    factory = str(authority["factoryMethod"])
    integer = str(authority["integerMethod"])
    errors: list[str] = []
    expected_factory = f"public static {type_symbol} {factory}(Map<String, String> environment)"
    expected_integer = (
        f"private static int {integer}(Map<String, String> environment, "
        "String name, int defaultValue)"
    )
    if java_method_header(source, type_symbol, factory) != expected_factory:
        errors.append(f"resolver authority {identifier} factory signature is unsupported")
    if java_method_header(source, type_symbol, integer) != expected_integer:
        errors.append(f"resolver authority {identifier} integer signature is unsupported")
    if java_method_digest(source, type_symbol, factory) != authority["factoryBodyDigest"]:
        errors.append(f"resolver authority {identifier} factory body digest has drifted")
    if java_method_digest(source, type_symbol, integer) != authority["integerBodyDigest"]:
        errors.append(f"resolver authority {identifier} integer body digest has drifted")
    dependencies = authority["dependencyBodyDigests"]
    reachable = java_reachable_helper_methods(source, type_symbol, (integer,))
    if not isinstance(dependencies, dict) or reachable != set() or set(dependencies) != reachable \
            or any(java_method_digest(source, type_symbol, method) != digest
                   for method, digest in dependencies.items()):
        errors.append(f"resolver authority {identifier} has incomplete integer helper dependencies")
    constructor = java_compact_constructor_span(source, type_symbol)
    if java_span_digest(source, constructor) != authority["validationBodyDigest"]:
        errors.append(f"resolver authority {identifier} compact constructor digest has drifted")
    validation_dependencies = authority["validationDependencyBodyDigests"]
    validation_reachable = java_reachable_helpers_from_span(source, type_symbol, constructor)
    if not isinstance(validation_dependencies, dict) or validation_reachable is None \
            or validation_reachable != {"positive", "burst"} \
            or set(validation_dependencies) != validation_reachable \
            or any(java_method_digest(source, type_symbol, method) != digest
                   for method, digest in validation_dependencies.items()):
        errors.append(f"resolver authority {identifier} has incomplete validation helper dependencies")
    declared_methods = java_declared_method_names(source, type_symbol)
    burst_calls = java_method_calls(source, type_symbol, "burst", declared_methods)
    if burst_calls is None or "positive" not in burst_calls:
        errors.append(f"resolver authority {identifier} burst validation no longer delegates to positive")

    test_relative = Path(str(authority["testPath"]))
    test_type = str(authority["testType"])
    if current_source_owner(root, f"{test_relative.as_posix()}#{test_type}") is None:
        errors.append(f"resolver authority {identifier} has no tracked Java test type")
    else:
        test_source = (root / test_relative).read_text(encoding="utf-8")
        methods = authority["testMethods"]
        digests = authority["testMethodDigests"]
        if not java_test_type_is_directly_runnable(test_source, test_type):
            errors.append(
                f"resolver authority {identifier} test type is not a supported runnable top-level class")
        if not java_has_exact_rate_test_imports(test_source, test_type):
            errors.append(
                f"resolver authority {identifier} test type does not bind the exact JUnit and Stream types")
        if not isinstance(methods, dict) or set(methods) != ENVIRONMENT_RESOLVER_TEST_ROLES \
                or any(not isinstance(method, str) or not method.strip()
                       for method in methods.values()) \
                or len(set(methods.values())) != len(ENVIRONMENT_RESOLVER_TEST_ROLES) \
                or not isinstance(digests, dict) or set(digests) != set(methods.values()) \
                or any(java_method_digest(test_source, test_type, method) != digests.get(method)
                       for method in methods.values()):
            errors.append(f"resolver authority {identifier} has missing rate-limit test evidence")
        else:
            parameterized = {"malformedOverflowRefusal", "nonPositiveRefusal"}
            ordinary = {
                "blankTypedDefault", "asciiTrimContract", "documentedBoundaryAcceptance",
                "relationalConstraintRefusal",
            }
            for role in parameterized:
                method = str(methods[role])
                if java_method_header(test_source, test_type, method) != f"void {method}(String name)":
                    errors.append(
                        f"resolver authority {identifier} test role {role} has unsupported signature")
                if java_method_annotations(test_source, test_type, method) != (
                        "@ParameterizedTest", f'@MethodSource("{methods["bindingEnumeration"]}")'):
                    errors.append(
                        f"resolver authority {identifier} test role {role} is not linked to its enumeration")
            for role in ordinary:
                method = str(methods[role])
                if java_method_header(test_source, test_type, method) != f"void {method}()":
                    errors.append(
                        f"resolver authority {identifier} test role {role} has unsupported signature")
                if java_method_annotations(test_source, test_type, method) != ("@Test",):
                    errors.append(
                        f"resolver authority {identifier} test role {role} is not a runnable @Test")
            blank_span = java_method_span(
                test_source, test_type, str(methods["blankTypedDefault"]),
            )
            blank_code = strip_c_comments_and_literals(
                test_source[slice(*blank_span)] if blank_span is not None else "",
            )
            if re.search(
                    rf'\b{re.escape(str(methods["bindingEnumeration"]))}\s*\(', blank_code,
            ) is None:
                errors.append(
                    f"resolver authority {identifier} blank-default test does not use its enumeration")
    return errors


def resolver_authority_errors(root: Path, authorities: object) -> list[str]:
    if not isinstance(authorities, dict):
        return ["property-bound settings require a resolverAuthorities object"]
    errors: list[str] = []
    required = ("path", "type", "integerMethod", "wholeMethod", "integerBodyDigest",
                "wholeBodyDigest", "dependencyBodyDigests", "testPath", "testType",
                "testMethods", "testMethodDigests", "compositionMethods",
                "compositionMethodDigests", "compositionLinks")
    for identifier, authority in authorities.items():
        if isinstance(authority, dict) and authority.get("kind") == "java-environment-integer-resolver-v1":
            errors.extend(environment_resolver_authority_errors(root, str(identifier), authority))
            continue
        if isinstance(authority, dict) and "kind" in authority:
            errors.append(f"resolver authority {identifier} has unsupported kind {authority['kind']}")
            continue
        if not isinstance(authority, dict) or any(key not in authority for key in required):
            errors.append(f"resolver authority {identifier} requires {', '.join(required)}")
            continue
        relative = Path(str(authority["path"]))
        source_path = root / relative
        if current_source_owner(root, f"{relative.as_posix()}#{authority['type']}") is None:
            errors.append(f"resolver authority {identifier} has no tracked Java type")
            continue
        source = source_path.read_text(encoding="utf-8")
        for key in ("integerMethod", "wholeMethod"):
            method = str(authority[key])
            digest = java_method_digest(source, str(authority["type"]), method)
            if digest != authority[f"{key.removesuffix('Method')}BodyDigest"]:
                errors.append(f"resolver authority {identifier} {method} body digest has drifted")
        integer_span = java_method_span(source, str(authority["type"]), str(authority["integerMethod"]))
        if integer_span is None or not re.search(
                rf"\b{re.escape(str(authority['wholeMethod']))}\s*\(",
                strip_c_comments_and_literals(source[slice(*integer_span)])):
            errors.append(f"resolver authority {identifier} integer helper does not delegate to whole")
        dependencies = authority["dependencyBodyDigests"]
        reachable = java_reachable_helper_methods(
            source, str(authority["type"]),
            (str(authority["integerMethod"]), str(authority["wholeMethod"])),
        )
        if not isinstance(dependencies, dict) or reachable is None or set(dependencies) != reachable \
                or any(java_method_digest(source, str(authority["type"]), method) != digest
                       for method, digest in dependencies.items()):
            errors.append(f"resolver authority {identifier} has incomplete or drifted helper dependencies")
        composition_methods = authority["compositionMethods"]
        composition_digests = authority["compositionMethodDigests"]
        composition_links = authority["compositionLinks"]
        if not isinstance(composition_methods, dict) \
                or set(composition_methods) != RESOLVER_COMPOSITION_METHOD_ROLES \
                or any(not isinstance(method, str) or not method.strip()
                       for method in composition_methods.values()) \
                or len(set(composition_methods.values())) != len(RESOLVER_COMPOSITION_METHOD_ROLES) \
                or not isinstance(composition_digests, dict) \
                or set(composition_digests) != set(composition_methods.values()) \
                or not isinstance(composition_links, dict) \
                or set(composition_links) != RESOLVER_COMPOSITION_LINK_ROLES:
            errors.append(f"resolver authority {identifier} has incomplete fallback-composition evidence")
        else:
            for method in composition_methods.values():
                if java_method_digest(source, str(authority["type"]), str(method)) \
                        != composition_digests.get(method):
                    errors.append(f"resolver authority {identifier} composition method {method} has drifted")
            for role, link in composition_links.items():
                if not isinstance(link, dict) or set(link) != {"method", "expression"} \
                        or link["method"] not in composition_methods.values() \
                        or not isinstance(link["expression"], str) or not link["expression"].strip():
                    errors.append(
                        f"resolver authority {identifier} fallback-composition link {role} is incomplete")
                    continue
                span = java_method_span(source, str(authority["type"]), str(link["method"]))
                code = normalized(strip_c_comments_and_literals(
                    source[slice(*span)] if span is not None else ""))
                if normalized(str(link["expression"])) not in code:
                    errors.append(
                        f"resolver authority {identifier} fallback-composition link {role} has drifted")
        test_relative = Path(str(authority["testPath"]))
        if current_source_owner(root, f"{test_relative.as_posix()}#{authority['testType']}") is None:
            errors.append(f"resolver authority {identifier} has no tracked Java test type")
        else:
            test_source = (root / test_relative).read_text(encoding="utf-8")
            methods = authority["testMethods"]
            digests = authority["testMethodDigests"]
            if not isinstance(methods, dict) or set(methods) != RESOLVER_TEST_ROLES \
                    or any(not isinstance(method, str) or not method for method in methods.values()) \
                    or not isinstance(digests, dict) or set(digests) != set(methods.values()) or any(
                    java_method_digest(test_source, str(authority["testType"]), method)
                    != digests.get(method) for method in methods.values()):
                errors.append(f"resolver authority {identifier} has missing precedence/refusal test evidence")
    return errors


def graph_default_constructor_arguments(source: str, type_symbol: str,
                                        components: tuple[str, ...]) \
        -> dict[str, tuple[str, int, int]] | None:
    """Return the exact component arguments of one direct static DEFAULTS constructor."""
    initializer = java_static_final_initializer(source, type_symbol, "DEFAULTS")
    if initializer is None:
        return None
    _expression, start, end = initializer
    actual = source[start:end]
    code = strip_c_comments_and_literals(source)[start:end]
    constructor = re.match(rf"\s*new\s+{re.escape(type_symbol)}\s*\(", code)
    if constructor is None:
        return None
    opening = code.find("(", constructor.start())
    parsed = split_java_arguments(actual, code, opening)
    if parsed is None or len(parsed[0]) != len(components) \
            or code[parsed[1] + 1:].strip():
        return None
    return {
        component: (argument, start + argument_start, start + argument_end)
        for component, (argument, argument_start, argument_end)
        in zip(components, parsed[0])
    }


def graph_record_semantics_match(source: str, type_symbol: str,
                                 components: tuple[str, ...], expected_constructor: str) -> bool:
    """Bind graph defaults to the accepted compact-constructor and implicit-accessor semantics."""
    compact = java_compact_constructor_span(source, type_symbol)
    if compact is None or normalized(strip_c_comments(source[slice(*compact)])) \
            != normalized(expected_constructor):
        return False
    return all(java_method_span(source, type_symbol, component) is None
               for component in components)


def graph_numeric_constant_initializer(source: str, type_symbol: str,
                                       field: str) -> tuple[str, int, int] | None:
    """Return one checker-supported direct int/long constant initializer."""
    if type_symbol != "GraphDefinitionStore":
        initializer = java_static_final_initializer(source, type_symbol, field)
        span = java_type_span(source, type_symbol)
        if initializer is None or span is None:
            return None
        base, limit = span
        code = strip_c_comments_and_literals(source)[base:limit]
        depths = java_brace_depths(code)
        declarations = [match for match in re.finditer(
            rf"\b(?:public|private)\s+static\s+final\s+(?:int|long)\s+"
            rf"{re.escape(field)}\s*=", code,
        ) if depths[match.start()] == 1]
        return initializer if len(declarations) == 1 else None

    span = java_type_span(source, type_symbol)
    if span is None:
        return None
    base, limit = span
    actual = source[base:limit]
    code = strip_c_comments_and_literals(source)[base:limit]
    depths = java_brace_depths(code)
    matches: list[tuple[str, int, int]] = []
    for declaration in re.finditer(rf"\bint\s+{re.escape(field)}\s*=", code):
        if depths[declaration.start()] != 1:
            continue
        equals = code.find("=", declaration.start(), declaration.end())
        semicolon = code.find(";", equals + 1)
        if semicolon < 0 or any(char in code[equals + 1:semicolon] for char in "{}"):
            continue
        start = base + equals + 1
        end = base + semicolon
        matches.append((normalized(source[start:end]), start, end))
    return matches[0] if len(matches) == 1 else None


def numeric_candidate_ids_in_source_span(relative: Path, source: str, start: int, end: int,
                                         discovered: dict[str, Candidate]) -> list[str]:
    """Return numeric atom IDs from one exact source span, preserving lexical multiplicity."""
    grouped: dict[tuple[str, str, str, str, str], list[str]] = {}
    for candidate in discovered.values():
        if candidate.path != relative.as_posix():
            continue
        key = (candidate.symbol, candidate.kind, candidate.role, candidate.expression,
               candidate.evidence_digest)
        grouped.setdefault(key, []).append(candidate.id)
    occurrences: Counter[tuple[str, str, str, str, str]] = Counter()
    selected: list[str] = []
    for offset, symbol_name, candidate_kind, candidate_role, expression, evidence in code_candidates(
            relative, source, surface(relative) or "java"):
        evidence_digest = hashlib.sha256(evidence.encode("utf-8")).hexdigest()
        key = (symbol_name, candidate_kind, candidate_role, expression, evidence_digest)
        occurrence = occurrences[key]
        occurrences[key] += 1
        identifiers = grouped.get(key, [])
        if start <= offset < end and NUMBER.fullmatch(expression) and occurrence < len(identifiers):
            selected.append(identifiers[occurrence])
    return selected


def graph_numeric_value_and_evidence(
        expression: str, current_owner: str, sources: dict[str, tuple[Path, str]],
        span: tuple[Path, str, int, int] | None, discovered: dict[str, Candidate],
        active: set[str] | None = None, require_evidence: bool = True) -> tuple[int, list[str]] | None:
    """Evaluate the closed graph integer grammar and retain terminal numeric atom IDs."""
    active = set() if active is None else active
    direct_ids = (numeric_candidate_ids_in_source_span(*span, discovered)
                  if span is not None else [])
    referenced_ids: list[str] = []

    def resolve(token: str) -> int | None:
        qualified = token if "." in token else f"{current_owner}.{token}"
        expected = GRAPH_NUMERIC_CONSTANT_EXPRESSIONS.get(qualified)
        if expected is None or qualified in active:
            return None
        owner, field = qualified.split(".", 1)
        source_info = sources.get(owner)
        if source_info is None:
            return None
        relative, source = source_info
        initializer = graph_numeric_constant_initializer(source, owner, field)
        if initializer is None or normalized(initializer[0]) != normalized(expected):
            return None
        active.add(qualified)
        resolved = graph_numeric_value_and_evidence(
            initializer[0], owner, sources,
            (relative, source, initializer[1], initializer[2]), discovered, active,
            require_evidence)
        active.remove(qualified)
        if resolved is None:
            return None
        value, identifiers = resolved
        referenced_ids.extend(identifiers)
        return value

    # Every supported long literal is within int32; the suffix changes Java type, not its value.
    integer_expression = re.sub(r"(?<=\d)[lL]\b", "", expression)
    value = java_int_expression_value(integer_expression, resolve)
    if value is None:
        return None
    identifiers = direct_ids + referenced_ids
    return (value, identifiers) if identifiers or not require_evidence else None


def graph_limit_family_from_source(root: Path,
                                   discovered: dict[str, Candidate]) -> dict[str, object] | None:
    """Derive the closed 25-setting GraphExecutionLimits binding family from source."""
    if set(GRAPH_LIMIT_AUTHORITY_BY_SETTING) != set(GRAPH_LIMIT_SOURCE_BY_SETTING) \
            or len(GRAPH_LIMIT_SOURCE_CONTRACTS) != 25:
        return None
    source = (root / GRAPH_EXECUTION_LIMITS_PATH).read_text(encoding="utf-8")
    graph_ml_source = (root / GRAPH_ML_LIMITS_PATH).read_text(encoding="utf-8")
    payload_source = (root / PAYLOAD_LIMITS_PATH).read_text(encoding="utf-8")
    graph_store_source = (root / GRAPH_DEFINITION_STORE_PATH).read_text(encoding="utf-8")
    root_components = java_record_components(source, "GraphExecutionLimits")
    graph_ml_components = java_record_components(graph_ml_source, "GraphMlLimits")
    payload_components = java_record_components(payload_source, "PayloadLimits")
    if root_components != (
            "graphMl", "payload", "maxFanOut", "maxResidentActors",
            "maxLiveActorsPerTraversal", "maxInFlightHopsPerTraversal",
            "maxQueuedAdmissionsPerNode", "maxTraversalSteps", "maxAmplifiedDeliveries",
            "maxCumulativePayloadBytes", "maxRecoveryDeliveriesPerAttempt") \
            or graph_ml_components != (
                "maxBytes", "maxNodes", "maxEdges", "maxProperties", "maxDepth",
                "maxStringLength", "maxKeys", "maxElements", "maxAttributes",
                "maxNamespaceDeclarations") \
            or payload_components != (
                "maxEncodedBytes", "maxDepth", "maxCollectionSize", "maxValueCount",
                "maxTextLength", "maxKeyLength"):
        return None
    if java_package(graph_ml_source) != "ai.ravenroot.core.graph" \
            or java_package(payload_source) != "ai.ravenroot.api.payload" \
            or java_package(graph_store_source) != "ai.ravenroot.api.persistence" \
            or not java_exact_top_level_type_header(
                graph_store_source, "GraphDefinitionStore",
                "public interface GraphDefinitionStore extends AutoCloseable") \
            or any(imported.rsplit(".", 1)[-1] == "AutoCloseable" for imported in re.findall(
                r"(?m)^\s*import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)\s*;",
                strip_c_comments_and_literals(graph_store_source))) \
            or not java_has_no_simple_name_shadow(
                graph_store_source, "GraphDefinitionStore", {"AutoCloseable"}) \
            or not exact_import_identity(
                graph_ml_source, "ai.ravenroot.api.persistence.GraphDefinitionStore") \
            or not java_has_no_simple_name_shadow(
                graph_ml_source, "GraphMlLimits", {"GraphDefinitionStore"}):
        return None
    if any(not graph_record_semantics_match(record_source, type_symbol, components, constructor)
           for record_source, type_symbol, components, constructor in (
        (graph_ml_source, "GraphMlLimits", graph_ml_components, """
            GraphMlLimits {
                if (maxBytes < 1 || maxNodes < 1 || maxEdges < 1 || maxProperties < 1
                        || maxDepth < 1 || maxStringLength < 1 || maxKeys < 1
                        || maxElements < 1 || maxAttributes < 1 || maxNamespaceDeclarations < 1) {
                    throw new IllegalArgumentException("GraphML limits must all be positive");
                }
                if (maxBytes > GraphDefinitionStore.HARD_MAX_DEFINITION_BYTES
                        || maxNodes > HARD_MAX_NODES || maxEdges > HARD_MAX_EDGES
                        || maxProperties > HARD_MAX_PROPERTIES || maxDepth > HARD_MAX_DEPTH
                        || maxStringLength > HARD_MAX_STRING_LENGTH || maxKeys > HARD_MAX_KEYS
                        || maxElements > HARD_MAX_ELEMENTS || maxAttributes > HARD_MAX_ATTRIBUTES
                        || maxNamespaceDeclarations > HARD_MAX_NAMESPACE_DECLARATIONS) {
                    throw new IllegalArgumentException("GraphML limits exceed the supported safety ceiling");
                }
            }
        """),
        (payload_source, "PayloadLimits", payload_components, """
            PayloadLimits {
                if (maxEncodedBytes < 1 || maxDepth < 1 || maxCollectionSize < 1 || maxValueCount < 1
                        || maxTextLength < 1 || maxKeyLength < 1) {
                    throw new IllegalArgumentException("payload limits must all be positive");
                }
                if (maxEncodedBytes > HARD_MAX_ENCODED_BYTES || maxDepth > HARD_MAX_DEPTH
                        || maxCollectionSize > HARD_MAX_COLLECTION_SIZE
                        || maxValueCount > HARD_MAX_VALUE_COUNT || maxTextLength > HARD_MAX_TEXT_LENGTH
                        || maxKeyLength > HARD_MAX_KEY_LENGTH) {
                    throw new IllegalArgumentException("payload limits exceed the supported safety ceiling");
                }
            }
        """),
        (source, "GraphExecutionLimits", root_components, """
            GraphExecutionLimits {
                Objects.requireNonNull(graphMl, "graphMl");
                Objects.requireNonNull(payload, "payload");
                positiveWithin("maxFanOut", maxFanOut, HARD_MAX_FAN_OUT);
                positiveWithin("maxResidentActors", maxResidentActors, HARD_MAX_RESIDENT_ACTORS);
                positiveWithin("maxLiveActorsPerTraversal", maxLiveActorsPerTraversal, HARD_MAX_LIVE_ACTORS);
                positiveWithin("maxInFlightHopsPerTraversal", maxInFlightHopsPerTraversal,
                        HARD_MAX_IN_FLIGHT_HOPS);
                positiveWithin("maxQueuedAdmissionsPerNode", maxQueuedAdmissionsPerNode,
                        HARD_MAX_QUEUED_ADMISSIONS);
                positiveWithin("maxTraversalSteps", maxTraversalSteps, HARD_MAX_TRAVERSAL_STEPS);
                positiveWithin("maxAmplifiedDeliveries", maxAmplifiedDeliveries, HARD_MAX_AMPLIFIED_DELIVERIES);
                positiveWithin("maxCumulativePayloadBytes", maxCumulativePayloadBytes,
                        HARD_MAX_CUMULATIVE_PAYLOAD_BYTES);
                positiveWithin("maxRecoveryDeliveriesPerAttempt", maxRecoveryDeliveriesPerAttempt,
                        HARD_MAX_RECOVERY_DELIVERIES);
            }
        """),
    )):
        return None
    for record_source, record_type in (
            (graph_ml_source, "GraphMlLimits"), (payload_source, "PayloadLimits")):
        imports = re.findall(
            r"(?m)^\s*import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)\s*;",
            strip_c_comments_and_literals(record_source),
        )
        if any(imported.rsplit(".", 1)[-1] == "IllegalArgumentException"
               for imported in imports) \
                or not java_has_no_simple_name_shadow(
                    record_source, record_type, {"IllegalArgumentException"}):
            return None
    if java_package(source) != "ai.ravenroot.core.runtime" \
            or java_method_header(source, "GraphExecutionLimits", "fromEnvironment") != \
            "public static GraphExecutionLimits fromEnvironment(Map<String, String> environment)" \
            or any(not exact_import_identity(source, imported) for imported in (
                "java.util.Map", "java.util.Objects", "ai.ravenroot.core.graph.GraphMlLimits",
                "ai.ravenroot.api.payload.PayloadLimits",
                "ai.ravenroot.api.persistence.GraphDefinitionStore",
            )) \
            or not java_has_no_simple_name_shadow(
                source, "GraphExecutionLimits",
                {"Map", "Objects", "GraphMlLimits", "PayloadLimits", "GraphDefinitionStore"}):
        return None
    java_lang_types = {"String", "Long", "NumberFormatException", "IllegalArgumentException"}
    normal_imports = re.findall(
        r"(?m)^\s*import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)\s*;",
        strip_c_comments_and_literals(source),
    )
    if any(imported.rsplit(".", 1)[-1] in java_lang_types for imported in normal_imports) \
            or not java_has_no_simple_name_shadow(
                source, "GraphExecutionLimits", java_lang_types):
        return None
    factory_span = java_method_span(source, "GraphExecutionLimits", "fromEnvironment")
    if factory_span is None:
        return None
    expected_factory = """
        fromEnvironment(Map<String, String> environment) {
            Objects.requireNonNull(environment, "environment");
            GraphExecutionLimits defaults = DEFAULTS;
            GraphMlLimits graphMl = defaults.graphMl;
            graphMl = new GraphMlLimits(
                    integer(environment, MAX_GRAPHML_BYTES_VARIABLE, graphMl.maxBytes(),
                            GraphDefinitionStore.HARD_MAX_DEFINITION_BYTES),
                    integer(environment, MAX_NODES_VARIABLE, graphMl.maxNodes(), GraphMlLimits.HARD_MAX_NODES),
                    integer(environment, MAX_EDGES_VARIABLE, graphMl.maxEdges(), GraphMlLimits.HARD_MAX_EDGES),
                    integer(environment, MAX_PROPERTIES_VARIABLE, graphMl.maxProperties(),
                            GraphMlLimits.HARD_MAX_PROPERTIES),
                    integer(environment, MAX_GRAPHML_DEPTH_VARIABLE, graphMl.maxDepth(), GraphMlLimits.HARD_MAX_DEPTH),
                    integer(environment, MAX_GRAPHML_STRING_LENGTH_VARIABLE, graphMl.maxStringLength(),
                            GraphMlLimits.HARD_MAX_STRING_LENGTH),
                    integer(environment, MAX_GRAPHML_KEYS_VARIABLE, graphMl.maxKeys(), GraphMlLimits.HARD_MAX_KEYS),
                    integer(environment, MAX_GRAPHML_ELEMENTS_VARIABLE, graphMl.maxElements(),
                            GraphMlLimits.HARD_MAX_ELEMENTS),
                    integer(environment, MAX_GRAPHML_ATTRIBUTES_VARIABLE, graphMl.maxAttributes(),
                            GraphMlLimits.HARD_MAX_ATTRIBUTES),
                    integer(environment, MAX_GRAPHML_NAMESPACE_DECLARATIONS_VARIABLE,
                            graphMl.maxNamespaceDeclarations(), GraphMlLimits.HARD_MAX_NAMESPACE_DECLARATIONS));
            PayloadLimits payload = defaults.payload;
            payload = new PayloadLimits(
                    integer(environment, MAX_PAYLOAD_BYTES_VARIABLE, payload.maxEncodedBytes(),
                            PayloadLimits.HARD_MAX_ENCODED_BYTES),
                    integer(environment, MAX_PAYLOAD_DEPTH_VARIABLE, payload.maxDepth(), PayloadLimits.HARD_MAX_DEPTH),
                    integer(environment, MAX_PAYLOAD_COLLECTION_SIZE_VARIABLE, payload.maxCollectionSize(),
                            PayloadLimits.HARD_MAX_COLLECTION_SIZE),
                    integer(environment, MAX_PAYLOAD_VALUE_COUNT_VARIABLE, payload.maxValueCount(),
                            PayloadLimits.HARD_MAX_VALUE_COUNT),
                    integer(environment, MAX_PAYLOAD_TEXT_LENGTH_VARIABLE, payload.maxTextLength(),
                            PayloadLimits.HARD_MAX_TEXT_LENGTH),
                    integer(environment, MAX_PAYLOAD_KEY_LENGTH_VARIABLE, payload.maxKeyLength(),
                            PayloadLimits.HARD_MAX_KEY_LENGTH));
            return new GraphExecutionLimits(graphMl, payload,
                    integer(environment, MAX_FAN_OUT_VARIABLE, defaults.maxFanOut, HARD_MAX_FAN_OUT),
                    integer(environment, MAX_RESIDENT_ACTORS_VARIABLE, defaults.maxResidentActors,
                            HARD_MAX_RESIDENT_ACTORS),
                    integer(environment, MAX_LIVE_ACTORS_VARIABLE, defaults.maxLiveActorsPerTraversal,
                            HARD_MAX_LIVE_ACTORS),
                    integer(environment, MAX_IN_FLIGHT_HOPS_VARIABLE, defaults.maxInFlightHopsPerTraversal,
                            HARD_MAX_IN_FLIGHT_HOPS),
                    integer(environment, MAX_QUEUED_ADMISSIONS_VARIABLE, defaults.maxQueuedAdmissionsPerNode,
                            HARD_MAX_QUEUED_ADMISSIONS),
                    longInteger(environment, MAX_TRAVERSAL_STEPS_VARIABLE, defaults.maxTraversalSteps,
                            HARD_MAX_TRAVERSAL_STEPS),
                    longInteger(environment, MAX_AMPLIFIED_DELIVERIES_VARIABLE, defaults.maxAmplifiedDeliveries,
                            HARD_MAX_AMPLIFIED_DELIVERIES),
                    longInteger(environment, MAX_CUMULATIVE_PAYLOAD_BYTES_VARIABLE,
                            defaults.maxCumulativePayloadBytes, HARD_MAX_CUMULATIVE_PAYLOAD_BYTES),
                    integer(environment, MAX_RECOVERY_DELIVERIES_VARIABLE,
                            defaults.maxRecoveryDeliveriesPerAttempt, HARD_MAX_RECOVERY_DELIVERIES));
        }
    """
    if normalized(strip_c_comments(source[slice(*factory_span)])) != normalized(expected_factory):
        return None
    factory_code = normalized(strip_c_comments_and_literals(source[slice(*factory_span)]))
    required_composition = (
        "Objects.requireNonNull(environment,              );",
        "GraphExecutionLimits defaults = DEFAULTS;",
        "GraphMlLimits graphMl = defaults.graphMl;",
        "PayloadLimits payload = defaults.payload;",
    )
    # The string masker removes the requireNonNull label, but retains the structural call.
    if any(normalized(value) not in factory_code for value in required_composition) \
            or java_identifier_write_count(factory_code, "environment") != 0 \
            or java_identifier_write_count(factory_code, "defaults") != 1 \
            or java_identifier_write_count(factory_code, "graphMl") != 2 \
            or java_identifier_write_count(factory_code, "payload") != 2:
        return None
    root_graph = java_constructor_component_call(
        source, "GraphExecutionLimits", "fromEnvironment", "GraphExecutionLimits",
        root_components, "graphMl")
    root_payload = java_constructor_component_call(
        source, "GraphExecutionLimits", "fromEnvironment", "GraphExecutionLimits",
        root_components, "payload")
    if root_graph is None or root_graph[0] != "graphMl" \
            or root_payload is None or root_payload[0] != "payload":
        return None

    exact_helpers = {
        "integer": (
            "private static int integer(Map<String, String> environment, String name, int fallback, int ceiling)",
            "integer(Map<String, String> environment, String name, int fallback, int ceiling) { "
            "long value = longInteger(environment, name, fallback, ceiling); return (int) value; }",
        ),
        "longInteger": (
            "private static long longInteger(Map<String, String> environment, String name, long fallback, long ceiling)",
            "longInteger(Map<String, String> environment, String name, long fallback, long ceiling) { "
            "String raw = environment.get(name); if (raw == null || raw.isBlank()) return fallback; "
            "long value; try { value = Long.parseLong(raw.strip()); } catch (NumberFormatException invalid) "
            "{ throw invalid(name, ceiling); } if (value < 1 || value > ceiling) throw invalid(name, ceiling); "
            "return value; }",
        ),
        "invalid": (
            "private static IllegalArgumentException invalid(String name, long ceiling)",
            "invalid(String name, long ceiling) { return new IllegalArgumentException(name + "
            "\" must be a whole number from 1 through \" + ceiling); }",
        ),
        "positiveWithin": (
            "private static void positiveWithin(String name, long value, long ceiling)",
            "positiveWithin(String name, long value, long ceiling) { if (value < 1) "
            "throw new IllegalArgumentException(name + \" must be positive\"); if (value > ceiling) "
            "throw new IllegalArgumentException(name + \" exceeds the supported safety ceiling\"); }",
        ),
    }
    helper_digests: dict[str, str] = {}
    for method, (header, body) in exact_helpers.items():
        span = java_method_span(source, "GraphExecutionLimits", method)
        if java_method_header(source, "GraphExecutionLimits", method) != header \
                or span is None \
                or normalized(strip_c_comments(source[slice(*span)])) != normalized(body):
            return None
        digest = java_method_digest(source, "GraphExecutionLimits", method)
        if digest is None:
            return None
        helper_digests[method] = digest

    target_components = {
        "GraphMlLimits": graph_ml_components,
        "PayloadLimits": payload_components,
        "GraphExecutionLimits": root_components,
    }
    numeric_sources = {
        "GraphExecutionLimits": (GRAPH_EXECUTION_LIMITS_PATH, source),
        "GraphMlLimits": (GRAPH_ML_LIMITS_PATH, graph_ml_source),
        "PayloadLimits": (PAYLOAD_LIMITS_PATH, payload_source),
        "GraphDefinitionStore": (GRAPH_DEFINITION_STORE_PATH, graph_store_source),
    }
    default_arguments = {
        target: graph_default_constructor_arguments(numeric_sources[target][1], target, components)
        for target, components in target_components.items()
    }
    if any(arguments is None for arguments in default_arguments.values()):
        return None
    root_defaults = default_arguments["GraphExecutionLimits"]
    if root_defaults is None \
            or normalized(root_defaults["graphMl"][0]) != "GraphMlLimits.DEFAULTS" \
            or normalized(root_defaults["payload"][0]) != "PayloadLimits.DEFAULTS":
        return None
    settings: list[dict[str, object]] = []
    for setting, target, root_component, environment_symbol, helper, fallback, ceiling \
            in GRAPH_LIMIT_SOURCE_CONTRACTS:
        fixed = GRAPH_LIMIT_AUTHORITY_BY_SETTING[setting]
        field = str(fixed["field"])
        components = target_components[target]
        call = java_constructor_component_call(
            source, "GraphExecutionLimits", "fromEnvironment", target, components, field)
        if call is None:
            return None
        argument, start, end = call
        expected_argument = f"{helper}(environment, {environment_symbol}, {fallback}, {ceiling})"
        if normalized(argument) != normalized(expected_argument):
            return None
        default_contract = GRAPH_LIMIT_DEFAULT_CONTRACTS.get(setting)
        typed_defaults = default_arguments[target]
        if default_contract is None or typed_defaults is None or field not in typed_defaults:
            return None
        default_expression, default_start, default_end = typed_defaults[field]
        expected_default_expression, unit = default_contract
        if normalized(default_expression) != normalized(expected_default_expression):
            return None
        default_result = graph_numeric_value_and_evidence(
            default_expression, target, numeric_sources,
            (numeric_sources[target][0], numeric_sources[target][1], default_start, default_end),
            discovered,
        )
        ceiling_result = graph_numeric_value_and_evidence(
            ceiling, "GraphExecutionLimits", numeric_sources, None, discovered,
            require_evidence=False)
        if default_result is None or ceiling_result is None:
            return None
        default_value, default_ids = default_result
        ceiling_value, _ceiling_ids = ceiling_result
        initializer = java_static_final_initializer(
            source, "GraphExecutionLimits", environment_symbol)
        environment = str(fixed["environment"])
        if initializer is None or normalized(initializer[0]) != f'"{environment}"':
            return None
        type_span = java_type_span(source, "GraphExecutionLimits")
        type_code = strip_c_comments_and_literals(source)[slice(*type_span)] \
            if type_span is not None else ""
        depths = java_brace_depths(type_code)
        declarations = [match for match in re.finditer(
            rf"\bpublic\s+static\s+final\s+String\s+{re.escape(environment_symbol)}\s*=", type_code,
        ) if depths[match.start()] == 1]
        environment_ids = candidate_ids_in_source_span(
            GRAPH_EXECUTION_LIMITS_PATH, source, initializer[1], initializer[2],
            "environment-binding", environment, discovered)
        if len(declarations) != 1 or len(environment_ids) != 1 \
                or java_identifier_write_count(factory_code, environment_symbol) != 0:
            return None
        root_index = root_components.index(root_component)
        target_index = components.index(field)
        settings.append({
            "setting": setting, "typedOwner": fixed["typedOwner"], "field": field,
            "sourceOwner": f"{GRAPH_EXECUTION_LIMITS_PATH.as_posix()}#GraphExecutionLimits",
            "factoryMethod": "fromEnvironment", "rootComponent": root_component,
            "rootComponentIndex": root_index, "targetConstructor": target,
            "targetComponentIndex": target_index, "environmentSymbol": environment_symbol,
            "environment": environment, "environmentCandidateId": environment_ids[0],
            "helper": helper, "fallbackAccessor": fallback, "ceilingAccessor": ceiling,
            "callDigest": hashlib.sha256(argument.encode("utf-8")).hexdigest(),
            "defaultExpression": expected_default_expression,
            "defaultValue": default_value,
            "defaultDisplay": f"{default_value} {unit}",
            "defaultEvidence": sorted(default_ids),
            "ceilingValue": ceiling_value,
            "validationDisplay": f"1..{ceiling_value}",
        })
    return {
        "kind": "java-graph-environment-family-v1",
        "sourceOwner": f"{GRAPH_EXECUTION_LIMITS_PATH.as_posix()}#GraphExecutionLimits",
        "factoryMethod": "fromEnvironment",
        "factoryBodyDigest": java_method_digest(source, "GraphExecutionLimits", "fromEnvironment"),
        "helperBodyDigests": helper_digests,
        "settings": settings,
    }


def graph_limit_authority_errors(root: Path, authorities: object,
                                 entries: dict[str, dict[str, object]],
                                 discovered: dict[str, Candidate]) -> list[str]:
    """Verify the mandatory graph family across nested typed owners and exact carriers."""
    expected_settings = set(GRAPH_LIMIT_AUTHORITY_BY_SETTING)
    reviewed = {
        str(entry.get("setting")) for entry in entries.values()
        if entry.get("status") != "pending-review"
        and str(entry.get("setting", "")) in expected_settings
    }
    if not reviewed:
        return ([] if authorities in (None, {})
                else ["graph limit authority exists without reviewed graph settings"])
    errors: list[str] = []
    if reviewed != expected_settings:
        errors.append("reviewed graph settings do not equal the closed 25-setting family")
    derived = graph_limit_family_from_source(root, discovered)
    if derived is None:
        return errors + ["GraphExecutionLimits environment source family has drifted"]
    if not isinstance(authorities, dict) or set(authorities) != {GRAPH_LIMIT_FAMILY_ID} \
            or authorities.get(GRAPH_LIMIT_FAMILY_ID) != derived:
        errors.append("graph settings require the exact source-derived 25-setting family authority")
    for spec in derived["settings"]:
        setting = str(spec["setting"])
        setting_entries = [entry for entry in entries.values() if entry.get("setting") == setting]
        if not setting_entries:
            errors.append(f"{setting}: graph family has no inventory rows")
            continue
        representative = setting_entries[0]
        if representative.get("owner") != spec["typedOwner"] \
                or representative.get("field") != spec["field"] \
                or representative.get("bindings") != [spec["environment"]] \
                or representative.get("bindingAuthority") is not None:
            errors.append(f"{setting}: graph typed owner/field/environment metadata has drifted")
        if any(entry.get("default") != spec["defaultDisplay"]
               or entry.get("validation") != spec["validationDisplay"]
               or not isinstance(entry.get("defaultEvidence"), list)
               or Counter(str(identifier) for identifier in entry["defaultEvidence"])
               != Counter(str(identifier) for identifier in spec["defaultEvidence"])
               for entry in setting_entries):
            errors.append(f"{setting}: graph default, range, or exact default evidence has drifted")
        if any(identifier not in discovered
               or entries.get(identifier, {}).get("setting") != setting
               for identifier in spec["defaultEvidence"]):
            errors.append(f"{setting}: graph typed default atoms are absent or assigned elsewhere")
        if current_source_owner(root, str(spec["typedOwner"])) is None \
                or not current_source_field(root, str(spec["typedOwner"]), str(spec["field"])):
            errors.append(f"{setting}: graph typed owner does not declare its exact component")
        source_id = str(spec["environmentCandidateId"])
        source_candidate = discovered.get(source_id)
        if source_candidate is None or source_candidate.path != GRAPH_EXECUTION_LIMITS_PATH.as_posix() \
                or source_candidate.kind != "environment-binding" \
                or source_candidate.expression != spec["environment"] \
                or entries.get(source_id, {}).get("setting") != setting:
            errors.append(f"{setting}: graph source environment candidate is absent or assigned elsewhere")
        source_ids = {
            candidate.id for candidate in discovered.values()
            if candidate.path == GRAPH_EXECUTION_LIMITS_PATH.as_posix()
            and candidate.kind == "environment-binding"
            and candidate.expression == spec["environment"]
        }
        if source_ids != {source_id}:
            errors.append(f"{setting}: graph source environment candidate partition has drifted")
        coverage = representative.get("coverageEvidence")
        carrier_ids: set[str] = set()
        if not isinstance(coverage, dict) \
                or coverage.get("kind") != "graph-platform-carriers-v1":
            errors.append(f"{setting}: graph family requires complete platform carrier evidence")
        else:
            for field in ("composeCandidateIds", "helmTemplateCandidateIds",
                          "helmSchemaEnvironmentCandidateIds", "rawKubernetesCandidateIds"):
                identifiers = coverage.get(field)
                if not isinstance(identifiers, list):
                    errors.append(f"{setting}: graph carrier evidence has no {field}")
                    continue
                carrier_ids.update(str(identifier) for identifier in identifiers)
        assigned = {
            str(entry["id"]) for entry in setting_entries
            if entry.get("kind") == "environment-binding"
        }
        if assigned != {source_id} | carrier_ids or any(
                identifier not in discovered
                or discovered[identifier].expression != spec["environment"]
                or entries.get(identifier, {}).get("setting") != setting
                for identifier in carrier_ids):
            errors.append(f"{setting}: graph environment candidates are not fully partitioned")
    return errors


def deployment_carrier_evidence_errors(setting: str, contract: dict[str, object],
                                       entries: dict[str, dict[str, object]],
                                       discovered: dict[str, Candidate]) -> tuple[list[str], set[str]]:
    evidence = contract.get("carrierEvidence")
    required = {"kind", "environment", "expectedCandidateIds"}
    if not isinstance(evidence, dict) or set(evidence) != required:
        return ([f"{setting}: environment-bound setting requires exact carrierEvidence fields"], set())
    errors: list[str] = []
    if evidence["kind"] != "deployment-environment-carriers-v1":
        errors.append(f"{setting}: unsupported carrierEvidence kind")
    bindings = contract.get("bindings", [])
    environment = str(evidence["environment"])
    if not isinstance(bindings, list) or bindings != [environment]:
        errors.append(f"{setting}: carrier environment must be the setting's sole binding")
    expected = evidence["expectedCandidateIds"]
    if not isinstance(expected, dict) or set(expected) != set(DEPLOYMENT_ENVIRONMENT_CARRIER_PATHS):
        return (errors + [f"{setting}: carrierEvidence must retain every checker-owned carrier group"], set())
    accounted: set[str] = set()
    for group, paths in DEPLOYMENT_ENVIRONMENT_CARRIER_PATHS.items():
        actual_ids = sorted(
            candidate.id for candidate in discovered.values()
            if candidate.path in paths and candidate.kind == "environment-binding"
            and candidate.expression == environment
        )
        declared = expected[group]
        if not isinstance(declared, list) or [str(identifier) for identifier in declared] != actual_ids:
            errors.append(f"{setting}: {group} carrier candidate set has drifted")
            continue
        accounted.update(actual_ids)
        if any(entries.get(identifier, {}).get("setting") != setting for identifier in actual_ids):
            errors.append(f"{setting}: {group} carrier candidate is absent or assigned elsewhere")
    return errors, accounted


def environment_binding_authority_errors(root: Path, setting: str, contract: dict[str, object],
                                         setting_entries: list[dict[str, object]],
                                         entries: dict[str, dict[str, object]],
                                         discovered: dict[str, Candidate],
                                         resolver_authorities: object) -> list[str]:
    required = {
        "kind", "sourceOwner", "method", "constructorType", "component", "componentIndex",
        "helper", "environmentCandidateId", "sourceEnvironmentCandidateIds", "environment",
        "defaultAccessor", "valueTransform", "callDigest", "resolverAuthority",
    }
    authority = contract.get("bindingAuthority")
    if not isinstance(authority, dict) or set(authority) != required:
        return [f"{setting}: environment-bound setting requires exact bindingAuthority fields"]
    errors: list[str] = []
    if authority["kind"] != "java-environment-constructor-v1":
        errors.append(f"{setting}: unsupported environment bindingAuthority kind")
    source_owner = str(authority["sourceOwner"])
    if source_owner != contract.get("owner"):
        errors.append(f"{setting}: environment binding sourceOwner must equal the typed setting owner")
        return errors
    resolved = current_source_owner(root, source_owner)
    if resolved is None or resolved[0].suffix != ".java":
        return errors + [f"{setting}: environment binding sourceOwner must be a tracked Java record"]
    source_path, source_type = resolved
    source = (root / source_path).read_text(encoding="utf-8")
    components = java_record_components(source, source_type)
    component = str(authority["component"])
    index = authority["componentIndex"]
    if not isinstance(index, int) or isinstance(index, bool) or not 0 <= index < len(components) \
            or components[index] != component or component != contract.get("field"):
        errors.append(f"{setting}: environment binding component index/field has drifted")
        return errors
    if authority["constructorType"] != source_type:
        errors.append(f"{setting}: environment constructorType must be the exact owner record")
        return errors
    method = str(authority["method"])
    call = java_constructor_component_call(source, source_type, method, source_type, components, component)
    if call is None:
        return errors + [f"{setting}: environment binding has no unique constructor-position call"]
    argument, start, end = call
    environment = str(authority["environment"])
    identity = re.fullmatch(
        rf'integer\(environment, "{re.escape(environment)}", DEFAULTS\.{re.escape(component)}\)',
        argument,
    )
    duration = re.fullmatch(
        rf'Duration\.ofSeconds\(integer\(environment, "{re.escape(environment)}", '
        rf'\(int\) DEFAULTS\.{re.escape(component)}\.toSeconds\(\)\)\)',
        argument,
    )
    expected_transform = "identity" if identity else "duration-seconds" if duration else None
    expected_accessor = (f"DEFAULTS.{component}" if identity
                         else f"(int) DEFAULTS.{component}.toSeconds()" if duration else None)
    if authority["helper"] != "integer" or authority["valueTransform"] != expected_transform \
            or authority["defaultAccessor"] != expected_accessor:
        errors.append(f"{setting}: constructor component is not a supported environment integer call")
    if hashlib.sha256(argument.encode("utf-8")).hexdigest() != authority["callDigest"]:
        errors.append(f"{setting}: environment binding callDigest has drifted")
    if contract.get("bindings") != [environment]:
        errors.append(f"{setting}: environment binding must be the setting's sole binding")
    constructor_ids = candidate_ids_in_source_span(
        source_path, source, start, end, "environment-binding", environment, discovered,
    )
    if constructor_ids != [str(authority["environmentCandidateId"])]:
        errors.append(f"{setting}: constructor environment candidate is not the exact component literal")
    source_ids = sorted(
        candidate.id for candidate in discovered.values()
        if candidate.path == source_path.as_posix() and candidate.kind == "environment-binding"
        and candidate.expression == environment
    )
    declared_source_ids = authority["sourceEnvironmentCandidateIds"]
    if not isinstance(declared_source_ids, list) \
            or [str(identifier) for identifier in declared_source_ids] != source_ids:
        errors.append(f"{setting}: source environment candidate partition has drifted")
    carrier_errors, carrier_ids = deployment_carrier_evidence_errors(
        setting, contract, entries, discovered,
    )
    errors.extend(carrier_errors)
    assigned_ids = {
        str(entry["id"]) for entry in setting_entries
        if entry.get("kind") == "environment-binding"
    }
    if assigned_ids != set(source_ids) | carrier_ids:
        errors.append(f"{setting}: assigned environment candidates are not fully partitioned")
    resolver = str(authority["resolverAuthority"])
    if not isinstance(resolver_authorities, dict) or resolver not in resolver_authorities:
        errors.append(f"{setting}: environment bindingAuthority references an absent resolver authority")
    else:
        resolved_authority = resolver_authorities[resolver]
        if not isinstance(resolved_authority, dict) \
                or resolved_authority.get("kind") != "java-environment-integer-resolver-v1" \
                or resolved_authority.get("path") != source_path.as_posix() \
                or resolved_authority.get("type") != source_type \
                or resolved_authority.get("factoryMethod") != method \
                or resolved_authority.get("integerMethod") != authority["helper"]:
            errors.append(f"{setting}: environment resolver authority does not match the binding source")
    return errors


def dual_source_binding_authority_errors(root: Path, setting: str, contract: dict[str, object],
                                        entries: dict[str, dict[str, object]],
                                        discovered: dict[str, Candidate],
                                        resolver_authorities: object) -> list[str]:
    bindings = contract.get("bindings", [])
    property_candidates = [entry for entry in entries.values()
                           if entry.get("setting") == setting and entry.get("kind") == "property-binding"]
    authority = contract.get("bindingAuthority")
    if not property_candidates:
        return [] if authority is None else [f"{setting}: bindingAuthority exists without a property candidate"]
    required = ("kind", "sourceOwner", "method", "constructorType", "component", "helper",
                "propertyCandidateId", "property", "environmentCandidateId", "environment",
                "defaultAccessor", "callDigest", "resolverAuthority")
    if not isinstance(authority, dict) or any(not isinstance(authority.get(key), str)
                                              or not str(authority[key]).strip() for key in required):
        return [f"{setting}: property-bound setting requires atomic bindingAuthority fields {', '.join(required)}"]
    errors: list[str] = []
    if authority["kind"] != "java-dual-source-constructor-v1":
        errors.append(f"{setting}: unsupported bindingAuthority kind")
    source_owner = str(authority["sourceOwner"])
    resolved_source = current_source_owner(root, source_owner)
    if resolved_source is None or resolved_source[0].suffix != ".java":
        errors.append(f"{setting}: bindingAuthority sourceOwner must be a tracked Java type")
        return errors
    source_path, source_type = resolved_source
    owner = current_source_owner(root, str(contract.get("owner", "")))
    component = str(authority["component"])
    if owner is None or owner[0].suffix != ".java" or component != contract.get("field"):
        errors.append(f"{setting}: bindingAuthority component must match its Java setting owner")
        return errors
    owner_source = (root / owner[0]).read_text(encoding="utf-8")
    components = java_record_components(owner_source, owner[1])
    if str(authority["constructorType"]).rsplit(".", 1)[-1] != owner[1]:
        errors.append(f"{setting}: bindingAuthority constructorType does not match its setting owner")
        return errors
    call = java_constructor_component_call(
        (root / source_path).read_text(encoding="utf-8"), source_type, str(authority["method"]),
        str(authority["constructorType"]), components, component,
    )
    if call is None:
        errors.append(f"{setting}: bindingAuthority has no unique constructor-position call")
        return errors
    argument, start, end = call
    pattern = re.compile(
        r'^(integer|whole)\(properties, environment, "([^"]+)", "([^"]+)", '
        rf'defaults\.{re.escape(component)}\(\)\)$'
    )
    parsed = pattern.fullmatch(argument)
    if parsed is None or parsed.group(1) != authority["helper"]:
        errors.append(f"{setting}: constructor component is not a supported direct integer/whole authority call")
        return errors
    helper, property_name, environment_name = parsed.groups()
    expected_accessor = f"defaults.{component}()"
    if (property_name != authority["property"] or environment_name != authority["environment"]
            or authority["defaultAccessor"] != expected_accessor):
        errors.append(f"{setting}: bindingAuthority literals/default accessor do not match the direct call")
    digest = hashlib.sha256(argument.encode("utf-8")).hexdigest()
    if digest != authority["callDigest"]:
        errors.append(f"{setting}: bindingAuthority callDigest has drifted")
    if not isinstance(bindings, list) or sorted(str(value) for value in bindings) != sorted(
            (property_name, environment_name)):
        errors.append(f"{setting}: bindings do not equal the constructor authority pair")
    source = (root / source_path).read_text(encoding="utf-8")
    expected_candidates = (
        (str(authority["propertyCandidateId"]), "property-binding", property_name),
        (str(authority["environmentCandidateId"]), "environment-binding", environment_name),
    )
    component_candidate_ids: dict[str, list[str]] = {}
    for candidate_id, kind, name in expected_candidates:
        candidate = discovered.get(candidate_id)
        entry = entries.get(candidate_id)
        if candidate is None or entry is None or entry.get("setting") != setting:
            errors.append(f"{setting}: binding authority candidate is absent or assigned elsewhere: {candidate_id}")
            continue
        actual_ids = candidate_ids_in_source_span(
            source_path, source, start, end, kind, name, discovered,
        )
        component_candidate_ids[kind] = actual_ids
        if candidate.path != source_path.as_posix() or candidate.kind != kind or candidate.expression != name \
                or actual_ids != [candidate_id]:
            errors.append(f"{setting}: binding candidate is not the literal in its constructor component: {candidate_id}")
    assigned_property_ids = {str(entry["id"]) for entry in property_candidates}
    if assigned_property_ids != {str(authority["propertyCandidateId"])}:
        errors.append(f"{setting}: every property candidate must be the one atomic binding authority")
    assigned_environment_ids = {
        candidate_id for candidate_id in component_candidate_ids.get("environment-binding", [])
        if entries.get(candidate_id, {}).get("setting") == setting
    }
    if assigned_environment_ids != {str(authority["environmentCandidateId"])}:
        errors.append(f"{setting}: constructor binding authority must select exactly one environment candidate")
    resolver = str(authority["resolverAuthority"])
    if not isinstance(resolver_authorities, dict) or resolver not in resolver_authorities:
        errors.append(f"{setting}: bindingAuthority references an absent resolver authority")
    else:
        resolver_contract = resolver_authorities[resolver]
        if not isinstance(resolver_contract, dict) or helper not in {
                resolver_contract.get("integerMethod"), resolver_contract.get("wholeMethod")}:
            errors.append(f"{setting}: binding helper is outside its resolver authority")
        elif resolver_contract.get("path") != source_path.as_posix() \
                or resolver_contract.get("type") != source_type:
            errors.append(f"{setting}: resolver authority must be the binding source type")
    return errors


def binding_authority_errors(root: Path, setting: str, contract: dict[str, object],
                             setting_entries: list[dict[str, object]],
                             entries: dict[str, dict[str, object]],
                             discovered: dict[str, Candidate],
                             resolver_authorities: object) -> list[str]:
    property_candidates = [entry for entry in setting_entries if entry.get("kind") == "property-binding"]
    environment_candidates = [
        entry for entry in setting_entries if entry.get("kind") == "environment-binding"
    ]
    if property_candidates:
        return dual_source_binding_authority_errors(
            root, setting, contract, entries, discovered, resolver_authorities,
        )
    if not environment_candidates:
        return ([] if contract.get("bindingAuthority") is None
                else [f"{setting}: bindingAuthority exists without a binding candidate"])
    if setting in GRAPH_LIMIT_AUTHORITY_BY_SETTING:
        return ([] if contract.get("bindingAuthority") is None
                else [f"{setting}: graph binding belongs to the closed graph family authority"])
    return environment_binding_authority_errors(
        root, setting, contract, setting_entries, entries, discovered, resolver_authorities,
    )


def environment_resolver_group_errors(root: Path,
                                      representatives: dict[str, dict[str, object]],
                                      resolver_authorities: object) -> list[str]:
    """Require one reviewed authority for every component of each supported env-only factory."""
    if not isinstance(resolver_authorities, dict):
        return (["environment-bound settings require a resolverAuthorities object"]
                if any(isinstance(item.get("bindingAuthority"), dict)
                       and item["bindingAuthority"].get("kind") == "java-environment-constructor-v1"
                       for item in representatives.values()) else [])
    groups: dict[str, list[tuple[str, dict[str, object], dict[str, object]]]] = {}
    for setting, contract in representatives.items():
        authority = contract.get("bindingAuthority")
        if isinstance(authority, dict) and authority.get("kind") == "java-environment-constructor-v1":
            groups.setdefault(str(authority.get("resolverAuthority", "")), []).append(
                (setting, contract, authority),
            )
    errors: list[str] = []
    environment_resolvers = {
        str(identifier) for identifier, authority in resolver_authorities.items()
        if isinstance(authority, dict)
        and authority.get("kind") == "java-environment-integer-resolver-v1"
    }
    if set(groups) != environment_resolvers:
        errors.append("environment resolver authorities must be referenced by one exact component set")
    for resolver_id, contracts in groups.items():
        resolver = resolver_authorities.get(resolver_id)
        if not isinstance(resolver, dict):
            continue
        path = Path(str(resolver.get("path", "")))
        type_symbol = str(resolver.get("type", ""))
        if current_source_owner(root, f"{path.as_posix()}#{type_symbol}") is None:
            continue
        source = (root / path).read_text(encoding="utf-8")
        components = java_record_components(source, type_symbol)
        actual = {(item[2].get("componentIndex"), item[2].get("component")) for item in contracts}
        expected = set(enumerate(components))
        if actual != expected or len(contracts) != len(components):
            errors.append(f"resolver authority {resolver_id} does not bijectively cover every record component")
        environments = [str(item[2].get("environment", "")) for item in contracts]
        if len(set(environments)) != len(components):
            errors.append(f"resolver authority {resolver_id} environment bindings are not one-to-one")
        if any(item[2].get("sourceOwner") != item[1].get("owner")
               or item[2].get("method") != resolver.get("factoryMethod")
               for item in contracts):
            errors.append(f"resolver authority {resolver_id} component ownership/factory has drifted")
        test_methods = resolver.get("testMethods", {})
        enumeration = (test_methods.get("bindingEnumeration")
                       if isinstance(test_methods, dict) else None)
        test_path = Path(str(resolver.get("testPath", "")))
        test_type = str(resolver.get("testType", ""))
        test_source = ((root / test_path).read_text(encoding="utf-8")
                       if current_source_owner(root, f"{test_path.as_posix()}#{test_type}") is not None
                       else "")
        if enumeration and java_method_header(test_source, test_type, str(enumeration)) \
                != f"private static Stream<String> {enumeration}()":
            errors.append(
                f"resolver authority {resolver_id} binding enumeration has unsupported factory signature")
        enumerated = (java_direct_stream_string_return(
            test_source, test_type, str(enumeration),
        ) if enumeration else None)
        if enumerated is None or Counter(enumerated) != Counter(environments) \
                or len(enumerated) != len(environments):
            errors.append(
                f"resolver authority {resolver_id} binding enumeration is not one direct exact Stream.of literal list")
    return errors


def default_authority_errors(root: Path, setting: str, contract: dict[str, object],
                             entries: dict[str, dict[str, object]],
                             discovered: dict[str, Candidate]) -> list[str]:
    property_bound = any(entry.get("setting") == setting and entry.get("kind") == "property-binding"
                         for entry in entries.values())
    binding_authority = contract.get("bindingAuthority")
    environment_bound = isinstance(binding_authority, dict) \
        and binding_authority.get("kind") == "java-environment-constructor-v1"
    authority = contract.get("defaultAuthority")
    if authority is None:
        return ([f"{setting}: bound setting requires defaultAuthority"]
                if property_bound or environment_bound else [])
    required = {"owner", "instanceSymbol", "field", "sourceExpression", "candidateIds"}
    if environment_bound:
        required.update({"componentIndex", "evaluatedDefault"})
    if not isinstance(authority, dict) or not required.issubset(authority):
        return [f"{setting}: defaultAuthority requires {', '.join(sorted(required))}"]
    if environment_bound and set(authority) != required:
        return [f"{setting}: environment defaultAuthority requires exactly {', '.join(sorted(required))}"]
    errors: list[str] = []
    owner = str(authority["owner"])
    field = str(authority["field"])
    if owner != contract.get("owner") or field != contract.get("field"):
        errors.append(f"{setting}: defaultAuthority owner/field must match the setting authority")
    resolved = current_source_owner(root, owner)
    if resolved is None or resolved[0].suffix != ".java":
        errors.append(f"{setting}: defaultAuthority must resolve to a tracked Java record")
    else:
        relative, symbol = resolved
        owner_source = (root / relative).read_text(encoding="utf-8")
        actual_span = java_record_default_expression_span(
            owner_source, symbol, str(authority["instanceSymbol"]), field)
        if actual_span is None or normalized(str(authority["sourceExpression"])) != normalized(actual_span[0]):
            errors.append(f"{setting}: defaultAuthority sourceExpression does not match the record component")
        if environment_bound:
            components = java_record_components(owner_source, symbol)
            index = authority["componentIndex"]
            if not isinstance(index, int) or isinstance(index, bool) or not 0 <= index < len(components) \
                    or components[index] != field:
                errors.append(f"{setting}: defaultAuthority componentIndex has drifted")
            evaluated = evaluated_java_default(
                actual_span[0] if actual_span is not None else "",
                isinstance(binding_authority, dict)
                and binding_authority.get("valueTransform") == "duration-seconds",
            )
            declared = authority["evaluatedDefault"]
            if not isinstance(declared, dict) or set(declared) != {"kind", "value"} \
                    or declared != evaluated:
                errors.append(f"{setting}: evaluatedDefault does not match the typed Java expression")
            elif contract.get("default") != str(declared["value"]):
                errors.append(f"{setting}: default must be the canonical evaluated decimal value")
    candidate_ids = authority.get("candidateIds")
    if not isinstance(candidate_ids, list):
        errors.append(f"{setting}: defaultAuthority candidateIds must be an array")
    elif resolved is not None and resolved[0].suffix == ".java" and actual_span is not None:
        expected_ids = candidate_ids_in_source_span(
            resolved[0], owner_source, actual_span[1], actual_span[2],
            "fixed-declaration", str(authority["instanceSymbol"]), discovered,
        )
        if [str(candidate_id) for candidate_id in candidate_ids] != expected_ids or any(
                entries.get(candidate_id, {}).get("setting") != setting for candidate_id in expected_ids):
            errors.append(f"{setting}: defaultAuthority candidateIds are not the exact initializer atom multiset")
        reference = authority.get("constantReferenceAuthority")
        if expected_ids and reference is not None:
            errors.append(f"{setting}: direct default atoms cannot also use constantReferenceAuthority")
        if not expected_ids and reference is None:
            errors.append(f"{setting}: indirect default requires constantReferenceAuthority")
        if not expected_ids and reference is not None:
            errors.extend(constant_reference_authority_errors(
                root, setting, contract, authority, owner_source, discovered, entries))
    return errors


def constant_reference_authority_errors(root: Path, setting: str, contract: dict[str, object],
                                        default_authority: dict[str, object], owner_source: str,
                                        discovered: dict[str, Candidate],
                                        entries: dict[str, dict[str, object]]) -> list[str]:
    """Verify a bounded ordered static-final reference chain ending in fixed atoms."""
    reference = default_authority.get("constantReferenceAuthority")
    if not isinstance(reference, dict) or reference.get("kind") != "java-static-final-chain-v1" \
            or not isinstance(reference.get("hops"), list) or not 1 <= len(reference["hops"]) <= 4:
        return [f"{setting}: constantReferenceAuthority requires a bounded static-final hop chain"]
    previous_owner = str(default_authority["owner"])
    previous_source = owner_source
    previous_expression = str(default_authority["sourceExpression"])
    errors: list[str] = []
    terminal_ids: list[str] = []
    for index, hop in enumerate(reference["hops"]):
        required = ("owner", "field", "sourceExpression", "initializerDigest", "candidateIds")
        if not isinstance(hop, dict) or any(key not in hop for key in required) \
                or not isinstance(hop.get("candidateIds"), list):
            errors.append(f"{setting}: constant reference hop {index} is incomplete")
            return errors
        hop_owner = str(hop["owner"])
        if "#" not in hop_owner:
            errors.append(f"{setting}: constant reference hop {index} owner must be path#type")
            return errors
        resolved = current_source_owner(root, hop_owner)
        if resolved is None or resolved[0].suffix != ".java":
            errors.append(f"{setting}: constant reference hop {index} has no tracked Java owner")
            return errors
        relative, symbol = resolved
        hop_source = (root / relative).read_text(encoding="utf-8")
        field = str(hop["field"])
        if not java_constant_reference_matches(
                previous_source, previous_owner, previous_expression, hop_owner, field, hop_source):
            errors.append(f"{setting}: constant reference hop {index} does not match the preceding initializer")
        initializer = java_static_final_initializer(hop_source, symbol, field)
        if initializer is None:
            errors.append(f"{setting}: constant reference hop {index} is not one direct static-final field")
            return errors
        expression, start, end = initializer
        if normalized(str(hop["sourceExpression"])) != normalized(expression):
            errors.append(f"{setting}: constant reference hop {index} initializer has drifted")
        digest = hashlib.sha256(normalized(expression).encode("utf-8")).hexdigest()
        if hop["initializerDigest"] != digest:
            errors.append(f"{setting}: constant reference hop {index} initializer digest has drifted")
        expected_ids = candidate_ids_in_source_span(
            relative, hop_source, start, end, "fixed-declaration", field, discovered)
        actual_ids = [str(candidate_id) for candidate_id in hop["candidateIds"]]
        if actual_ids != expected_ids or any(
                entries.get(candidate_id, {}).get("setting") != setting for candidate_id in expected_ids):
            errors.append(f"{setting}: constant reference hop {index} atom multiset has drifted")
        if index < len(reference["hops"]) - 1 and expected_ids:
            errors.append(f"{setting}: nonterminal constant reference hop contains fixed atoms")
        terminal_ids = expected_ids
        previous_owner = hop_owner
        previous_source = hop_source
        previous_expression = expression
    default_evidence = contract.get("defaultEvidence")
    if not terminal_ids or not isinstance(default_evidence, list) \
            or [str(candidate_id) for candidate_id in default_evidence] != terminal_ids:
        errors.append(f"{setting}: defaultEvidence must equal the terminal constant atom multiset")
    return errors


def schema_evidence_errors(setting: str, contract: dict[str, object],
                           entries: dict[str, dict[str, object]],
                           discovered: dict[str, Candidate],
                           evidence_records: dict[str, object]) -> list[str]:
    schema = contract.get("schemaEvidence")
    if schema is None:
        return []
    required = ("candidateId", "path", "pointer", "reference", "required")
    if not isinstance(schema, dict) or any(key not in schema for key in required):
        return [f"{setting}: schemaEvidence requires {', '.join(required)}"]
    errors: list[str] = []
    candidate_id = str(schema["candidateId"])
    candidate = discovered.get(candidate_id)
    inventory_entry = entries.get(candidate_id)
    if candidate is None or inventory_entry is None:
        return [f"{setting}: schemaEvidence candidate is not current: {candidate_id}"]
    if candidate.kind != "schema-reference-binding" or candidate.path != schema["path"] \
            or candidate.role != schema["pointer"] or candidate.expression != schema["reference"]:
        errors.append(f"{setting}: schemaEvidence does not match its reference candidate")
    if inventory_entry.get("setting") != setting:
        errors.append(f"{setting}: schemaEvidence candidate is assigned to another setting")
    try:
        resolved = json.loads(str(evidence_records.get(candidate.evidence_digest, "")))
    except json.JSONDecodeError:
        resolved = {}
    if resolved.get("resolutionError") is not None or resolved.get("resolved") is None:
        errors.append(f"{setting}: schemaEvidence reference is not a resolved local edge")
    if resolved.get("pointer") != schema["pointer"] or resolved.get("reference") != schema["reference"] \
            or resolved.get("required") is not schema["required"]:
        errors.append(f"{setting}: schemaEvidence pointer/reference/required metadata has drifted")
    return errors


def graph_platform_coverage_errors(root: Path, setting: str, contract: dict[str, object],
                                   entries: dict[str, dict[str, object]],
                                   discovered: dict[str, Candidate],
                                   evidence_records: dict[str, object],
                                   tracked_paths: set[Path]) -> list[str]:
    """Verify graph carrier coverage against exact current candidates and drift-test bodies."""
    if not setting.startswith("graph."):
        return []
    coverage = contract.get("coverageEvidence")
    candidate_fields = {
        "composeCandidateIds": ("compose.yaml", "environment-binding", None, 2),
        "helmValueCandidateIds": ("deploy/helm/ravenroot/values.yaml", "configuration-scalar", '""', 1),
        "helmTemplateCandidateIds": (
            "deploy/helm/ravenroot/templates/deployment.yaml", "environment-binding", None, 1),
        "helmSchemaEnvironmentCandidateIds": (
            "deploy/helm/ravenroot/values.schema.json", "environment-binding", None, 1),
        "helmSchemaReferenceCandidateIds": (
            "deploy/helm/ravenroot/values.schema.json", "schema-reference-binding",
            "#/definitions/graphBlank", 1),
        "rawKubernetesCandidateIds": (
            "deploy/kubernetes/ravenroot.yaml", "environment-binding", None, 1),
    }
    required = ("kind", "environment", "helmPath", "contractTestPath", "contractTestDigest",
                "shellTestPath", "shellTestDigest", *candidate_fields)
    if not isinstance(coverage, dict) or any(key not in coverage for key in required):
        return [f"{setting}: graph coverageEvidence requires {', '.join(required)}"]
    errors: list[str] = []
    if coverage["kind"] != "graph-platform-carriers-v1":
        errors.append(f"{setting}: unsupported graph coverageEvidence kind")
    environment = str(coverage["environment"])
    bindings = contract.get("bindings", [])
    if not isinstance(bindings, list) or bindings != [environment]:
        errors.append(f"{setting}: graph coverage environment must be the setting's sole binding")
    helm_leaf = str(coverage["helmPath"]).rsplit(".", 1)[-1]
    for field, (path, kind, fixed_expression, count) in candidate_fields.items():
        identifiers = coverage[field]
        if not isinstance(identifiers, list) or len(identifiers) != count \
                or len(set(str(identifier) for identifier in identifiers)) != count:
            errors.append(f"{setting}: {field} must contain {count} unique candidate ids")
            continue
        for identifier in identifiers:
            candidate = discovered.get(str(identifier))
            entry = entries.get(str(identifier))
            expected_expression = fixed_expression if fixed_expression is not None else environment
            if candidate is None or entry is None or entry.get("setting") != setting:
                errors.append(f"{setting}: {field} candidate is absent or assigned elsewhere: {identifier}")
                continue
            if candidate.path != path or candidate.kind != kind or candidate.expression != expected_expression:
                errors.append(f"{setting}: {field} candidate does not match its carrier: {identifier}")
            if field == "helmValueCandidateIds" and candidate.role != helm_leaf:
                errors.append(f"{setting}: Helm value candidate does not match helmPath: {identifier}")
            if field == "helmSchemaReferenceCandidateIds":
                try:
                    schema = json.loads(str(evidence_records.get(candidate.evidence_digest, "")))
                except json.JSONDecodeError:
                    schema = {}
                if schema.get("required") is not True or schema.get("resolutionError") is not None:
                    errors.append(f"{setting}: Helm schema reference must be required and locally resolved")
    for path_field, digest_field in (("contractTestPath", "contractTestDigest"),
                                     ("shellTestPath", "shellTestDigest")):
        relative = Path(str(coverage[path_field]))
        if relative.is_absolute() or ".." in relative.parts or relative not in tracked_paths:
            errors.append(f"{setting}: {path_field} must be a tracked in-repository file")
            continue
        actual_digest = hashlib.sha256((root / relative).read_bytes()).hexdigest()
        if actual_digest != coverage[digest_field]:
            errors.append(f"{setting}: {path_field} body digest has drifted")
    return errors


ROUTE_TABLE_AUTHORITY_ID = "route-table-all-v1"
ROUTE_HUMAN_TASK_SCHEMAS_BODY_DIGEST = \
    "2efd14c6ecb0e3f947800cbfe9a8af3ce591a5ac95e0ba05f6480d0564cc8d69"
ROUTE_EXECUTION_EVENT_SCHEMAS_BODY_DIGEST = \
    "f4b91c57c74f5f1ee76b9aca4a3edd52a2cce10a0f67efe3d4cacd270dd56f6b"
ROUTE_EVENT_STREAM_PUBLICATION_TEST_BODY_DIGEST = \
    "fd78aef6ecb775a1552fcfad1556dc02f4465345ac9d5837cb5521734a41a385"
ROUTE_OPENAPI_GENERATE_BODY_DIGEST = \
    "3c7a6a8dbebc41ae82f86ca79f1e3b7ccf29e74aeffedb692b5a365e1f2f7648"
ROUTE_OPENAPI_SUCCESS_RESPONSE_BODY_DIGEST = \
    "452562a1d0667dfdf51510a0d1eef559d251443b3b0dfaa4c8af543bd3288ce8"
ROUTE_TABLE_PATH = Path(
    "ravenroot/ravenroot-server/src/main/java/ai/ravenroot/server/spec/RouteTable.java")
ROUTE_DESCRIPTOR_PATH = Path(
    "ravenroot/ravenroot-server/src/main/java/ai/ravenroot/server/spec/RouteDescriptor.java")
OPENAPI_GENERATOR_PATH = Path(
    "ravenroot/ravenroot-server/src/main/java/ai/ravenroot/server/spec/OpenApiSpecGenerator.java")
ROUTE_TABLE_TEST_PATH = Path(
    "ravenroot/ravenroot-server/src/test/java/ai/ravenroot/server/spec/RouteTableSpecServerAgreementTest.java")
STABLE_EDGE_ID_PATH = Path(
    "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/application/StableEdgeId.java")
EDGE_WIRE_BUDGET_PATH = Path(
    "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/application/EdgeTraversalWireBudget.java")
STABLE_EDGE_TEST_PATH = Path(
    "ravenroot/ravenroot-application-api/src/test/java/ai/ravenroot/api/application/StableEdgeIdContractTest.java")
STABLE_EDGE_WIRE_TEST_PATH = Path(
    "ravenroot/ravenroot-server/src/test/java/ai/ravenroot/server/StableEdgeIdWireContractTest.java")
ROUTE_BOUND_CANDIDATES = {
    "oc-0b67657cac8e5b904054": ("StableEdgeId.MAX_UTF8_BYTES",),
    "oc-7ab123337eeb18906fc2":
        ("EdgeTraversalWireBudget.MAX_AUXILIARY_ESCAPED_VALUE_BYTES",),
    "oc-7bab59779a16e10b10d7": ("StableEdgeId.SSE_FRAME_MAX_BYTES",),
    "oc-418656067bc7b4ad0c5c": (
        "StableEdgeId.MAX_UTF8_BYTES",
        "EdgeTraversalWireBudget.MAX_AUXILIARY_ESCAPED_VALUE_BYTES",
    ),
    "oc-8eed875577d7d07c6447": ("StableEdgeId.SSE_FRAME_MAX_BYTES",),
}
ROUTE_BOUND_PATHS = {
    "oc-0b67657cac8e5b904054": "/v1/events",
    "oc-7ab123337eeb18906fc2": "/v1/events",
    "oc-7bab59779a16e10b10d7": "/v1/events",
    "oc-418656067bc7b4ad0c5c": "/v1/events/recent",
    "oc-8eed875577d7d07c6447": "/v1/events/recent",
}
EXECUTION_RUNTIME_FAMILY_ID = "execution-runtime-engine-four-v1"
EXECUTION_RUNTIME_SOURCE_FINAL_REVISION = "63661709c127bdc206857343382d3a1d4d47274a"
EXECUTION_RUNTIME_CURRENT_SOURCE_REVISION = "b2adc1f67edb5564aa891a99c12dde6e4228f727"
EXECUTION_RUNTIME_CARRIER_REKEY_REVISION = "b2adc1f67edb5564aa891a99c12dde6e4228f727"
EXECUTION_RUNTIME_CARRIER_REKEY_PARENT = "91e2277c3baff0147fae152852520a64fac175be"
EXECUTION_RUNTIME_CLAIM_BASE_REVISION = "f4be893d5b34742460a6b4658867aac69b558015"
EXECUTION_RUNTIME_CONFIGURATION_PATH = Path(
    "ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/ExecutionRuntimeConfiguration.java")
EXECUTION_ENGINE_POLICY_PATH = Path(
    "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/execution/ExecutionEnginePolicy.java")
TERMINAL_NODE_HISTORY_PATH = Path(
    "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/execution/TerminalNodeHistory.java")
EXECUTION_STORE_BOOTSTRAP_PATH = Path(
    "ravenroot/ravenroot-server/src/main/java/ai/ravenroot/server/persistence/ExecutionStoreBootstrap.java")
EXECUTION_RUNTIME_FAMILY_CANDIDATE_PATHS = frozenset({
    EXECUTION_RUNTIME_CONFIGURATION_PATH,
    EXECUTION_ENGINE_POLICY_PATH,
    TERMINAL_NODE_HISTORY_PATH,
    Path("compose.yaml"),
    Path("deploy/helm/ravenroot/values.yaml"),
    Path("deploy/helm/ravenroot/templates/deployment.yaml"),
    Path("deploy/helm/ravenroot/values.schema.json"),
    Path("deploy/kubernetes/ravenroot.yaml"),
    Path("scripts/publish_environment_reference.py"),
})
EXECUTION_RUNTIME_SOURCE_FINAL_PATHS = frozenset({
    EXECUTION_RUNTIME_CONFIGURATION_PATH,
    EXECUTION_ENGINE_POLICY_PATH,
    TERMINAL_NODE_HISTORY_PATH,
    Path("ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/execution/ExecutionEngine.java"),
    Path("ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/execution/ExecutionEngineProvider.java"),
    Path("ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/execution/ExecutionEngines.java"),
    Path("ravenroot/ravenroot-akka/src/main/java/ai/ravenroot/akka/AkkaExecutionEngine.java"),
    Path("ravenroot/ravenroot-akka/src/main/java/ai/ravenroot/akka/AkkaExecutionEngineProvider.java"),
    Path("ravenroot/ravenroot-pekko/src/main/java/ai/ravenroot/pekko/PekkoExecutionEngine.java"),
    Path("ravenroot/ravenroot-pekko/src/main/java/ai/ravenroot/pekko/PekkoExecutionEngineProvider.java"),
    Path("ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/manifest/ExecutionManifestResolver.java"),
    Path("ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/GraphRunner.java"),
    Path("ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/DefaultRavenrootApplication.java"),
    Path("ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/runtime/DefaultGraphDeployment.java"),
    Path("ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/pause/DurableExecutionPauseService.java"),
    Path("ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/approval/PinnedGraphToolApprovalContinuationExecutor.java"),
    Path("ravenroot/ravenroot-core/src/main/java/ai/ravenroot/core/humantask/PinnedGraphHumanTaskContinuationExecutor.java"),
    Path("compose.yaml"),
    Path("deploy/helm/ravenroot/templates/_helpers.tpl"),
    Path("deploy/helm/ravenroot/templates/deployment.yaml"),
    Path("deploy/helm/ravenroot/values.schema.json"),
    Path("deploy/helm/ravenroot/values.yaml"),
    Path("deploy/kubernetes/ravenroot.yaml"),
    Path("docs/reference/configuration.md"),
    Path("scripts/publish_environment_reference.py"),
    EXECUTION_STORE_BOOTSTRAP_PATH,
})
EXECUTION_RUNTIME_SETTINGS = (
    {
        "setting": "execution-engine.actor-node-stash-capacity",
        "environment": "RAVENROOT_ENGINE_MAX_STASHED_COMMANDS_PER_NODE",
        "component": "maxStashedCommandsPerNode", "helm": "maxStashedCommandsPerNode",
        "default": "10000", "maximum": 10000,
        "owner": f"{EXECUTION_ENGINE_POLICY_PATH.as_posix()}#ExecutionEnginePolicy",
        "field": "maxStashedCommandsPerNode", "defaultPath": EXECUTION_ENGINE_POLICY_PATH,
        "defaultExpression": "10_000", "durable": True,
    },
    {
        "setting": "execution-engine.actor-lifecycle-step-bound",
        "environment": "RAVENROOT_ENGINE_LIFECYCLE_STEP_SECONDS",
        "component": "lifecycleStepBound", "helm": "lifecycleStepSeconds",
        "default": "10 seconds", "maximum": 10,
        "owner": f"{EXECUTION_ENGINE_POLICY_PATH.as_posix()}#ExecutionEnginePolicy",
        "field": "lifecycleStepBound", "defaultPath": EXECUTION_ENGINE_POLICY_PATH,
        "defaultExpression": "10", "durable": True,
    },
    {
        "setting": "execution-engine.terminal-node-history-capacity",
        "environment": "RAVENROOT_ENGINE_TERMINAL_HISTORY_CAPACITY",
        "component": "terminalNodeHistoryCapacity", "helm": "terminalHistoryCapacity",
        "default": "1024", "maximum": 1024,
        "owner": f"{EXECUTION_ENGINE_POLICY_PATH.as_posix()}#ExecutionEnginePolicy",
        "field": "terminalNodeHistoryCapacity", "defaultPath": TERMINAL_NODE_HISTORY_PATH,
        "defaultExpression": "1024", "durable": False,
    },
    {
        "setting": "graph-runner.shutdown-step-bound",
        "environment": "RAVENROOT_GRAPH_RUNNER_SHUTDOWN_STEP_SECONDS",
        "component": "runnerShutdownStepBound", "helm": "runnerShutdownStepSeconds",
        "default": "10 seconds", "maximum": 10,
        "owner": f"{EXECUTION_RUNTIME_CONFIGURATION_PATH.as_posix()}#ExecutionRuntimeConfiguration",
        "field": "runnerShutdownStepBound", "defaultPath": EXECUTION_RUNTIME_CONFIGURATION_PATH,
        "defaultExpression": "10", "durable": False,
    },
)
EXECUTION_RUNTIME_STAGES = (
    ("b6dd1b756c57248e98696de6b0a256e5abdd7f12", "policy/config"),
    ("5582e16ea063eca417dcd05f60f15a4c0e491e82", "SPI"),
    ("465e70a25caaf1f31f81417e030d903a2d52b551", "adapters and manifest"),
    ("8b977d8c798e3937cc5535d402aba3a64586c61e", "runner and five owners"),
    ("81e8b9bfe2b36f1e85ebce730c462b8cb4ef1ddf", "server and CLI composition"),
    ("da0ed207dfe9823a8e40fdb16f32d65fcb647e77", "carriers/docs"),
    (EXECUTION_RUNTIME_SOURCE_FINAL_REVISION, "terminal policy constant alias"),
)
EXECUTION_RUNTIME_CLI_PATH = Path(
    "ravenroot/ravenroot-cli/src/main/java/ai/ravenroot/cli/RavenrootCliMain.java")
EXECUTION_RUNTIME_SERVER_PATH = Path(
    "ravenroot/ravenroot-server/src/main/java/ai/ravenroot/server/RavenrootServerMain.java")
EXECUTION_RUNTIME_TEST_AUTHORITIES = (
    ("ravenroot/ravenroot-application-api/src/test/java/ai/ravenroot/api/execution/ExecutionEnginePolicyTest.java",
     "ExecutionEnginePolicyTest", "compatibilityFingerprintHasOneStableOwnerAndExcludesTerminalHistory"),
    ("ravenroot/ravenroot-application-api/src/test/java/ai/ravenroot/api/execution/ExecutionEngineProviderPolicyTest.java",
     "ExecutionEngineProviderPolicyTest", "historicalProviderDelegatesOnlyTheExactFrozenLegacyPolicy"),
    ("ravenroot/ravenroot-akka/src/test/java/ai/ravenroot/akka/AkkaExecutionEnginePolicyTest.java",
     "AkkaExecutionEnginePolicyTest", "conversionPreservesOneNanosecondAndSaturatesBeforeOverflow"),
    ("ravenroot/ravenroot-pekko/src/test/java/ai/ravenroot/pekko/PekkoExecutionEnginePolicyTest.java",
     "PekkoExecutionEnginePolicyTest", "conversionPreservesOneNanosecondAndSaturatesBeforeOverflow"),
    ("ravenroot/ravenroot-core/src/test/java/ai/ravenroot/core/runtime/ExecutionRuntimeConfigurationTest.java",
     "ExecutionRuntimeConfigurationTest", "eachBindingMapsToItsIndependentPolicyComponent"),
    ("ravenroot/ravenroot-core/src/test/java/ai/ravenroot/core/runtime/GraphRunnerShutdownTest.java",
     "GraphRunnerShutdownTest", "convertsEveryPositiveShutdownDurationWithoutLosingSubMillisecondValuesOrOverflowing"),
    ("ravenroot/ravenroot-server/src/test/java/ai/ravenroot/server/RavenrootServerMainLifecycleTest.java",
     "RavenrootServerMainLifecycleTest", "oneResolvedExecutionRuntimeReachesEveryServerExecutionConsumer"),
    ("ravenroot/ravenroot-cli/src/test/java/ai/ravenroot/cli/RavenrootCliMainExecutionRuntimeConfigurationTest.java",
     "RavenrootCliMainExecutionRuntimeConfigurationTest", "localCompositionProjectsOneTupleIntoTheEngineAndActualRunnerShutdown"),
    ("ravenroot/ravenroot-server/src/test/java/ai/ravenroot/server/persistence/ExecutionStoreBootstrapTest.java",
     "ExecutionStoreBootstrapTest", "resolvedBusyTimeoutReachesAllThreeOwnedConnections"),
)
EXECUTION_RUNTIME_TRANSITIONS = (
    ("oc-48bca2478a170c1b31cf", "execution-engine.actor-node-stash-capacity", "baseline-duplicate-atom", "6c81c82769749773df85a17424514a592a24ac40", "465e70a25caaf1f31f81417e030d903a2d52b551"),
    ("oc-82d21c2d5ca718e22886", "execution-engine.actor-node-stash-capacity", "baseline-duplicate-atom", "6c81c82769749773df85a17424514a592a24ac40", "465e70a25caaf1f31f81417e030d903a2d52b551"),
    ("oc-aa41b9b80dfbc15a16d8", "execution-engine.actor-node-stash-capacity", "feature-transient-atom", "251b60daab99a549c51d88e21c829d641329d123", "8b977d8c798e3937cc5535d402aba3a64586c61e"),
    ("oc-9b1bde733ded46e394b1", "execution-engine.actor-lifecycle-step-bound", "baseline-duplicate-atom", "6c81c82769749773df85a17424514a592a24ac40", "465e70a25caaf1f31f81417e030d903a2d52b551"),
    ("oc-3ba88abd98e9a79ff3d4", "execution-engine.actor-lifecycle-step-bound", "baseline-duplicate-atom", "6c81c82769749773df85a17424514a592a24ac40", "465e70a25caaf1f31f81417e030d903a2d52b551"),
    ("oc-ba35660d0c978073ce87", "execution-engine.actor-lifecycle-step-bound", "feature-transient-atom", "251b60daab99a549c51d88e21c829d641329d123", "8b977d8c798e3937cc5535d402aba3a64586c61e"),
    ("oc-20800ee826c00923e97e", "execution-engine.terminal-node-history-capacity", "feature-transient-atom", "251b60daab99a549c51d88e21c829d641329d123", "8b977d8c798e3937cc5535d402aba3a64586c61e"),
    ("oc-01aa43acbebb78d6857b", "graph-runner.shutdown-step-bound", "feature-transient-atom", "251b60daab99a549c51d88e21c829d641329d123", "8b977d8c798e3937cc5535d402aba3a64586c61e"),
    ("oc-1b497b651ab803655991", "graph-runner.shutdown-step-bound", "baseline-authority-relocation", "251b60daab99a549c51d88e21c829d641329d123", "8b977d8c798e3937cc5535d402aba3a64586c61e"),
    ("oc-1eed65896f5bba1182c6", "execution-engine.terminal-node-history-capacity", "feature-transient-atom", "c9534676f60f03d0433549d8d00baf6fc3cbdf25", "63661709c127bdc206857343382d3a1d4d47274a"),
    ("oc-95929fb19c5a85e01179>oc-5c95bb22070332a3062f", "execution-engine.actor-node-stash-capacity", "feature-transient-identity-rekey", "c9534676f60f03d0433549d8d00baf6fc3cbdf25", "63661709c127bdc206857343382d3a1d4d47274a"),
    ("oc-36be8f29c4600f158d7c>oc-58a624fec85532ae75ff", "execution-engine.actor-lifecycle-step-bound", "feature-transient-identity-rekey", "c9534676f60f03d0433549d8d00baf6fc3cbdf25", "63661709c127bdc206857343382d3a1d4d47274a"),
)
EXECUTION_RUNTIME_CONSOLIDATIONS = (
    ("execution-engine.actor-node-stash-capacity",
     ("oc-48bca2478a170c1b31cf", "oc-82d21c2d5ca718e22886"), 1),
    ("execution-engine.actor-lifecycle-step-bound",
     ("oc-3ba88abd98e9a79ff3d4", "oc-9b1bde733ded46e394b1"), 1),
)
EXECUTION_RUNTIME_CURRENT_CARRIER_REKEYS = (
    ("execution-engine.actor-node-stash-capacity", "oc-c5204a7b3b5efc758558",
     "oc-9ccb2bccdc17fc78fd3d", "2f4a6bf2be99cb0f9145af87c9c14b35c7297fd4876305930f3ffe2bb16b2554"),
    ("execution-engine.actor-lifecycle-step-bound", "oc-f4e7f5616ddcf5af551a",
     "oc-98345297c4f565a376d0", "4feb0f47eb0fa8627acbe9e3a0cc8d9b4e1a3d7f201b063eaad4b8c2e1a794ba"),
    ("execution-engine.terminal-node-history-capacity", "oc-7dfa4728faf45b9499d4",
     "oc-13f971b19670397787ab", "a56fa5e18c5b7cba4b34340916441c7ca4a41d9eb3f1b86115b8e16eb5b0b9da"),
    ("graph-runner.shutdown-step-bound", "oc-04fde683748febc56038",
     "oc-c5293a23d9389592cd69", "ac3972a04125fef2b3fdc2990027d616296a3f99d7c283e0f1e3c6d3adac5b58"),
)
HUMAN_TASK_LEGACY_CONSOLIDATIONS = (
    ("oc-f01e525c4ff5b6f33249", "human-task.default-response-bytes"),
    ("oc-d40d2ea5bfc7803d6e82", "human-task.max-response-bytes"),
    ("oc-4844edec942e6a8b464d", "human-task.default-escalation-seconds"),
    ("oc-5dfc25380697ad06798f", "human-task.max-escalation-seconds"),
    ("oc-1d2b767994ede099b3c3", "human-task.default-expiry-seconds"),
    ("oc-13b0a967d1d36e28730b", "human-task.max-expiry-seconds"),
    ("oc-c3ea195fb165360a4828", "human-task.max-title-bytes"),
    ("oc-24c8e019785e1de2bc91", "human-task.max-description-bytes"),
    ("oc-401a4ab6635b0d9514c1", "human-task.max-response-schema-bytes"),
    ("oc-3f2fb897eedcf9c577c9", "human-task.max-authorization-tokens"),
    ("oc-ccb69cc8780301b73e95", "human-task.max-authorization-token-bytes"),
    ("oc-c9a0a2c5318c61bea479", "human-task.max-decision-body-bytes"),
    ("oc-8f67b44960c639030c00", "human-task.default-page-size"),
    ("oc-cd38a8e272108ae06209", "human-task.max-page-size"),
    ("oc-e1bcd51c2967d5211bd3", "human-task.response-max-depth"),
    ("oc-616f8cc86912d14abe00", "human-task.response-max-collection-size"),
    ("oc-9589010b7bb26bcb3c6a", "human-task.response-max-value-count"),
    ("oc-ddda07e21ca51c2d0064", "human-task.response-max-text-length"),
    ("oc-2ac60d58022213773715", "human-task.response-max-key-length"),
    ("oc-c925180c0c9029f36005", "human-task.write-attempts"),
    ("oc-0d5f8b5da9c4a272aba5", "human-task.max-confirmation-prompt-bytes"),
    ("oc-1645a4edb3a288cd04f3", "human-task.max-confirmation-action-label-bytes"),
    ("oc-bc749046cd358114d4cf", "human-task.max-decision-comment-bytes"),
    ("oc-570a24e3b58118350d41", "human-task.attention-poll-millis"),
    ("oc-d62da50d672cec9a003f", "human-task.attention-poll-backoff-max-millis"),
    ("oc-3414cbc2d6051a7c7307", "human-task.default-attention-page-size"),
    ("oc-892a803718d418e181cb", "human-task.max-attention-page-size"),
)
ASSISTANT_CONFIGURATION_PATH = Path(
    "ravenroot/ravenroot-server/src/main/java/ai/ravenroot/server/assistant/AssistantConfiguration.java")
ASSISTANT_CONFIGURATION_TEST_PATH = Path(
    "ravenroot/ravenroot-server/src/test/java/ai/ravenroot/server/assistant/AssistantConfigurationTest.java")
ASSISTANT_SERVICE_PATH = Path(
    "ravenroot/ravenroot-server/src/main/java/ai/ravenroot/server/assistant/AssistantService.java")
ASSISTANT_SERVICE_TEST_PATH = Path(
    "ravenroot/ravenroot-server/src/test/java/ai/ravenroot/server/assistant/AssistantGraphProposalTest.java")
ASSISTANT_PLATFORM_TEST_PATH = Path("scripts/tests/test_assistant_platform_configuration.sh")
ASSISTANT_LIMIT_FAMILY_ID = "assistant-operational-limits-v1"
ASSISTANT_LIMIT_COMPONENTS = (
    "enabled", "providerId", "endpoint", "model", "credential", "egressPolicy", "timeout",
    "maxOutputTokens", "maxToolIterations", "credentialSource", "allowLocalHttp",
)
ASSISTANT_LIMIT_SETTINGS = (
    {
        "setting": "assistant.max-output-tokens", "component": "maxOutputTokens",
        "componentIndex": 7, "environmentSymbol": "MAX_OUTPUT_TOKENS_VARIABLE",
        "environment": "RAVENROOT_ASSISTANT_MAX_OUTPUT_TOKENS",
        "defaultSymbol": "DEFAULT_MAX_OUTPUT_TOKENS", "defaultValue": 16_000,
        "helmField": "maxOutputTokens",
    },
    {
        "setting": "assistant.max-tool-iterations", "component": "maxToolIterations",
        "componentIndex": 8, "environmentSymbol": "MAX_TOOL_ITERATIONS_VARIABLE",
        "environment": "RAVENROOT_ASSISTANT_MAX_TOOL_ITERATIONS",
        "defaultSymbol": "DEFAULT_MAX_TOOL_ITERATIONS", "defaultValue": 8,
        "helmField": "maxToolIterations",
    },
)
ASSISTANT_CARRIER_PATHS = {
    "compose": frozenset({"compose.yaml"}),
    "deploymentExamples": frozenset({"docs/examples/assistant/compose.override.yaml"}),
    "helm": frozenset({
        "deploy/helm/ravenroot/values.yaml", "deploy/helm/ravenroot/values.schema.json",
        "deploy/helm/ravenroot/templates/deployment.yaml",
    }),
    "rawKubernetes": frozenset({"deploy/kubernetes/ravenroot.yaml"}),
}


def java_source_candidates(relative: Path, source: str) -> tuple[tuple[int, Candidate], ...]:
    """Reproduce stable candidate IDs and retain offsets for one Java source."""
    provisional = [
        (relative.as_posix(), line_number(source, offset), symbol, kind, role, expression,
         evidence, "java", False, offset)
        for offset, symbol, kind, role, expression, evidence
        in code_candidates(relative, source, "java")
    ]
    occurrences: Counter[tuple[str, str, str, str, str]] = Counter()
    result: list[tuple[int, Candidate]] = []
    for row in sorted(provisional, key=lambda item: item[:-1]):
        path, line, symbol, kind, role, expression, evidence, surface_name, fixture, offset = row
        key = (path, symbol, kind, role, expression)
        occurrence = occurrences[key]
        occurrences[key] += 1
        evidence_digest = hashlib.sha256(evidence.encode("utf-8")).hexdigest()
        material = "\0".join(
            (path, symbol, kind, role, expression, evidence_digest, str(occurrence)))
        candidate = Candidate(
            "oc-" + hashlib.sha256(material.encode("utf-8")).hexdigest()[:20],
            path, line, symbol, kind, role, expression,
            hashlib.sha256(expression.encode("utf-8")).hexdigest(), evidence,
            evidence_digest, surface_name, fixture,
        )
        result.append((offset, candidate))
    return tuple(result)


def java_string_value(expression: str) -> str | None:
    """Decode the bounded Java string-literal subset shared with JSON escaping."""
    try:
        value = json.loads(expression)
    except json.JSONDecodeError:
        return None
    return value if isinstance(value, str) else None


def java_literal_concatenation(expression: str) -> tuple[str, tuple[tuple[int, int], ...]] | None:
    """Read one expression made only from Java double-quoted literals and plus operators."""
    token = re.compile(r'"(?:\\.|[^"\\])*"')
    cursor = 0
    values: list[str] = []
    spans: list[tuple[int, int]] = []
    for match in token.finditer(expression):
        separator = expression[cursor:match.start()]
        if values:
            if re.fullmatch(r"\s*\+\s*", separator) is None:
                return None
        elif separator.strip():
            return None
        value = java_string_value(match.group())
        if value is None:
            return None
        values.append(value)
        spans.append(match.span())
        cursor = match.end()
    if not values or expression[cursor:].strip():
        return None
    return "".join(values), tuple(spans)


def direct_factory_arguments(expression: str, factory: str) \
        -> tuple[tuple[str, int, int], ...] | None:
    """Return arguments of one expression that is exactly `factory(...)`."""
    code = strip_c_comments_and_literals(expression)
    match = re.match(rf"\s*{re.escape(factory)}\s*\(", code)
    if match is None:
        return None
    opening = code.find("(", match.start())
    parsed = split_java_arguments(expression, code, opening)
    if parsed is None or code[parsed[1] + 1:].strip():
        return None
    return tuple(parsed[0])


def route_table_descriptors(source: str) -> tuple[tuple[tuple[str, int, int], ...], ...] | None:
    """Parse the direct RouteTable.ALL List.of initializer and its nine-argument descriptors."""
    span = java_type_span(source, "RouteTable")
    if span is None or java_package(source) != "ai.ravenroot.server.spec" \
            or not exact_import_identity(source, "java.util.List") \
            or not exact_import_identity(source, "java.util.Set") \
            or not same_package_type_identity(
                source, "RouteTable", "ai.ravenroot.server.spec.RouteDescriptor") \
            or not java_has_no_simple_name_shadow(source, "RouteTable", {"List", "Set"}):
        return None
    base, limit = span
    actual = source[base:limit]
    code = strip_c_comments_and_literals(source)[base:limit]
    depths = java_brace_depths(code)
    declarations = [
        match for match in re.finditer(
            r"\bpublic\s+static\s+final\s+List\s*<\s*RouteDescriptor\s*>\s+"
            r"ALL\s*=\s*List\s*\.\s*of\s*\(", code)
        if depths[match.start()] == 1
    ]
    if len(declarations) != 1:
        return None
    opening = code.find("(", declarations[0].start())
    parsed = split_java_arguments(actual, code, opening)
    if parsed is None or re.match(r"\s*;", code[parsed[1] + 1:]) is None:
        return None
    descriptors: list[tuple[tuple[str, int, int], ...]] = []
    for _descriptor, start, end in parsed[0]:
        descriptor = actual[start:end]
        descriptor_code = strip_c_comments_and_literals(descriptor)
        match = re.match(r"\s*new\s+RouteDescriptor\s*\(", descriptor_code)
        if match is None:
            return None
        descriptor_opening = descriptor_code.find("(", match.start())
        arguments = split_java_arguments(descriptor, descriptor_code, descriptor_opening)
        if arguments is None or len(arguments[0]) != 9 \
                or descriptor_code[arguments[1] + 1:].strip():
            return None
        descriptors.append(tuple(
            (argument, base + start + argument_start, base + start + argument_end)
            for argument, argument_start, argument_end in arguments[0]
        ))
    return tuple(descriptors)


def route_table_candidate_partitions(source: str) -> tuple[
        dict[str, list[str]], list[dict[str, object]], dict[str, Candidate]] | None:
    """Bind every supported RouteTable candidate to one typed constructor position."""
    descriptors = route_table_descriptors(source)
    if descriptors is None:
        return None
    occurrences = java_source_candidates(ROUTE_TABLE_PATH, source)
    by_id = {candidate.id: candidate for _offset, candidate in occurrences}
    partitions = {role: [] for role in ("methods", "path", "summary", "successStatuses")}
    details: list[dict[str, object]] = []

    def ids_in(start: int, end: int) -> list[str]:
        return [candidate.id for offset, candidate in occurrences if start <= offset < end]

    for ordinal, arguments in enumerate(descriptors, 1):
        methods = direct_factory_arguments(arguments[0][0], "Set.of")
        path = java_literal_concatenation(arguments[1][0])
        summary = java_literal_concatenation(arguments[2][0])
        statuses = direct_factory_arguments(arguments[5][0], "Set.of")
        if methods is None or not methods or path is None or len(path[1]) != 1 or summary is None:
            return None
        method_values: list[str] = []
        for argument, _start, _end in methods:
            value = java_literal_concatenation(argument)
            if value is None or len(value[1]) != 1 or value[0] not in {"GET", "POST", "DELETE"}:
                return None
            method_values.append(value[0])
        if not path[0].startswith("/") or not summary[0].strip():
            return None
        status_arguments = statuses if statuses is not None else ((arguments[5][0], 0, len(arguments[5][0])),)
        status_values: list[int] = []
        for status, _start, _end in status_arguments:
            if re.fullmatch(r"\s*[0-9](?:_?[0-9])*\s*", status) is None:
                return None
            value = int(status.strip().replace("_", ""))
            if not 200 <= value < 300:
                return None
            status_values.append(value)
        role_arguments = {
            "methods": arguments[0], "path": arguments[1],
            "summary": arguments[2], "successStatuses": arguments[5],
        }
        role_ids = {role: ids_in(argument[1], argument[2])
                    for role, argument in role_arguments.items()}
        if any(not role_ids[role] for role in role_ids):
            return None
        for role, identifiers in role_ids.items():
            partitions[role].extend(identifiers)
        details.append({
            "ordinal": ordinal, "path": path[0], "summary": summary[0],
            "statusValues": status_values, "candidateIds": role_ids,
        })
    return partitions, details, by_id


def exact_import_identity(source: str, qualified: str) -> bool:
    """Require one exact normal import and no competing/local declaration of its simple name."""
    code = strip_c_comments_and_literals(source)
    simple = qualified.rsplit(".", 1)[-1]
    imports = re.findall(
        r"(?m)^\s*import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)\s*;", code)
    matching = [item for item in imports if item.rsplit(".", 1)[-1] == simple]
    return matching == [qualified] and re.search(
        rf"\b(?:class|record|enum|interface)\s+{re.escape(simple)}\b"
        rf"|@interface\s+{re.escape(simple)}\b", code,
    ) is None


def same_package_type_identity(source: str, type_symbol: str, qualified: str) -> bool:
    """Require a same-package simple type with no import, local type, or direct-value shadow."""
    package, simple = qualified.rsplit(".", 1)
    code = strip_c_comments_and_literals(source)
    imports = re.findall(
        r"(?m)^\s*import\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)\s*;", code)
    return java_package(source) == package \
        and not any(item.rsplit(".", 1)[-1] == simple for item in imports) \
        and java_has_no_simple_name_shadow(source, type_symbol, {simple})


def java_has_no_simple_name_shadow(source: str, type_symbol: str,
                                   names: set[str]) -> bool:
    """Reject local type/direct-value/static-import bindings for reviewed simple type names."""
    code = strip_c_comments_and_literals(source)
    for name in names:
        if re.search(
                rf"\b(?:class|record|enum|interface)\s+{re.escape(name)}\b"
                rf"|@interface\s+{re.escape(name)}\b", code,
        ) is not None or java_type_declares_field(source, type_symbol, name):
            return False
    static_imports = re.findall(
        r"(?m)^\s*import\s+static\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.\*)"
        r"\s*;|^\s*import\s+static\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)\s*;",
        code,
    )
    imported = [left or right for left, right in static_imports]
    return not any(item.endswith(".*") or item.rsplit(".", 1)[-1] in names for item in imported)


def java_has_exact_junit_assertions(source: str, type_symbol: str,
                                    names: set[str]) -> bool:
    """Bind reviewed assertion calls to exact JUnit methods and reject local method/value shadows."""
    code = strip_c_comments_and_literals(source)
    static_imports = re.findall(
        r"(?m)^\s*import\s+static\s+"
        r"([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*(?:\.\*)?)\s*;", code)
    if any(item.endswith(".*") for item in static_imports):
        return False
    for name in names:
        matching = [item for item in static_imports if item.rsplit(".", 1)[-1] == name]
        if matching != [f"org.junit.jupiter.api.Assertions.{name}"] \
                or java_direct_method_declaration_count(source, type_symbol, name) != 0 \
                or java_type_declares_field(source, type_symbol, name):
            return False
    return True


def java_span_uses_only_simple_receiver(source: str, span: tuple[int, int] | None,
                                        name: str) -> bool:
    """Require every occurrence of one supported imported name to be a dotted receiver."""
    if span is None:
        return False
    code = strip_c_comments_and_literals(source)[slice(*span)]
    return all(re.match(r"\s*\.", code[match.end():]) is not None
               for match in re.finditer(rf"\b{re.escape(name)}\b", code))


def java_direct_return_expression(source: str, type_symbol: str, method: str) -> str | None:
    """Return the one direct return expression from a supported method, without comments."""
    span = java_method_span(source, type_symbol, method)
    if span is None:
        return None
    actual = strip_c_comments(source[slice(*span)])
    code = strip_c_comments_and_literals(source[slice(*span)])
    depths = java_brace_depths(code)
    returns = [match for match in re.finditer(r"\breturn\b", code)
               if depths[match.start()] == 1]
    if len(returns) != 1:
        return None
    start = returns[0].end()
    semicolon = next((offset for offset in range(start, len(code))
                      if code[offset] == ";" and depths[offset] == 1), None)
    if semicolon is None:
        return None
    return normalized(actual[start:semicolon])


def route_success_response_expressions(source: str) -> tuple[str, str] | None:
    """Parse the closed SSE special case and final generic response return."""
    span = java_method_span(source, "OpenApiSpecGenerator", "successResponse")
    if span is None:
        return None
    actual = strip_c_comments(source[slice(*span)])
    code = strip_c_comments_and_literals(source[slice(*span)])
    depths = java_brace_depths(code)
    body_open = code.find("{")
    if body_open < 0:
        return None
    leading_if = next((match for match in re.finditer(r"\bif\s*\(", code)
                       if depths[match.start()] == 1), None)
    if leading_if is None or code[body_open + 1:leading_if.start()].strip():
        return None
    condition_open = code.find("(", leading_if.start())
    condition_close = matching_delimiter(code, condition_open, "(", ")")
    if condition_close is None or normalized(actual[leading_if.start():condition_close + 1]) != normalized(
            'if ("/v1/events".equals(route.path()) && "GET".equals(method) && status == 200)'):
        return None
    branch_open_match = re.match(r"\s*\{", code[condition_close + 1:])
    if branch_open_match is None:
        return None
    branch_open = condition_close + 1 + branch_open_match.end() - 1
    branch_close = matching_delimiter(code, branch_open, "{", "}")
    if branch_close is None:
        return None
    nested_returns = [match for match in re.finditer(r"\breturn\b", code)
                      if depths[match.start()] == 2]
    direct_returns = [match for match in re.finditer(r"\breturn\b", code)
                      if depths[match.start()] == 1]
    if len(nested_returns) != 1 or len(direct_returns) != 1 \
            or len(re.findall(r"\breturn\b", code)) != 2 \
            or not branch_open < nested_returns[0].start() < branch_close:
        return None
    nested_start = nested_returns[0].end()
    nested_semicolon = next((offset for offset in range(nested_start, branch_close)
                             if code[offset] == ";" and depths[offset] == 2), None)
    if nested_semicolon is None \
            or code[branch_open + 1:nested_returns[0].start()].strip() \
            or code[nested_semicolon + 1:branch_close].strip():
        return None
    if not re.match(r"\s*String\s+schema\s*=\s*null\s*;", code[branch_close + 1:]):
        return None
    direct_start = direct_returns[0].end()
    direct_semicolon = next((offset for offset in range(direct_start, len(code))
                             if code[offset] == ";" and depths[offset] == 1), None)
    if direct_semicolon is None:
        return None
    if code[direct_semicolon + 1:].strip() != "}":
        return None
    top_level_ifs = [match for match in re.finditer(r"\bif\s*\(", code)
                     if depths[match.start()] == 1]
    top_level_elses = [match for match in re.finditer(r"\belse\b", code)
                       if depths[match.start()] == 1]
    other_controls = [match for match in re.finditer(
        r"\b(?:switch|for|while|do|try|catch)\b", code,
    ) if depths[match.start()] >= 1]
    if len(top_level_ifs) != 5 or len(top_level_elses) != 3 or other_controls:
        return None
    return (
        normalized(actual[nested_start:nested_semicolon]),
        normalized(actual[direct_start:direct_semicolon]),
    )


def java_direct_field_has_annotation(source: str, type_symbol: str, field: str,
                                     annotation: str) -> bool:
    span = java_type_span(source, type_symbol)
    if span is None:
        return False
    code = strip_c_comments_and_literals(source)[slice(*span)]
    depths = java_brace_depths(code)
    return any(depths[match.start()] == 1 for match in re.finditer(
        rf"@{re.escape(annotation)}\s+(?:[A-Za-z_$][\w$<>?,.\[\]]*\s+)+{re.escape(field)}\s*;",
        code,
    ))


def java_int_expression_value(expression: str, resolver) -> int | None:
    """Evaluate the small checked Java integer expression grammar used by wire-bound constants."""
    tokens = re.findall(r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*|[0-9](?:_?[0-9])*|[()+*/-]",
                        expression)
    if "".join(tokens) != re.sub(r"\s+", "", expression):
        return None
    position = 0

    def checked(value: int) -> int:
        if value < -2_147_483_648 or value > 2_147_483_647:
            raise ValueError
        return value

    def atom() -> int:
        nonlocal position
        if position >= len(tokens):
            raise ValueError
        token = tokens[position]
        position += 1
        if token == "(":
            value = add()
            if position >= len(tokens) or tokens[position] != ")":
                raise ValueError
            position += 1
            return value
        if re.fullmatch(r"[0-9](?:_?[0-9])*", token):
            return checked(int(token.replace("_", "")))
        resolved = resolver(token)
        if resolved is None:
            raise ValueError
        return checked(resolved)

    def multiply() -> int:
        nonlocal position
        value = atom()
        while position < len(tokens) and tokens[position] in {"*", "/"}:
            operator = tokens[position]
            position += 1
            right = atom()
            if operator == "/":
                if right == 0:
                    raise ValueError
                value = (abs(value) // abs(right)) * (-1 if (value < 0) != (right < 0) else 1)
            else:
                value *= right
            value = checked(value)
        return value

    def add() -> int:
        nonlocal position
        value = multiply()
        while position < len(tokens) and tokens[position] in {"+", "-"}:
            operator = tokens[position]
            position += 1
            right = multiply()
            value = checked(value + right if operator == "+" else value - right)
        return value

    try:
        value = add()
        return value if position == len(tokens) else None
    except ValueError:
        return None


def public_static_final_int_expression(source: str, type_symbol: str, field: str) -> str | None:
    initializer = java_static_final_initializer(source, type_symbol, field)
    span = java_type_span(source, type_symbol)
    if initializer is None or span is None:
        return None
    code = strip_c_comments_and_literals(source)[slice(*span)]
    depths = java_brace_depths(code)
    declarations = [match for match in re.finditer(
        rf"\bpublic\s+static\s+final\s+int\s+{re.escape(field)}\s*=", code,
    ) if depths[match.start()] == 1]
    return initializer[0] if len(declarations) == 1 else None


def route_bound_values(root: Path) -> dict[str, int] | None:
    sources = {
        "StableEdgeId": (STABLE_EDGE_ID_PATH, "StableEdgeId"),
        "EdgeTraversalWireBudget": (EDGE_WIRE_BUDGET_PATH, "EdgeTraversalWireBudget"),
    }
    if any(current_source_owner(root, f"{path.as_posix()}#{type_symbol}") is None
           for path, type_symbol in sources.values()):
        return None
    texts = {name: (root / path).read_text(encoding="utf-8")
             for name, (path, _type) in sources.items()}
    if not same_package_type_identity(
            texts["EdgeTraversalWireBudget"], "EdgeTraversalWireBudget",
            "ai.ravenroot.api.application.StableEdgeId"):
        return None
    cache: dict[str, int] = {}
    active: set[str] = set()

    def resolve(qualified: str, current: str | None = None) -> int | None:
        name = qualified if "." in qualified else f"{current}.{qualified}"
        if name in cache:
            return cache[name]
        if name in active or "." not in name:
            return None
        owner, field = name.split(".", 1)
        if owner not in sources or "." in field:
            return None
        expression = public_static_final_int_expression(texts[owner], owner, field)
        if expression is None:
            return None
        active.add(name)
        value = java_int_expression_value(expression, lambda token: resolve(token, owner))
        active.remove(name)
        if value is not None:
            cache[name] = value
        return value

    required = {qualified for fields in ROUTE_BOUND_CANDIDATES.values() for qualified in fields}
    result = {name: resolve(name) for name in required}
    return None if any(value is None for value in result.values()) else {
        name: int(value) for name, value in result.items()
    }


def route_table_consumer_errors(root: Path, authority: dict[str, object]) -> list[str]:
    errors: list[str] = []
    consumer_digests = authority.get("consumerBodyDigests")
    required_digests = {
        "routeDescriptorValidation", "openApiGenerate", "openApiPathEntry",
        "openApiOperationEntry", "openApiSuccessResponse", "openApiHumanTaskSchemas",
        "openApiExecutionEventSchemas",
    }
    if not isinstance(consumer_digests, dict) or set(consumer_digests) != required_digests:
        return ["RouteTable authority requires exact typed consumer body digests"]

    descriptor = (root / ROUTE_DESCRIPTOR_PATH).read_text(encoding="utf-8")
    expected_components = (
        "methods", "path", "summary", "authenticated", "registersContext", "successStatuses",
        "wireErrorCodes", "assistantPosture", "sideEffectFree",
    )
    compact = java_compact_constructor_span(descriptor, "RouteDescriptor")
    compact_code = normalized(strip_c_comments_and_literals(
        descriptor[slice(*compact)] if compact is not None else ""))
    if java_package(descriptor) != "ai.ravenroot.server.spec" \
            or java_record_components(descriptor, "RouteDescriptor") != expected_components \
            or java_span_digest(descriptor, compact) != consumer_digests["routeDescriptorValidation"]:
        errors.append("RouteTable RouteDescriptor typed component/validation digest has drifted")
    for required in (
        "path == null || path.isBlank()", "summary == null || summary.isBlank()",
        "status < 200 || status >= 300", "methods = Set.copyOf(methods)",
        "successStatuses = Set.copyOf(successStatuses)",
    ):
        if normalized(required) not in compact_code:
            errors.append(f"RouteTable RouteDescriptor validation lost {required}")
    descriptor_code = normalized(strip_c_comments_and_literals(descriptor))
    forwarding = normalized(
        "public RouteDescriptor(Set<String> methods, String path, String summary, "
        "boolean authenticated, boolean registersContext, int successStatus, "
        "List<String> wireErrorCodes, AssistantPosture assistantPosture, boolean sideEffectFree) { "
        "this(methods, path, summary, authenticated, registersContext, Set.of(successStatus), "
        "wireErrorCodes, assistantPosture, sideEffectFree); }")
    if forwarding not in descriptor_code:
        errors.append("RouteTable RouteDescriptor convenience constructor lost positional forwarding")

    generator = (root / OPENAPI_GENERATOR_PATH).read_text(encoding="utf-8")
    authoritative_methods = ("generate", "pathEntry", "operationEntry", "successResponse")
    receiver_names = {"JsonStrings", "Collectors"}
    receiver_values_are_unshadowed = all(
        java_span_uses_only_simple_receiver(
            generator, java_method_span(generator, "OpenApiSpecGenerator", method), receiver)
        for method in authoritative_methods for receiver in receiver_names
    )
    if not same_package_type_identity(
            generator, "OpenApiSpecGenerator", "ai.ravenroot.server.spec.RouteDescriptor"):
        errors.append("RouteTable OpenAPI consumer does not resolve the same-package RouteDescriptor type")
    if not exact_import_identity(generator, "ai.ravenroot.server.audit.JsonStrings") \
            or not exact_import_identity(generator, "java.util.List") \
            or not exact_import_identity(generator, "java.util.stream.Collectors") \
            or not java_has_no_simple_name_shadow(
                generator, "OpenApiSpecGenerator", receiver_names) \
            or not receiver_values_are_unshadowed:
        errors.append("RouteTable OpenAPI consumer import/receiver identity has drifted")
    generate_span = java_method_span(generator, "OpenApiSpecGenerator", "generate")
    generate_source = generator[slice(*generate_span)] if generate_span else ""
    generate_code = normalized(strip_c_comments_and_literals(generate_source))
    if java_method_header(generator, "OpenApiSpecGenerator", "generate") != \
            "public static String generate(List<RouteDescriptor> routes)" \
            or java_method_digest(generator, "OpenApiSpecGenerator", "generate") != \
            consumer_digests["openApiGenerate"] \
            or java_method_digest(generator, "OpenApiSpecGenerator", "generate") != \
            ROUTE_OPENAPI_GENERATE_BODY_DIGEST:
        errors.append("RouteTable OpenAPI generate signature/body has drifted")
    publication_chain = (
        "json.append(routes.stream().sorted(java.util.Comparator.comparing(RouteDescriptor::path))"
        ".map(OpenApiSpecGenerator::pathEntry).collect(Collectors.joining()));")
    generate_compact = re.sub(r"\s+", "", strip_c_comments_and_literals(generate_source))
    if publication_chain not in generate_compact:
        errors.append("RouteTable OpenAPI generate lost the routes-to-pathEntry append chain")
    if normalized("return json.toString()") not in generate_code:
        errors.append("RouteTable OpenAPI generate lost return json.toString()")
    for expression in (
        "String existingSchemas = humanTaskSchemas()",
        'json.append(existingSchemas, 0, existingSchemas.lastIndexOf("\\n    }"))',
        'json.append(",\\n").append(executionEventSchemas()).append("    }\\n")',
    ):
        if normalized(expression) not in normalized(strip_c_comments(generate_source)):
            errors.append(f"RouteTable OpenAPI generate lost schema publication flow: {expression}")
    for role, method, header, required in (
        ("openApiPathEntry", "pathEntry", "private static String pathEntry(RouteDescriptor route)",
         ("route.methods().stream().sorted().map(method -> operationEntry(route, method))",
          "JsonStrings.escape(route.path())", "operations")),
        ("openApiOperationEntry", "operationEntry",
         "private static String operationEntry(RouteDescriptor route, String method)",
         ("method.toLowerCase(java.util.Locale.ROOT)", "JsonStrings.escape(route.summary())",
          "route.successStatuses().stream().sorted().forEach(status -> responses.add(successResponse(route, method, status)))")),
    ):
        span = java_method_span(generator, "OpenApiSpecGenerator", method)
        code = normalized(strip_c_comments_and_literals(generator[slice(*span)] if span else ""))
        if java_method_header(generator, "OpenApiSpecGenerator", method) != header \
                or java_method_digest(generator, "OpenApiSpecGenerator", method) != consumer_digests[role]:
            errors.append(f"RouteTable typed consumer {method} signature/body has drifted")
        for expression in required:
            if normalized(expression) not in code:
                errors.append(f"RouteTable typed consumer {method} lost {expression}")
    success_expressions = route_success_response_expressions(generator)
    sse_return, generic_return = success_expressions if success_expressions is not None else ("", "")
    sse_literal = java_literal_concatenation(sse_return) if sse_return else None
    sse_value = sse_literal[0] if sse_literal is not None else ""
    status_prefix = normalized(
        '"          \\"" + status + "\\": {\\"description\\": \\"success\\"" +')
    if java_method_header(generator, "OpenApiSpecGenerator", "successResponse") != \
            "private static String successResponse(RouteDescriptor route, String method, int status)" \
            or java_method_digest(generator, "OpenApiSpecGenerator", "successResponse") != \
            consumer_digests["openApiSuccessResponse"] \
            or java_method_digest(generator, "OpenApiSpecGenerator", "successResponse") != \
            ROUTE_OPENAPI_SUCCESS_RESPONSE_BODY_DIGEST \
            or success_expressions is None \
            or not sse_value.lstrip().startswith('"200":') \
            or '"text/event-stream"' not in sse_value \
            or '"#/components/schemas/ExecutionStreamEvent"' not in sse_value \
            or not generic_return.startswith(status_prefix) \
            or normalized("schema == null ?") not in generic_return \
            or normalized("+ schema +") not in generic_return:
        errors.append("RouteTable OpenAPI successResponse lost status serialization")
    for name in (
        "X-Ravenroot-Event-Source", "X-Ravenroot-Event-Continuity",
        "X-Ravenroot-Event-Schema-Version",
    ):
        if sse_value.count(f'"{name}":') != 1:
            errors.append(f"RouteTable OpenAPI SSE response lost header {name}")
    for event, schema in (
        ("execution", "ExecutionStreamEvent"),
        ("stream-truncated", "EventStreamTruncated"),
        ("stream-overrun", "EventStreamOverrun"),
    ):
        clause = f'"{event}":{{"dataSchema":{{"$ref":"#/components/schemas/{schema}"}}}}'
        if clause not in sse_value:
            errors.append(f"RouteTable OpenAPI SSE response lost {event} data schema")
    for method, digest_key in (
        ("humanTaskSchemas", "openApiHumanTaskSchemas"),
        ("executionEventSchemas", "openApiExecutionEventSchemas"),
    ):
        method_digest = java_method_digest(generator, "OpenApiSpecGenerator", method)
        if java_method_header(generator, "OpenApiSpecGenerator", method) != \
                f"private static String {method}()" \
                or method_digest != consumer_digests[digest_key] \
                or (method == "humanTaskSchemas"
                    and method_digest != ROUTE_HUMAN_TASK_SCHEMAS_BODY_DIGEST) \
                or (method == "executionEventSchemas"
                    and method_digest != ROUTE_EXECUTION_EVENT_SCHEMAS_BODY_DIGEST) \
                or java_direct_return_expression(generator, "OpenApiSpecGenerator", method) is None:
            errors.append(f"RouteTable OpenAPI {method} schema helper has drifted")
    execution_return = java_direct_return_expression(
        generator, "OpenApiSpecGenerator", "executionEventSchemas")
    execution_literal = java_literal_concatenation(execution_return) if execution_return else None
    execution_value = execution_literal[0] if execution_literal is not None else ""
    for schema in (
        "ExecutionStreamEventBase", "ExecutionStreamEvent", "RingExecutionStreamEvent",
        "DurableExecutionStreamEvent", "EventStreamTruncated", "EventStreamOverrun",
    ):
        if execution_value.count(f'"{schema}":') != 1:
            errors.append(f"RouteTable OpenAPI executionEventSchemas lost {schema}")
    return errors


def route_publication_test_errors(root: Path, authority: dict[str, object]) -> list[str]:
    evidence = authority.get("publicationTestAuthority")
    required = {"testBodyDigest", "eventStreamSpecBodyDigest", "checkedInSpecBodyDigest"}
    if not isinstance(evidence, dict) or set(evidence) != required:
        return ["RouteTable authority requires exact publication test evidence"]
    source = (root / ROUTE_TABLE_TEST_PATH).read_text(encoding="utf-8")
    errors: list[str] = []
    test_type = "RouteTableSpecServerAgreementTest"
    method = "theCheckedInSpecMatchesWhatTheTableGeneratesRightNow"
    helper = "checkedInSpec"
    if java_package(source) != "ai.ravenroot.server.spec" \
            or not java_test_type_is_directly_runnable(source, test_type) \
            or not exact_import_identity(source, "org.junit.jupiter.api.Test") \
            or not exact_import_identity(source, "org.junit.jupiter.api.io.TempDir") \
            or not exact_import_identity(source, "java.nio.file.Path") \
            or not java_direct_field_has_annotation(source, test_type, "uiDirectory", "TempDir") \
            or not same_package_type_identity(
                source, test_type, "ai.ravenroot.server.spec.RouteTable") \
            or not same_package_type_identity(
                source, test_type, "ai.ravenroot.server.spec.OpenApiSpecGenerator") \
            or not java_has_exact_junit_assertions(
                source, test_type, {"assertEquals", "assertFalse", "assertTrue"}):
        errors.append("RouteTable publication test type/import/TempDir identity has drifted")
    if java_method_header(source, test_type, method) != f"void {method}() throws Exception" \
            or java_method_annotations(source, test_type, method) != ("@Test",) \
            or java_method_digest(source, test_type, method) != evidence["testBodyDigest"]:
        errors.append("RouteTable publication parity test is not an exact runnable @Test")
    span = java_method_span(source, test_type, method)
    code = normalized(strip_c_comments_and_literals(source[slice(*span)] if span else ""))
    for expression in (
        "OpenApiSpecGenerator.generate(RouteTable.ALL)", "checkedInSpec()",
        "assertEquals(generatedNow, onDisk",
    ):
        if normalized(expression) not in code:
            errors.append(f"RouteTable publication parity test lost {expression}")
    event_method = "eventStreamSpecSeparatesSseTextFromVersionedDataAndControlFrames"
    event_digest = java_method_digest(source, test_type, event_method)
    if java_method_header(source, test_type, event_method) != f"void {event_method}()" \
            or java_method_annotations(source, test_type, event_method) != ("@Test",) \
            or event_digest != evidence["eventStreamSpecBodyDigest"] \
            or event_digest != ROUTE_EVENT_STREAM_PUBLICATION_TEST_BODY_DIGEST:
        errors.append("RouteTable event-stream publication test is not an exact runnable @Test")
    event_span = java_method_span(source, test_type, event_method)
    event_source = normalized(strip_c_comments(
        source[slice(*event_span)] if event_span is not None else ""))
    for expression in (
        "OpenApiSpecGenerator.generate(RouteTable.ALL)",
        'paths.get("/v1/events")',
        'members.apply(operation.get("responses")).get("200")',
        'assertEquals(Set.of("text/event-stream"), content.keySet())',
        'Set.of("X-Ravenroot-Event-Source", "X-Ravenroot-Event-Continuity", '
        '"X-Ravenroot-Event-Schema-Version")',
        'Set.of("execution", "stream-truncated", "stream-overrun")',
        'schemas.get("ExecutionStreamEvent")',
        'assertFalse(members.apply(baseProperties.get("occurredAt")).containsKey("format")',
        'paths.get("/v1/events/recent")',
        'assertFalse(members.apply(members.apply(recent.get("responses")).get("200"))'
        '.containsKey("content")',
    ):
        if normalized(expression) not in event_source:
            errors.append(f"RouteTable event-stream publication test lost {expression}")
    helper_span = java_method_span(source, test_type, helper)
    helper_code = normalized(strip_c_comments_and_literals(
        source[slice(*helper_span)] if helper_span else ""))
    if java_method_header(source, test_type, helper) != \
            "private static String checkedInSpec() throws IOException" \
            or java_method_digest(source, test_type, helper) != evidence["checkedInSpecBodyDigest"] \
            or "getResourceAsStream(" not in helper_code or "readAllBytes()" not in helper_code:
        errors.append("RouteTable checkedInSpec helper closure has drifted")
    return errors


def route_bound_test_errors(root: Path, authority: dict[str, object]) -> list[str]:
    evidence = authority.get("boundTestBodyDigests")
    required = {
        "StableEdgeIdContractTest": {
            "acceptsTheExactUtf8BoundWithoutChangingIdentityAndRejectsOneByteMore",
            "auxiliaryReserveIsEnforcedAsOneCombinedEscapedByteBudget",
        },
        "StableEdgeIdWireContractTest": {
            "worstCaseEscapedMaximumFitsTheCompleteRuntimeClientFrame",
            "saturatedLiveAndLogFieldsStillFitWithTheMaximumEscapedIdentity",
            "saturatedDurableProjectionAndPayloadStayInsideTheirExplicitBounds",
        },
    }
    if not isinstance(evidence, dict) or set(evidence) != set(required):
        return ["RouteTable authority requires exact typed-bound test evidence"]
    errors: list[str] = []
    for test_type, methods in required.items():
        relative = STABLE_EDGE_TEST_PATH if test_type == "StableEdgeIdContractTest" \
            else STABLE_EDGE_WIRE_TEST_PATH
        source = (root / relative).read_text(encoding="utf-8")
        recorded = evidence.get(test_type)
        type_names = {"StableEdgeId", "EdgeTraversalWireBudget"}
        same_package = test_type == "StableEdgeIdContractTest"
        imports_are_exact = (java_package(source) == "ai.ravenroot.api.application"
                             and same_package_type_identity(
                                 source, test_type,
                                 "ai.ravenroot.api.application.StableEdgeId")
                             and same_package_type_identity(
                                 source, test_type,
                                 "ai.ravenroot.api.application.EdgeTraversalWireBudget")
                             if same_package else
                             exact_import_identity(source, "ai.ravenroot.api.application.StableEdgeId")
                             and exact_import_identity(
                                 source, "ai.ravenroot.api.application.EdgeTraversalWireBudget"))
        if not isinstance(recorded, dict) or set(recorded) != methods \
                or not java_test_type_is_directly_runnable(source, test_type) \
                or not exact_import_identity(source, "org.junit.jupiter.api.Test") \
                or not imports_are_exact \
                or not java_has_no_simple_name_shadow(source, test_type, type_names) \
                or not java_has_exact_junit_assertions(
                    source, test_type, {"assertEquals", "assertThrows", "assertTrue"}):
            errors.append(f"RouteTable typed-bound test authority {test_type} is incomplete")
            continue
        for method in methods:
            if java_method_header(source, test_type, method) != f"void {method}()" \
                    or java_method_annotations(source, test_type, method) != ("@Test",) \
                    or java_method_digest(source, test_type, method) != recorded[method]:
                errors.append(f"RouteTable typed-bound test {test_type}.{method} has drifted")
                continue
            span = java_method_span(source, test_type, method)
            code = normalized(strip_c_comments_and_literals(
                source[slice(*span)] if span is not None else ""))
            structural = {
                "acceptsTheExactUtf8BoundWithoutChangingIdentityAndRejectsOneByteMore": (
                    "StableEdgeId.MAX_UTF8_BYTES", "assertEquals", "assertThrows"),
                "auxiliaryReserveIsEnforcedAsOneCombinedEscapedByteBudget": (
                    "EdgeTraversalWireBudget.MAX_AUXILIARY_ESCAPED_VALUE_BYTES",
                    "requireLiveProjection", "requireDurableProjection", "assertThrows"),
                "worstCaseEscapedMaximumFitsTheCompleteRuntimeClientFrame": (
                    "StableEdgeId.MAX_UTF8_BYTES", "StableEdgeId.SSE_FRAME_MAX_BYTES",
                    "liveFrame.length", "assertTrue"),
                "saturatedLiveAndLogFieldsStillFitWithTheMaximumEscapedIdentity": (
                    "EdgeTraversalWireBudget.MAX_AUXILIARY_ESCAPED_VALUE_BYTES",
                    "StableEdgeId.SSE_FRAME_MAX_BYTES", "liveFrame.length", "logLine.length"),
                "saturatedDurableProjectionAndPayloadStayInsideTheirExplicitBounds": (
                    "EdgeTraversalWireBudget.MAX_AUXILIARY_ESCAPED_VALUE_BYTES",
                    "StableEdgeId.SSE_FRAME_MAX_BYTES", "durableFrame.length", "assertEquals"),
            }[method]
            for expression in structural:
                if expression not in code:
                    errors.append(
                        f"RouteTable typed-bound test {test_type}.{method} lost {expression}")
    return errors


def route_table_authority_errors(root: Path, authorities: object,
                                 entries: dict[str, dict[str, object]],
                                 discovered: dict[str, Candidate]) -> list[str]:
    """Verify the closed RouteTable retained-candidate family and publication evidence."""
    reviewed = [entry for entry in entries.values()
                if entry.get("path") == ROUTE_TABLE_PATH.as_posix()
                and entry.get("status") != "pending-review"]
    if not reviewed:
        return [] if authorities in (None, {}) else ["RouteTable authority exists without reviewed rows"]
    if not isinstance(authorities, dict) or set(authorities) != {ROUTE_TABLE_AUTHORITY_ID}:
        return ["reviewed RouteTable rows require the one closed RouteTable authority"]
    authority = authorities[ROUTE_TABLE_AUTHORITY_ID]
    required = {
        "kind", "candidateIdsByRole", "descriptorCandidateIds", "consumerBodyDigests",
        "publicationTestAuthority", "boundTestBodyDigests", "publishedBoundClauses",
    }
    if not isinstance(authority, dict) or set(authority) != required \
            or authority.get("kind") != "java-route-descriptor-publication-v1":
        return ["RouteTable authority has an unsupported or incomplete shape"]
    required_sources = {
        ROUTE_TABLE_PATH: "RouteTable", ROUTE_DESCRIPTOR_PATH: "RouteDescriptor",
        OPENAPI_GENERATOR_PATH: "OpenApiSpecGenerator",
        ROUTE_TABLE_TEST_PATH: "RouteTableSpecServerAgreementTest",
        STABLE_EDGE_ID_PATH: "StableEdgeId", EDGE_WIRE_BUDGET_PATH: "EdgeTraversalWireBudget",
        STABLE_EDGE_TEST_PATH: "StableEdgeIdContractTest",
        STABLE_EDGE_WIRE_TEST_PATH: "StableEdgeIdWireContractTest",
    }
    if any(current_source_owner(root, f"{path.as_posix()}#{symbol}") is None
           for path, symbol in required_sources.items()):
        return ["RouteTable authority has a missing tracked source/test owner"]
    source = (root / ROUTE_TABLE_PATH).read_text(encoding="utf-8")
    if not same_package_type_identity(
            source, "RouteTable", "ai.ravenroot.server.spec.RouteDescriptor"):
        return ["RouteTable.ALL does not resolve the same-package RouteDescriptor type"]
    parsed = route_table_candidate_partitions(source)
    if parsed is None:
        return ["RouteTable.ALL is not the supported direct RouteDescriptor table"]
    partitions, details, source_candidates = parsed
    errors: list[str] = []
    expected_counts = {"methods": 60, "path": 53, "summary": 348, "successStatuses": 54}
    if len(details) != 53 or {role: len(ids) for role, ids in partitions.items()} != expected_counts:
        errors.append("RouteTable authority no longer has the reviewed 53/515 positional shape")
    recorded = authority["candidateIdsByRole"]
    if not isinstance(recorded, dict) or set(recorded) != set(expected_counts) \
            or any(recorded.get(role) != partitions[role] for role in expected_counts):
        errors.append("RouteTable authority candidate positional partitions have drifted")
    descriptor_evidence = [
        {"ordinal": detail["ordinal"], "path": detail["path"],
         "candidateIds": detail["candidateIds"]}
        for detail in details
    ]
    if authority["descriptorCandidateIds"] != descriptor_evidence:
        errors.append("RouteTable authority descriptor ordinal/path candidate positions have drifted")
    all_ids = [identifier for values in partitions.values() for identifier in values]
    if len(all_ids) != len(set(all_ids)) or set(all_ids) != set(source_candidates):
        errors.append("RouteTable authority does not partition every RouteTable candidate exactly once")
    expected_classification = {
        **{identifier: "protocol-or-format-invariant"
           for role in ("methods", "path", "successStatuses") for identifier in partitions[role]},
        **{identifier: "published-contract-description" for identifier in partitions["summary"]},
    }
    for identifier, classification in expected_classification.items():
        entry = entries.get(identifier)
        if entry is None or entry.get("classification") != classification \
                or entry.get("status") != "retained" \
                or entry.get("retainedAuthority") != ROUTE_TABLE_AUTHORITY_ID:
            errors.append(f"RouteTable candidate {identifier} lacks its exact retained positional authority")
        if identifier not in discovered or discovered[identifier].path != ROUTE_TABLE_PATH.as_posix():
            errors.append(f"RouteTable candidate {identifier} is absent from current discovery")
    if set(expected_classification) != {str(entry.get("id")) for entry in reviewed}:
        errors.append("RouteTable reviewed rows do not equal the complete supported candidate family")

    clauses = authority["publishedBoundClauses"]
    expected_clauses = {identifier: list(fields)
                        for identifier, fields in ROUTE_BOUND_CANDIDATES.items()}
    if clauses != expected_clauses:
        errors.append("RouteTable published bound clauses do not use exact candidate-specific authorities")
    values = route_bound_values(root)
    by_path = {str(detail["path"]): str(detail["summary"]) for detail in details}
    if values is None:
        errors.append("RouteTable typed wire-bound constants are not resolvable")
    else:
        max_id = values["StableEdgeId.MAX_UTF8_BYTES"]
        auxiliary = values["EdgeTraversalWireBudget.MAX_AUXILIARY_ESCAPED_VALUE_BYTES"]
        frame = values["StableEdgeId.SSE_FRAME_MAX_BYTES"]
        typed_clauses = {
            "/v1/events": (
                f"edgeId is accepted unchanged up to {max_id} strict UTF-8 bytes;",
                f"all auxiliary traversal strings share a {auxiliary}-byte escaped budget",
                f"the complete frame below {frame} bytes.",
            ),
            "/v1/events/recent": (
                f"edgeId is accepted unchanged up to {max_id} strict UTF-8 bytes; "
                f"auxiliary traversal strings share a {auxiliary}-byte escaped budget",
                f"keeping each frame below {frame} bytes.",
            ),
        }
        for path, expected in typed_clauses.items():
            summary = by_path.get(path, "")
            for clause in expected:
                if summary.count(clause) != 1:
                    errors.append(f"RouteTable {path} summary lost typed bound clause: {clause}")
        for identifier, fields in ROUTE_BOUND_CANDIDATES.items():
            expected_path = ROUTE_BOUND_PATHS[identifier]
            detail = next((item for item in details
                           if item["path"] == expected_path
                           and identifier in item["candidateIds"]["summary"]), None)
            candidate = source_candidates.get(identifier)
            if detail is None or candidate is None or any(
                    str(values[field]) not in candidate.expression for field in fields):
                errors.append(
                    f"RouteTable typed bound candidate {identifier} is not the exact {expected_path} clause")
    errors.extend(route_table_consumer_errors(root, authority))
    errors.extend(route_publication_test_errors(root, authority))
    errors.extend(route_bound_test_errors(root, authority))
    return errors


def assistant_limit_binding_call(source: str, component: str) -> tuple[str, int, int] | None:
    call = java_constructor_component_call(
        source, "AssistantConfiguration", "fromEnvironment", "AssistantConfiguration",
        ASSISTANT_LIMIT_COMPONENTS, component,
    )
    return call


def java_identifier_write_count(code: str, identifier: str) -> int:
    """Count direct/compound writes in one already masked Java span."""
    name = re.escape(identifier)
    writes = re.findall(
        rf"\b{name}\s*(?:>>>=|>>=|<<=|=(?!=)|[+\-*/%&|^]=|\+\+|--)"
        rf"|(?:\+\+|--)\s*\b{name}\b",
        code,
    )
    return len(writes)


def assistant_limit_source_specs(source: str) -> list[dict[str, object]] | None:
    """Derive the checker-owned two symbol-bound AssistantConfiguration components."""
    if java_package(source) != "ai.ravenroot.server.assistant" \
            or java_record_components(source, "AssistantConfiguration") != ASSISTANT_LIMIT_COMPONENTS \
            or java_method_header(source, "AssistantConfiguration", "fromEnvironment") != \
            "public static AssistantConfiguration fromEnvironment(Map<String, String> environment)" \
            or not exact_import_identity(source, "java.util.Map") \
            or not java_has_no_simple_name_shadow(
                source, "AssistantConfiguration", {"Map"}):
        return None
    factory_span = java_method_span(source, "AssistantConfiguration", "fromEnvironment")
    factory_code = strip_c_comments_and_literals(source[slice(*factory_span)] if factory_span else "")
    if len(re.findall(r"\bboundedPositiveInteger\s*\(", factory_code)) != 2 \
            or len(re.findall(
                r"\bMap\s*<\s*String\s*,\s*String\s*>\s+env\s*=\s*"
                r"environment\s*==\s*null\s*\?\s*Map\.of\s*\(\s*\)\s*:\s*environment\s*;",
                factory_code,
            )) != 1:
        return None
    protected_symbols = {
        str(setting[key]) for setting in ASSISTANT_LIMIT_SETTINGS
        for key in ("environmentSymbol", "defaultSymbol")
    }
    if java_identifier_write_count(factory_code, "env") != 1 \
            or java_identifier_write_count(factory_code, "environment") != 0 \
            or any(java_identifier_write_count(factory_code, symbol) != 0
                   for symbol in protected_symbols):
        return None
    result: list[dict[str, object]] = []
    for expected in ASSISTANT_LIMIT_SETTINGS:
        call = assistant_limit_binding_call(source, str(expected["component"]))
        if call is None:
            return None
        argument, start, end = call
        pattern = re.fullmatch(
            r"boundedPositiveInteger\(env\.get\(([A-Za-z_$][\w$]*)\),\s*"
            r"([A-Za-z_$][\w$]*),\s*([A-Za-z_$][\w$]*)\)", argument,
        )
        if pattern is None or pattern.group(1) != pattern.group(2):
            return None
        env_symbol, default_symbol = pattern.group(1), pattern.group(3)
        env_initializer = java_static_final_initializer(source, "AssistantConfiguration", env_symbol)
        default_initializer = java_static_final_initializer(
            source, "AssistantConfiguration", default_symbol)
        type_span = java_type_span(source, "AssistantConfiguration")
        type_code = strip_c_comments_and_literals(source)[slice(*type_span)] \
            if type_span is not None else ""
        type_depths = java_brace_depths(type_code)
        env_declarations = [match for match in re.finditer(
            rf"\bpublic\s+static\s+final\s+String\s+{re.escape(env_symbol)}\s*=", type_code,
        ) if type_depths[match.start()] == 1]
        if env_initializer is None or default_initializer is None \
                or len(env_declarations) != 1 \
                or not re.fullmatch(rf'"{re.escape(str(expected["environment"]))}"', env_initializer[0]) \
                or public_static_final_int_expression(
                    source, "AssistantConfiguration", default_symbol) is None:
            return None
        default_value = java_int_expression_value(default_initializer[0], lambda _token: None)
        derived = dict(expected)
        derived.update({
            "call": argument, "callStart": start, "callEnd": end,
            "environmentSymbol": env_symbol, "environmentSpan": env_initializer[1:],
            "defaultSymbol": default_symbol, "defaultExpression": default_initializer[0],
            "defaultSpan": default_initializer[1:], "defaultValue": default_value,
        })
        if any(derived[key] != expected[key] for key in (
                "environmentSymbol", "defaultSymbol", "defaultValue")):
            return None
        result.append(derived)
    return result


def assistant_limit_carrier_errors(root: Path, spec: dict[str, object], evidence: object,
                                   entries: dict[str, dict[str, object]],
                                   discovered: dict[str, Candidate]) -> tuple[list[str], set[str]]:
    required = {"environment", "expectedCandidateIds"}
    setting = str(spec["setting"])
    if not isinstance(evidence, dict) or set(evidence) != required \
            or evidence.get("environment") != spec["environment"]:
        return ([f"{setting}: assistant carrier evidence has an unsupported shape"], set())
    groups = evidence["expectedCandidateIds"]
    if not isinstance(groups, dict) or set(groups) != set(ASSISTANT_CARRIER_PATHS):
        return ([f"{setting}: assistant carrier evidence must include every checker-owned group"], set())
    errors: list[str] = []
    accounted: set[str] = set()
    environment = str(spec["environment"])
    for group, paths in ASSISTANT_CARRIER_PATHS.items():
        actual = sorted(candidate.id for candidate in discovered.values()
                        if candidate.path in paths and candidate.kind == "environment-binding"
                        and candidate.expression == environment)
        if groups.get(group) != actual:
            errors.append(f"{setting}: assistant {group} candidate set has drifted")
        accounted.update(actual)
        if any(entries.get(identifier, {}).get("setting") != setting for identifier in actual):
            errors.append(f"{setting}: assistant carrier candidate is absent or assigned elsewhere")

    helm_field = str(spec["helmField"])
    maximum = int(spec["defaultValue"])
    values_source = (root / "deploy/helm/ravenroot/values.yaml").read_text(encoding="utf-8")
    if yaml_scalar_at_path(values_source, f"assistant.{helm_field}") != '""':
        errors.append(f"{setting}: Helm value must be an explicit blank default")
    try:
        schema = json.loads((root / "deploy/helm/ravenroot/values.schema.json").read_text(
            encoding="utf-8"))
        assistant = schema["properties"]["assistant"]
        expected_fields = {str(item["helmField"]) for item in ASSISTANT_LIMIT_SETTINGS}
        required_fields = assistant.get("required")
        properties = assistant.get("properties")
        if set(assistant) != {"type", "additionalProperties", "required", "properties"} \
                or assistant.get("type") != "object" \
                or assistant.get("additionalProperties") is not False \
                or not isinstance(required_fields, list) or len(required_fields) != 2 \
                or set(required_fields) != expected_fields \
                or not isinstance(properties, dict) or set(properties) != expected_fields:
            raise ValueError("unsupported assistant schema object")
        leaf = properties[helm_field]
        branches = leaf["oneOf"]
        if not isinstance(branches, list) or len(branches) != 2:
            raise ValueError("unsupported assistant schema branch set")
        integer = next(item for item in branches if item.get("type") == "integer")
        reference = next(item for item in branches if "$ref" in item)
        resolved_reference = resolved_json_schema_value(schema, reference)
        expected_reference = {
            "$ref": "#/definitions/graphBlank",
            "resolved": {
                "type": "string",
                "pattern": "^[\t-\r\x1c- \u1680\u2000-\u2006\u2008-\u200a"
                           "\u2028-\u2029\u205f\u3000]*$",
            },
        }
        if set(leaf) != {"x-ravenroot-environment", "oneOf"} \
                or branches != [integer, reference] \
                or helm_field not in assistant["required"] \
                or leaf.get("x-ravenroot-environment") != environment \
                or integer != {"type": "integer", "minimum": 1, "maximum": maximum} \
                or resolved_reference != expected_reference:
            errors.append(f"{setting}: Helm schema binding/range/blank reference has drifted")
    except (KeyError, TypeError, ValueError, StopIteration, json.JSONDecodeError):
        errors.append(f"{setting}: Helm schema binding/range/blank reference is unsupported")
    template = (root / "deploy/helm/ravenroot/templates/deployment.yaml").read_text(encoding="utf-8")
    expected_template = (f"- name: {environment}\n"
                         f"              value: {{{{ include \"ravenroot.graphLimitValue\" "
                         f".Values.assistant.{helm_field} }}}}")
    if template.count(expected_template) != 1:
        errors.append(f"{setting}: Helm template environment-to-value mapping has drifted")
    raw = (root / "deploy/kubernetes/ravenroot.yaml").read_text(encoding="utf-8")
    if len(re.findall(
            rf"(?m)^\s*- name:\s*{re.escape(environment)}\s*$\n\s*value:\s*\"\"\s*$", raw)) != 1:
        errors.append(f"{setting}: raw Kubernetes blank carrier has drifted")
    for relative in (Path("compose.yaml"), Path("docs/examples/assistant/compose.override.yaml")):
        text = (root / relative).read_text(encoding="utf-8")
        if text.count(f"{environment}: ${{{environment}:-}}") != 1:
            errors.append(f"{setting}: {relative.as_posix()} blank forwarding has drifted")
    return errors, accounted


def assistant_limit_conversion_errors(root: Path, spec: dict[str, object], conversion: object) \
        -> list[str]:
    required = {
        "kind", "issue", "beforeRevision", "afterRevision", "path", "ownerType", "method",
        "constructorType", "component", "componentIndex", "beforeArgument", "afterArgument",
        "environmentSymbol", "environment", "defaultSymbol",
    }
    setting = str(spec["setting"])
    if not isinstance(conversion, dict) or set(conversion) != required \
            or conversion.get("kind") != "java-constructor-binding-conversion-v1":
        return [f"{setting}: assistant conversion authority has an unsupported shape"]
    path = ASSISTANT_CONFIGURATION_PATH.as_posix()
    errors: list[str] = []
    transition, before_source, after_source = revision_transition_errors(
        root, setting, conversion, path=path, symbol="AssistantConfiguration", label="conversion")
    errors.extend(transition)
    before = str(conversion["beforeRevision"])
    after = str(conversion["afterRevision"])
    if commit_exists(root, before) and commit_exists(root, after):
        parent = subprocess.run(
            ["git", "rev-parse", f"{after}^"], cwd=root, capture_output=True, text=True)
        if parent.returncode != 0 or parent.stdout.strip() != before:
            errors.append(f"{setting}: assistant conversion revisions must be direct parent/child")
    metadata = {
        "path": path, "ownerType": "AssistantConfiguration", "method": "fromEnvironment",
        "constructorType": "AssistantConfiguration", "component": spec["component"],
        "componentIndex": spec["componentIndex"], "environmentSymbol": spec["environmentSymbol"],
        "environment": spec["environment"], "defaultSymbol": spec["defaultSymbol"],
    }
    if any(conversion.get(key) != value for key, value in metadata.items()):
        errors.append(f"{setting}: assistant conversion metadata does not match its source family")
    if before_source is not None and after_source is not None:
        before_call = assistant_limit_binding_call(before_source, str(spec["component"]))
        after_call = assistant_limit_binding_call(after_source, str(spec["component"]))
        expected_before = str(spec["defaultSymbol"])
        expected_after = (
            f"boundedPositiveInteger(env.get({spec['environmentSymbol']}), "
            f"{spec['environmentSymbol']}, {spec['defaultSymbol']})")
        if before_call is None or before_call[0] != expected_before \
                or conversion.get("beforeArgument") != expected_before \
                or after_call is None or after_call[0] != expected_after \
                or conversion.get("afterArgument") != expected_after:
            errors.append(f"{setting}: assistant conversion constructor arguments have drifted")
        before_env = java_static_final_initializer(
            before_source, "AssistantConfiguration", str(spec["environmentSymbol"]))
        after_env = java_static_final_initializer(
            after_source, "AssistantConfiguration", str(spec["environmentSymbol"]))
        before_default = java_static_final_initializer(
            before_source, "AssistantConfiguration", str(spec["defaultSymbol"]))
        after_default = java_static_final_initializer(
            after_source, "AssistantConfiguration", str(spec["defaultSymbol"]))
        if before_env is not None or after_env is None \
                or after_env[0] != f'"{spec["environment"]}"' \
                or before_default is None or after_default is None \
                or before_default[0] != after_default[0]:
            errors.append(f"{setting}: assistant conversion declaration/default transition has drifted")
    return errors


def assistant_limit_resolver_errors(root: Path, resolver: object) -> list[str]:
    required = {
        "kind", "path", "type", "factoryMethod", "factoryBodyDigest", "integerMethod",
        "integerBodyDigest", "dependencyBodyDigests", "testPath", "testType", "testBodyDigests",
        "testHelperBodyDigests",
    }
    if not isinstance(resolver, dict) or set(resolver) != required \
            or resolver.get("kind") != "java-symbol-bounded-positive-integer-resolver-v1":
        return ["assistant operational limits require one exact resolver authority"]
    errors: list[str] = []
    path = ASSISTANT_CONFIGURATION_PATH
    test_path = ASSISTANT_CONFIGURATION_TEST_PATH
    if resolver.get("path") != path.as_posix() or resolver.get("type") != "AssistantConfiguration" \
            or resolver.get("factoryMethod") != "fromEnvironment" \
            or resolver.get("integerMethod") != "boundedPositiveInteger":
        errors.append("assistant resolver source identity has drifted")
    source = (root / path).read_text(encoding="utf-8")
    if resolver.get("factoryBodyDigest") != java_method_digest(
            source, "AssistantConfiguration", "fromEnvironment") \
            or resolver.get("integerBodyDigest") != java_method_digest(
                source, "AssistantConfiguration", "boundedPositiveInteger"):
        errors.append("assistant resolver factory/helper digest has drifted")
    dependencies = resolver.get("dependencyBodyDigests")
    expected_dependencies = {
        method: java_method_digest(source, "AssistantConfiguration", method)
        for method in ("trimmed", "boundedIntegerRefusal")
    }
    integer_span = java_method_span(source, "AssistantConfiguration", "boundedPositiveInteger")
    integer_code = normalized(strip_c_comments(
        source[slice(*integer_span)] if integer_span else ""))
    expected_integer_code = normalized("""
        boundedPositiveInteger(String value, String variable, int defaultAndMaximum) {
            String normalized = trimmed(value);
            if (normalized == null) { return defaultAndMaximum; }
            int parsed;
            try { parsed = Integer.parseInt(normalized); }
            catch (NumberFormatException invalid) {
                throw boundedIntegerRefusal(variable, defaultAndMaximum);
            }
            if (parsed < 1 || parsed > defaultAndMaximum) {
                throw boundedIntegerRefusal(variable, defaultAndMaximum);
            }
            return parsed;
        }
    """)
    if dependencies != expected_dependencies \
            or java_method_header(source, "AssistantConfiguration", "boundedPositiveInteger") != \
            "private static int boundedPositiveInteger(String value, String variable, int defaultAndMaximum)" \
            or integer_code != expected_integer_code:
        errors.append("assistant resolver helper closure/contract has drifted")
    trimmed_span = java_method_span(source, "AssistantConfiguration", "trimmed")
    trimmed_code = normalized(strip_c_comments(
        source[slice(*trimmed_span)] if trimmed_span else ""))
    refusal = java_direct_return_expression(
        source, "AssistantConfiguration", "boundedIntegerRefusal")
    expected_trimmed_code = normalized("""
        trimmed(String value) {
            if (value == null) { return null; }
            String stripped = value.strip();
            return stripped.isEmpty() ? null : stripped;
        }
    """)
    normal_imports = re.findall(
        r"(?m)^\s*import\s+(?!static\s)([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)\s*;",
        strip_c_comments_and_literals(source),
    )
    java_lang_names = {"Integer", "NumberFormatException", "IllegalArgumentException"}
    if trimmed_code != expected_trimmed_code \
            or java_method_header(source, "AssistantConfiguration", "trimmed") != \
            "private static String trimmed(String value)" \
            or refusal is None \
            or refusal != normalized(
                'new IllegalArgumentException(variable + " must be a whole number from 1 to " + maximum)') \
            or java_method_header(source, "AssistantConfiguration", "boundedIntegerRefusal") != \
            "private static IllegalArgumentException boundedIntegerRefusal(String variable, int maximum)" \
            or any(item.rsplit(".", 1)[-1] in java_lang_names for item in normal_imports) \
            or not java_has_no_simple_name_shadow(
                source, "AssistantConfiguration", java_lang_names):
        errors.append("assistant resolver blank/refusal dependency structure has drifted")
    if resolver.get("testPath") != test_path.as_posix() \
            or resolver.get("testType") != "AssistantConfigurationTest":
        errors.append("assistant resolver test identity has drifted")
        return errors
    test_source = (root / test_path).read_text(encoding="utf-8")
    roles = (
        "assistantOperationalLimitsDefaultAndTightenIndependently",
        "invalidAssistantOperationalLimitsAreCauseFreeAndDoNotEchoValues",
        "compactConstructorKeepsItsCompatibilityFallbacks",
    )
    recorded = resolver.get("testBodyDigests")
    helper_recorded = resolver.get("testHelperBodyDigests")
    if not isinstance(recorded, dict) or set(recorded) != set(roles) \
            or not java_test_type_is_directly_runnable(test_source, "AssistantConfigurationTest") \
            or not exact_import_identity(test_source, "org.junit.jupiter.api.Test") \
            or not java_has_exact_junit_assertions(
                test_source, "AssistantConfigurationTest",
                {"assertEquals", "assertFalse", "assertNull", "assertThrows", "assertTrue"}):
        errors.append("assistant resolver runnable test authority is incomplete")
    for method in roles:
        if java_method_header(test_source, "AssistantConfigurationTest", method) != f"void {method}()" \
                or java_method_annotations(test_source, "AssistantConfigurationTest", method) != ("@Test",) \
                or not isinstance(recorded, dict) \
                or recorded.get(method) != java_method_digest(
                    test_source, "AssistantConfigurationTest", method):
            errors.append(f"assistant resolver test role {method} has drifted")
    default_span = java_method_span(
        test_source, "AssistantConfigurationTest",
        "assistantOperationalLimitsDefaultAndTightenIndependently")
    default_code = normalized(strip_c_comments_and_literals(
        test_source[slice(*default_span)] if default_span else ""))
    required_default_clauses = (
        "AssistantConfiguration.fromEnvironment(Map.of())",
        "AssistantConfiguration.DEFAULT_MAX_OUTPUT_TOKENS",
        "defaults.maxOutputTokens()",
        "AssistantConfiguration.DEFAULT_MAX_TOOL_ITERATIONS",
        "defaults.maxToolIterations()",
        "for (String blank : new String[]",
        "blankConfiguration.maxOutputTokens()",
        "blankConfiguration.maxToolIterations()",
        "outputOnly.maxOutputTokens()", "outputOnly.maxToolIterations()",
        "iterationsOnly.maxOutputTokens()", "iterationsOnly.maxToolIterations()",
        "maxima.maxOutputTokens()", "maxima.maxToolIterations()",
    )
    invalid_span = java_method_span(
        test_source, "AssistantConfigurationTest",
        "invalidAssistantOperationalLimitsAreCauseFreeAndDoNotEchoValues")
    invalid_code = normalized(strip_c_comments_and_literals(
        test_source[slice(*invalid_span)] if invalid_span else ""))
    if any(normalized(clause) not in default_code for clause in required_default_clauses) \
            or invalid_code.count("assertInvalidLimit(") != 2 \
            or "AssistantConfiguration.MAX_OUTPUT_TOKENS_VARIABLE" not in invalid_code \
            or "AssistantConfiguration.MAX_TOOL_ITERATIONS_VARIABLE" not in invalid_code \
            or "AssistantConfiguration.DEFAULT_MAX_OUTPUT_TOKENS" not in invalid_code \
            or "AssistantConfiguration.DEFAULT_MAX_TOOL_ITERATIONS" not in invalid_code:
        errors.append("assistant resolver runnable test clauses have drifted")
    helper_method = "assertInvalidLimit"
    helper_span = java_method_span(test_source, "AssistantConfigurationTest", helper_method)
    helper_code = normalized(strip_c_comments_and_literals(
        test_source[slice(*helper_span)] if helper_span else ""))
    if helper_recorded != {helper_method: java_method_digest(
            test_source, "AssistantConfigurationTest", helper_method)} \
            or java_method_header(test_source, "AssistantConfigurationTest", helper_method) != \
            "private static void assertInvalidLimit(String variable, int maximum, String... invalidValues)" \
            or any(expression not in helper_code for expression in (
                "for (String invalid : invalidValues)",
                "AssistantConfiguration.fromEnvironment(Map.of(variable, invalid))",
                "variable + + maximum", "failure.getCause()", "failure.getMessage().contains(",
            )):
        errors.append("assistant resolver invalid-value test helper closure has drifted")
    return errors


def assistant_limit_consumer_errors(root: Path, consumer: object) -> list[str]:
    required = {"path", "type", "method", "bodyDigest", "testPath", "testType", "testBodyDigest"}
    if not isinstance(consumer, dict) or set(consumer) != required:
        return ["assistant operational limits require exact live consumer evidence"]
    errors: list[str] = []
    source = (root / ASSISTANT_SERVICE_PATH).read_text(encoding="utf-8")
    if consumer.get("path") != ASSISTANT_SERVICE_PATH.as_posix() \
            or consumer.get("type") != "AssistantService" or consumer.get("method") != "send" \
            or consumer.get("bodyDigest") != java_method_digest(source, "AssistantService", "send") \
            or not exact_import_identity(
                source, "ai.ravenroot.server.assistant.provider.AssistantProvider") \
            or not java_has_no_simple_name_shadow(
                source, "AssistantService", {"AssistantProvider"}):
        errors.append("assistant live consumer source identity/digest has drifted")
    span = java_method_span(source, "AssistantService", "send")
    actual = source[slice(*span)] if span else ""
    code = strip_c_comments_and_literals(actual)
    loops = list(re.finditer(
        r"for\s*\(\s*int\s+iteration\s*=\s*0\s*;\s*iteration\s*<\s*"
        r"configuration\.maxToolIterations\s*\(\s*\)\s*;\s*iteration\+\+\s*\)\s*\{", code))
    if len(loops) != 1:
        errors.append("assistant live consumer has no exact active configured provider loop")
    else:
        opening = code.find("{", loops[0].start())
        closing = matching_delimiter(code, opening, "{", "}")
        loop_actual = actual[opening + 1:closing] if closing is not None else ""
        loop_code = code[opening + 1:closing] if closing is not None else ""
        calls = list(re.finditer(r"\bturnProvider\.complete\s*\(", loop_code))
        if len(calls) != 1:
            errors.append("assistant live consumer provider call is not uniquely inside the configured loop")
        else:
            call_open = loop_code.find("(", calls[0].start())
            complete = split_java_arguments(loop_actual, loop_code, call_open)
            request = complete[0][0][0] if complete is not None and len(complete[0]) == 1 else ""
            request_code = strip_c_comments_and_literals(request)
            request_match = re.match(r"\s*new\s+AssistantProvider\.Request\s*\(", request_code)
            request_args = (split_java_arguments(
                request, request_code, request_code.find("(", request_match.start()))
                if request_match is not None else None)
            if request_args is None or len(request_args[0]) != 5 \
                    or request_args[0][4][0] != "configuration.maxOutputTokens()":
                errors.append("assistant live consumer output limit is not the exact provider request argument")
    if span is None or not java_span_uses_only_simple_receiver(source, span, "configuration"):
        errors.append("assistant live consumer configuration receiver is shadowed or unsupported")
    if normalized("AssistantProvider turnProvider = providerFor(context.subject());") not in \
            normalized(code) or code.count("AssistantProvider turnProvider") != 1:
        errors.append("assistant live consumer provider selection has drifted")

    test_source = (root / ASSISTANT_SERVICE_TEST_PATH).read_text(encoding="utf-8")
    test_type = "AssistantGraphProposalTest"
    method = "configuredOperationalLimitsReachEveryRequestAndStopTheProviderLoop"
    if consumer.get("testPath") != ASSISTANT_SERVICE_TEST_PATH.as_posix() \
            or consumer.get("testType") != test_type \
            or consumer.get("testBodyDigest") != java_method_digest(test_source, test_type, method) \
            or not java_test_type_is_directly_runnable(test_source, test_type) \
            or not exact_import_identity(test_source, "org.junit.jupiter.api.Test") \
            or not java_has_exact_junit_assertions(
                test_source, test_type, {"assertEquals", "assertInstanceOf", "assertTrue"}) \
            or java_method_header(test_source, test_type, method) != f"void {method}()" \
            or java_method_annotations(test_source, test_type, method) != ("@Test",) \
            or not same_package_type_identity(
                test_source, test_type,
                "ai.ravenroot.server.assistant.AssistantHarness") \
            or not same_package_type_identity(
                test_source, test_type,
                "ai.ravenroot.server.assistant.AssistantOutcome"):
        errors.append("assistant live consumer runnable test authority has drifted")
    test_span = java_method_span(test_source, test_type, method)
    test_code = normalized(strip_c_comments_and_literals(
        test_source[slice(*test_span)] if test_span else ""))
    for expression in (
        "readyConfiguration(17, 2)", "callingTool", "answering",
        "AssistantOutcome.Reason.TOOL_LOOP_EXHAUSTED", "assertEquals(2, provider.callCount())",
        "assertEquals(2, provider.received().size())", "request.maxTokens() == 17",
    ):
        if normalized(expression) not in test_code:
            errors.append(f"assistant live consumer test lost {expression}")
    if test_code.count("callingTool(") != 2 or test_code.count("answering(") != 1:
        errors.append("assistant live consumer test lost its two-call/third-sentinel structure")
    chain = re.compile(
        r"new\s+AssistantHarness\.ScriptedProviderView\s*\(\s*\)\s*"
        r"\.callingTool\s*\([^)]*\)\s*\.callingTool\s*\([^)]*\)\s*"
        r"\.answering\s*\([^)]*\)", re.S)
    if len(chain.findall(strip_c_comments(test_source[slice(*test_span)] if test_span else ""))) != 1:
        errors.append("assistant live consumer test lost its ordered provider script")
    return errors


def assistant_limit_compatibility_errors(root: Path, compatibility: object) -> list[str]:
    required = {"constructorBodyDigest", "testBodyDigest"}
    if not isinstance(compatibility, dict) or set(compatibility) != required:
        return ["assistant operational limits require exact compact-constructor compatibility evidence"]
    source = (root / ASSISTANT_CONFIGURATION_PATH).read_text(encoding="utf-8")
    span = java_compact_constructor_span(source, "AssistantConfiguration")
    constructor = normalized(strip_c_comments_and_literals(source[slice(*span)] if span else ""))
    errors: list[str] = []
    if compatibility.get("constructorBodyDigest") != java_span_digest(source, span) \
            or java_identifier_write_count(constructor, "maxOutputTokens") != 1 \
            or java_identifier_write_count(constructor, "maxToolIterations") != 1 \
            or constructor.count(normalized(
                "maxOutputTokens = maxOutputTokens > 0 ? maxOutputTokens : DEFAULT_MAX_OUTPUT_TOKENS;")) != 1 \
            or constructor.count(normalized(
                "maxToolIterations = maxToolIterations > 0 ? maxToolIterations : DEFAULT_MAX_TOOL_ITERATIONS;")) != 1:
        errors.append("assistant compact-constructor field-specific compatibility has drifted")
    test_source = (root / ASSISTANT_CONFIGURATION_TEST_PATH).read_text(encoding="utf-8")
    method = "compactConstructorKeepsItsCompatibilityFallbacks"
    test_span = java_method_span(test_source, "AssistantConfigurationTest", method)
    test_code = normalized(strip_c_comments_and_literals(
        test_source[slice(*test_span)] if test_span else ""))
    required_test_clauses = (
        "new AssistantConfiguration(true, null, null, null, null, OutboundHttpPolicy.disabled(), Duration.ZERO, 0, -1)",
        "AssistantConfiguration.DEFAULT_MAX_OUTPUT_TOKENS",
        "configuration.maxOutputTokens()",
        "AssistantConfiguration.DEFAULT_MAX_TOOL_ITERATIONS",
        "configuration.maxToolIterations()",
        "AssistantConfiguration.DEFAULT_MAX_OUTPUT_TOKENS + 1",
        "positiveValues.maxOutputTokens()",
        "AssistantConfiguration.DEFAULT_MAX_TOOL_ITERATIONS + 1",
        "positiveValues.maxToolIterations()",
    )
    if compatibility.get("testBodyDigest") != java_method_digest(
            test_source, "AssistantConfigurationTest", method) \
            or any(normalized(clause) not in test_code for clause in required_test_clauses):
        errors.append("assistant compact-constructor compatibility test has drifted")
    return errors


def assistant_limit_family_index(authorities: object) -> dict[str, dict[str, object]]:
    """Return only the checker-owned, exact two-setting family metadata."""
    if not isinstance(authorities, dict) or set(authorities) != {ASSISTANT_LIMIT_FAMILY_ID}:
        return {}
    family = authorities[ASSISTANT_LIMIT_FAMILY_ID]
    required = {
        "kind", "settings", "resolverAuthority", "conversionAuthorities", "carrierEvidence",
        "consumerAuthority", "compatibilityAuthority", "platformTestDigest",
    }
    if not isinstance(family, dict) or set(family) != required \
            or family.get("kind") != "assistant-symbol-operational-limits-v1" \
            or not isinstance(family.get("settings"), list) or len(family["settings"]) != 2 \
            or not isinstance(family.get("conversionAuthorities"), dict) \
            or not isinstance(family.get("carrierEvidence"), dict):
        return {}
    settings = {str(item.get("setting")): item
                for item in family["settings"] if isinstance(item, dict)}
    expected = {str(item["setting"]) for item in ASSISTANT_LIMIT_SETTINGS}
    if set(settings) != expected or set(family["conversionAuthorities"]) != expected \
            or set(family["carrierEvidence"]) != expected:
        return {}
    return {
        setting: {
            "settingAuthority": settings[setting],
            "conversion": family["conversionAuthorities"][setting],
            "carrierEvidence": family["carrierEvidence"][setting],
        }
        for setting in expected
    }


def assistant_limit_expected_entry_ids(source: str, spec: dict[str, object],
                                       discovered: dict[str, Candidate]) -> tuple[
                                           list[str], list[str], set[str]]:
    source_rows = java_source_candidates(ASSISTANT_CONFIGURATION_PATH, source)
    declaration_ids = sorted(
        candidate.id for offset, candidate in source_rows
        if spec["environmentSpan"][0] <= offset < spec["environmentSpan"][1])
    default_ids = candidate_ids_in_source_span(
        ASSISTANT_CONFIGURATION_PATH, source, *spec["defaultSpan"],
        "fixed-declaration", str(spec["defaultSymbol"]), discovered)
    carrier_ids = {
        candidate.id for candidate in discovered.values()
        if any(candidate.path in paths for paths in ASSISTANT_CARRIER_PATHS.values())
        and candidate.kind == "environment-binding"
        and candidate.expression == spec["environment"]
    }
    return declaration_ids, default_ids, set(declaration_ids) | set(default_ids) | carrier_ids


def assistant_limit_entry_adapter_errors(root: Path, setting: str,
                                         setting_entries: list[dict[str, object]],
                                         entries: dict[str, dict[str, object]],
                                         discovered: dict[str, Candidate],
                                         authorities: object) -> list[str]:
    """Tie generic inventory rows to the fixed Assistant family without an opt-out flag."""
    index = assistant_limit_family_index(authorities)
    family = index.get(setting)
    source = (root / ASSISTANT_CONFIGURATION_PATH).read_text(encoding="utf-8")
    specs = assistant_limit_source_specs(source) or []
    spec = next((item for item in specs if item["setting"] == setting), None)
    if family is None or spec is None:
        return [f"{setting}: assistant inventory row has no exact family authority"]
    setting_authority = family["settingAuthority"]
    if not isinstance(setting_authority, dict):
        return [f"{setting}: assistant family setting authority is malformed"]
    declaration_ids, default_ids, expected_ids = assistant_limit_expected_entry_ids(
        source, spec, discovered)
    actual_ids = {str(entry["id"]) for entry in setting_entries}
    errors: list[str] = []
    if actual_ids != expected_ids:
        errors.append(f"{setting}: assistant inventory rows do not equal the source-derived partition")
    expected_binding = setting_authority.get("bindingAuthority")
    expected_default = setting_authority.get("defaultAuthority")
    expected_carrier = family["carrierEvidence"]
    expected_conversion = family["conversion"]
    for entry in setting_entries:
        identifier = str(entry["id"])
        if entry.get("bindingAuthority") != expected_binding \
                or entry.get("defaultAuthority") != expected_default \
                or entry.get("carrierEvidence") != expected_carrier \
                or entry.get("conversion") != expected_conversion:
            errors.append(f"{identifier}: assistant row authority metadata differs from its family")
        if entry.get("defaultEvidence") != default_ids:
            errors.append(f"{identifier}: assistant defaultEvidence differs from its direct default atom")
        if entry.get("owner") != f"{ASSISTANT_CONFIGURATION_PATH.as_posix()}#AssistantConfiguration" \
                or entry.get("field") != spec["component"] \
                or entry.get("bindings") != [spec["environment"]] \
                or entry.get("default") != str(spec["defaultValue"]):
            errors.append(f"{identifier}: assistant generic setting metadata has drifted")
    return errors


def assistant_limit_authority_errors(root: Path, authorities: object,
                                     entries: dict[str, dict[str, object]],
                                     discovered: dict[str, Candidate]) -> list[str]:
    """Verify the closed two-setting assistant limit family before public classification."""
    source_owner = f"{ASSISTANT_CONFIGURATION_PATH.as_posix()}#AssistantConfiguration"
    if current_source_owner(root, source_owner) is None:
        return [] if authorities in (None, {}) else ["assistant limit authority exists without its source family"]
    source = (root / ASSISTANT_CONFIGURATION_PATH).read_text(encoding="utf-8")
    derived = assistant_limit_source_specs(source)
    if derived is None:
        return ["AssistantConfiguration operational-limit source family has drifted"]
    if not isinstance(authorities, dict) or set(authorities) != {ASSISTANT_LIMIT_FAMILY_ID}:
        return ["AssistantConfiguration operational limits require one closed family authority"]
    authority = authorities[ASSISTANT_LIMIT_FAMILY_ID]
    required = {
        "kind", "settings", "resolverAuthority", "conversionAuthorities", "carrierEvidence",
        "consumerAuthority", "compatibilityAuthority", "platformTestDigest",
    }
    if not isinstance(authority, dict) or set(authority) != required \
            or authority.get("kind") != "assistant-symbol-operational-limits-v1":
        return ["assistant operational-limit family authority has an unsupported shape"]
    errors: list[str] = []
    family_index = assistant_limit_family_index(authorities)
    settings = authority["settings"]
    if not isinstance(settings, list) or len(settings) != 2:
        return ["assistant operational-limit authority must contain exactly two settings"]
    contracts = {str(item.get("setting")): item for item in settings if isinstance(item, dict)}
    if set(contracts) != {str(item["setting"]) for item in ASSISTANT_LIMIT_SETTINGS}:
        return ["assistant operational-limit setting family is incomplete"]
    conversions = authority["conversionAuthorities"]
    carriers = authority["carrierEvidence"]
    if not isinstance(conversions, dict) or set(conversions) != set(contracts) \
            or not isinstance(carriers, dict) or set(carriers) != set(contracts):
        errors.append("assistant conversion/carrier authorities must cover both settings")
    expected_entry_ids: set[str] = set()
    for spec in derived:
        setting = str(spec["setting"])
        contract = contracts[setting]
        if set(contract) != {"setting", "bindingAuthority", "defaultAuthority"}:
            errors.append(f"{setting}: assistant setting authority has an unsupported shape")
            continue
        binding = contract["bindingAuthority"]
        default = contract["defaultAuthority"]
        binding_keys = {
            "kind", "sourceOwner", "method", "constructorType", "component", "componentIndex",
            "helper", "environmentSymbol", "environment", "environmentCandidateId",
            "declarationCandidateIds", "callDigest", "resolverAuthority",
        }
        default_keys = {
            "kind", "owner", "field", "componentIndex", "constant", "sourceExpression",
            "candidateIds", "evaluatedDefault",
        }
        if not isinstance(binding, dict) or set(binding) != binding_keys \
                or binding.get("kind") != "java-symbol-environment-constructor-v1" \
                or not isinstance(default, dict) or set(default) != default_keys \
                or default.get("kind") != "java-static-final-int-default-v1":
            errors.append(f"{setting}: assistant binding/default authority has an unsupported shape")
            continue
        expected_binding = {
            "sourceOwner": source_owner, "method": "fromEnvironment",
            "constructorType": "AssistantConfiguration", "component": spec["component"],
            "componentIndex": spec["componentIndex"], "helper": "boundedPositiveInteger",
            "environmentSymbol": spec["environmentSymbol"], "environment": spec["environment"],
            "resolverAuthority": "assistant-bounded-positive-integer-v1",
        }
        expected_default = {
            "owner": source_owner, "field": spec["component"],
            "componentIndex": spec["componentIndex"], "constant": spec["defaultSymbol"],
            "sourceExpression": spec["defaultExpression"], "evaluatedDefault": spec["defaultValue"],
        }
        if contract.get("setting") != setting \
                or any(binding.get(key) != value for key, value in expected_binding.items()) \
                or any(default.get(key) != value for key, value in expected_default.items()) \
                or binding.get("callDigest") != hashlib.sha256(
                    str(spec["call"]).encode("utf-8")).hexdigest():
            errors.append(f"{setting}: assistant symbol binding/default metadata has drifted")
        env_ids = candidate_ids_in_source_span(
            ASSISTANT_CONFIGURATION_PATH, source, *spec["environmentSpan"],
            "environment-binding", str(spec["environment"]), discovered)
        declaration_ids = sorted(
            candidate.id for candidate in discovered.values()
            if candidate.path == ASSISTANT_CONFIGURATION_PATH.as_posix()
            and spec["environmentSpan"][0] <= next(
                (offset for offset, item in java_source_candidates(ASSISTANT_CONFIGURATION_PATH, source)
                 if item.id == candidate.id), -1) < spec["environmentSpan"][1]
        )
        default_ids = candidate_ids_in_source_span(
            ASSISTANT_CONFIGURATION_PATH, source, *spec["defaultSpan"],
            "fixed-declaration", str(spec["defaultSymbol"]), discovered)
        if binding.get("environmentCandidateId") not in env_ids or env_ids != [
                binding.get("environmentCandidateId")]:
            errors.append(f"{setting}: assistant environment declaration binding has drifted")
        if binding.get("declarationCandidateIds") != declaration_ids \
                or default.get("candidateIds") != default_ids:
            errors.append(f"{setting}: assistant declaration/default candidate partition has drifted")
        carrier_errors, carrier_ids = assistant_limit_carrier_errors(
            root, spec, carriers.get(setting) if isinstance(carriers, dict) else None,
            entries, discovered)
        errors.extend(carrier_errors)
        assigned = set(declaration_ids) | set(default_ids) | carrier_ids
        expected_entry_ids.update(assigned)
        if any(entries.get(identifier, {}).get("setting") != setting for identifier in assigned):
            errors.append(f"{setting}: assistant proof candidates are absent or assigned elsewhere")
        if any(entries.get(identifier, {}).get("status") != "converted"
               or entries.get(identifier, {}).get("classification") != "operator-configurable"
               for identifier in assigned):
            errors.append(f"{setting}: assistant proof candidates must all be converted operator settings")
        errors.extend(assistant_limit_conversion_errors(
            root, spec, conversions.get(setting) if isinstance(conversions, dict) else None))
    errors.extend(assistant_limit_resolver_errors(root, authority["resolverAuthority"]))
    errors.extend(assistant_limit_consumer_errors(root, authority["consumerAuthority"]))
    errors.extend(assistant_limit_compatibility_errors(root, authority["compatibilityAuthority"]))
    platform = (root / ASSISTANT_PLATFORM_TEST_PATH).read_text(encoding="utf-8")
    if authority["platformTestDigest"] != hashlib.sha256(platform.encode("utf-8")).hexdigest() \
            or any(platform.count(
                f"{spec['environment']} {spec['component']} {spec['defaultSymbol']} "
                f"assistant.{spec['helmField']}") != 1 for spec in ASSISTANT_LIMIT_SETTINGS):
        errors.append("assistant platform carrier test source evidence has drifted")
    fixed_settings = set(family_index)
    actual_entry_ids = {
        identifier for identifier, entry in entries.items()
        if entry.get("setting") in fixed_settings
    }
    if actual_entry_ids != expected_entry_ids:
        errors.append("assistant inventory assignments do not equal the independently derived family rows")
    specialized_rows = {
        identifier for identifier, entry in entries.items()
        if isinstance(entry.get("bindingAuthority"), dict)
        and entry["bindingAuthority"].get("kind") == "java-symbol-environment-constructor-v1"
    }
    if any(entries[identifier].get("setting") not in fixed_settings for identifier in specialized_rows):
        errors.append("assistant specialized authority cannot declare an unknown setting")
    return errors


def execution_runtime_expected_entry_ids(
        root: Path, discovered: dict[str, Candidate]) -> tuple[dict[str, dict[str, list[str]]], list[str]]:
    """Derive the accepted four-setting 10+15 partitions from bounded live discovery."""
    errors: list[str] = []
    result: dict[str, dict[str, list[str]]] = {}
    publisher = "scripts/publish_environment_reference.py"
    schema_path = "deploy/helm/ravenroot/values.schema.json"
    template_path = "deploy/helm/ravenroot/templates/deployment.yaml"
    raw_path = "deploy/kubernetes/ravenroot.yaml"
    values_path = "deploy/helm/ravenroot/values.yaml"

    candidates = list(discovered.values())
    for spec in EXECUTION_RUNTIME_SETTINGS:
        setting = str(spec["setting"])
        environment = str(spec["environment"])
        environment_rows = [candidate for candidate in candidates
                            if candidate.kind == "environment-binding"
                            and candidate.expression == environment]
        production_environment = [candidate for candidate in environment_rows
                                  if candidate.path != publisher]
        schema_environment = [candidate for candidate in production_environment
                              if candidate.path == schema_path]
        template_environment = [candidate for candidate in production_environment
                                if candidate.path == template_path]
        raw_environment = [candidate for candidate in production_environment
                           if candidate.path == raw_path]
        publisher_environment = [candidate for candidate in environment_rows
                                 if candidate.path == publisher]
        java_declaration = [candidate for candidate in candidates
                            if candidate.path == EXECUTION_RUNTIME_CONFIGURATION_PATH.as_posix()
                            and candidate.kind == "fixed-declaration"
                            and candidate.expression == json.dumps(environment)]
        canonical_default = [candidate for candidate in candidates
                             if candidate.path == Path(spec["defaultPath"]).as_posix()
                             and candidate.kind == "fixed-declaration"
                             and candidate.expression == spec["defaultExpression"]]
        helm_blank = [candidate for candidate in candidates
                      if candidate.path == values_path
                      and candidate.kind == "configuration-scalar"
                      and candidate.role == spec["helm"] and candidate.expression == '""']
        schema_reference = [candidate for candidate in candidates
                            if candidate.path == schema_path
                            and candidate.kind == "schema-reference-binding"
                            and candidate.expression == "#/definitions/graphBlank"
                            and str(spec["helm"]) in candidate.role]
        if not (len(production_environment) == 6 and len(schema_environment) == 1
                and len(template_environment) == 1 and len(raw_environment) == 1
                and len(publisher_environment) == 1
                and len(java_declaration) == len(canonical_default) == len(helm_blank)
                == len(schema_reference) == 1):
            errors.append(f"{setting}: engine operator candidate topology has drifted")
            result[setting] = {"operatorCandidateIds": [], "supportingCandidateIds": []}
            continue

        schema_lines = {schema_environment[0].line, schema_environment[0].line + 1}
        schema_support = [candidate for candidate in candidates
                          if candidate.path == schema_path and candidate.line in schema_lines
                          and candidate.kind == "configuration-scalar"]
        template_support = [candidate for candidate in candidates
                            if candidate.path == template_path
                            and candidate.line == template_environment[0].line + 1
                            and candidate.kind == "configuration-scalar"]
        raw_support = [candidate for candidate in candidates
                       if candidate.path == raw_path and candidate.line == raw_environment[0].line + 1
                       and candidate.kind == "configuration-scalar" and candidate.expression == '""']
        publisher_support = [candidate for candidate in candidates
                             if candidate.path == publisher
                             and candidate.line == publisher_environment[0].line]
        operator = production_environment + java_declaration + canonical_default + helm_blank + schema_reference
        supporting = schema_support + template_support + raw_support + publisher_support
        if len(operator) != 10 or len({candidate.id for candidate in operator}) != 10:
            errors.append(f"{setting}: engine operator partition must contain 10 unique candidates")
        if len(schema_support) != 11 or len(template_support) != 1 or len(raw_support) != 1 \
                or len(publisher_support) != 2 or len(supporting) != 15 \
                or len({candidate.id for candidate in supporting}) != 15:
            errors.append(f"{setting}: engine supporting partition must contain exact 11+1+1+2 roles")
        if {candidate.id for candidate in operator} & {candidate.id for candidate in supporting}:
            errors.append(f"{setting}: engine operator and supporting partitions overlap")
        result[setting] = {
            "operatorCandidateIds": sorted(candidate.id for candidate in operator),
            "supportingCandidateIds": sorted(candidate.id for candidate in supporting),
        }
    union = [identifier for partition in result.values() for key in (
        "operatorCandidateIds", "supportingCandidateIds") for identifier in partition[key]]
    if len(union) != 100 or len(set(union)) != 100:
        errors.append("execution runtime family must map exactly 100 unique candidates")
    return result, errors


def execution_runtime_source_errors(root: Path) -> list[str]:
    """Pin reviewed complex bodies and verify the live server/CLI composition seams."""
    errors: list[str] = []
    for path in sorted(EXECUTION_RUNTIME_SOURCE_FINAL_PATHS):
        expected = committed_source(root, EXECUTION_RUNTIME_CURRENT_SOURCE_REVISION, path.as_posix())
        try:
            actual = (root / path).read_text(encoding="utf-8")
        except OSError:
            actual = None
        if expected is None or actual != expected:
            errors.append(f"execution runtime reviewed source has drifted: {path.as_posix()}")

    configuration = (root / EXECUTION_RUNTIME_CONFIGURATION_PATH).read_text(encoding="utf-8")
    policy = (root / EXECUTION_ENGINE_POLICY_PATH).read_text(encoding="utf-8")
    terminal = (root / TERMINAL_NODE_HISTORY_PATH).read_text(encoding="utf-8")
    if java_record_components(configuration, "ExecutionRuntimeConfiguration") != \
            ("enginePolicy", "runnerShutdownStepBound") \
            or java_record_components(policy, "ExecutionEnginePolicy") != \
            ("maxStashedCommandsPerNode", "lifecycleStepBound", "terminalNodeHistoryCapacity") \
            or java_static_final_initializer(terminal, "TerminalNodeHistory", "DEFAULT_CAPACITY") is None \
            or java_static_final_initializer(terminal, "TerminalNodeHistory", "DEFAULT_CAPACITY")[0] != "1024" \
            or normalized("this(DEFAULT_CAPACITY)") not in normalized(strip_c_comments_and_literals(terminal)):
        errors.append("execution runtime typed policy/default structure has drifted")

    cli = (root / EXECUTION_RUNTIME_CLI_PATH).read_text(encoding="utf-8")
    pinned_cli = committed_source(
        root, EXECUTION_RUNTIME_CURRENT_SOURCE_REVISION, EXECUTION_RUNTIME_CLI_PATH.as_posix())
    if pinned_cli is None or any(
            java_method_digest(cli, "RavenrootCliMain", method) !=
            java_method_digest(pinned_cli, "RavenrootCliMain", method)
            for method in ("main", "embeddedRuntime")):
        errors.append("execution runtime embedded CLI composition has drifted")
    cli_main = java_method_span(cli, "RavenrootCliMain", "main")
    cli_code = normalized(strip_c_comments(cli[slice(*cli_main)] if cli_main else ""))
    if "return;" not in cli_code or "embeddedRuntime(System.getenv(), ExecutionEngines::create)" not in cli_code \
            or cli_code.index("return;") > cli_code.index("embeddedRuntime(System.getenv(), ExecutionEngines::create)"):
        errors.append("execution runtime CLI remote return/local composition order has drifted")

    server = (root / EXECUTION_RUNTIME_SERVER_PATH).read_text(encoding="utf-8")
    pinned_server = committed_source(
        root, EXECUTION_RUNTIME_CURRENT_SOURCE_REVISION, EXECUTION_RUNTIME_SERVER_PATH.as_posix())
    server_run = java_method_span(server, "RavenrootServerMain", "run")
    server_code = normalized(strip_c_comments(
        server[slice(*server_run)] if server_run else ""))
    runtime_call = "ResolvedExecutionRuntime.fromEnvironment(System.getenv())"
    engine_call = 'executionRuntime.createEngine(engineId, "ravenroot-server", ExecutionEngines::create)'
    stores = list(re.finditer(
        r"ExecutionStoreConfiguration\s*\.\s*(?:fromEnvironment|resolveEnvironment)"
        r"\(System\.getenv\(\)\)", server_code))
    if pinned_server is None or java_method_digest(server, "RavenrootServerMain", "run") != \
            java_method_digest(pinned_server, "RavenrootServerMain", "run") \
            or runtime_call not in server_code or engine_call not in server_code or len(stores) != 1 \
            or not server_code.index(runtime_call) < stores[0].start() < server_code.index(engine_call):
        errors.append("execution runtime server resolve/store/engine startup order has drifted")

    bootstrap = (root / EXECUTION_STORE_BOOTSTRAP_PATH).read_text(encoding="utf-8")
    configured = java_method_span(bootstrap, "ExecutionStoreBootstrap", "openConfigured")
    bootstrap_code = normalized(strip_c_comments(
        bootstrap[slice(*configured)] if configured else ""))
    bootstrap_clauses = (
        "int busyTimeoutMillis = Math.toIntExact(storeConfig.busyTimeout().toMillis())",
        "new SqliteGraphDefinitionStore(configuration.location(), clock, "
        "ai.ravenroot.api.persistence.GraphDefinitionReferences.NONE, "
        "graphMlLimits.maxBytes(), busyTimeoutMillis)",
        "new SqliteExecutionManifestStore(configuration.location(), clock, "
        "ai.ravenroot.api.persistence.ExecutionManifestReferences.NONE, busyTimeoutMillis)",
    )
    if any(normalized(clause) not in bootstrap_code for clause in bootstrap_clauses):
        errors.append("execution store graph/manifest busy-timeout bootstrap seam has drifted")

    errors.extend(execution_runtime_test_source_errors(root))
    return errors


def execution_runtime_test_source_errors(
        root: Path, source_overrides: dict[str, str] | None = None) -> list[str]:
    """Pin the runnable tests that substantiate the accepted engine and bootstrap seams."""
    errors: list[str] = []
    overrides = source_overrides or {}
    for path, type_symbol, method in EXECUTION_RUNTIME_TEST_AUTHORITIES:
        try:
            source = overrides[path] if path in overrides \
                else (root / path).read_text(encoding="utf-8")
        except OSError:
            errors.append(f"execution runtime runnable test is absent: {path}#{method}")
            continue
        pinned = committed_source(root, EXECUTION_RUNTIME_CURRENT_SOURCE_REVISION, path)
        if pinned is None or source != pinned:
            errors.append(f"execution runtime runnable test source identity has drifted: {type_symbol}")
        if java_type_span(source, type_symbol) is None \
                or not exact_import_identity(source, "org.junit.jupiter.api.Test") \
                or java_method_annotations(source, type_symbol, method) != ("@Test",) \
                or re.search(r"@(?:org\.junit\.jupiter\.api\.)?Disabled\b",
                             strip_c_comments_and_literals(source)) is not None:
            errors.append(f"execution runtime runnable test authority has drifted: {type_symbol}#{method}")
    return errors


def execution_runtime_authority_from_source(
        root: Path, discovered: dict[str, Candidate]) -> dict[str, object] | None:
    settings, errors = execution_runtime_expected_entry_ids(root, discovered)
    if errors:
        return None
    return {
        "kind": EXECUTION_RUNTIME_FAMILY_ID,
        "claimBaseRevision": EXECUTION_RUNTIME_CLAIM_BASE_REVISION,
        "sourceFinalRevision": EXECUTION_RUNTIME_SOURCE_FINAL_REVISION,
        "currentSourceRevision": EXECUTION_RUNTIME_CURRENT_SOURCE_REVISION,
        "settings": settings,
        "implementationStages": [
            {"revision": revision, "purpose": purpose}
            for revision, purpose in EXECUTION_RUNTIME_STAGES
        ],
        "candidateTransitions": [
            {"candidateIdentity": identity, "setting": setting, "category": category,
             "beforeRevision": before, "afterRevision": after, "redundancyDelta": 0}
            for identity, setting, category, before, after in EXECUTION_RUNTIME_TRANSITIONS
        ],
        "currentCarrierRekeys": [
            {"setting": setting, "beforeCandidateId": before_id,
             "afterCandidateId": after_id,
             "path": "deploy/helm/ravenroot/values.schema.json",
             "kind": "configuration-scalar", "role": "x-ravenroot-environment",
             "expression": '"x-ravenroot-environment"',
             "evidenceDigest": evidence_digest,
             "beforeRevision": EXECUTION_RUNTIME_CARRIER_REKEY_PARENT,
             "afterRevision": EXECUTION_RUNTIME_CARRIER_REKEY_REVISION,
             "cause": "preceding persistence schema carrier insertion shifted lexical duplicate identity",
             "redundancyDelta": 0}
            for setting, before_id, after_id, evidence_digest
            in EXECUTION_RUNTIME_CURRENT_CARRIER_REKEYS
        ],
    }


def execution_runtime_authority_errors(root: Path, authorities: object,
                                       entries: dict[str, dict[str, object]],
                                       discovered: dict[str, Candidate]) -> list[str]:
    settings = {str(spec["setting"]) for spec in EXECUTION_RUNTIME_SETTINGS}
    assigned = {identifier for identifier, entry in entries.items()
                if entry.get("setting") in settings}
    retained = {identifier for identifier, entry in entries.items()
                if entry.get("retainedAuthority") == EXECUTION_RUNTIME_FAMILY_ID}
    if not assigned and not retained and authorities is None:
        return []
    if not isinstance(authorities, dict) or set(authorities) != {EXECUTION_RUNTIME_FAMILY_ID}:
        return ["execution runtime requires one exact closed family authority"]
    authority = authorities[EXECUTION_RUNTIME_FAMILY_ID]
    required = {"kind", "claimBaseRevision", "sourceFinalRevision", "currentSourceRevision",
                "settings", "implementationStages", "candidateTransitions", "currentCarrierRekeys"}
    if not isinstance(authority, dict) or set(authority) != required:
        return ["execution runtime family authority has an unsupported shape"]
    errors: list[str] = []
    if authority["kind"] != EXECUTION_RUNTIME_FAMILY_ID \
            or authority["claimBaseRevision"] != EXECUTION_RUNTIME_CLAIM_BASE_REVISION \
            or authority["sourceFinalRevision"] != EXECUTION_RUNTIME_SOURCE_FINAL_REVISION \
            or authority["currentSourceRevision"] != EXECUTION_RUNTIME_CURRENT_SOURCE_REVISION:
        errors.append("execution runtime revision authority has drifted")
    expected, partition_errors = execution_runtime_expected_entry_ids(root, discovered)
    errors.extend(partition_errors)
    if authority["settings"] != expected:
        errors.append("execution runtime recorded candidate partition differs from live bounded discovery")
    expected_stages = [{"revision": revision, "purpose": purpose}
                       for revision, purpose in EXECUTION_RUNTIME_STAGES]
    if authority["implementationStages"] != expected_stages:
        errors.append("execution runtime implementation stage history has drifted")
    history = (EXECUTION_RUNTIME_CLAIM_BASE_REVISION,
               *(revision for revision, _purpose in EXECUTION_RUNTIME_STAGES),
               EXECUTION_RUNTIME_CURRENT_SOURCE_REVISION)
    if any(not commit_exists(root, revision) for revision in history) \
            or any(not revision_is_ancestor(root, before, after)
                   for before, after in zip(history, history[1:])):
        errors.append("execution runtime implementation history is incomplete or out of order")
    expected_transitions = [
        {"candidateIdentity": identity, "setting": setting, "category": category,
         "beforeRevision": before, "afterRevision": after, "redundancyDelta": 0}
        for identity, setting, category, before, after in EXECUTION_RUNTIME_TRANSITIONS
    ]
    if authority["candidateTransitions"] != expected_transitions:
        errors.append("execution runtime candidate transition history or zero-credit accounting has drifted")
    for before, after in sorted({(before, after) for _identity, _setting, _category, before, after
                                 in EXECUTION_RUNTIME_TRANSITIONS}):
        parent = subprocess.run(
            ["git", "rev-parse", f"{after}^"], cwd=root, capture_output=True, text=True)
        if parent.returncode != 0 or parent.stdout.strip() != before:
            errors.append("execution runtime candidate transition is not a direct parent/child change")
    expected_rekeys = execution_runtime_authority_from_source(root, discovered)
    expected_rekeys = expected_rekeys["currentCarrierRekeys"] if expected_rekeys is not None else []
    if authority["currentCarrierRekeys"] != expected_rekeys:
        errors.append("execution runtime current carrier identity rekeys have drifted")
    elif any(item["redundancyDelta"] != 0 for item in authority["currentCarrierRekeys"]):
        errors.append("execution runtime current carrier identity rekeys cannot receive consolidation credit")
    rekey_parent = subprocess.run(
        ["git", "rev-parse", f"{EXECUTION_RUNTIME_CARRIER_REKEY_REVISION}^"],
        cwd=root, capture_output=True, text=True)
    if rekey_parent.returncode != 0 \
            or rekey_parent.stdout.strip() != EXECUTION_RUNTIME_CARRIER_REKEY_PARENT:
        errors.append("execution runtime current carrier rekey revision is not the reviewed direct change")
    schema_path = Path("deploy/helm/ravenroot/values.schema.json")
    before_schema = committed_source(
        root, EXECUTION_RUNTIME_CARRIER_REKEY_PARENT, schema_path.as_posix())
    after_schema = committed_source(
        root, EXECUTION_RUNTIME_CARRIER_REKEY_REVISION, schema_path.as_posix())
    before_candidates = ({candidate.id: candidate for candidate in discover_source_texts(
        {schema_path: before_schema})} if before_schema is not None else {})
    after_candidates = ({candidate.id: candidate for candidate in discover_source_texts(
        {schema_path: after_schema})} if after_schema is not None else {})
    for item in expected_rekeys:
        before_id = str(item["beforeCandidateId"])
        after_id = str(item["afterCandidateId"])
        before_candidate = before_candidates.get(before_id)
        after_candidate = after_candidates.get(after_id)
        candidate = discovered.get(after_id)
        if before_candidate is None or after_candidate is None or candidate is None \
                or any(getattr(candidate, attribute) != item[field] for attribute, field in (
                ("path", "path"), ("kind", "kind"), ("role", "role"),
                ("expression", "expression"), ("evidence_digest", "evidenceDigest"))) \
                or any(getattr(before_candidate, attribute) != getattr(after_candidate, attribute)
                       for attribute in ("path", "symbol", "kind", "role", "expression",
                                         "expression_digest", "evidence", "evidence_digest")):
            errors.append(f"execution runtime current carrier rekey target has drifted: {after_id}")
        setting_partition = expected.get(str(item["setting"]), {})
        supporting = setting_partition.get("supportingCandidateIds", [])
        if after_id not in supporting or item["beforeCandidateId"] in supporting:
            errors.append(f"execution runtime current carrier rekey is not reflected in mapping: {after_id}")
    expected_ids = {identifier for partition in expected.values()
                    for key in ("operatorCandidateIds", "supportingCandidateIds")
                    for identifier in partition[key]}
    if assigned | retained != expected_ids:
        errors.append("execution runtime inventory assignments do not equal the exact 100-row family")
    for setting, partition in expected.items():
        for identifier in partition["operatorCandidateIds"]:
            entry = entries.get(identifier, {})
            if entry.get("setting") != setting or entry.get("status") != "converted" \
                    or entry.get("classification") != "operator-configurable" \
                    or entry.get("executionRuntimeAuthority") != EXECUTION_RUNTIME_FAMILY_ID:
                errors.append(f"{setting}: engine operator row is absent or misclassified: {identifier}")
        for identifier in partition["supportingCandidateIds"]:
            entry = entries.get(identifier, {})
            if entry.get("setting") != setting or entry.get("status") != "retained" \
                    or entry.get("classification") != "derived" \
                    or entry.get("retainedAuthority") != EXECUTION_RUNTIME_FAMILY_ID:
                errors.append(f"{setting}: engine supporting row is absent or misclassified: {identifier}")
    errors.extend(execution_runtime_source_errors(root))
    return errors


def verified_human_task_legacy_delta(document: dict[str, object]) -> int | None:
    retired = document.get("retiredEntries", [])
    if not isinstance(retired, list):
        return None
    actual = [entry for entry in retired if isinstance(entry, dict)
              and str(entry.get("setting", "")).startswith("human-task.")
              and entry.get("status") == "duplicate-removed"]
    if not actual:
        return 0
    expected = set(HUMAN_TASK_LEGACY_CONSOLIDATIONS)
    if {(str(entry.get("id", "")), str(entry.get("setting", ""))) for entry in actual} != expected:
        return None
    owners = {
        "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/persistence/"
        "HumanTaskPolicy.java#HumanTaskPolicy",
        "ravenroot/ravenroot-application-api/src/main/java/ai/ravenroot/api/persistence/"
        "HumanTaskPolicy.java#Confirmation",
    }
    for entry in actual:
        removal = entry.get("removal")
        if entry.get("path") != "deploy/helm/ravenroot/values.yaml" \
                or not isinstance(removal, dict) \
                or removal.get("kind") != "yaml-default-authority-v1" \
                or removal.get("replacementOwner") not in owners:
            return None
    return len(expected)


def authority_consolidation_errors(document: dict[str, object]) -> list[str]:
    consolidations = document.get("authorityConsolidations")
    engine = document.get("executionRuntimeAuthorities")
    if consolidations is None and engine is None:
        return []
    expected = [
        {"setting": setting, "claimBaseRevision": EXECUTION_RUNTIME_CLAIM_BASE_REVISION,
         "removedCandidateIds": list(removed), "baselineAuthorityCount": 2,
         "finalAuthorityCount": 1, "redundancyDelta": delta}
        for setting, removed, delta in EXECUTION_RUNTIME_CONSOLIDATIONS
    ]
    if not isinstance(consolidations, list) or any(
            not isinstance(item, dict) for item in consolidations):
        return ["authorityConsolidations must be an array of verified groups"]
    errors = []
    if consolidations != expected:
        errors.append(
            "execution runtime authorityConsolidations must contain only two exact 2-to-1 baseline groups")
    if verified_human_task_legacy_delta(document) is None:
        errors.append("verified Human Task legacy redundancy groups are incomplete or have drifted")
    return errors


def github_schema_authority_from_source(
        root: Path, candidates: Iterable[Candidate]) -> dict[str, object] | None:
    """Build the closed four-blob GitHub v1 payload-schema authority."""
    by_path = {path: [] for path in GITHUB_SCHEMA_PATHS}
    for candidate in candidates:
        if candidate.path in by_path:
            by_path[candidate.path].append(candidate)
    files: list[dict[str, object]] = []
    reference_count = 0
    for path, (source_digest, count, kind_counts) in sorted(GITHUB_SCHEMA_PATHS.items()):
        source = (root / path).read_text(encoding="utf-8")
        if hashlib.sha256(source.encode("utf-8")).hexdigest() != source_digest:
            return None
        try:
            document = strict_json_document(source)
        except ValueError:
            return None
        if not isinstance(document, dict):
            return None
        current = sorted(by_path[path], key=lambda candidate: candidate.id)
        current_kinds = dict(sorted(Counter(candidate.kind for candidate in current).items()))
        references = [candidate for candidate in current
                      if candidate.kind == "schema-reference-binding"]
        if len(current) != count or current_kinds != kind_counts \
                or any(not candidate.expression.startswith("#/")
                       or json.loads(candidate.evidence).get("resolutionError") is not None
                       for candidate in references):
            return None
        reference_count += len(references)
        files.append({
            "path": path, "sourceSha256": source_digest, "candidateCount": count,
            "candidateIds": [candidate.id for candidate in current], "kindCounts": kind_counts,
        })
    all_ids = {candidate.id for path in by_path for candidate in by_path[path]}
    security_ids = set(GITHUB_SECURITY_OWNER_SYMBOLS)
    if len(all_ids) != 636 or reference_count != 10 or not security_ids <= all_ids:
        return None

    index_source = (root / GITHUB_SCHEMA_INDEX_PATH).read_text(encoding="utf-8")
    try:
        index = strict_json_document(index_source)
    except ValueError:
        return None
    if hashlib.sha256(index_source.encode("utf-8")).hexdigest() != GITHUB_SCHEMA_INDEX_SHA256 \
            or not isinstance(index, dict) or index.get("version") != "ravenroot.github.schemas.v1" \
            or index.get("mediaType") != "application/schema+json":
        return None

    method_proof: dict[str, str] = {}
    for role, (path, type_name, method, digest) in GITHUB_SECURITY_GUARD_METHODS.items():
        source = (root / path).read_text(encoding="utf-8")
        if java_method_digest(source, type_name, method) != digest:
            return None
        method_proof[role] = digest
    test_source = (root / GITHUB_SCHEMA_TEST_PATH).read_text(encoding="utf-8")
    test_method = "versionedSchemasAreDiscoverableForExactlyTheFiveBehaviors"
    if hashlib.sha256(test_source.encode("utf-8")).hexdigest() != GITHUB_SCHEMA_TEST_SHA256 \
            or "@Disabled" in strip_c_comments(test_source) \
            or "@Test void " + test_method + "() throws Exception" not in test_source \
            or not java_test_type_is_directly_runnable(test_source, "GithubBoundaryTest"):
        return None
    return {
        "kind": "github-versioned-payload-schema-family-v1",
        "sourceRevision": GITHUB_SCHEMA_REVIEWED_REVISION,
        "files": files,
        "candidateIdsByClassification": {
            "protocol-or-format-invariant": sorted(all_ids - security_ids),
            "security-ceiling-or-default": sorted(security_ids),
        },
        "candidatePartitionSha256": GITHUB_SCHEMA_PARTITION_SHA256,
        "schemaIndexSha256": GITHUB_SCHEMA_INDEX_SHA256,
        "securityOwnerSymbols": dict(sorted(GITHUB_SECURITY_OWNER_SYMBOLS.items())),
        "javaGuardMethodDigests": dict(sorted(method_proof.items())),
        "testSourceSha256": GITHUB_SCHEMA_TEST_SHA256,
        "semanticRetirementCredit": 0,
        "duplicateAuthorityCredit": 0,
    }


def helm_schema_position_rows(
        root: Path, candidates: Iterable[Candidate]) -> list[dict[str, object]] | None:
    """Map only the reviewed Helm keyword/type IDs to decoded JSON positions.

    JSON decoding establishes member/value semantics. The lexical token stream is used only to
    associate those decoded atoms with scanner line positions; this is deliberately not a JSON
    Schema evaluator and grants no authority to keyword operands, references, or ENV carriers.
    """
    target = root / HELM_SCHEMA_PATH
    if not target.is_file() or target.is_symlink():
        return None
    source = target.read_text(encoding="utf-8")
    try:
        document = strict_json_document(source)
    except ValueError:
        return None
    if not isinstance(document, dict):
        return None

    atoms: list[tuple[object, str, str]] = []

    def escaped_pointer_token(value: object) -> str:
        return str(value).replace("~", "~0").replace("/", "~1")

    def walk(value: object, pointer: str = "") -> None:
        if isinstance(value, dict):
            for key, child in value.items():
                child_pointer = f"{pointer}/{escaped_pointer_token(key)}"
                atoms.append((key, child_pointer, "key"))
                walk(child, child_pointer)
        elif isinstance(value, list):
            for index, child in enumerate(value):
                walk(child, f"{pointer}/{index}")
        else:
            atoms.append((value, pointer, "value"))

    walk(document)
    tokens = list(re.finditer(
        r'"(?:\\.|[^"\\])*"|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?|true|false|null',
        source,
    ))
    if len(tokens) != len(atoms):
        return None
    locations: dict[tuple[int, str], list[dict[str, object]]] = {}
    for token, (value, pointer, position) in zip(tokens, atoms):
        try:
            decoded = json.loads(token.group())
        except json.JSONDecodeError:
            return None
        if decoded != value or type(decoded) is not type(value):
            return None
        line = source.count("\n", 0, token.start()) + 1
        column = token.start() - source.rfind("\n", 0, token.start())
        locations.setdefault((line, normalized(token.group())), []).append({
            "pointer": pointer,
            "position": position,
            "value": value,
        })

    schema_nodes: set[str] = set()

    def collect_schema_nodes(node: object, pointer: str = "") -> bool:
        if not isinstance(node, dict):
            return False
        schema_nodes.add(pointer)
        for member in ("properties", "definitions"):
            if member not in node:
                continue
            children = node[member]
            if not isinstance(children, dict):
                return False
            for name, child in children.items():
                child_pointer = f"{pointer}/{member}/{escaped_pointer_token(name)}"
                if not collect_schema_nodes(child, child_pointer):
                    return False
        if "oneOf" in node:
            alternatives = node["oneOf"]
            if not isinstance(alternatives, list):
                return False
            for index, child in enumerate(alternatives):
                if not collect_schema_nodes(child, f"{pointer}/oneOf/{index}"):
                    return False
        if "items" in node:
            if not isinstance(node["items"], dict) \
                    or not collect_schema_nodes(node["items"], f"{pointer}/items"):
                return False
        return True

    if not collect_schema_nodes(document):
        return None

    used: Counter[tuple[int, str]] = Counter()
    rows: list[dict[str, object]] = []
    seen_closed: set[str] = set()
    for candidate in candidates:
        if candidate.path != HELM_SCHEMA_PATH.as_posix() \
                or candidate.kind != "configuration-scalar":
            continue
        key = (candidate.line, candidate.expression)
        possible = locations.get(key, [])
        index = used[key]
        used[key] += 1
        if index >= len(possible):
            return None
        if candidate.id not in HELM_SCHEMA_CLOSED_IDS:
            continue
        location = possible[index]
        pointer = str(location["pointer"])
        parent = pointer.rsplit("/", 1)[0]
        value = location["value"]
        if location["position"] == "key" and value in HELM_SCHEMA_KEYWORDS \
                and parent in schema_nodes:
            category = "schema-keyword-spelling"
        elif location["position"] == "value" and pointer.endswith("/type") \
                and parent in schema_nodes and value in HELM_SCHEMA_TYPE_VOCABULARY:
            category = "schema-type-vocabulary-selection-frozen"
        else:
            return None
        seen_closed.add(candidate.id)
        rows.append({
            "id": candidate.id,
            "pointer": pointer,
            "position": location["position"],
            "value": value,
            "kind": candidate.kind,
            "role": candidate.role,
            "expression": candidate.expression,
            "expressionDigest": candidate.expression_digest,
            "evidenceDigest": candidate.evidence_digest,
            "category": category,
        })
    if seen_closed != HELM_SCHEMA_CLOSED_IDS or len(rows) != len(HELM_SCHEMA_CLOSED_IDS):
        return None
    rows.sort(key=lambda item: str(item["id"]))
    return rows


def helm_schema_authority_from_source(
        root: Path, candidates: Iterable[Candidate]) -> dict[str, object] | None:
    """Build the exact 360-keyword/84-type Helm schema position authority."""
    rows = helm_schema_position_rows(root, candidates)
    if rows is None:
        return None
    payload = [[row[field] for field in (
        "id", "pointer", "position", "value", "kind", "role", "expression",
        "expressionDigest", "evidenceDigest", "category",
    )] for row in rows]
    position_digest = hashlib.sha256(json.dumps(
        payload, separators=(",", ":"), ensure_ascii=False,
    ).encode("utf-8")).hexdigest()
    by_category = {
        category: sorted(str(row["id"]) for row in rows if row["category"] == category)
        for category in (
            "schema-keyword-spelling", "schema-type-vocabulary-selection-frozen",
        )
    }
    if position_digest != HELM_SCHEMA_REVIEWED_SPEC_SHA256 \
            or len(by_category["schema-keyword-spelling"]) != 360 \
            or len(by_category["schema-type-vocabulary-selection-frozen"]) != 84:
        return None
    return {
        "kind": HELM_SCHEMA_CLOSED_FAMILY_ID,
        "sourceRevision": HELM_SCHEMA_REVIEWED_REVISION,
        "sourcePath": HELM_SCHEMA_PATH.as_posix(),
        "reviewedSourceSha256": HELM_SCHEMA_REVIEWED_SOURCE_SHA256,
        "positionSpecSha256": position_digest,
        "candidateIdsByRole": by_category,
        "keywordVocabulary": sorted(HELM_SCHEMA_KEYWORDS),
        "typeVocabulary": sorted(HELM_SCHEMA_TYPE_VOCABULARY),
        "candidateCount": len(rows),
        "semanticRetirementCredit": 0,
        "duplicateAuthorityCredit": 0,
    }


def helm_schema_authority_errors(
        root: Path, authorities: object, entries: dict[str, dict[str, object]],
        discovered: dict[str, Candidate]) -> list[str]:
    """Validate the opt-in closed Helm family without blessing adjacent operands."""
    claimed = {identifier for identifier, entry in entries.items()
               if entry.get("retainedAuthority") == HELM_SCHEMA_CLOSED_FAMILY_ID}
    if authorities is None and not claimed:
        return []
    family_candidates = tuple(candidate for candidate in discovered.values()
                              if candidate.path == HELM_SCHEMA_PATH.as_posix())
    expected = helm_schema_authority_from_source(root, family_candidates)
    if expected is None:
        return ["Helm schema keyword/type positions or candidate identity have drifted"]
    if not isinstance(authorities, dict) or set(authorities) != {HELM_SCHEMA_CLOSED_FAMILY_ID} \
            or authorities.get(HELM_SCHEMA_CLOSED_FAMILY_ID) != expected:
        return ["Helm schema requires the exact checker-owned 360-keyword/84-type authority"]
    errors: list[str] = []
    keyword_ids = set(expected["candidateIdsByRole"]["schema-keyword-spelling"])
    for identifier in HELM_SCHEMA_CLOSED_IDS:
        rationale = (HELM_SCHEMA_KEYWORD_RATIONALE if identifier in keyword_ids
                     else HELM_SCHEMA_TYPE_RATIONALE)
        entry = entries.get(identifier, {})
        if entry.get("status") != "retained" \
                or entry.get("classification") != "protocol-or-format-invariant" \
                or entry.get("retainedAuthority") != HELM_SCHEMA_CLOSED_FAMILY_ID \
                or entry.get("rationale") != rationale:
            errors.append(f"{identifier}: Helm schema row lost its exact retained authority")
    if claimed != HELM_SCHEMA_CLOSED_IDS:
        errors.append("Helm schema authority has missing or extra claimed rows")
    return errors


def github_schema_partition_digest(entries: dict[str, dict[str, object]],
                                   identifiers: set[str]) -> str:
    reviewed = sorted((identifier, entries.get(identifier, {}).get("classification"),
                       entries.get(identifier, {}).get("rationale"))
                      for identifier in identifiers)
    payload = json.dumps(reviewed, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def github_schema_authority_errors(
        root: Path, authorities: object, entries: dict[str, dict[str, object]],
        discovered: dict[str, Candidate]) -> list[str]:
    family_candidates = tuple(candidate for candidate in discovered.values()
                              if candidate.path in GITHUB_SCHEMA_PATHS)
    if not family_candidates:
        return [] if authorities is None else ["GitHub schema authority exists without its blobs"]
    expected = github_schema_authority_from_source(root, family_candidates)
    if expected is None:
        return ["GitHub schema source, publication, Java guard, or runnable test proof has drifted"]
    if not isinstance(authorities, dict) or set(authorities) != {GITHUB_SCHEMA_FAMILY_ID} \
            or authorities.get(GITHUB_SCHEMA_FAMILY_ID) != expected:
        return ["GitHub schemas require the exact checker-owned 636-row authority"]
    expected_ids = {candidate.id for candidate in family_candidates}
    errors: list[str] = []
    for identifier in expected_ids:
        expected_classification = ("security-ceiling-or-default"
                                   if identifier in GITHUB_SECURITY_OWNER_SYMBOLS
                                   else "protocol-or-format-invariant")
        entry = entries.get(identifier, {})
        if entry.get("status") != "retained" \
                or entry.get("classification") != expected_classification \
                or entry.get("retainedAuthority") != GITHUB_SCHEMA_FAMILY_ID:
            errors.append(f"{identifier}: GitHub schema row lost its exact retained authority")
    claimed = {identifier for identifier, entry in entries.items()
               if entry.get("retainedAuthority") == GITHUB_SCHEMA_FAMILY_ID}
    if claimed != expected_ids:
        errors.append("GitHub schema authority has missing or extra claimed rows")
    if github_schema_partition_digest(entries, expected_ids) != GITHUB_SCHEMA_PARTITION_SHA256:
        errors.append("GitHub schema classification/rationale partition has drifted")
    return errors


def ui_text_catalog_pairs(source: str) -> tuple[tuple[str, str], ...] | None:
    """Parse the one accepted frozen English catalog into exact key/value roles."""
    tables = list(re.finditer(
        r"const\s+ENGLISH_MESSAGES\s*=\s*Object\.freeze\s*\(\s*\{"
        r"(?P<body>.*?)\}\s*\)\s*;", strip_c_comments(source), re.DOTALL,
    ))
    if len(tables) != 1:
        return None
    quoted = r'''(?:"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')'''
    pair = re.compile(rf"\s*(?P<key>{quoted})\s*:\s*(?P<value>{quoted})\s*,", re.DOTALL)
    body = tables[0].group("body")
    pairs: list[tuple[str, str]] = []
    position = 0
    while position < len(body):
        match = pair.match(body, position)
        if match is None:
            return tuple(pairs) if not body[position:].strip() else None
        pairs.append((match.group("key"), match.group("value")))
        position = match.end()
    return tuple(pairs)


def ui_text_pair_change_summary(before: str, after: str) -> dict[str, object] | None:
    """Compare catalog pairs by key so scanner-wide lexical churn cannot imply retirements."""
    before_pairs = ui_text_catalog_pairs(before)
    after_pairs = ui_text_catalog_pairs(after)
    if before_pairs is None or after_pairs is None:
        return None
    before_map = dict(before_pairs)
    after_map = dict(after_pairs)
    if len(before_map) != len(before_pairs) or len(after_map) != len(after_pairs):
        return None
    shared = set(before_map) & set(after_map)
    unchanged = sorted(key for key in shared if before_map[key] == after_map[key])
    changed = sorted(key for key in shared if before_map[key] != after_map[key])
    added = sorted(set(after_map) - set(before_map))
    removed = sorted(set(before_map) - set(after_map))
    return {
        "comparisonKeyRole": "catalog-key",
        "comparisonValueRole": "presentation-text",
        "unchangedPairs": [{"key": key, "value": after_map[key]} for key in unchanged],
        "changedPairs": [{
            "key": key, "beforeValue": before_map[key], "afterValue": after_map[key],
        } for key in changed],
        "addedPairs": [{"key": key, "value": after_map[key]} for key in added],
        "removedPairs": [{"key": key, "value": before_map[key]} for key in removed],
    }


def ui_text_atomic_rekey_record(root: Path, current_ids: set[str]) -> dict[str, object] | None:
    """Bind the two pending schema-v1 statements to reviewed source-pair history."""
    inventory_source = committed_source(
        root, UI_TEXT_SCHEMA_V1_INVENTORY_REVISION,
        "scripts/operational-configuration-inventory.json",
    )
    old_catalog_source = committed_source(
        root, UI_TEXT_SCHEMA_V1_INVENTORY_REVISION, UI_TEXT_CATALOG_PATH.as_posix(),
    )
    if inventory_source is None or old_catalog_source is None \
            or hashlib.sha256(old_catalog_source.encode("utf-8")).hexdigest() \
            != UI_TEXT_SCHEMA_V1_SOURCE_SHA256:
        return None
    try:
        document = json.loads(inventory_source)
    except json.JSONDecodeError:
        return None
    old_rows = [entry for entry in document.get("entries", [])
                if isinstance(entry, dict)
                and entry.get("path") == UI_TEXT_CATALOG_PATH.as_posix()]
    if sorted(str(entry.get("id")) for entry in old_rows) != sorted(UI_TEXT_SCHEMA_V1_IDS) \
            or any(entry.get("status") != "pending-review"
                   or entry.get("classification") is not None for entry in old_rows):
        return None
    current_source = (root / UI_TEXT_CATALOG_PATH).read_text(encoding="utf-8")
    pair_history = ui_text_pair_change_summary(old_catalog_source, current_source)
    if pair_history is None or len(pair_history["unchangedPairs"]) != 125 \
            or pair_history["changedPairs"] or len(pair_history["addedPairs"]) != 8 \
            or pair_history["removedPairs"] \
            or len(UI_TEXT_SCHEMA_V4_SOURCE_NEW_IDS) != 16 \
            or not UI_TEXT_SCHEMA_V4_SOURCE_NEW_IDS <= current_ids \
            or not UI_TEXT_LOCALE_IDS <= current_ids:
        return None
    lexical_rekeys = current_ids - UI_TEXT_SCHEMA_V4_SOURCE_NEW_IDS - UI_TEXT_LOCALE_IDS
    if len(lexical_rekeys) != 250 or len(current_ids) != 268:
        return None
    return {
        "kind": "schema-v1-statements-to-v4-atomic-family-v1",
        "beforeInventoryRevision": UI_TEXT_SCHEMA_V1_INVENTORY_REVISION,
        "beforePrecursors": [
            {
                "id": entry["id"],
                "kind": entry["kind"],
                "expressionDigest": entry["expressionDigest"],
            }
            for entry in sorted(old_rows, key=lambda item: str(item["id"]))
        ],
        "afterCandidateIds": sorted(current_ids),
        "sourceComparison": {
            "beforeSourceSha256": UI_TEXT_SCHEMA_V1_SOURCE_SHA256,
            "catalogPairHistory": pair_history,
            "sourceNewCandidateIds": sorted(UI_TEXT_SCHEMA_V4_SOURCE_NEW_IDS),
            "sourceLexicallyRekeyedCandidateIds": sorted(lexical_rekeys),
            "sourceStableCandidateIds": sorted(UI_TEXT_LOCALE_IDS),
        },
        "semanticRetirementCredit": 0,
        "duplicateAuthorityCredit": 0,
    }


def ui_text_catalog_authority_from_source(
        root: Path, candidates: Iterable[Candidate]) -> dict[str, object] | None:
    """Return the reviewed 268-position catalog authority when all bounded seams match."""
    source = (root / UI_TEXT_CATALOG_PATH).read_text(encoding="utf-8")
    if hashlib.sha256(source.encode("utf-8")).hexdigest() != UI_TEXT_CATALOG_SHA256:
        return None
    code = strip_c_comments(source)
    tables = list(re.finditer(
        r"const\s+ENGLISH_MESSAGES\s*=\s*Object\.freeze\s*\(\s*\{(?P<body>.*?)\}\s*\)\s*;",
        code, re.DOTALL,
    ))
    if len(tables) != 1:
        return None
    quoted = r'''(?:"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')'''
    pair = re.compile(rf"\s*(?P<key>{quoted})\s*:\s*(?P<value>{quoted})\s*,", re.DOTALL)
    body = tables[0].group("body")
    keys: list[str] = []
    position = 0
    while position < len(body):
        match = pair.match(body, position)
        if match is None:
            if body[position:].strip():
                return None
            break
        keys.append(match.group("key"))
        position = match.end()
    masked = strip_c_comments_and_literals(source)
    if len(keys) != 133 or len(set(keys)) != 133 \
            or re.search(r"\b(?:const|let|var|class|function)\s+Object\b|\bObject\s*=", masked) \
            or normalized("export const UI_TEXT_CATALOGS = Object.freeze({ en: ENGLISH_MESSAGES });") \
            not in normalized(code):
        return None
    required_lookup = (
        "Object.hasOwn(ENGLISH_MESSAGES, key)", "String(locale || 'en').replaceAll('_', '-')",
        "Intl.getCanonicalLocales(input)", "normalized.split('-')[0]",
        "new Set([normalized, language, 'en'])", "Object.hasOwn(params, name)",
        "template.replace(/\\{([A-Za-z][A-Za-z0-9_]*)\\}/g",
        "Object.fromEntries(Object.entries(catalogs)", "{ ...UI_TEXT_CATALOGS, ...supplied }",
        "{ ...ENGLISH_MESSAGES, ...(supplied.en || {}) }",
        "candidates.map(candidate => available[candidate]).find",
    )
    if any(clause not in source for clause in required_lookup):
        return None

    current = {candidate.id: candidate for candidate in candidates
               if candidate.path == UI_TEXT_CATALOG_PATH.as_posix()}
    current_ids = set(current)
    protocol_ids = current_ids - UI_TEXT_PRESENTATION_IDS
    if len(current_ids) != 268 or not UI_TEXT_PRESENTATION_IDS <= current_ids \
            or len(protocol_ids) != 135 or not UI_TEXT_LOCALE_IDS <= protocol_ids:
        return None

    commands = (root / UI_TEXT_APP_COMMANDS_PATH).read_text(encoding="utf-8")
    command_imports = javascript_static_imports(commands)
    commands_code = normalized(strip_c_comments_and_literals(commands))
    if command_imports is None or command_imports.count(normalized(
            "import { hasUiText, uiText } from './ui-text.js';")) != 1 \
            or commands_code.count(normalized(
                "].map(command => localizeCommand(command, t));")) != 1 \
            or commands_code.count(normalized(
                "export function createAppCommands(actions, { t = uiText } = {})")) != 1:
        return None
    start = commands.find("function localizeCommand(command, t) {")
    end = commands.find("\n}\n", start)
    if start < 0 or end < 0:
        return None
    localize_digest = hashlib.sha256(normalized(strip_c_comments(
        commands[start:end + 2])).encode("utf-8")).hexdigest()
    app = (root / UI_TEXT_APP_PATH).read_text(encoding="utf-8")
    app_without_comments = strip_c_comments(app)
    app_imports = javascript_static_imports(app)
    if app_imports is None or app_imports.count(normalized(
            "import { uiText } from './ui-text.js';")) != 1 \
            or app_imports.count(normalized(
                "import { createAppCommands, createNodeActionCatalog } "
                "from './app-commands.js';")) != 1 \
            or len(re.findall(
                r"(?m)^const commandRegistry = "
                r"createCommandRegistry\(createAppCommands\(\{$",
                app_without_comments)) != 1:
        return None
    sinks = [line.strip() for line in app.splitlines()
             if "uiText(" in line and not line.lstrip().startswith("//")]
    sink_digest = hashlib.sha256("\n".join(sinks).encode("utf-8")).hexdigest()
    test_source = (root / UI_TEXT_TEST_PATH).read_text(encoding="utf-8")
    if localize_digest != UI_TEXT_LOCALIZE_DIGEST or sink_digest != UI_TEXT_APP_SINK_DIGEST \
            or len(sinks) != 26 \
            or hashlib.sha256(test_source.encode("utf-8")).hexdigest() != UI_TEXT_TEST_SHA256 \
            or "import { describe, expect, it } from 'vitest';" not in test_source \
            or len(re.findall(r"(?m)^\s*it\('", test_source)) != 4 \
            or re.search(r"\b(?:describe|it)\.(?:skip|todo)\s*\(", test_source):
        return None
    atomic_rekey = ui_text_atomic_rekey_record(root, current_ids)
    if atomic_rekey is None:
        return None
    return {
        "kind": "javascript-frozen-presentation-catalog-v1",
        "sourceRevision": UI_TEXT_REVIEWED_REVISION,
        "sourcePath": UI_TEXT_CATALOG_PATH.as_posix(),
        "sourceSha256": UI_TEXT_CATALOG_SHA256,
        "candidateIdsByClassification": {
            "presentation-text": sorted(UI_TEXT_PRESENTATION_IDS),
            "protocol-or-format-invariant": sorted(protocol_ids),
        },
        "catalogPairCount": 133,
        "consumerProof": {
            "localizeCommandDigest": UI_TEXT_LOCALIZE_DIGEST,
            "appSinkDigest": UI_TEXT_APP_SINK_DIGEST,
            "testSourceSha256": UI_TEXT_TEST_SHA256,
            "appUiTextImport": "import { uiText } from './ui-text.js';",
            "appCommandBinding": "createCommandRegistry(createAppCommands({",
            "localizedCommandMap": "].map(command => localizeCommand(command, t));",
        },
        "atomicRekey": atomic_rekey,
        "semanticRetirementCredit": 0,
        "duplicateAuthorityCredit": 0,
    }


def ui_text_catalog_authority_errors(
        root: Path, authorities: object, entries: dict[str, dict[str, object]],
        discovered: dict[str, Candidate]) -> list[str]:
    family_candidates = tuple(candidate for candidate in discovered.values()
                              if candidate.path == UI_TEXT_CATALOG_PATH.as_posix())
    if not family_candidates:
        return [] if authorities is None else ["UI text authority exists without its catalog"]
    expected = ui_text_catalog_authority_from_source(root, family_candidates)
    if expected is None:
        return ["UI text catalog, consumer, or runnable test proof has drifted"]
    if not isinstance(authorities, dict) or set(authorities) != {UI_TEXT_FAMILY_ID} \
            or authorities.get(UI_TEXT_FAMILY_ID) != expected:
        return ["UI text catalog requires the exact checker-owned 268-row authority"]
    errors: list[str] = []
    expected_ids = {candidate.id for candidate in family_candidates}
    for identifier in expected_ids:
        presentation = identifier in UI_TEXT_PRESENTATION_IDS
        classification = "presentation-text" if presentation else "protocol-or-format-invariant"
        rationale = UI_TEXT_PRESENTATION_RATIONALE if presentation else UI_TEXT_PROTOCOL_RATIONALE
        entry = entries.get(identifier, {})
        if entry.get("status") != "retained" or entry.get("classification") != classification \
                or entry.get("retainedAuthority") != UI_TEXT_FAMILY_ID \
                or entry.get("rationale") != rationale:
            errors.append(f"{identifier}: UI text row lost its exact catalog authority")
    claimed = {identifier for identifier, entry in entries.items()
               if entry.get("retainedAuthority") == UI_TEXT_FAMILY_ID}
    if claimed != expected_ids:
        errors.append("UI text catalog authority has missing or extra claimed rows")
    return errors


def verification_script_fixture_authority_from_source(
        root: Path, candidates: Iterable[Candidate]) -> dict[str, object] | None:
    """Build the closed 513-row fixture mapping only from the reviewed eleven scripts."""
    by_path: dict[str, list[Candidate]] = {
        path: [] for path in VERIFICATION_SCRIPT_FIXTURES
    }
    for candidate in candidates:
        if candidate.path in by_path:
            by_path[candidate.path].append(candidate)
    files: list[dict[str, object]] = []
    for path, (purpose, source_digest, count, kind_counts) in sorted(
            VERIFICATION_SCRIPT_FIXTURES.items()):
        target = root / path
        if not target.is_file() or target.is_symlink() \
                or tracked_git_mode(root, Path(path)) != "100755" \
                or target.read_text(encoding="utf-8").splitlines()[0] != \
                VERIFICATION_SCRIPT_SHEBANGS[path] \
                or hashlib.sha256(target.read_bytes()).hexdigest() != source_digest:
            return None
        current = sorted(by_path[path], key=lambda candidate: candidate.id)
        if len(current) != count or dict(sorted(Counter(
                candidate.kind for candidate in current).items())) != kind_counts:
            return None
        files.append({
            "path": path,
            "purpose": purpose,
            "sourceSha256": source_digest,
            "candidateCount": count,
            "candidateIds": [candidate.id for candidate in current],
            "kindCounts": kind_counts,
            "gitMode": "100755",
            "shebang": VERIFICATION_SCRIPT_SHEBANGS[path],
        })
    return {
        "kind": "explicit-verification-script-fixture-family-v1",
        "sourceRevision": VERIFICATION_SCRIPT_FIXTURE_REVISION,
        "files": files,
        "inboundGuards": list(VERIFICATION_SCRIPT_INBOUND_GUARDS),
        "allowedExecutableCallers": [dict(item) for item in VERIFICATION_SCRIPT_CALLERS],
    }


def verification_script_fixture_authority_errors(
        root: Path, authorities: object, entries: dict[str, dict[str, object]],
        discovered: dict[str, Candidate]) -> list[str]:
    family_candidates = tuple(candidate for candidate in discovered.values()
                              if candidate.path in VERIFICATION_SCRIPT_FIXTURES)
    if not family_candidates:
        return [] if authorities is None else [
            "verification-script fixture authority exists without reviewed scripts"]
    expected = verification_script_fixture_authority_from_source(root, family_candidates)
    if expected is None:
        return ["verification-script fixture source or candidate partition has drifted"]
    if not isinstance(authorities, dict) or set(authorities) != {
            VERIFICATION_SCRIPT_FIXTURE_FAMILY_ID} \
            or authorities.get(VERIFICATION_SCRIPT_FIXTURE_FAMILY_ID) != expected:
        return ["verification-script fixtures require the exact checker-owned 513-row authority"]
    errors: list[str] = []
    expected_ids = {candidate.id for candidate in family_candidates}
    for identifier in expected_ids:
        entry = entries.get(identifier, {})
        if entry.get("status") != "retained" or entry.get("classification") != "test-fixture" \
                or entry.get("retainedAuthority") != VERIFICATION_SCRIPT_FIXTURE_FAMILY_ID \
                or entry.get("rationale") != VERIFICATION_SCRIPT_FIXTURE_RATIONALE:
            errors.append(
                f"{identifier}: verification-script fixture row lost its exact retained authority")
    claimed = {identifier for identifier, entry in entries.items()
               if entry.get("retainedAuthority") == VERIFICATION_SCRIPT_FIXTURE_FAMILY_ID}
    if claimed != expected_ids:
        errors.append("verification-script fixture authority has missing or extra claimed rows")
    errors.extend(verification_script_inbound_errors(root))
    return errors


def inventory_errors(root: Path, document: dict[str, object], candidates: tuple[Candidate, ...]) -> list[str]:
    errors: list[str] = []
    migration_history = document.get("migrationHistory", [])
    if isinstance(migration_history, list):
        for migration in migration_history:
            if not isinstance(migration, dict) \
                    or "sourceRevision" not in migration or "sourcePath" not in migration:
                continue
            source_revision = migration["sourceRevision"]
            source_path = migration["sourcePath"]
            if not historical_source_locator_is_safe(source_revision, source_path):
                errors.append(
                    f"inventory migration source is not locally resolvable: "
                    f"{source_revision}:{source_path}")
    # Reject unsafe Git arguments before source-owner or family validation can invoke Git.
    if errors:
        return errors
    raw_entries = document["entries"]
    assert isinstance(raw_entries, list)
    entries: dict[str, dict[str, object]] = {}
    evidence_records = document.get("evidenceRecords", {})
    if not isinstance(evidence_records, dict):
        errors.append("inventory evidenceRecords must be an object")
        evidence_records = {}
    for entry in raw_entries:
        if not isinstance(entry, dict) or not isinstance(entry.get("id"), str):
            errors.append("inventory entry has no string id")
            continue
        identifier = str(entry["id"])
        if identifier in entries:
            errors.append(f"duplicate inventory id: {identifier}")
        entries[identifier] = entry

    discovered = {candidate.id: candidate for candidate in candidates}
    assistant_authorities = document.get("assistantLimitAuthorities")
    assistant_settings = {str(item["setting"]) for item in ASSISTANT_LIMIT_SETTINGS}
    execution_runtime_settings = {str(item["setting"]) for item in EXECUTION_RUNTIME_SETTINGS}
    assistant_family = assistant_limit_family_index(assistant_authorities)
    for identifier, candidate in discovered.items():
        entry = entries.get(identifier)
        if entry is None:
            errors.append(
                f"unclassified operational candidate: {candidate.path}:{candidate.line} "
                f"{candidate.symbol} {candidate.kind} {candidate.role} {candidate.expression}; "
                f"evidence: {candidate.evidence}"
            )
            continue
        for key, value in candidate.source_fields().items():
            if entry.get(key) != value:
                errors.append(f"stale inventory metadata for {identifier}: {key} is {entry.get(key)!r}, expected {value!r}")
        if evidence_records.get(candidate.evidence_digest) != candidate.evidence:
            errors.append(f"stale or missing full evidence for {identifier}: {candidate.evidence_digest}")
        status = entry.get("status")
        classification = entry.get("classification")
        rationale = entry.get("rationale")
        if status not in STATUSES:
            errors.append(f"{identifier}: invalid status {status!r}")
        if status == "pending-review":
            if classification is not None:
                errors.append(f"{identifier}: pending-review candidate must not guess a classification")
        elif classification not in CLASSIFICATIONS:
            errors.append(f"{identifier}: reviewed candidate has invalid classification {classification!r}")
        elif status not in CLASSIFICATION_STATUSES[classification]:
            errors.append(f"{identifier}: {status!r} is invalid for classification {classification!r}")
        if status != "pending-review" and classification != "test-fixture" \
                and (not isinstance(rationale, str) or not rationale.strip()):
            errors.append(f"{identifier}: classification rationale is required")
        if candidate.surface == "test-fixture" and (
                classification != "test-fixture" or status != "retained"):
            errors.append(f"{identifier}: test-fixture surface must remain a retained test-fixture")
        if classification == "test-fixture" and candidate.surface != "test-fixture":
            errors.append(f"{identifier}: only a test-fixture surface may use the test-fixture classification")
        if classification == "published-contract-description" and (
                candidate.path != ROUTE_TABLE_PATH.as_posix()
                or entry.get("retainedAuthority") != ROUTE_TABLE_AUTHORITY_ID):
            errors.append(
                f"{identifier}: published-contract-description requires the closed RouteTable authority")
        if classification == "presentation-text" and (
                candidate.path != UI_TEXT_CATALOG_PATH.as_posix()
                or entry.get("retainedAuthority") != UI_TEXT_FAMILY_ID):
            errors.append(f"{identifier}: presentation-text requires the closed UI text authority")
        if classification == "operator-configurable" and status != "pending-review":
            for field in ("setting", "owner", "field", "default", "validation", "scope", "pinning",
                          "coverage"):
                if not isinstance(entry.get(field), str) or not str(entry[field]).strip():
                    errors.append(f"{identifier}: reviewed operator setting requires {field}")
            bindings = entry.get("bindings")
            if not isinstance(bindings, list) or any(
                    not isinstance(binding, str) or not binding.strip() for binding in bindings):
                errors.append(f"{identifier}: reviewed operator setting requires a string bindings array")
            default_evidence = entry.get("defaultEvidence")
            if not isinstance(default_evidence, list) or not default_evidence or any(
                    not isinstance(evidence_id, str) or not evidence_id.strip()
                    for evidence_id in default_evidence):
                errors.append(f"{identifier}: reviewed operator setting requires defaultEvidence candidate ids")
            owner = str(entry.get("owner", ""))
            if current_source_owner(root, owner) is None:
                errors.append(f"{identifier}: owner is not a tracked in-repository path#symbol: {owner}")
            elif not current_source_field(root, owner, str(entry.get("field", ""))):
                errors.append(f"{identifier}: field is not declared by its typed owner: {entry.get('field')}")
            if status == "converted":
                conversion = entry.get("conversion")
                setting = str(entry.get("setting", ""))
                if setting in assistant_settings:
                    family = assistant_family.get(setting)
                    if family is None or conversion != family["conversion"]:
                        errors.append(
                            f"{identifier}: converted assistant row must cite its exact family conversion")
                    continue
                if setting in execution_runtime_settings:
                    if conversion != {"issue": "#225", "authority": EXECUTION_RUNTIME_FAMILY_ID}:
                        errors.append(
                            f"{identifier}: converted execution-runtime row must cite its exact family authority")
                    continue
                required = ("issue", "beforeRevision", "afterRevision", "path", "symbol",
                            "binding", "bindingSymbol", "field", "beforeExpression", "afterExpression")
                if not isinstance(conversion, dict) or any(
                        not isinstance(conversion.get(field), str) or not str(conversion[field]).strip()
                        for field in required):
                    errors.append(
                        f"{identifier}: converted setting requires setting-specific source-verifiable "
                        "conversion provenance"
                    )
                else:
                    transition, before_source, after_source = revision_transition_errors(
                        root, identifier, conversion, path=str(conversion["path"]),
                        symbol=str(conversion["symbol"]), label="conversion",
                    )
                    errors.extend(transition)
                    errors.extend(conversion_evidence_errors(
                        identifier, entry, conversion, before_source, after_source,
                    ))
                    if str(conversion["field"]) != str(entry.get("field", "")):
                        errors.append(f"{identifier}: conversion field does not match the setting field")
        if status == "deferred" and not re.search(r"(?:#\d+|https://)", str(entry.get("followUp", ""))):
            errors.append(f"{identifier}: deferred candidate requires a concrete linked followUp")

    for identifier, entry in entries.items():
        if identifier not in discovered:
            errors.append(f"stale inventory entry: {identifier} ({entry.get('path', 'unknown path')})")

    retired_entries = document.get("retiredEntries", [])
    assert isinstance(retired_entries, list)
    retired_ids: set[str] = set()
    for entry in retired_entries:
        if not isinstance(entry, dict) or not isinstance(entry.get("id"), str):
            errors.append("retired inventory entry has no string id")
            continue
        identifier = str(entry["id"])
        if identifier in retired_ids or identifier in entries:
            errors.append(f"duplicate active/retired inventory id: {identifier}")
        retired_ids.add(identifier)
        if not isinstance(entry.get("retirementRationale"), str) \
                or not str(entry["retirementRationale"]).strip():
            errors.append(f"{identifier}: retired entry requires retirementRationale")
        if entry.get("status") == "duplicate-removed":
            removal = entry.get("removal")
            required = ("issue", "beforeRevision", "afterRevision", "replacementOwner")
            if not isinstance(removal, dict) or any(
                    not isinstance(removal.get(field), str) or not str(removal[field]).strip()
                    for field in required):
                errors.append(f"{identifier}: duplicate removal requires source-verifiable removal provenance")
            elif removal.get("kind") == "yaml-default-authority-v1":
                errors.extend(yaml_default_removal_errors(root, identifier, entry, removal, entries))
            else:
                transition, before_source, after_source = revision_transition_errors(
                    root, identifier, removal, path=str(entry.get("path", "")),
                    symbol=str(entry.get("symbol", "")), label="removal",
                )
                # A removed duplicate may delete its authority or change that source, but cannot leave
                # the exact old expression in place and merely relabel its inventory row.
                errors.extend(error for error in transition
                              if "path/symbol is absent from afterRevision" not in error)
                if before_source is not None and after_source is not None \
                        and str(entry.get("evidence", "")) in after_source:
                    errors.append(f"{identifier}: removed duplicate evidence remains in afterRevision")
                replacement = str(removal["replacementOwner"])
                if "#" not in replacement:
                    errors.append(f"{identifier}: replacementOwner must be path#symbol")
                else:
                    replacement_path, replacement_symbol = replacement.rsplit("#", 1)
                    replacement_source = committed_source(
                        root, str(removal["afterRevision"]), replacement_path,
                    )
                    if replacement_source is None or not re.search(
                            rf"\b{re.escape(replacement_symbol)}\b", replacement_source):
                        errors.append(f"{identifier}: replacementOwner is absent from afterRevision")

    migration_history = document.get("migrationHistory", [])
    assert isinstance(migration_history, list)
    for migration in migration_history:
        required = ("fromSchema", "toSchema", "sourceRevision", "sourcePath", "sourceFileDigest",
                    "candidateCount", "statusCounts", "rationale")
        if not isinstance(migration, dict) or any(field not in migration for field in required):
            errors.append("inventory migration record is incomplete")
            continue
        source_revision = str(migration["sourceRevision"])
        source_path = str(migration["sourcePath"])
        source = committed_source(root, source_revision, source_path)
        if source is None:
            errors.append(f"inventory migration source is not locally resolvable: {source_revision}:{source_path}")
            continue
        digest = hashlib.sha256(source.encode("utf-8")).hexdigest()
        if digest != migration["sourceFileDigest"]:
            errors.append(f"inventory migration source digest mismatch: {source_revision}:{source_path}")
        try:
            source_document = json.loads(source)
        except json.JSONDecodeError:
            errors.append(f"inventory migration source is not JSON: {source_revision}:{source_path}")
            continue
        source_entries = source_document.get("entries", [])
        source_counts = Counter(str(entry.get("status")) for entry in source_entries
                                if isinstance(entry, dict))
        if len(source_entries) != migration["candidateCount"] or dict(sorted(source_counts.items())) != migration["statusCounts"]:
            errors.append(f"inventory migration source counts mismatch: {source_revision}:{source_path}")

    authorities: dict[str, tuple[str, tuple[object, ...]]] = {}
    authority_fields = ("owner", "field", "default", "validation", "scope", "pinning", "coverage")
    for identifier, entry in entries.items():
        if entry.get("classification") != "operator-configurable" or entry.get("status") == "pending-review":
            continue
        owner = str(entry.get("owner", "")).strip()
        setting = str(entry.get("setting", "")).strip()
        if not setting:
            continue
        metadata = tuple(entry.get(field) for field in authority_fields) + (
            tuple(entry.get("bindings", [])), tuple(entry.get("defaultEvidence", [])),
            json.dumps(entry.get("bindingAuthority"), sort_keys=True),
            json.dumps(entry.get("defaultAuthority"), sort_keys=True),
            json.dumps(entry.get("schemaEvidence"), sort_keys=True),
            json.dumps(entry.get("coverageEvidence"), sort_keys=True),
            json.dumps(entry.get("carrierEvidence"), sort_keys=True),
        )
        previous = authorities.get(setting)
        if previous is not None and previous[1] != metadata:
            errors.append(
                f"inconsistent configuration authority metadata for {setting}: "
                f"{previous[0]} and {identifier}"
            )
        else:
            authorities[setting] = (identifier, metadata)

    resolver_authorities = document.get("resolverAuthorities")
    if resolver_authorities is not None:
        errors.extend(resolver_authority_errors(root, resolver_authorities))
    errors.extend(route_table_authority_errors(
        root, document.get("routeTableAuthorities"), entries, discovered,
    ))
    errors.extend(assistant_limit_authority_errors(
        root, assistant_authorities, entries, discovered,
    ))
    errors.extend(graph_limit_authority_errors(
        root, document.get("graphLimitAuthorities"), entries, discovered,
    ))
    errors.extend(execution_runtime_authority_errors(
        root, document.get("executionRuntimeAuthorities"), entries, discovered,
    ))
    errors.extend(ui_text_catalog_authority_errors(
        root, document.get("uiTextAuthorities"), entries, discovered,
    ))
    errors.extend(helm_schema_authority_errors(
        root, document.get("helmSchemaAuthorities"), entries, discovered,
    ))
    errors.extend(github_schema_authority_errors(
        root, document.get("githubSchemaAuthorities"), entries, discovered,
    ))
    errors.extend(verification_script_fixture_authority_errors(
        root, document.get("verificationScriptFixtureAuthorities"), entries, discovered,
    ))
    errors.extend(authority_consolidation_errors(document))

    tracked_paths = set(tracked_files(root))
    representatives: dict[str, dict[str, object]] = {}
    for setting in authorities:
        setting_entries = [entry for entry in entries.values() if entry.get("setting") == setting]
        setting_ids = {str(entry["id"]) for entry in setting_entries}
        representative = entries[authorities[setting][0]]
        representatives[setting] = representative
        bindings = {str(binding) for entry in setting_entries for binding in entry.get("bindings", [])}
        if representative.get("bindingAuthority") is None:
            evidenced_bindings = {str(entry.get("expression")) for entry in setting_entries
                                  if entry.get("kind") == "environment-binding"}
            for binding in sorted(bindings - evidenced_bindings):
                errors.append(f"{setting}: binding {binding} has no same-setting environment-binding candidate")
        if setting in assistant_settings:
            errors.extend(assistant_limit_entry_adapter_errors(
                root, setting, setting_entries, entries, discovered, assistant_authorities,
            ))
        elif setting not in execution_runtime_settings:
            errors.extend(binding_authority_errors(
                root, setting, representative, setting_entries, entries, discovered,
                resolver_authorities,
            ))
            errors.extend(default_authority_errors(
                root, setting, representative, entries, discovered))
        if setting not in execution_runtime_settings:
            errors.extend(schema_evidence_errors(
                setting, representative, entries, discovered, evidence_records))
            errors.extend(graph_platform_coverage_errors(
                root, setting, representative, entries, discovered, evidence_records, tracked_paths,
            ))
        for entry in setting_entries:
            evidence_ids = entry.get("defaultEvidence", [])
            if isinstance(evidence_ids, list):
                for evidence_id in evidence_ids:
                    if evidence_id not in setting_ids:
                        errors.append(f"{entry['id']}: defaultEvidence {evidence_id} is not assigned to {setting}")
    errors.extend(environment_resolver_group_errors(root, representatives, resolver_authorities))
    return errors


def render_report(document: dict[str, object]) -> str:
    entries = document["entries"]
    assert isinstance(entries, list)
    typed = [entry for entry in entries if isinstance(entry, dict)]
    statuses = Counter(str(entry.get("status")) for entry in typed)
    classifications = Counter(str(entry.get("classification")) for entry in typed
                              if entry.get("classification") is not None)
    retained_classifications = Counter(
        str(entry.get("classification")) for entry in typed
        if entry.get("status") == "retained" and entry.get("classification") is not None)
    surfaces = Counter(str(entry.get("surface")) for entry in typed)
    reviewed = len(typed) - statuses["pending-review"]
    operator_entries = [entry for entry in typed if entry.get("classification") == "operator-configurable"]
    operator_settings = {str(entry["setting"]) for entry in operator_entries if entry.get("setting")}
    converted_settings = {str(entry["setting"]) for entry in operator_entries
                          if entry.get("status") == "converted" and entry.get("setting")}
    operator = len(operator_settings)
    converted = len(converted_settings)
    retired = document.get("retiredEntries", [])
    assert isinstance(retired, list)
    migrations = document.get("migrationHistory", [])
    assert isinstance(migrations, list)
    consolidations = document.get("authorityConsolidations")
    if isinstance(consolidations, list):
        expected_engine = [
            {"setting": setting, "claimBaseRevision": EXECUTION_RUNTIME_CLAIM_BASE_REVISION,
             "removedCandidateIds": list(removed), "baselineAuthorityCount": 2,
             "finalAuthorityCount": 1, "redundancyDelta": delta}
            for setting, removed, delta in EXECUTION_RUNTIME_CONSOLIDATIONS
        ]
        legacy_delta = verified_human_task_legacy_delta(document)
        duplicates = (legacy_delta if legacy_delta is not None else 0) \
            + (sum(item["redundancyDelta"] for item in expected_engine)
               if consolidations == expected_engine else 0)
    else:
        duplicate_settings = {str(entry["setting"]) for entry in retired if isinstance(entry, dict)
                              and entry.get("status") == "duplicate-removed" and entry.get("setting")}
        duplicates = len(duplicate_settings)
    deferred = statuses["deferred"]
    hardcoded = statuses["confirmed-hardcoded"]
    complete = statuses["pending-review"] == 0 and deferred == 0 and hardcoded == 0
    lines = [
        "# Operational configuration audit", "",
        "<!-- Generated by scripts/audit_operational_configuration.py; do not edit directly. -->", "",
        "This report is generated from the checked operational-configuration inventory. The bounded",
        "lexical scanner records candidates matched by its documented patterns; a candidate is not an",
        "operator setting until its semantic classification says so.", "",
        f"**Audit state:** {'complete' if complete else 'in progress'}. "
        f"{statuses['pending-review']} candidate(s) still require semantic review, {deferred} are deferred, "
        f"and {hardcoded} confirmed hard-coded candidates remain unresolved.", "",
        "## Coverage", "",
        "Scanned production surfaces: Java `src/main`, browser `src` and `public`, runtime/release Python",
        "and shell scripts, Dockerfiles, Compose, Helm, raw deployment manifests, and the two supported",
        "Compose override examples. The scanner also records testkit `src/main` and script verification",
        "constants as test fixtures.", "",
        "Excluded from production counts: ordinary `src/test`, UI test/e2e fixtures, generated build output",
        "(`target`, `dist`), dependency trees (`node_modules`), audit-tool implementation, ordinary prose,",
        "and vendored content.", "",
        "Each row represents one atomic fixed value or binding. Its full containing expression is retained as",
        "evidence without excerpt truncation. Candidate identity is `path + containing symbol + candidate",
        "kind/semantic role + normalized atomic value + normalized full-expression digest + lexical duplicate",
        "index`; source line remains checked metadata.", "",
        "The scanner is deliberately lexical: it covers declared constants, known policy constructors,",
        "and `get`, `await`, `tryAcquire`, `tryLock`, `waitFor`, and `awaitTermination` calls whose supported",
        "forms use a simple or fully qualified explicit `TimeUnit` constant. Other method names, dynamic or",
        "statically imported units, receiver-type inference, and values assembled only through reflection,",
        "generated sources, or arbitrary data flow remain outside this bounded pattern. Environment bindings,",
        "deployment scalars, and container identity/port directives are covered separately;",
        "semantic review and focused source inventories remain required for those boundaries.", "",
        "## Reproducible counts", "",
        "| Measure | Count |", "|---|---:|",
        f"| Atomic operational candidates discovered | {len(typed)} |",
        f"| Reviewed | {reviewed} |",
        f"| Pending review | {statuses['pending-review']} |",
        f"| Confirmed hard-coded candidates awaiting remediation | {hardcoded} |",
        f"| Unique confirmed operator-configurable parameters | {operator} |",
        f"| Unique parameters converted to centralized configuration | {converted} |",
        f"| Duplicate authorities removed | {duplicates} |",
        f"| Retained security ceilings or defaults | {classifications['security-ceiling-or-default']} |",
        f"| Retained protocol or format invariants | {classifications['protocol-or-format-invariant']} |",
        f"| Retained published contract descriptions | {retained_classifications['published-contract-description']} |",
        f"| Retained presentation text | {retained_classifications['presentation-text']} |",
        f"| Retained derived values | {classifications['derived']} |",
        f"| Test fixtures | {classifications['test-fixture']} |",
        f"| Intentionally deferred | {deferred} |", "",
        f"Retired source candidates preserved in inventory history: {len(retired)}.", "",
        f"Checked inventory-schema migrations: {len(migrations)}. Validation requires the recorded source",
        "revision to be present locally; CI must fetch that history before enabling this gate.", "",
        "Surface counts are derived from the same inventory:", "",
    ]
    lines.extend(f"- `{name}`: {count}" for name, count in sorted(surfaces.items()))
    lines.extend(("", "## Operator settings", "",
        "Every reviewed operator setting must name one typed owner, bindings, default, validation,",
                  "scope, pinning policy, and deployment/reference coverage. Pending candidates do not appear",
                  "in this table.", "",
                  "| Setting | State | Owner | Field | Bindings | Default | Validation | Scope | Pinning | Coverage |", "|---|---|---|---|---|---|---|---|---|---|"))
    if operator_entries:
        canonical: dict[str, list[dict[str, object]]] = {}
        for entry in operator_entries:
            canonical.setdefault(str(entry["setting"]), []).append(entry)
        for setting, setting_entries in sorted(canonical.items()):
            entry = setting_entries[0]
            item_states = {str(item["status"]) for item in setting_entries}
            states = "converted" if "converted" in item_states else ", ".join(sorted(item_states))
            bindings = ", ".join(f"`{value}`" for value in entry.get("bindings", []))
            lines.append("| {setting} | {status} | `{owner}` | `{field}` | {bindings} | {default} | {validation} | {scope} | {pinning} | {coverage} |".format(
                setting=setting, status=states, owner=entry.get("owner", ""),
                field=entry.get("field", ""),
                bindings=bindings or "none", default=entry.get("default", ""), validation=entry.get("validation", ""),
                scope=entry.get("scope", ""), pinning=entry.get("pinning", ""),
                coverage=entry.get("coverage", "")))
    else:
        lines.append("| _None reviewed yet_ |  |  |  |  |  |  |  |  |  |")
    lines.extend(("", "## Deferred values", "", "| Candidate | Follow-up | Rationale |", "|---|---|---|"))
    deferred_entries = [entry for entry in typed if entry.get("status") == "deferred"]
    if deferred_entries:
        for entry in deferred_entries:
            lines.append(f"| `{entry['path']}:{entry['line']}` | {entry.get('followUp', 'missing')} | {entry['rationale']} |")
    else:
        lines.append("| _None classified yet_ |  |  |")
    pending_files = Counter(str(entry["path"]) for entry in typed if entry.get("status") == "pending-review")
    lines.extend(("", "## Pending-review distribution", "",
                  "The machine-readable inventory retains every pending expression and its digest. This compact",
                  "view identifies where semantic review remains without copying thousands of source excerpts",
                  "into the operator report.", "", "| Source | Candidates |", "|---|---:|"))
    for path, count in sorted(pending_files.items()):
        lines.append(f"| `{path}` | {count} |")
    lines.extend(("", "## Reviewed candidate ledger", "",
                  "The machine-readable inventory is authoritative; this table shows reviewed non-fixture entries.", "",
                  "| ID | Source | Surface | State | Classification | Rationale |", "|---|---|---|---|---|---|"))
    reviewed_entries = [entry for entry in typed if entry.get("status") != "pending-review"
                        and entry.get("classification") != "test-fixture"]
    for entry in sorted(reviewed_entries, key=lambda item: (str(item["path"]), int(item["line"]), str(item["id"]))):
        rationale = str(entry["rationale"]).replace("|", "\\|").replace("\n", " ")
        lines.append(f"| `{entry['id']}` | `{entry['path']}:{entry['line']}` `{entry['symbol']}` | "
                     f"{entry['surface']} | {entry['status']} | {entry.get('classification') or '—'} | {rationale} |")
    if not reviewed_entries:
        lines.append("| _No reviewed non-fixture candidates yet_ |  |  |  |  |  |")
    lines.extend(("", "## Validation", "",
                  "Run `python3 scripts/audit_operational_configuration.py --check`. It rejects a new",
                  "unclassified value, binding, directive, or inline operational call; a changed classified",
                  "expression; stale inventory metadata; invalid classification/provenance; inconsistent setting",
                  "authorities; unresolved hard-coded settings; or report drift.", ""))
    return "\n".join(lines)


def refresh_inventory(root: Path, inventory_path: Path = INVENTORY, report_path: Path = REPORT,
                      *, accept_retired_pending: bool = False) -> tuple[list[str], dict[str, int]]:
    """Refresh source metadata without discarding a semantic review decision.

    A changed expression has a new stable ID. Pending entries may be retired only through the
    explicit command flag; a reviewed entry additionally needs an in-inventory ``retirement``
    object with ``approved: true`` and a nonblank rationale. Every retired row stays in history.
    """
    document = load_inventory(inventory_path, allow_previous_schema=True)
    raw_entries = document["entries"]
    assert isinstance(raw_entries, list)
    migrating_schema = document.get("schemaVersion") != SCHEMA_VERSION
    migration_record: dict[str, object] | None = None
    if migrating_schema:
        revision = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, check=True,
                                  capture_output=True, text=True).stdout.strip()
        source_path = inventory_path.resolve().relative_to(root.resolve()).as_posix()
        committed = subprocess.run(["git", "show", f"{revision}:{source_path}"], cwd=root,
                                   capture_output=True)
        if committed.returncode != 0 or committed.stdout != inventory_path.read_bytes():
            raise ValueError("schema migration requires the source inventory to be committed unchanged")
        source_counts = Counter(str(entry.get("status")) for entry in raw_entries
                                if isinstance(entry, dict))
        migration_record = {
            "fromSchema": document.get("schemaVersion"), "toSchema": SCHEMA_VERSION,
            "sourceRevision": revision, "sourcePath": source_path,
            "sourceFileDigest": hashlib.sha256(committed.stdout).hexdigest(),
            "candidateCount": len(raw_entries), "statusCounts": dict(sorted(source_counts.items())),
            "rationale": "Candidate identity schema changed; pending and mechanical fixture rows were reissued without claiming semantic review.",
        }
    old = {str(entry["id"]): entry for entry in raw_entries if isinstance(entry, dict) and "id" in entry}
    candidates = discover(root)
    discovered = {candidate.id: candidate for candidate in candidates}
    removed = [entry for identifier, entry in old.items() if identifier not in discovered]
    errors: list[str] = []
    for entry in removed:
        identifier = str(entry["id"])
        if entry.get("classification") == "test-fixture" and entry.get("status") == "retained":
            continue
        if entry.get("status") == "pending-review" and accept_retired_pending:
            continue
        retirement = entry.get("retirement")
        approved = isinstance(retirement, dict) and retirement.get("approved") is True \
            and isinstance(retirement.get("rationale"), str) and str(retirement["rationale"]).strip()
        if approved:
            continue
        if entry.get("status") == "pending-review":
            errors.append(
                f"refresh would retire pending candidate {identifier} {entry.get('path')}:{entry.get('line')}; "
                "review the diff and rerun with --accept-retired-pending"
            )
        else:
            errors.append(
                f"refresh would retire reviewed candidate {identifier} {entry.get('path')}:{entry.get('line')}; "
                "add retirement.approved=true and retirement.rationale to that exact inventory entry"
            )
    if errors:
        return errors, {"added": sum(identifier not in old for identifier in discovered),
                        "retired": len(removed), "preserved": sum(identifier in old for identifier in discovered)}

    merged: list[dict[str, object]] = []
    added = 0
    updated = 0
    for candidate in candidates:
        entry = old.get(candidate.id)
        if entry is None:
            merged.append(candidate.inventory_entry())
            added += 1
            continue
        preserved = dict(entry)
        preserved.pop("evidence", None)
        if preserved.get("status") == "pending-review":
            preserved.pop("rationale", None)
        source_fields = candidate.source_fields()
        if any(preserved.get(key) != value for key, value in source_fields.items()):
            updated += 1
        preserved.update(source_fields)
        merged.append(preserved)

    retired_history = list(document.get("retiredEntries", []))
    semantic_removed = [entry for entry in removed if entry.get("status") != "pending-review"
                        and entry.get("classification") != "test-fixture"]
    archive_rows = semantic_removed if migrating_schema else removed
    for entry in archive_rows:
        archived = dict(entry)
        retirement = archived.pop("retirement", None)
        if entry.get("classification") == "test-fixture":
            rationale = (f"Candidate identity retired during inventory schema v{SCHEMA_VERSION} migration."
                         if migrating_schema else "Mechanically classified test-fixture candidate retired after source refresh.")
        elif entry.get("status") == "pending-review":
            rationale = "Pending candidate retired after an explicitly accepted source refresh."
        else:
            assert isinstance(retirement, dict)
            rationale = str(retirement["rationale"]).strip()
        archived["retirementRationale"] = rationale
        if "evidence" not in archived:
            old_evidence = document.get("evidenceRecords", {})
            if isinstance(old_evidence, dict):
                archived["evidence"] = old_evidence.get(str(archived.get("evidenceDigest")), "")
        retired_history.append(archived)

    refreshed = dict(document)
    refreshed["schemaVersion"] = SCHEMA_VERSION
    refreshed["entries"] = merged
    refreshed["retiredEntries"] = retired_history
    migration_history = list(document.get("migrationHistory", []))
    if migration_record is not None:
        migration_history.append(migration_record)
    refreshed["migrationHistory"] = migration_history
    refreshed["evidenceRecords"] = {
        digest: evidence for digest, evidence in sorted({
            candidate.evidence_digest: candidate.evidence for candidate in candidates
        }.items())
    }
    validation_errors = inventory_errors(root, refreshed, candidates)
    if validation_errors:
        return validation_errors, {"added": added, "retired": len(removed),
                                   "preserved": len(merged) - added, "metadataUpdated": updated}
    rendered = render_report(refreshed)
    inventory_temporary = inventory_path.with_suffix(inventory_path.suffix + ".tmp")
    report_temporary = report_path.with_suffix(report_path.suffix + ".tmp")
    inventory_temporary.write_text(json.dumps(refreshed, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    report_temporary.write_text(rendered, encoding="utf-8")
    inventory_temporary.replace(inventory_path)
    report_temporary.replace(report_path)
    return [], {"added": added, "retired": len(removed), "preserved": len(merged) - added,
                "metadataUpdated": updated}


def bootstrap(root: Path, inventory_path: Path, report_path: Path) -> None:
    if inventory_path.exists():
        raise ValueError(f"refusing to overwrite existing inventory: {inventory_path}")
    candidates = discover(root)
    document = {
        "schemaVersion": SCHEMA_VERSION,
        "description": "Machine-reviewed fixed operational candidates; counts and report are generated.",
        "entries": [candidate.inventory_entry() for candidate in candidates],
        "evidenceRecords": {
            digest: evidence for digest, evidence in sorted({
                candidate.evidence_digest: candidate.evidence for candidate in candidates
            }.items())
        },
    }
    inventory_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    inventory_path.write_text(json.dumps(document, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    report_path.write_text(render_report(document), encoding="utf-8")


def check(root: Path, inventory_path: Path = INVENTORY, report_path: Path = REPORT,
          *, require_complete: bool = True) -> list[str]:
    try:
        document = load_inventory(inventory_path)
    except ValueError as invalid:
        return [str(invalid)]
    candidates = discover(root)
    errors = inventory_errors(root, document, candidates)
    if require_complete:
        entries = document["entries"]
        assert isinstance(entries, list)
        pending = sum(isinstance(entry, dict) and entry.get("status") == "pending-review"
                      for entry in entries)
        deferred = sum(isinstance(entry, dict) and entry.get("status") == "deferred"
                       for entry in entries)
        hardcoded = sum(isinstance(entry, dict) and entry.get("status") == "confirmed-hardcoded"
                        for entry in entries)
        if pending or deferred or hardcoded:
            errors.append(
                f"audit is incomplete: {pending} pending-review, {deferred} deferred, and "
                f"{hardcoded} confirmed-hardcoded candidate(s); "
                "use --check-inventory only while completing reviewed remediation waves"
            )
    expected = render_report(document)
    if not report_path.is_file() or report_path.read_text(encoding="utf-8") != expected:
        errors.append(f"generated audit report has drifted: {report_path.relative_to(root)}")
    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true",
                      help="require a complete inventory and current generated report (the default)")
    mode.add_argument("--check-inventory", action="store_true",
                      help="check incremental inventory integrity while pending review remains")
    mode.add_argument("--refresh-inventory", action="store_true",
                      help="preserve reviews while adding and retiring changed source candidates")
    mode.add_argument("--bootstrap", action="store_true", help="create the initial inventory and report")
    parser.add_argument("--accept-retired-pending", action="store_true",
                        help="with --refresh-inventory, explicitly archive removed pending entries")
    args = parser.parse_args(argv)
    if args.accept_retired_pending and not args.refresh_inventory:
        parser.error("--accept-retired-pending requires --refresh-inventory")
    root = args.root.resolve()
    inventory = root / "scripts" / INVENTORY.name
    report = root / "docs" / "architecture" / REPORT.name
    try:
        if args.bootstrap:
            bootstrap(root, inventory, report)
            print(f"Bootstrapped {len(discover(root))} operational candidates.")
            return 0
        if args.refresh_inventory:
            errors, summary = refresh_inventory(
                root, inventory, report, accept_retired_pending=args.accept_retired_pending,
            )
            if errors:
                for error in errors:
                    print(f"ERROR: {error}", file=sys.stderr)
                print("Refresh plan: " + ", ".join(f"{key}={value}" for key, value in summary.items()),
                      file=sys.stderr)
                return 1
            print("Refreshed operational inventory ("
                  + ", ".join(f"{key}={value}" for key, value in summary.items()) + ").")
            return 0
        errors = check(root, inventory, report, require_complete=not args.check_inventory)
    except (OSError, ValueError, subprocess.CalledProcessError) as failure:
        print(f"ERROR: {failure}", file=sys.stderr)
        return 1
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    document = load_inventory(inventory)
    print(f"Operational configuration inventory is current ({len(document['entries'])} candidates).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
