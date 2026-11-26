package io.github.pcdae.adloadtest.kerberos;

import java.util.concurrent.CompletionException;

import javax.security.auth.Subject;
import javax.security.auth.kerberos.KerberosTicket;
import javax.security.auth.login.LoginContext;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.java.sampler.AbstractJavaSamplerClient;
import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext;
import org.apache.jmeter.samplers.SampleResult;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;

import io.github.pcdae.adloadtest.common.Params;
import io.github.pcdae.adloadtest.common.Results;
import io.github.pcdae.adloadtest.common.UserProfile;

/**
 * Authorisation to a service: uses the thread's TGT to request a service ticket (TGS-REQ/TGS-REP)
 * for {@code kerberos.spn}, then builds the AP-REQ a client would send to that service. In AD the
 * TGS step is where the user's group memberships (the PAC) are assembled.
 *
 * <p>Java caches service tickets in the Subject, so cached ones are dropped first to make sure
 * every sample really goes to the KDC.
 */
public class KerberosServiceTicketSampler extends AbstractJavaSamplerClient {

    private static final Oid KRB5_MECH;

    static {
        try {
            KRB5_MECH = new Oid("1.2.840.113554.1.2.2");
        } catch (GSSException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private String spn;

    @Override
    public Arguments getDefaultParameters() {
        Arguments args = new Arguments();
        args.addArgument("profile", "${profile}");
        args.addArgument("expect", "${expect}");
        Params.addPropertyBacked(args, "kerberos.spn", "ldap@dc1.lab.local");
        return args;
    }

    @Override
    public void setupTest(JavaSamplerContext ctx) {
        spn = Params.get(ctx, "kerberos.spn", "ldap@dc1.lab.local");
    }

    @Override
    public SampleResult runTest(JavaSamplerContext ctx) {
        UserProfile profile = UserProfile.from(ctx);
        SampleResult result = profile.start();
        LoginContext lc = KerberosSupport.current();
        if (lc == null) {
            // Expected when the KDC rightly refused this iteration's (disabled or expired) user a TGT.
            return profile.rejected(result, "NO_TGT", "No TGT on this thread; the Kerberos TGT sampler did not get one");
        }
        Subject subject = lc.getSubject();
        subject.getPrivateCredentials(KerberosTicket.class)
                .removeIf(t -> !t.getServer().getName().startsWith("krbtgt/"));
        try {
            int tokenLength = Subject.callAs(subject, this::initContext);
            return profile.accepted(result, "TICKET_ISSUED", "service ticket for " + spn + " (AP-REQ " + tokenLength + " bytes)");
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String code = cause instanceof GSSException g ? "GSS_" + g.getMajorString().toUpperCase().replace(' ', '_')
                    : Results.codeFor(cause);
            return Results.fail(result, code, cause.getMessage());
        } catch (RuntimeException e) {
            return Results.fail(result, Results.codeFor(e), e.getMessage());
        }
    }

    private int initContext() throws GSSException {
        GSSManager manager = GSSManager.getInstance();
        GSSName server = manager.createName(spn, GSSName.NT_HOSTBASED_SERVICE);
        GSSContext context = manager.createContext(server, KRB5_MECH, null, GSSContext.DEFAULT_LIFETIME);
        try {
            context.requestMutualAuth(false);
            context.requestCredDeleg(false);
            byte[] token = context.initSecContext(new byte[0], 0, 0);
            return token == null ? 0 : token.length;
        } finally {
            context.dispose();
        }
    }
}
