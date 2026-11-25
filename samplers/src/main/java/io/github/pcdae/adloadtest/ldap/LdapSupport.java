package io.github.pcdae.adloadtest.ldap;

import java.security.GeneralSecurityException;

import javax.net.SocketFactory;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext;

import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPConnectionOptions;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.util.ssl.SSLUtil;
import com.unboundid.util.ssl.TrustAllTrustManager;
import com.unboundid.util.ssl.TrustStoreTrustManager;

import io.github.pcdae.adloadtest.common.Params;

/** Connection settings shared by the LDAP samplers. */
final class LdapSupport {

    static final String HOST = "ad.host";
    static final String UPN_SUFFIX = "ad.upnSuffix";
    static final String BASE_DN = "ad.baseDn";
    static final String PORT = "ldap.port";
    static final String TLS = "ldap.tls";
    static final String TRUST_ALL = "ldap.trustAll";
    static final String TRUSTSTORE = "ldap.truststore";
    static final String CONNECT_TIMEOUT = "ldap.connectTimeoutMs";
    static final String RESPONSE_TIMEOUT = "ldap.responseTimeoutMs";

    final String host;
    final int port;
    final String upnSuffix;
    final String baseDn;
    private final SocketFactory socketFactory;
    private final LDAPConnectionOptions options;

    LdapSupport(JavaSamplerContext ctx) throws GeneralSecurityException {
        host = Params.get(ctx, HOST, "dc1.lab.local");
        boolean tls = Params.getBool(ctx, TLS, true);
        port = Params.getInt(ctx, PORT, tls ? 636 : 389);
        upnSuffix = Params.get(ctx, UPN_SUFFIX, "lab.local");
        baseDn = Params.get(ctx, BASE_DN, "DC=lab,DC=local");

        if (tls) {
            String truststore = Params.get(ctx, TRUSTSTORE, "");
            SSLUtil ssl = Params.getBool(ctx, TRUST_ALL, true) || truststore.isEmpty()
                    ? new SSLUtil(new TrustAllTrustManager())
                    : new SSLUtil(new TrustStoreTrustManager(truststore));
            socketFactory = ssl.createSSLSocketFactory();
        } else {
            socketFactory = SocketFactory.getDefault();
        }

        options = new LDAPConnectionOptions();
        options.setConnectTimeoutMillis(Params.getInt(ctx, CONNECT_TIMEOUT, 5000));
        options.setResponseTimeoutMillis(Params.getInt(ctx, RESPONSE_TIMEOUT, 10000));
        // No background reader thread per connection: cheaper when opening one per sample.
        options.setUseSynchronousMode(true);
    }

    static void addDefaults(Arguments args) {
        Params.addPropertyBacked(args, HOST, "dc1.lab.local");
        Params.addPropertyBacked(args, PORT, "636");
        Params.addPropertyBacked(args, TLS, "true");
        Params.addPropertyBacked(args, TRUST_ALL, "true");
        Params.addPropertyBacked(args, UPN_SUFFIX, "lab.local");
    }

    LDAPConnection connect() throws LDAPException {
        return new LDAPConnection(socketFactory, options, host, port);
    }

    /** Accepts a bare sAMAccountName, a UPN or a DN and returns something AD will bind with. */
    String bindName(String username) {
        if (username.contains("@") || username.contains("=") || username.contains("\\")) {
            return username;
        }
        return username + "@" + upnSuffix;
    }
}
