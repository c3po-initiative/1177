package se.inera.journalen.proxy.server;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import se.inera.journalen.proxy.upstream.InvanarClient;

@Interceptor
public class PassthroughAuthInterceptor {

    private static final Logger log = LoggerFactory.getLogger(PassthroughAuthInterceptor.class);

    /** Endpoints we let through unauthenticated. */
    private static final String[] PUBLIC_PATHS = {"metadata", ""};

    private final String idpBase;
    private final String journalenBase;
    private final SessionCache sessions;

    public PassthroughAuthInterceptor(String idpBase, String journalenBase, SessionCache sessions) {
        this.idpBase = idpBase;
        this.journalenBase = journalenBase;
        this.sessions = sessions;
    }

    @Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_PROCESSED)
    public boolean preProcess(HttpServletRequest request, HttpServletResponse response) {
        // Public paths bypass auth.
        String op = request.getRequestURI();
        if (op != null) {
            for (String pub : PUBLIC_PATHS) {
                if (op.endsWith("/" + pub) || op.endsWith("/fhir") || op.endsWith("/fhir/")) {
                    return true;
                }
            }
            if (op.endsWith("/metadata")) return true;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Basic ")) {
            response.setHeader("WWW-Authenticate", "Basic realm=\"1177 Journalen\"");
            throw new AuthenticationException("Missing Basic authentication. " +
                    "Use HTTP Basic with personnummer + password.");
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new AuthenticationException("Invalid Basic auth encoding");
        }
        int colon = decoded.indexOf(':');
        if (colon < 0) {
            throw new AuthenticationException("Basic auth must be 'identifier:password'");
        }
        String identifier = normalizePersonnummer(decoded.substring(0, colon));
        String password = decoded.substring(colon + 1);

        InvanarClient client;
        try {
            client = sessions.acquire(identifier, password,
                    () -> new InvanarClient(idpBase, journalenBase));
        } catch (InvanarClient.UpstreamException e) {
            if (e.statusCode == 401 || e.statusCode == 403) {
                throw new AuthenticationException("Upstream rejected credentials: " + e.getMessage());
            }
            throw new ca.uhn.fhir.rest.server.exceptions.InternalErrorException(
                    "Upstream login failed: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new ca.uhn.fhir.rest.server.exceptions.InternalErrorException(
                    "Upstream login I/O failure: " + e.getMessage(), e);
        }
        request.setAttribute(AuthContext.CLIENT_KEY, client);
        request.setAttribute(AuthContext.IDENTIFIER_KEY, identifier);
        // Stash the password so providers that need a second SP login (e.g. bokadetider for
        // Appointments) can do it lazily. Per-request only — never logged or persisted.
        request.setAttribute(AuthContext.PASSWORD_KEY, password);
        return true;
    }

    @Hook(Pointcut.SERVER_INCOMING_REQUEST_POST_PROCESSED)
    public void copyAttributes(RequestDetails req, HttpServletRequest request) {
        Object client = request.getAttribute(AuthContext.CLIENT_KEY);
        if (client != null) req.getUserData().put(AuthContext.CLIENT_KEY, client);
        Object id = request.getAttribute(AuthContext.IDENTIFIER_KEY);
        if (id != null) req.getUserData().put(AuthContext.IDENTIFIER_KEY, id);
        Object pw = request.getAttribute(AuthContext.PASSWORD_KEY);
        if (pw != null) req.getUserData().put(AuthContext.PASSWORD_KEY, pw);
    }

    // No close-on-completion hook here: the SessionCache owns client lifecycles. Closing the
    // client mid-cache-window would invalidate other in-flight requests.

    /**
     * Strips hyphens and a leading {@code +} from the personnummer so callers can use the
     * human-readable {@code 19650713-2758} form. The IDP only accepts 10 or 12 digits.
     */
    static String normalizePersonnummer(String s) {
        if (s == null) return null;
        return s.replace("-", "").replace("+", "").trim();
    }
}
