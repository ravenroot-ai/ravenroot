<!-- Ordinary pull requests, including those from forks, target dev by default. -->
<!-- Topics explicitly selected for 0.8 maintenance start from and target exactly release/0.8. -->
<!-- Never merge dev into release/0.8. Arbitrary release/* destinations are not supported. -->
<!-- Main sources are exactly internal dev or release/0.8, or protected internal hotfix/*; never fork heads. -->
<!-- A release/0.8 promotion to main additionally requires prior trusted main source-policy activation -->
<!-- through the separately reviewed, owner-authorized path in docs/governance/impact-aware-ci.md. -->

## Summary

<!-- State the user, operator, or contributor outcome. -->

## Change classification

- [ ] Breaking public-contract change
- [ ] Backward-compatible feature
- [ ] Backward-compatible fix
- [ ] Security correction
- [ ] Documentation or maintenance only

## Release note

- [ ] I added the required file under `.changes/`.
- [ ] No change fragment is required because this has no user-visible effect; the reason is stated
  below.

<!-- Link the fragment or explain the omission. -->

## Main-branch intent (maintainers)

<!-- Ignore this section unless the pull request targets main. -->
<!-- A pull request to main requires exactly one repository label: -->
<!-- release:none, release:patch, release:minor, or release:major. -->
<!-- release:none is accepted only when every changed path is documentation or public content. -->

- [ ] The pull request has exactly one applicable `release:*` label when its base is `main`.
- [ ] A `release:none` pull request changes only documentation or public content and does not change
  product, build, deployment, automation, or release metadata.

## Validation

<!-- List exact tests and checks with their results, plus anything that could not be run. -->

- [ ] Relevant automated tests pass.
- [ ] Public documentation and examples match the change.
- [ ] Compatibility, migration, security, and operator impacts are described where applicable.

## Public repository boundary

- [ ] The pull request targets `dev` by default, or exactly `release/0.8` for an explicitly selected
  maintenance topic. A pull request to `main` uses only an authorized exact internal `dev` or
  `release/0.8` source, or a protected internal `hotfix/*` source; maintenance promotion also meets
  the trusted source-policy activation prerequisite.
- [ ] All public text and commit messages are in English.
- [ ] The change contains no credentials, private paths, private repository references, personal data,
  or private project-management and operational material.
