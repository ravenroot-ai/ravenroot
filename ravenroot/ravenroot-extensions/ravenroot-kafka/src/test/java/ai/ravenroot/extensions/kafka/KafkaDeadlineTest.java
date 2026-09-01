package ai.ravenroot.extensions.kafka;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class KafkaDeadlineTest {
    /**
     * Both {@code elapsedMs<170} ceilings in this file guard a real 100ms {@code timeoutMs}
     * deadline ({@code KafkaProduceNodeBehavior.deadline(now, settings.timeoutMs)}) -- applying the
     * "does the property depend on time?" question from
     * {@code docs/qa/what-the-testkits-do-not-cover.md}. Here the answer
     * is yes for this test: the property is that the deadline cancels ownership near 100ms and never
     * lets a producer materialized after cancellation become usable, so widening the ceiling would
     * weaken exactly what it verifies. Left unwidened; the number is a measurement, not a tuned guess.
     *
     * <p>The seam question is checked on the sources rather than assumed:
     * {@code KafkaProduceNodeBehavior} carries an
     * injectable {@code LongSupplier ticker} -- field, package-private 5-arg constructor, already used
     * by three other tests in this class ({@code blockedSendHoldsAdmissionUntilDelayedCloseRevokesOwnership},
     * {@code blockedFlushHoldsAdmissionUntilDelayedCloseRevokesOwnership},
     * {@code rateRefusalAndCompletedInvocationReleaseAdmissionForNextWindow}) -- and both
     * {@code deadline(now, ms)} and {@code remainingNanos(deadline)}/{@code remainingMillis(deadline)}
     * read it directly, in the same outer class. Unlike IMAP's {@code DeadlineWatchdog}, there is no
     * separate nested class here that loses access to the supplier: the seam reaches the computation
     * completely.
     *
     * <p>It still does not let the wait be skipped. The actual block is
     * {@code FutureTask.get(long timeout, TimeUnit unit)} -- {@code createClient}'s
     * {@code task.get(remainingNanos(deadline), NANOSECONDS)} for this test, the close-deadline
     * watcher's {@code close.get(remaining, NANOSECONDS)} for the sibling below -- and that JDK
     * primitive enforces whatever nanos value it is given against {@code System.nanoTime()} internally
     * ({@code AbstractQueuedSynchronizer}'s timed park), a mechanism the JDK exposes no injection point
     * for. {@code ticker} only controls the raw nanos number computed and handed to {@code get(...)};
     * once that call is made, the wait it performs is real wall-clock time no matter what the ticker
     * reports before or after. Faking the ticker to report a mostly-elapsed budget would shrink the real
     * wait, but it would then be testing a near-zero-budget edge case, not whether cancellation happens
     * near the full configured 100ms of genuine blocking -- a different, weaker property. No production change
     * closes this either: the JDK's timed {@code Future.get} is not clock-injectable by design, so
     * "measure the deadline without going through wall-clock" is out of reach here even though, unlike
     * IMAP, the seam itself is not missing.
     *
     * <p>So the number was verified as a measurement (Apple M1 Max, 10 cores, this machine; JDK 21).
     * This test genuinely blocks the full ~100ms -- {@code establish()} is
     * held on a latch and {@code create()}'s future does not resolve until the deadline cancels it:
     * isolated (8 runs) 101-110ms; under artificial ~9-way CPU contention across this machine's 10 cores
     * (8 runs) 101-117ms, no failures. Separately, 6 full runs of the actual
     * {@code result.get(180ms)}/{@code elapsedMs<170} assertions below, under the same contention, all
     * green. That leaves ~53ms (~1.45x) headroom between the slowest contended run and the 170ms
     * ceiling.
     *
     * <p>{@code result.get(180, TimeUnit.MILLISECONDS)} and {@code elapsedMs<170} below are the same
     * constraint stated twice -- the wait must outlast the ceiling it polices. Since the ceiling is
     * kept as a measurement rather than widened, the wait stays unchanged alongside it.
     */
    @Test void blockedCreateReturnsAtDeadlineCancelsOwnershipAndNeverSends() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),exited=new CountDownLatch(1);AtomicInteger cancels=new AtomicInteger(),claims=new AtomicInteger(),sends=new AtomicInteger();
        KafkaProtocol protocol=(profile,password,timeout)->new KafkaProtocol.CreateAttempt(){@Override public void establish(){entered.countDown();await(release);exited.countDown();}@Override public KafkaProtocol.Client claim(){claims.incrementAndGet();return client(sends,false);}@Override public void cancel(){cancels.incrementAndGet();}};
        var action=behavior(protocol).create(KafkaTestSupport.configuration(Map.of("timeoutMs","100")));var result=action.handle(KafkaTestSupport.message(KafkaTestSupport.payload())).toCompletableFuture();assertTrue(entered.await(1,TimeUnit.SECONDS));long start=System.nanoTime();try{assertEquals("TEMPORARY_FAILURE",((Map<?,?>)result.get(180,TimeUnit.MILLISECONDS).payload()).get("status"));assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<170);assertEquals(1,cancels.get());assertEquals(0,sends.get());}finally{release.countDown();}assertTrue(exited.await(1,TimeUnit.SECONDS));assertEquals(0,claims.get(),"a producer materialized after cancellation must never become usable");
    }
    /**
     * This regression test was formerly named
     * {@code blockedCloseAddsNoPostDeadlineGraceAndAdmissionIsReusable}. That name promised two claims
     * sharing one green while only the second was exercised: with
     * the fake client's {@code close(timeout, revoked)} invoking {@code revoked} synchronously before its own
     * blocking loop, and that loop being interruptible (a plain {@code Thread.sleep(1000)} that returns on
     * {@code InterruptedException}), the production close-deadline watcher could always rescue the follow-up
     * invocation on its own -- forcing the worker to return near the 100ms deadline regardless of whether
     * {@code revoked} had already fired. Checked directly: wiring {@code CloseHandoff.launch()} to hand the
     * client a no-op instead of {@code this::acknowledge} left this test green across 5 runs, because the
     * FutureTask's own {@code finally { acknowledge(); ... }} still ran once the interrupted {@code close()}
     * returned. So, as originally written, the name's claim that admission survival depends on revoke
     * preceding the block was not provable against a realistic regression in that wiring -- it was rescued by
     * a redundant safety net the test never isolated from.
     *
     * <p>The fixture, not the assertions, makes {@code client(sends, true)}'s blocking loop
     * swallow {@code InterruptedException} and keep sleeping, so the fake {@code close()} genuinely never
     * returns -- matching what the name has always said -- and the redundant safety net (which depends on
     * {@code close()} eventually returning) can no longer be what saves the follow-up call. Re-run with the
     * SAME no-op-instead-of-{@code this::acknowledge} mutation against this stricter fixture: deterministic
     * red, 5/5 runs, second {@code KafkaTestSupport.output(...)} call returns {@code TEMPORARY_FAILURE}
     * (not {@code ACKNOWLEDGED}) at 0ms elapsed, because with the client never signalling revoke and never
     * returning, nothing else can release the permit. First-call elapsed under the mutation: 123-131ms
     * (5 runs; the production interrupt still fires at the deadline and unblocks {@code startAndAwait()}'s
     * own bounded wait, so the first call's own ceiling stays irrelevant to this property; the second call's
     * status is what breaks). Machine: Apple M1 Max, 10 cores, this machine; JDK 21.
     *
     * <p>Renamed to describe only what is now provably exercised: a {@code close()} that never returns and
     * never itself signals completion does not strand the admission permit, because production wires
     * {@code revoked} to fire, and release the permit, before handing control to that endless block. The
     * {@code elapsedMs<170} ceiling below is unchanged, with no widening to make room. Re-measured
     * against the hardened fixture on this machine: isolated (8 runs) 20-27ms; under ~9-way contention
     * (8 runs, ~10 background {@code yes} processes on 10 cores) 37-107ms, no failures -- close to the
     * previously observed 0-21ms/0-52ms range for this shape (the hardened loop does not change the fast path, only
     * what happens when it is not taken). Still a hang-detection ceiling, not a deadline-precision one; no
     * dependency on the 100ms {@code timeoutMs} figure.
     *
     * <p>The post-deadline-grace half of the old name is gone from here because it was never provable by
     * this shape: injecting 400ms of grace passes green in both positions around
     * {@code revoked.run()}). It has its own test below, {@link #closeAttemptingToOutlastTheDeadlineGetsNoGrace()},
     * built around a fake client that tries to outlast the deadline instead of finishing before it.
     */
    @Test void blockedCloseDoesNotStrandAdmissionForReuse() throws Exception {
        AtomicInteger sends=new AtomicInteger();KafkaProtocol protocol=(profile,password,timeout)->new KafkaProtocol.CreateAttempt(){@Override public void establish(){}@Override public KafkaProtocol.Client claim(){return client(sends,true);}@Override public void cancel(){}};
        var action=behavior(protocol).create(KafkaTestSupport.configuration(Map.of("timeoutMs","100","maxConcurrency","1")));long start=System.nanoTime();assertEquals("ACKNOWLEDGED",KafkaTestSupport.output(action,KafkaTestSupport.payload()).get("status"));assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<170);assertEquals("ACKNOWLEDGED",KafkaTestSupport.output(action,KafkaTestSupport.payload()).get("status"));assertEquals(2,sends.get());
    }
    /**
     * The property the old combined test's name promised but never reached: {@code close()} gets no
     * grace beyond what remains of the 100ms deadline when it tries to run past it. Unlike the test above,
     * this fake client does not call {@code revoked} first -- it tries to sleep 400ms (four times the
     * deadline, the same injection size used to disprove the old test) and only then signals completion,
     * so if production ever let {@code close()} run past its budget, this is where it would show.
     *
     * <p>It does not, because nothing in {@code CloseHandoff.launch()} extends the close-deadline watcher's
     * budget past {@code remainingNanos(deadline)} -- the same {@code deadline} field computed once at the
     * start of {@code execute()}. Each invocation gets a fresh ~100ms window; the watcher's forced
     * {@code worker.interrupt()} cuts the attempted 400ms sleep short every time, near that window's own
     * deadline, not the client's requested duration. Measured on this machine (Apple M1 Max, 10 cores;
     * JDK 21): isolated (8 runs) first-call 101-103ms, second-call 105-111ms; under ~9-way contention
     * (8 runs, ~10 background {@code yes} processes on 10 cores) first-call 103-116ms, second-call
     * 108-118ms, no failures -- comfortably under the unwidened 170ms ceiling both times and nowhere near
     * the 400ms the client asks for.
     *
     * <p>Mutation, reported with the time: added an explicit 300ms grace to the close-deadline watcher's own
     * budget in {@code launch()} ({@code long graced = remaining + 300ms; close.get(graced, NANOSECONDS)},
     * leaving {@code startAndAwait()} and the {@code remaining == 0} fast path untouched). Deterministic red,
     * 3/3 runs: first call still resolves at 130-131ms (unaffected -- {@code startAndAwait()}'s own bound is
     * separate and unchanged), but the second {@code KafkaTestSupport.output(...)} call fails immediately
     * (0ms) with {@code TEMPORARY_FAILURE} instead of {@code ACKNOWLEDGED} -- the first call's permit is
     * still held (the grace-extended close has not released it yet) when the second call's non-blocking
     * {@code tryAcquire} runs. No widened ceiling was needed to see this; the assertion that catches it is
     * the existing {@code assertEquals("ACKNOWLEDGED", ...)} on the second call, not a new time bound.
     */
    @Test void closeAttemptingToOutlastTheDeadlineGetsNoGrace() throws Exception {
        AtomicInteger sends=new AtomicInteger();
        KafkaProtocol protocol=(p,password,t)->attempt(new KafkaProtocol.Client(){@Override public void send(KafkaProtocol.Record r,KafkaProtocol.Observer o,int budget){sends.incrementAndGet();o.acknowledged(metadata(r));}@Override public void flush(){}@Override public void close(int timeout,Runnable revoked){try{Thread.sleep(400);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}revoked.run();}});
        var action=behavior(protocol).create(KafkaTestSupport.configuration(Map.of("timeoutMs","100","maxConcurrency","1")));
        long start=System.nanoTime();assertEquals("ACKNOWLEDGED",KafkaTestSupport.output(action,KafkaTestSupport.payload()).get("status"));assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<170);
        long start2=System.nanoTime();assertEquals("ACKNOWLEDGED",KafkaTestSupport.output(action,KafkaTestSupport.payload()).get("status"));assertTrue(Duration.ofNanos(System.nanoTime()-start2).toMillis()<170,"close must be cut off at the deadline, not after the 400ms sleep it attempts");
        assertEquals(2,sends.get());
    }
    @Test void blockedSendHoldsAdmissionUntilDelayedCloseRevokesOwnership() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),exited=new CountDownLatch(1),firstClosed=new CountDownLatch(1),closed=new CountDownLatch(2);AtomicInteger sends=new AtomicInteger(),closes=new AtomicInteger();var cleanup=new DelayedExecutor();var controls=new KafkaRuntimeControls(System::nanoTime,command->Thread.ofVirtual().start(command),cleanup,1,1,16);
        KafkaProtocol protocol=(p,password,t)->attempt(new KafkaProtocol.Client(){@Override public void send(KafkaProtocol.Record r,KafkaProtocol.Observer o,int budget){if(sends.incrementAndGet()==1){entered.countDown();await(release);exited.countDown();}else o.acknowledged(metadata(r));}@Override public void flush(){}@Override public void close(int timeout,Runnable revoked){closes.incrementAndGet();revoked.run();firstClosed.countDown();closed.countDown();}});
        var action=behavior(protocol,controls,System::nanoTime).create(KafkaTestSupport.configuration(Map.of("timeoutMs","100","maxConcurrency","1")));var first=action.handle(KafkaTestSupport.message(KafkaTestSupport.payload())).toCompletableFuture();assertTrue(entered.await(1,TimeUnit.SECONDS));try{assertEquals("AMBIGUOUS",((Map<?,?>)first.get(180,TimeUnit.MILLISECONDS).payload()).get("status"));assertEquals(1,sends.get());assertEquals(0,closes.get());assertEquals(1,cleanup.queued());assertEquals(0,controls.global.availablePermits());assertEquals(1,controls.pendingCleanups.size());assertEquals("TEMPORARY_FAILURE",KafkaTestSupport.output(action,KafkaTestSupport.payload()).get("status"));assertEquals(1,sends.get());cleanup.release();assertTrue(firstClosed.await(1,TimeUnit.SECONDS));assertEquals("ACKNOWLEDGED",KafkaTestSupport.output(action,KafkaTestSupport.payload()).get("status"));assertTrue(closed.await(1,TimeUnit.SECONDS));awaitCleanupBaseline(controls);assertEquals(2,sends.get());assertEquals(2,closes.get());}finally{release.countDown();}assertTrue(exited.await(1,TimeUnit.SECONDS));
    }
    @Test void blockedFlushHoldsAdmissionUntilDelayedCloseRevokesOwnership() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),exited=new CountDownLatch(1),firstClosed=new CountDownLatch(1),closed=new CountDownLatch(2);AtomicInteger sends=new AtomicInteger(),flushes=new AtomicInteger(),closes=new AtomicInteger();var cleanup=new DelayedExecutor();var controls=new KafkaRuntimeControls(System::nanoTime,command->Thread.ofVirtual().start(command),cleanup,1,1,16);
        KafkaProtocol protocol=(p,password,t)->attempt(new KafkaProtocol.Client(){@Override public void send(KafkaProtocol.Record r,KafkaProtocol.Observer o,int budget){sends.incrementAndGet();o.acknowledged(metadata(r));}@Override public void flush(){if(flushes.incrementAndGet()==1){entered.countDown();await(release);exited.countDown();}}@Override public void close(int timeout,Runnable revoked){closes.incrementAndGet();revoked.run();firstClosed.countDown();closed.countDown();}});
        var action=behavior(protocol,controls,System::nanoTime).create(KafkaTestSupport.configuration(Map.of("timeoutMs","100","maxConcurrency","1")));var first=action.handle(KafkaTestSupport.message(KafkaTestSupport.payload())).toCompletableFuture();assertTrue(entered.await(1,TimeUnit.SECONDS));try{assertEquals("ACKNOWLEDGED",((Map<?,?>)first.get(180,TimeUnit.MILLISECONDS).payload()).get("status"));assertEquals(0,closes.get());assertEquals(1,cleanup.queued());assertEquals(0,controls.global.availablePermits());assertEquals(1,controls.pendingCleanups.size());assertEquals("TEMPORARY_FAILURE",KafkaTestSupport.output(action,KafkaTestSupport.payload()).get("status"));assertEquals(1,sends.get());cleanup.release();assertTrue(firstClosed.await(1,TimeUnit.SECONDS));assertEquals("ACKNOWLEDGED",KafkaTestSupport.output(action,KafkaTestSupport.payload()).get("status"));assertTrue(closed.await(1,TimeUnit.SECONDS));awaitCleanupBaseline(controls);assertEquals(2,sends.get());assertEquals(2,closes.get());}finally{release.countDown();}assertTrue(exited.await(1,TimeUnit.SECONDS));
    }
    @Test void executorRejectionReleasesEveryAdmissionLayerBeforeClientCreation() {
        AtomicInteger submissions=new AtomicInteger();var controls=new KafkaRuntimeControls(System::nanoTime,task->{if(submissions.getAndIncrement()==0)throw new java.util.concurrent.RejectedExecutionException();task.run();},1,1,8);var protocol=new KafkaTestSupport.FakeProtocol();var action=behavior(protocol,controls,System::nanoTime).create(KafkaTestSupport.configuration(Map.of("maxConcurrency","1")));assertEquals("TEMPORARY_FAILURE",KafkaTestSupport.output(action,KafkaTestSupport.payload()).get("status"));assertEquals(1,controls.global.availablePermits());assertTrue(controls.tenants.isEmpty());assertTrue(controls.profiles.isEmpty());assertEquals(0,protocol.creates.get());assertEquals("ACKNOWLEDGED",KafkaTestSupport.output(action,KafkaTestSupport.payload()).get("status"),"a second invocation proves the action gate was also released");assertEquals(1,protocol.creates.get());assertEquals(1,controls.global.availablePermits());assertTrue(controls.tenants.isEmpty());assertTrue(controls.profiles.isEmpty());
    }
    @Test void rateRefusalAndCompletedInvocationReleaseAdmissionForNextWindow() {
        AtomicLong now=new AtomicLong();var controls=new KafkaRuntimeControls(now::get,Runnable::run,1,1,8);var protocol=new KafkaTestSupport.FakeProtocol(KafkaTestSupport.Event.ACK,KafkaTestSupport.Event.ACK);var rateBehavior=new KafkaProduceNodeBehavior(ref->java.util.Optional.of(new ai.ravenroot.api.security.SecretValue(KafkaTestSupport.SECRET.toCharArray())),(tenant,name)->java.util.Optional.of(KafkaTestSupport.profile(tenant,name,100,1)),protocol,controls,now::get);var action=rateBehavior.create(KafkaTestSupport.configuration(Map.of("maxConcurrency","1")));assertEquals("ACKNOWLEDGED",KafkaTestSupport.output(action,KafkaTestSupport.payload()).get("status"));assertEquals("RATE_LIMITED",KafkaTestSupport.output(action,KafkaTestSupport.payload()).get("status"));assertEquals(1,protocol.sends.get());now.addAndGet(TimeUnit.SECONDS.toNanos(1));assertEquals("ACKNOWLEDGED",KafkaTestSupport.output(action,KafkaTestSupport.payload()).get("status"));assertEquals(2,protocol.sends.get());assertEquals(1,controls.global.availablePermits());assertTrue(controls.tenants.isEmpty());assertTrue(controls.profiles.isEmpty());
    }
    private static KafkaProtocol.CreateAttempt attempt(KafkaProtocol.Client client){return new KafkaProtocol.CreateAttempt(){@Override public void establish(){}@Override public KafkaProtocol.Client claim(){return client;}@Override public void cancel(){}};}
    private static KafkaProtocol.Metadata metadata(KafkaProtocol.Record r){return new KafkaProtocol.Metadata(r.topic(),0,1,1,-1,r.value().length);}
    private static void await(CountDownLatch latch){while(latch.getCount()>0)try{latch.await();}catch(InterruptedException ignored){}}
    private static void awaitCleanupBaseline(KafkaRuntimeControls controls)throws Exception{assertTrue(controls.awaitNoPendingCleanups(1,TimeUnit.SECONDS));assertEquals(1,controls.global.availablePermits());assertTrue(controls.tenants.isEmpty());assertTrue(controls.profiles.isEmpty());}
    private static KafkaProtocol.Client client(AtomicInteger sends,boolean blockClose){return new KafkaProtocol.Client(){@Override public void send(KafkaProtocol.Record record,KafkaProtocol.Observer observer,int timeout){sends.incrementAndGet();observer.acknowledged(new KafkaProtocol.Metadata(record.topic(),0,1,1,-1,record.value().length));}@Override public void flush(){}@Override public void close(int timeout,Runnable revoked){revoked.run();if(blockClose)while(true)try{Thread.sleep(1000);}catch(InterruptedException ignored){/* Genuinely never returns: the production interrupt must not be what rescues admission here. */}}};}
    private static KafkaProduceNodeBehavior behavior(KafkaProtocol protocol){return new KafkaProduceNodeBehavior(ref->java.util.Optional.of(new ai.ravenroot.api.security.SecretValue(KafkaTestSupport.SECRET.toCharArray())),(tenant,name)->java.util.Optional.of(KafkaTestSupport.profile(tenant,name,100)),protocol,new KafkaRuntimeControls(System::nanoTime,command->Thread.ofVirtual().start(command),1,1,16),System::nanoTime);}
    private static KafkaProduceNodeBehavior behavior(KafkaProtocol protocol,KafkaRuntimeControls controls,java.util.function.LongSupplier ticker){return new KafkaProduceNodeBehavior(ref->java.util.Optional.of(new ai.ravenroot.api.security.SecretValue(KafkaTestSupport.SECRET.toCharArray())),(tenant,name)->java.util.Optional.of(KafkaTestSupport.profile(tenant,name,100)),protocol,controls,ticker);}
    private static final class DelayedExecutor implements Executor{private final ArrayDeque<Runnable> queued=new ArrayDeque<>();private boolean released;@Override public void execute(Runnable task){synchronized(queued){if(!released){queued.addLast(task);return;}}task.run();}int queued(){synchronized(queued){return queued.size();}}void release(){while(true){Runnable task;synchronized(queued){released=true;task=queued.pollFirst();}if(task==null)return;task.run();}}}
}
