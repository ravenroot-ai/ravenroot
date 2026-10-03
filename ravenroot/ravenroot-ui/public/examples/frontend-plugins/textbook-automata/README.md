# Textbook automata frontend plugin

This directory is an installable Ravenroot frontend package. In Workbench, open **Drawing options**,
choose **Install package**, and select this directory. Open an executable workflow, choose **Edit mapping**,
apply `dfa-even-ones-presentation.json`, then select **Textbook DFA/NFA**.

The package changes only presentation. Its mapping explicitly connects synthetic states and
transitions to real workflow nodes and ordered edge paths. **Full flow** restores the complete
operational graph. NFA shapes and parallel transitions are renderer capabilities; this package does
not add an automata runtime, DOT import, determinization, minimization, or an educational simulator.

The package requests no permissions. Its module runs in Ravenroot's opaque-origin frontend sandbox,
with network, storage, navigation and direct host DOM access unavailable. It returns a bounded
declarative SVG scene which Ravenroot validates and materializes.
