package io.github.pcdae.adloadtest.common;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext;
import org.apache.jmeter.util.JMeterUtils;

/**
 * Sampler parameters fall back to a JMeter property of the same name, so shared settings
 * (host, realm, ports...) live in one properties file and the test plan only has to pass
 * per-sample values such as the username.
 */
public final class Params {

    private Params() {
    }

    public static String get(JavaSamplerContext ctx, String name, String defaultValue) {
        String value = ctx.getParameter(name);
        if (value == null || value.isBlank()) {
            value = JMeterUtils.getPropDefault(name, defaultValue);
        }
        return value == null ? null : value.trim();
    }

    public static int getInt(JavaSamplerContext ctx, String name, int defaultValue) {
        return Integer.parseInt(get(ctx, name, Integer.toString(defaultValue)));
    }

    public static double getDouble(JavaSamplerContext ctx, String name, double defaultValue) {
        return Double.parseDouble(get(ctx, name, Double.toString(defaultValue)));
    }

    public static boolean getBool(JavaSamplerContext ctx, String name, boolean defaultValue) {
        return Boolean.parseBoolean(get(ctx, name, Boolean.toString(defaultValue)));
    }

    /** Adds a parameter whose GUI default reads the matching JMeter property. */
    public static void addPropertyBacked(Arguments args, String name, String defaultValue) {
        args.addArgument(name, "${__P(" + name + "," + defaultValue + ")}");
    }
}
