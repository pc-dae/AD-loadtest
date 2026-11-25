package io.github.pcdae.adloadtest.ntlm;

import java.util.Properties;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.java.sampler.AbstractJavaSamplerClient;
import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext;
import org.apache.jmeter.samplers.SampleResult;

import io.github.pcdae.adloadtest.common.Params;
import io.github.pcdae.adloadtest.common.Results;
import jcifs.CIFSContext;
import jcifs.CIFSException;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbAuthException;
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

    private PropertyConfiguration config;
    private String url;
    private String domain;

    @Override
    public Arguments getDefaultParameters() {
        Arguments args = new Arguments();
        args.addArgument("username", "${username}");
        args.addArgument("password", "${password}");
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
        SampleResult result = Results.start();
        BaseContext base = new BaseContext(config);
        try {
            CIFSContext auth = base.withCredentials(new NtlmPasswordAuthenticator(domain, username, password));
            try (SmbFile share = new SmbFile(url, auth)) {
                share.list();
            }
            return Results.ok(result, "SUCCESS", "NTLM session for " + domain + "\\" + username);
        } catch (SmbAuthException e) {
            return Results.fail(result, "NT_STATUS_" + Integer.toHexString(e.getNtStatus()).toUpperCase(), e.getMessage());
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
