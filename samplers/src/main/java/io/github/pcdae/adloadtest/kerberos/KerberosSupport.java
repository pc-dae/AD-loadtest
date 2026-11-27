package io.github.pcdae.adloadtest.kerberos;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;

import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext;
import org.apache.jmeter.threads.JMeterContextService;

import io.github.pcdae.adloadtest.common.Generators;
import io.github.pcdae.adloadtest.common.Params;

/**
 * JAAS plumbing for the Kerberos samplers.
 *
 * <p>The KDCs are the domain controllers in {@code ad.host}: a krb5.conf listing them is generated and
 * made the JVM's Kerberos configuration. Java always asks the first KDC in the list and only moves on
 * when it fails, so each load generator starts the list at a different DC (see {@link Generators}).
 * Set {@code kerberos.config=file} to use the krb5.conf named by {@code java.security.krb5.conf} as is.
 */
final class KerberosSupport {

    /** Thread variable holding the current iteration's logged-in {@link LoginContext}. */
    static final String LOGIN_CONTEXT_VAR = "adloadtest.kerberos.loginContext";

    private static final String ENTRY = "ad-loadtest";

    private static final Configuration JAAS = jaas(false);
    /** Used once after the generated krb5.conf changes, to make the JVM re-read it. */
    private static final Configuration JAAS_REFRESH = jaas(true);

    private static String activeKrb5Conf;
    private static boolean refreshPending;

    private static Configuration jaas(boolean refresh) {
        return new Configuration() {
            @Override
            public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
                return new AppConfigurationEntry[] {
                    new AppConfigurationEntry("com.sun.security.auth.module.Krb5LoginModule",
                            AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                            Map.of("useTicketCache", "false",
                                    "doNotPrompt", "false",
                                    "storeKey", "false",
                                    "refreshKrb5Config", Boolean.toString(refresh),
                                    "isInitiator", "true"))
                };
            }
        };
    }

    private KerberosSupport() {
    }

    /** Points the JVM at a krb5.conf for the realm and the DCs in {@code ad.host}. */
    static synchronized void configure(JavaSamplerContext ctx, String realm) {
        if ("file".equalsIgnoreCase(Params.get(ctx, "kerberos.config", "generate"))) {
            return;
        }
        List<String> kdcs = Generators.rotated(Generators.hosts(ctx, "dc1.lab.local"));
        int port = Params.getInt(ctx, "kerberos.port", 88);
        String domain = Params.get(ctx, "ad.upnSuffix", realm.toLowerCase()).toLowerCase();
        StringBuilder conf = new StringBuilder()
                .append("[libdefaults]\n")
                .append("  default_realm = ").append(realm).append('\n')
                .append("  dns_lookup_kdc = false\n  dns_lookup_realm = false\n")
                .append("  dns_canonicalize_hostname = false\n  rdns = false\n")
                // AD tickets carry a PAC and often exceed a UDP datagram: always use TCP.
                .append("  udp_preference_limit = 1\n")
                .append("  kdc_timeout = ").append(Params.getInt(ctx, "kerberos.timeoutMs", 5000)).append('\n')
                .append("  max_retries = 1\n")
                .append("  default_tkt_enctypes = aes256-cts-hmac-sha1-96 aes128-cts-hmac-sha1-96\n")
                .append("  default_tgs_enctypes = aes256-cts-hmac-sha1-96 aes128-cts-hmac-sha1-96\n")
                .append("  permitted_enctypes = aes256-cts-hmac-sha1-96 aes128-cts-hmac-sha1-96\n")
                .append("[realms]\n  ").append(realm).append(" = {\n");
        kdcs.forEach(kdc -> conf.append("    kdc = ").append(kdc).append(':').append(port).append('\n'));
        conf.append("  }\n[domain_realm]\n")
                .append("  .").append(domain).append(" = ").append(realm).append('\n')
                .append("  ").append(domain).append(" = ").append(realm).append('\n');
        String content = conf.toString();
        if (content.equals(activeKrb5Conf)) {
            return;
        }
        try {
            Path file = Files.createTempFile("ad-loadtest-krb5-", ".conf");
            file.toFile().deleteOnExit();
            Files.writeString(file, content);
            System.setProperty("java.security.krb5.conf", file.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to write krb5.conf", e);
        }
        activeKrb5Conf = content;
        refreshPending = true;
    }

    private static synchronized Configuration jaasForLogin() {
        if (refreshPending) {
            refreshPending = false;
            return JAAS_REFRESH;
        }
        return JAAS;
    }

    /** Performs an AS exchange (with pre-authentication) and returns the logged-in context. */
    static LoginContext login(String principal, char[] password) throws LoginException {
        CallbackHandler handler = callbacks -> {
            for (Callback cb : callbacks) {
                if (cb instanceof NameCallback nc) {
                    nc.setName(principal);
                } else if (cb instanceof PasswordCallback pc) {
                    pc.setPassword(password);
                } else {
                    throw new UnsupportedCallbackException(cb);
                }
            }
        };
        LoginContext lc = new LoginContext(ENTRY, null, handler, jaasForLogin());
        lc.login();
        return lc;
    }

    static LoginContext current() {
        Object lc = JMeterContextService.getContext().getVariables().getObject(LOGIN_CONTEXT_VAR);
        return lc instanceof LoginContext ? (LoginContext) lc : null;
    }

    static void replaceCurrent(LoginContext next) {
        LoginContext previous = current();
        if (previous != null) {
            try {
                previous.logout();
            } catch (LoginException ignored) {
                // Nothing useful to do; the tickets are discarded either way.
            }
        }
        JMeterContextService.getContext().getVariables().putObject(LOGIN_CONTEXT_VAR, next);
    }
}
