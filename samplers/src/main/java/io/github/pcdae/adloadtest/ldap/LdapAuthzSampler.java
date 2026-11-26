package io.github.pcdae.adloadtest.ldap;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.java.sampler.AbstractJavaSamplerClient;
import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext;
import org.apache.jmeter.samplers.SampleResult;

import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.ldap.sdk.SimpleBindRequest;

import io.github.pcdae.adloadtest.common.Params;
import io.github.pcdae.adloadtest.common.Results;
import io.github.pcdae.adloadtest.common.UserProfile;

/**
 * Authorisation: asks AD whether the user is a member of the authorisation group, following
 * nested groups via LDAP_MATCHING_RULE_IN_CHAIN, which is the expensive query AD-backed apps
 * typically run.
 *
 * <p>With {@code authz.bindAs=user} each sample binds as the user on a fresh connection and
 * queries as them. With {@code authz.bindAs=service} the query runs over a long-lived connection
 * bound as a service account, which is the usual pattern for an application server.
 *
 * <p>The sample passes when the answer matches the {@code authorised} column of the users CSV. Disabled and
 * expired accounts are never authorised; with {@code authz.bindAs=user} their bind is rejected, which is
 * the expected outcome for them (see {@link UserProfile}).
 */
public class LdapAuthzSampler extends AbstractJavaSamplerClient {

    private static final String IN_CHAIN = "1.2.840.113556.1.4.1941";
    private static final String BIT_AND = "1.2.840.113556.1.4.803";
    private static final String ACCOUNT_DISABLE = "2";
    private static final String NEVER_EXPIRES = Long.toString(Long.MAX_VALUE);
    /** 100 ns intervals between 1601-01-01 (Windows FILETIME epoch) and 1970-01-01. */
    private static final long FILETIME_UNIX_OFFSET = 116_444_736_000_000_000L;

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
        args.addArgument("profile", "${profile}");
        args.addArgument("expect", "${expect}");
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
        UserProfile profile = UserProfile.from(ctx);
        SampleResult result = profile.start();
        LDAPConnection conn = null;
        try {
            if (asService) {
                conn = serviceConnection();
            } else {
                conn = ldap.connect();
                try {
                    conn.bind(new SimpleBindRequest(ldap.bindName(username), ctx.getParameter("password")));
                } catch (LDAPException e) {
                    if (e.getResultCode() != ResultCode.INVALID_CREDENTIALS) {
                        throw e;
                    }
                    return profile.rejected(result, LdapSupport.code(e), LdapSupport.message(e));
                }
                if (profile.expectRejection()) {
                    return profile.accepted(result, "SUCCESS", "bound as " + username);
                }
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
            return Results.fail(result, LdapSupport.code(e), LdapSupport.message(e));
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
        // Authorised = in the group (directly or nested), enabled and not expired.
        Filter filter = Filter.createANDFilter(
                Filter.createEqualityFilter("objectClass", "user"),
                Filter.createEqualityFilter("sAMAccountName", sam),
                Filter.createExtensibleMatchFilter("memberOf", IN_CHAIN, false, groupDn),
                Filter.createNOTFilter(Filter.createExtensibleMatchFilter("userAccountControl", BIT_AND, false, ACCOUNT_DISABLE)),
                Filter.createORFilter(
                        Filter.createEqualityFilter("accountExpires", "0"),
                        Filter.createEqualityFilter("accountExpires", NEVER_EXPIRES),
                        Filter.createGreaterOrEqualFilter("accountExpires", Long.toString(nowAsFileTime()))));
        SearchRequest request = new SearchRequest(ldap.baseDn, SearchScope.SUB, filter, "1.1");
        request.setSizeLimit(1);
        SearchResult search = conn.search(request);
        return search.getEntryCount() > 0;
    }

    private static long nowAsFileTime() {
        return System.currentTimeMillis() * 10_000L + FILETIME_UNIX_OFFSET;
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
