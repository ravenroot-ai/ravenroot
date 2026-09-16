# Disposable governed-Agent acceptance repository

The initial addition implementation intentionally fails its tests. The approved example first
records a development plan, then tests the baseline, fixes the defect, reruns tests and reviews
the uncommitted change. This directory is baked into the approved runtime image once; successive
Agents must preserve its `.git` identity and filesystem rather than cloning another repository.
