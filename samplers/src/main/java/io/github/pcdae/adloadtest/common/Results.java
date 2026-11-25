package io.github.pcdae.adloadtest.common;

import java.nio.charset.StandardCharsets;

import org.apache.jmeter.samplers.SampleResult;

/** Helpers for filling in a {@link SampleResult} consistently across samplers. */
public final class Results {

    private Results() {
    }

    public static SampleResult start() {
        SampleResult result = new SampleResult();
        result.setDataType(SampleResult.TEXT);
        result.sampleStart();
        return result;
    }

    public static SampleResult ok(SampleResult result, String code, String message) {
        if (result.getEndTime() == 0) {
            result.sampleEnd();
        }
        result.setSuccessful(true);
        result.setResponseCode(code);
        result.setResponseMessage(message);
        result.setResponseData(message, StandardCharsets.UTF_8.name());
        return result;
    }

    public static SampleResult fail(SampleResult result, String code, String message) {
        if (result.getEndTime() == 0) {
            result.sampleEnd();
        }
        result.setSuccessful(false);
        result.setResponseCode(code);
        result.setResponseMessage(message == null ? code : message);
        result.setResponseData(result.getResponseMessage(), StandardCharsets.UTF_8.name());
        return result;
    }

    /** A short, low-cardinality error code derived from the exception type. */
    public static String codeFor(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }
}
