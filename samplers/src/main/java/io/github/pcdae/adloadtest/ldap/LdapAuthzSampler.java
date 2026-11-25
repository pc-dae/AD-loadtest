package io.github.pcdae.adloadtest.ldap;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.java.sampler.AbstractJavaSamplerClient;
import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext;
import org.apache.jmeter.samplers.SampleResult;

import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.ldap.sdk.SimpleBindRequest;

import io.github.pcdae.adloadtest.common.Params;
import io.github.pcdae.adloadtest.common.Results;

/**
 * Authorisation: asks AD whether the user is a member of the authorisation group, following
 * nested groups via LDAP_MATCHING_RULE_IN_CHAIN, which is the expensive query AD-backed apps
 * typically run.
 *
 * <p>With {@code authz.bindAs=user} each sample binds as the user on a fresh connection and
 * queries as them. With {@code authz.bindAs=service} the query runs over a long-lived connection
 * bound as a service account, which is the usual pattern for an application server.
 *
 * <p>The sample passes when the answer matches the {@code authorised} column of the users CSV.
 */
public class LdapAuthzSampler extends AbstractJavaSamplerClient {

    private static final String IN_CHAIN = "1.2.840.113556.1.4.1941";

    private LdapSupport ldap;
    private String groupDn;
    private boolean asService;
    private String serviceUser;
    private String servicePassword;
    private LDAPConnection serviceConn;

    @Override
    public Arguments getDefaultParameters() {
        Arguments args = new Arguments();
        args.addArgument("username", "${username}");
        args.addArgument("password", "${password}");
        args.addArgument("authorised", "${authorised}");
        LdapSupport.addDefaults(args);
        Params.addPropertyBacked(args, LdapSupport.BASE_DN, "DC=lab\\,DC=local");
        Params.addPropertyBacked(args, "authz.groupDn", "CN=LT-Authorised\\,CN=Users\\,DC=lab\\,DC=local");
        Params.addPropertyBacked(args, "authz.bindAs", "user");
        return args;
    }

    @Override
    public void setupTest(JavaSamplerContext ctx) {
        try {
            ldap = new LdapSupport(ctx);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialise LDAP settings", e);
        }
        groupDn = Params.get(ctx, "authz.groupDn", "CN=LT-Authorised,CN=Users,DC=lab,DC=local");
        asService = "service".equalsIgnoreCase(Params.get(ctx, "authz.bindAs", "user"));
        serviceUser = Params.get(ctx, "authz.serviceUser", "svc-ltapp");
        servicePassword = Params.get(ctx, "authz.servicePassword", "");
    }

    @Override
    public SampleResult runTest(JavaSamplerContext ctx) {
        String username = ctx.getParameter("username");
        boolean expected = Boolean.parseBoolean(ctx.getParameter("authorised", "true"));
        SampleResult result = Results.start();
        LDAPConnection conn = null;
        try {
            if (asService) {
                conn = serviceConnection();
            } else {
                conn = ldap.connect();
                conn.bind(new SimpleBindRequest(ldap.bindName(username), ctx.getParameter("password")));
            }
            boolean actual = isMember(conn, username);
            result.sampleEnd();
            String outcome = actual ? "AUTHORISED" : "DENIED";
            if (actual == expected) {
                return Results.ok(result, outcome, username + " " + outcome.toLowerCase() + " for " + groupDn);
            }
            return Results.fail(result, "UNEXPECTED_" + outcome,
                    username + " expected " + (expected ? "authorised" : "denied") + " but was " + outcome.toLowerCase());
        } catch (LDAPException e) {
            if (asService) {
                closeServiceConnection();
            }
            return Results.fail(result, e.getResultCode().getName().toUpperCase().replace(' ', '_'),
                    e.getDiagnosticMessage() != null ? e.getDiagnosticMessage() : e.getMessage());
        } catch (RuntimeException e) {
            return Results.fail(result, Results.codeFor(e), e.getMessage());
        } finally {
            if (!asService && conn != null) {
                conn.close();
            }
        }
    }

    private boolean isMember(LDAPConnection conn, String username) throws LDAPException {
        String sam = username.contains("@") ? username.substring(0, username.indexOf('@')) : username;
        Filter filter = Filter.createANDFilter(
                Filter.createEqualityFilter("objectClass", "user"),
                Filter.createEqualityFilter("sAMAccountName", sam),
                Filter.createExtensibleMatchFilter("memberOf", IN_CHAIN, false, groupDn));
        SearchRequest request = new SearchRequest(ldap.baseDn, SearchScope.SUB, filter, "1.1");
        request.setSizeLimit(1);
        SearchResult search = conn.search(request);
        return search.getEntryCount() > 0;
    }

    private LDAPConnection serviceConnection() throws LDAPException {
        if (serviceConn == null || !serviceConn.isConnected()) {
            LDAPConnection conn = ldap.connect();
            try {
                conn.bind(new SimpleBindRequest(ldap.bindName(serviceUser), servicePassword));
            } catch (LDAPException e) {
                conn.close();
                throw e;
            }
            serviceConn = conn;
        }
        return serviceConn;
    }

    private void closeServiceConnection() {
        if (serviceConn != null) {
            serviceConn.close();
            serviceConn = null;
        }
    }

    @Override
    public void teardownTest(JavaSamplerContext ctx) {
        closeServiceConnection();
    }
}
