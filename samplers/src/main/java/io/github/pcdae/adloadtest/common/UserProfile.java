package io.github.pcdae.adloadtest.common;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.threads.JMeterVariables;
import org.apache.jmeter.util.JMeterUtils;

/**
 * The test user's profile and expected outcome, from the users CSV's {@code profile} and {@code expect}
 * columns. Disabled and expired accounts are expected to be rejected, so a rejection is a pass for them
 * and a successful login a failure.
 *
 * <p>Failure testing: with {@code badPassword.percent} set, that share of iterations (login attempts)
 * send a wrong password and are expected to be rejected, under the profile "bad-password". Every sampler
 * in the iteration sees the same decision. On a domain with an account lockout policy, set
 * {@code badPassword.maxPerUser} below the lockout threshold: once a user has had that many bad attempts
 * in this load generator's run, their later attempts use the right password.
 *
 * <p>Samples for profiles worth comparing (heavy, deep, disabled, expired, computer, bad-password) are
 * labelled "&lt;sampler&gt; [&lt;profile&gt;]" so the dashboard shows them separately; set the JMeter
 * property {@code label.byProfile=false} to keep one label per sampler.
 */
public final class UserProfile {

    public static final String BAD_PASSWORD = "bad-password";

    private static final Set<String> LABELLED = Set.of("heavy", "deep", "disabled", "expired", "computer", BAD_PASSWORD);
    private static final String ITERATION_VAR = "adloadtest.profile.iteration";
    private static final String BAD_VAR = "adloadtest.profile.badPassword";
    private static final String WRONG_SUFFIX = "-wrong";

    /** Bad attempts per user in the current run, keyed by test start so a JMeter server's next run starts afresh. */
    private static final Map<String, AtomicInteger> BAD_ATTEMPTS = new ConcurrentHashMap<>();
    private static volatile long badAttemptsRun;

    private final String profile;
    private final boolean expectRejection;
    private final boolean badPassword;

    private UserProfile(String profile, boolean expectRejection, boolean badPassword) {
        this.profile = profile;
        this.expectRejection = expectRejection;
        this.badPassword = badPassword;
    }

    public static UserProfile from(JavaSamplerContext ctx) {
        String profile = column(ctx, "profile", "standard");
        boolean expectRejection = "fail".equalsIgnoreCase(column(ctx, "expect", "ok"));
        if (badPasswordThisIteration(ctx.getParameter("username"))) {
            return new UserProfile(BAD_PASSWORD, true, true);
        }
        return new UserProfile(profile, expectRejection, false);
    }

    /** CSV columns missing from an older users file are left as unresolved ${...} references. */
    private static String column(JavaSamplerContext ctx, String name, String defaultValue) {
        String value = ctx.getParameter(name, defaultValue);
        return value == null || value.isBlank() || value.startsWith("${") ? defaultValue : value.trim();
    }

    /** Decided once per iteration and kept in thread variables, so the iteration's samplers agree. */
    private static boolean badPasswordThisIteration(String username) {
        JMeterVariables vars = JMeterContextService.getContext().getVariables();
        if (vars == null) {
            return false;
        }
        Integer iteration = vars.getIteration();
        if (iteration.equals(vars.getObject(ITERATION_VAR))) {
            return Boolean.TRUE.equals(vars.getObject(BAD_VAR));
        }
        boolean bad = chooseBadPassword(username);
        vars.putObject(ITERATION_VAR, iteration);
        vars.putObject(BAD_VAR, bad);
        return bad;
    }

    private static boolean chooseBadPassword(String username) {
        double percent = Double.parseDouble(JMeterUtils.getPropDefault("badPassword.percent", "0"));
        if (percent <= 0 || ThreadLocalRandom.current().nextDouble(100) >= percent) {
            return false;
        }
        int maxPerUser = JMeterUtils.getPropDefault("badPassword.maxPerUser", 0);
        if (maxPerUser <= 0 || username == null || username.startsWith("${")) {
            return true;
        }
        long run = JMeterContextService.getTestStartTime();
        if (badAttemptsRun != run) {
            synchronized (BAD_ATTEMPTS) {
                if (badAttemptsRun != run) {
                    BAD_ATTEMPTS.clear();
                    badAttemptsRun = run;
                }
            }
        }
        return BAD_ATTEMPTS.computeIfAbsent(username.toLowerCase(), u -> new AtomicInteger()).incrementAndGet() <= maxPerUser;
    }

    public boolean expectRejection() {
        return expectRejection;
    }

    /** The password to send: the CSV one, or a wrong one on a bad-password attempt. */
    public String password(JavaSamplerContext ctx) {
        String password = ctx.getParameter("password");
        return badPassword ? password + WRONG_SUFFIX : password;
    }

    public SampleResult start() {
        SampleResult result = Results.start();
        Sampler sampler = JMeterContextService.getContext().getCurrentSampler();
        if (sampler != null && LABELLED.contains(profile) && JMeterUtils.getPropDefault("label.byProfile", true)) {
            result.setSampleLabel(sampler.getName() + " [" + profile + "]");
        }
        return result;
    }

    /** The directory accepted the credentials. */
    public SampleResult accepted(SampleResult result, String code, String message) {
        if (expectRejection) {
            return Results.fail(result, "UNEXPECTED_SUCCESS", profile + " account was accepted: " + message);
        }
        return Results.ok(result, code, message);
    }

    /** The directory rejected the credentials (as opposed to a timeout or other error). */
    public SampleResult rejected(SampleResult result, String code, String message) {
        if (expectRejection) {
            return Results.ok(result, code, profile + " rejected as expected: " + message);
        }
        return Results.fail(result, code, message);
    }
}
