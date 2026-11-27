package io.github.pcdae.adloadtest.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.util.JMeterUtils;

/**
 * Splits a run across load generators and domain controllers.
 *
 * <p>In distributed mode every generator (JMeter server) runs the whole plan, so run-wide totals and
 * rates are divided between them: {@code generator.count} is sent with the run, {@code generator.index}
 * (0-based) is set when each server starts. A single JMeter process is generator 0 of 1.
 *
 * <p>{@code ad.host} may list several domain controllers, comma separated. Each thread sticks to one,
 * like a Windows client does once the DC locator has picked one, and threads are spread over the list
 * starting at a different DC on each generator.
 */
public final class Generators {

    private Generators() {
    }

    public static int index() {
        return Math.max(0, JMeterUtils.getPropDefault("generator.index", 0));
    }

    public static int count() {
        return Math.max(1, JMeterUtils.getPropDefault("generator.count", 1));
    }

    /** This generator's part of a run-wide total, so the parts add up to exactly {@code total}. */
    public static long share(long total) {
        int n = count();
        int i = Math.min(index(), n - 1);
        return total / n + (i < total % n ? 1 : 0);
    }

    /** This generator's part of a run-wide rate. */
    public static double share(double rate) {
        return rate / count();
    }

    public static List<String> hosts(JavaSamplerContext ctx, String defaultHost) {
        return Arrays.stream(Params.get(ctx, "ad.host", defaultHost).split(","))
                .map(String::trim)
                .filter(h -> !h.isEmpty())
                .toList();
    }

    /** The domain controller for the current thread. */
    public static String hostForThread(JavaSamplerContext ctx, String defaultHost) {
        List<String> hosts = hosts(ctx, defaultHost);
        int thread = JMeterContextService.getContext().getThreadNum();
        return hosts.get(Math.floorMod(thread + index(), hosts.size()));
    }

    /** The host list starting at this generator's first DC, e.g. for the order of KDCs in krb5.conf. */
    public static List<String> rotated(List<String> hosts) {
        List<String> out = new ArrayList<>(hosts);
        if (!out.isEmpty()) {
            Collections.rotate(out, -(index() % out.size()));
        }
        return out;
    }
}
