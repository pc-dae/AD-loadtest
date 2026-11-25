package io.github.pcdae.adloadtest.kerberos;

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

import org.apache.jmeter.threads.JMeterContextService;

/**
 * JAAS plumbing for the Kerberos samplers. The KDC and realm come from the krb5.conf named by
 * the {@code java.security.krb5.conf} system property.
 */
final class KerberosSupport {

    /** Thread variable holding the current iteration's logged-in {@link LoginContext}. */
    static final String LOGIN_CONTEXT_VAR = "adloadtest.kerberos.loginContext";

    private static final String ENTRY = "ad-loadtest";

    private static final Configuration JAAS = new Configuration() {
        @Override
        public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
            return new AppConfigurationEntry[] {
                new AppConfigurationEntry("com.sun.security.auth.module.Krb5LoginModule",
                        AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                        Map.of("useTicketCache", "false",
                                "doNotPrompt", "false",
                                "storeKey", "false",
                                "refreshKrb5Config", "false",
                                "isInitiator", "true"))
            };
        }
    };

    private KerberosSupport() {
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
        LoginContext lc = new LoginContext(ENTRY, null, handler, JAAS);
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
