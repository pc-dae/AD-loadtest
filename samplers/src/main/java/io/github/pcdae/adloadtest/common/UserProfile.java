package io.github.pcdae.adloadtest.common;

import java.util.Set;

import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.Sampler;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.util.JMeterUtils;

/**
 * The test user's profile and expected outcome, from the users CSV's {@code profile} and {@code expect}
 * columns. Disabled and expired accounts are expected to be rejected, so a rejection is a pass for them
 * and a successful login a failure.
 *
 * <p>Samples for profiles worth comparing (heavy, deep, disabled, expired) are labelled
 * "&lt;sampler&gt; [&lt;profile&gt;]" so the dashboard shows them separately; set the JMeter property
 * {@code label.byProfile=false} to keep one label per sampler.
 */
public final class UserProfile {

    private static final Set<String> LABELLED = Set.of("heavy", "deep", "disabled", "expired");

    private final String profile;
    private final boolean expectRejection;

    private UserProfile(String profile, boolean expectRejection) {
        this.profile = profile;
        this.expectRejection = expectRejection;
    }

    public static UserProfile from(JavaSamplerContext ctx) {
        return new UserProfile(column(ctx, "profile", "standard"), "fail".equalsIgnoreCase(column(ctx, "expect", "ok")));
    }

    /** CSV columns missing from an older users file are left as unresolved ${...} references. */
    private static String column(JavaSamplerContext ctx, String name, String defaultValue) {
        String value = ctx.getParameter(name, defaultValue);
        return value == null || value.isBlank() || value.startsWith("${") ? defaultValue : value.trim();
    }

    public boolean expectRejection() {
        return expectRejection;
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
            return Results.ok(result, code, profile + " account rejected as expected: " + message);
        }
        return Results.fail(result, code, message);
    }
}
