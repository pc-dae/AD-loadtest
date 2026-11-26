package io.github.pcdae.adloadtest.kerberos;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.java.sampler.AbstractJavaSamplerClient;
import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext;
import org.apache.jmeter.samplers.SampleResult;

import io.github.pcdae.adloadtest.common.Params;
import io.github.pcdae.adloadtest.common.Results;
import io.github.pcdae.adloadtest.common.UserProfile;

/**
 * Authentication: obtains a TGT for the test user from the KDC (AS-REQ/AS-REP, including the
 * pre-authentication round trip). The resulting credentials are kept for this thread so a
 * following {@link KerberosServiceTicketSampler} can request service tickets with them.
 */
public class KerberosTgtSampler extends AbstractJavaSamplerClient {

    private String realm;

    @Override
    public Arguments getDefaultParameters() {
        Arguments args = new Arguments();
        args.addArgument("username", "${username}");
        args.addArgument("password", "${password}");
        args.addArgument("profile", "${profile}");
        args.addArgument("expect", "${expect}");
        Params.addPropertyBacked(args, "ad.realm", "LAB.LOCAL");
        return args;
    }

    @Override
    public void setupTest(JavaSamplerContext ctx) {
        realm = Params.get(ctx, "ad.realm", "LAB.LOCAL");
    }

    @Override
    public SampleResult runTest(JavaSamplerContext ctx) {
        String username = ctx.getParameter("username");
        String principal = username.contains("@") ? username : username + "@" + realm;
        char[] password = ctx.getParameter("password").toCharArray();

        UserProfile profile = UserProfile.from(ctx);
        SampleResult result = profile.start();
        try {
            LoginContext lc = KerberosSupport.login(principal, password);
            result.sampleEnd();
            KerberosSupport.replaceCurrent(lc);
            return profile.accepted(result, "TGT_ISSUED", "TGT issued for " + principal);
        } catch (LoginException e) {
            KerberosSupport.replaceCurrent(null);
            String code = kdcErrorCode(e);
            // A KDC error is the directory saying no; anything else (no KDC, timeout) is a test failure.
            return code.startsWith("KDC_ERR") || code.startsWith("KRB_")
                    ? profile.rejected(result, code, e.getMessage())
                    : Results.fail(result, code, e.getMessage());
        } catch (RuntimeException e) {
            KerberosSupport.replaceCurrent(null);
            return Results.fail(result, Results.codeFor(e), e.getMessage());
        }
    }

    /**
     * The JDK reports KDC errors as a message ending in "(code)"; turn the common ones into
     * stable names so the dashboard groups them sensibly.
     */
    static String kdcErrorCode(LoginException e) {
        Matcher m = KDC_CODE.matcher(String.valueOf(e.getMessage()));
        if (!m.find()) {
            return Results.codeFor(e);
        }
        int code = Integer.parseInt(m.group(1));
        return KDC_ERRORS.getOrDefault(code, "KDC_ERR_" + code);
    }

    private static final Pattern KDC_CODE = Pattern.compile("\\((\\d+)\\)\\s*$");

    private static final Map<Integer, String> KDC_ERRORS = Map.of(
            6, "KDC_ERR_C_PRINCIPAL_UNKNOWN",
            12, "KDC_ERR_POLICY",
            14, "KDC_ERR_ETYPE_NOSUPP",
            18, "KDC_ERR_CLIENT_REVOKED",
            23, "KDC_ERR_KEY_EXPIRED",
            24, "KDC_ERR_PREAUTH_FAILED",
            37, "KRB_AP_ERR_SKEW",
            68, "KDC_ERR_WRONG_REALM");

    @Override
    public void teardownTest(JavaSamplerContext ctx) {
        KerberosSupport.replaceCurrent(null);
    }
}
