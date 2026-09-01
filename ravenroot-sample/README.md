# Ravenroot embedded sample

This project is intentionally outside the Ravenroot reactor: it represents a third-party
application embedding the public core. Its compile classpath depends on `ravenroot-core`, while the
Pekko adapter is supplied at runtime. No actor-library type appears in the application source.

The workflow is stored in `src/main/resources/ravenroot-sample.graphml`, loaded through
`GraphManager`, and executed by the adapter selected with `RAVENROOT_ENGINE`. The sample registers
one application behavior, `uppercase-text`; graph nodes whose behavior is not registered would be
executed by Ravenroot's default pass-through behavior.

`GraphRunner.execute` takes an explicit `SecurityContext`. The embedding application is the trusted
adapter at ingress, so it supplies that identity; Ravenroot does not authenticate anyone on its
behalf. `EmbeddedSample.callerIdentity()` states a fixed local identity because a command-line
sample has nothing to authenticate — **read its Javadoc before copying it**, since a real host must
project the identity it has already authenticated instead.

Because this module is outside the reactor it is also outside `mvn clean verify`, which is how it
once drifted out of compilation unnoticed. CI therefore compiles and runs it as a separate step
after `mvn install`, keeping it outside the reactor while no longer outside verification.

After installing the main reactor locally, run the default open-source configuration with:

```sh
RAVENROOT_ENGINE=pekko mvn --batch-mode --no-transfer-progress clean test exec:java \
  -Dexec.args="hello ravenroot"
```

Licensed Akka adopters first configure Akka's official secure Maven repository outside the project,
build the main reactor with the `akka` profile, and then run:

```sh
RAVENROOT_ENGINE=akka mvn --batch-mode --no-transfer-progress -Pakka test exec:java \
  -Dexec.args="hello ravenroot"
```

The GraphML definition and application behavior are unchanged between the two commands.
