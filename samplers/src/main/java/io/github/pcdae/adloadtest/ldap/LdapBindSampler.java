package io.github.pcdae.adloadtest.ldap;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.java.sampler.AbstractJavaSamplerClient;
import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext;
import org.apache.jmeter.samplers.SampleResult;

import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SimpleBindRequest;

import io.github.pcdae.adloadtest.common.Results;

/**
 * Authentication: opens a fresh connection and performs a simple bind as the test user.
 * The sample time covers TCP connect, TLS handshake (when enabled) and the bind itself,
 * which is what an application doing "LDAP authentication" pays per login.
 */
public class LdapBindSampler extends AbstractJavaSamplerClient {

    private LdapSupport ldap;

    @Override
    public Arguments getDefaultParameters() {
        Arguments args = new Arguments();
        args.addArgument("username", "${username}");
        args.addArgument("password", "${password}");
        LdapSupport.addDefaults(args);
        return args;
    }

    @Override
    public void setupTest(JavaSamplerContext ctx) {
        try {
            ldap = new LdapSupport(ctx);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialise LDAP settings", e);
        }
    }

    @Override
    public SampleResult runTest(JavaSamplerContext ctx) {
        String bindName = ldap.bindName(ctx.getParameter("username"));
        SampleResult result = Results.start();
        try (LDAPConnection conn = ldap.connect()) {
            conn.bind(new SimpleBindRequest(bindName, ctx.getParameter("password")));
            return Results.ok(result, "SUCCESS", "bound as " + bindName);
        } catch (LDAPException e) {
            return Results.fail(result, e.getResultCode().getName().toUpperCase().replace(' ', '_'),
                    e.getDiagnosticMessage() != null ? e.getDiagnosticMessage() : e.getMessage());
        } catch (RuntimeException e) {
            return Results.fail(result, Results.codeFor(e), e.getMessage());
        }
    }
}
