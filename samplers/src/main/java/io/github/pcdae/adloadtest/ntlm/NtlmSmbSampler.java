package io.github.pcdae.adloadtest.ntlm;

import java.util.Properties;
import java.util.Set;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.java.sampler.AbstractJavaSamplerClient;
import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext;
import org.apache.jmeter.samplers.SampleResult;

import io.github.pcdae.adloadtest.common.Params;
import io.github.pcdae.adloadtest.common.Results;
import io.github.pcdae.adloadtest.common.UserProfile;
import jcifs.CIFSContext;
import jcifs.CIFSException;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbAuthException;
import jcifs.smb.SmbException;
import jcifs.smb.SmbFile;

/**
 * Authentication: an SMB2 session setup using NTLMSSP (NTLMv2) against a domain controller,
 * followed by a tree connect and directory listing of {@code smb.share}. The DC validates the
 * NTLM response itself, so this exercises the same netlogon path as NTLM pass-through from an
 * application server.
 *
 * <p>A new jCIFS context is created per sample so no session is reused between samples.
 */
public class NtlmSmbSampler extends AbstractJavaSamplerClient {

    /** NT status codes meaning the DC refused the logon, as opposed to a protocol or network error. */
    private static final Set<Integer> LOGON_REJECTED = Set.of(
            0xC000006D, // LOGON_FAILURE
            0xC000006A, // WRONG_PASSWORD
            0xC0000064, // NO_SUCH_USER
            0xC000006E, // ACCOUNT_RESTRICTION
            0xC000006F, // INVALID_LOGON_HOURS
            0xC0000070, // INVALID_WORKSTATION
            0xC0000071, // PASSWORD_EXPIRED
            0xC0000072, // ACCOUNT_DISABLED
            0xC0000193, // ACCOUNT_EXPIRED
            0xC0000224, // PASSWORD_MUST_CHANGE
            0xC0000234); // ACCOUNT_LOCKED_OUT

    private PropertyConfiguration config;
    private String url;
    private String domain;

    @Override
    public Arguments getDefaultParameters() {
        Arguments args = new Arguments();
        args.addArgument("username", "${username}");
        args.addArgument("password", "${password}");
        args.addArgument("profile", "${profile}");
        args.addArgument("expect", "${expect}");
        Params.addPropertyBacked(args, "ad.host", "dc1.lab.local");
        Params.addPropertyBacked(args, "ad.domain", "LAB");
        Params.addPropertyBacked(args, "smb.share", "netlogon");
        return args;
    }

    @Override
    public void setupTest(JavaSamplerContext ctx) {
        String host = Params.get(ctx, "ad.host", "dc1.lab.local");
        String share = Params.get(ctx, "smb.share", "netlogon");
        int port = Params.getInt(ctx, "smb.port", 445);
        url = "smb://" + host + (port == 445 ? "" : ":" + port) + "/" + share + "/";
        domain = Params.get(ctx, "ad.domain", "LAB");

        Properties props = new Properties();
        props.setProperty("jcifs.smb.client.minVersion", Params.get(ctx, "smb.minVersion", "SMB202"));
        props.setProperty("jcifs.smb.client.maxVersion", Params.get(ctx, "smb.maxVersion", "SMB210"));
        props.setProperty("jcifs.resolveOrder", "DNS");
        props.setProperty("jcifs.smb.client.responseTimeout", Params.get(ctx, "smb.responseTimeoutMs", "10000"));
        props.setProperty("jcifs.smb.client.connTimeout", Params.get(ctx, "smb.connectTimeoutMs", "5000"));
        props.setProperty("jcifs.smb.client.soTimeout", Params.get(ctx, "smb.responseTimeoutMs", "10000"));
        try {
            config = new PropertyConfiguration(props);
        } catch (CIFSException e) {
            throw new IllegalStateException("Invalid jCIFS configuration", e);
        }
    }

    @Override
    public SampleResult runTest(JavaSamplerContext ctx) {
        String username = ctx.getParameter("username");
        String password = ctx.getParameter("password");
        UserProfile profile = UserProfile.from(ctx);
        SampleResult result = profile.start();
        BaseContext base = new BaseContext(config);
        try {
            CIFSContext auth = base.withCredentials(new NtlmPasswordAuthenticator(domain, username, password));
            try (SmbFile share = new SmbFile(url, auth)) {
                share.list();
            }
            return profile.accepted(result, "SUCCESS", "NTLM session for " + domain + "\\" + username);
        } catch (SmbException e) {
            String code = "NT_STATUS_" + Integer.toHexString(e.getNtStatus()).toUpperCase();
            // jCIFS only raises SmbAuthException for some logon failures, e.g. not ACCOUNT_EXPIRED.
            return e instanceof SmbAuthException || LOGON_REJECTED.contains(e.getNtStatus())
                    ? profile.rejected(result, code, e.getMessage())
                    : Results.fail(result, code, e.getMessage());
        } catch (Exception e) {
            return Results.fail(result, Results.codeFor(e), e.getMessage());
        } finally {
            try {
                base.close();
            } catch (CIFSException ignored) {
                // Closing transports after the timed section; failures here don't affect the result.
            }
        }
    }
}
