Main-push backend regression selection now receives the exact preceding commit. If an accepted minor
promotion reaches main before its immutable tag is created, recovery remains fail-closed: it requires
the absent expected tag, the exact prepared version, and GitHub evidence of the preceding internal
minor promotion. Tag validation accepts an existing target only when it identifies the exact recovery
merge being validated.
