package io.github.pcdae.adloadtest.common;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.java.sampler.AbstractJavaSamplerClient;
import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.JMeterContext.TestLogicalAction;
import org.apache.jmeter.threads.JMeterContextService;

/**
 * First step of every iteration (one login attempt) in a protocol's thread group. It records nothing,
 * it only shapes the load:
 *
 * <ul>
 * <li>{@code <protocol>.rate}: attempts per second for the whole run, shared by all the group's
 * threads. Attempts are spaced evenly; when the threads can't keep up the run simply falls behind
 * rather than bursting to catch up, so the rate is a ceiling.</li>
 * <li>{@code <protocol>.iterations}: total attempts for the run. Once they have all started, the
 * group's threads stop, so the run ends on the count (or on {@code duration}, whichever is first).</li>
 * </ul>
 *
 * Both are run-wide figures, divided between load generators (see {@link Generators}); 0 means no limit.
 */
public class PacingSampler extends AbstractJavaSamplerClient {

    /** Per protocol and test run: JMeter servers keep their JVM between runs. */
    private static final Map<String, Gate> GATES = new ConcurrentHashMap<>();

    private Gate gate;

    @Override
    public Arguments getDefaultParameters() {
        Arguments args = new Arguments();
        args.addArgument("protocol", "ldap");
        return args;
    }

    @Override
    public void setupTest(JavaSamplerContext ctx) {
        String protocol = ctx.getParameter("protocol", "ldap");
        long testStart = JMeterContextService.getTestStartTime();
        GATES.keySet().removeIf(k -> !k.endsWith("@" + testStart));
        gate = GATES.computeIfAbsent(protocol + "@" + testStart, k -> new Gate(
                Generators.share(Params.getDouble(ctx, protocol + ".rate", 0)),
                Generators.share(Params.getInt(ctx, protocol + ".iterations", 0))));
    }

    @Override
    public SampleResult runTest(JavaSamplerContext ctx) {
        SampleResult result = new SampleResult();
        result.setIgnore();
        if (!gate.admit()) {
            result.setStopThread(true);
            result.setTestLogicalAction(TestLogicalAction.START_NEXT_ITERATION_OF_THREAD);
            return result;
        }
        try {
            gate.awaitSlot();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.setTestLogicalAction(TestLogicalAction.START_NEXT_ITERATION_OF_THREAD);
        }
        return result;
    }

    private static final class Gate {
        private final long limit;
        private final long intervalNanos;
        private final AtomicLong started = new AtomicLong();
        private final AtomicLong nextSlot = new AtomicLong(Long.MIN_VALUE);

        Gate(double ratePerSecond, long limit) {
            this.limit = limit;
            this.intervalNanos = ratePerSecond > 0 ? Math.round(1_000_000_000d / ratePerSecond) : 0;
        }

        boolean admit() {
            return limit <= 0 || started.incrementAndGet() <= limit;
        }

        void awaitSlot() throws InterruptedException {
            if (intervalNanos == 0) {
                return;
            }
            long now = System.nanoTime();
            long prev;
            long slot;
            do {
                prev = nextSlot.get();
                slot = prev == Long.MIN_VALUE ? now : Math.max(prev, now);
            } while (!nextSlot.compareAndSet(prev, slot + intervalNanos));
            long wait = slot - now;
            if (wait > 0) {
                Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            }
        }
    }
}
