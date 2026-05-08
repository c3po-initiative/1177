package se.inera.journalen.proxy.server;

import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;

import se.inera.journalen.proxy.upstream.InvanarClient;

public final class AuthContext {

    public static final String CLIENT_KEY = "invanar.client";
    public static final String IDENTIFIER_KEY = "invanar.identifier";
    /** Per-request only. Used to lazily log in to secondary SPs (e.g. bokadetider). */
    public static final String PASSWORD_KEY = "invanar.password";

    private AuthContext() {}

    public static InvanarClient client(RequestDetails req) {
        Object o = req.getUserData().get(CLIENT_KEY);
        if (!(o instanceof InvanarClient c)) {
            throw new AuthenticationException("Not authenticated. Use HTTP Basic auth with personnummer + password.");
        }
        return c;
    }

    public static String identifier(RequestDetails req) {
        Object o = req.getUserData().get(IDENTIFIER_KEY);
        return o instanceof String s ? s : null;
    }

    public static String password(RequestDetails req) {
        Object o = req.getUserData().get(PASSWORD_KEY);
        return o instanceof String s ? s : null;
    }

    public static String patientReference(RequestDetails req) {
        String id = identifier(req);
        return id != null ? "Patient/" + id : "Patient/me";
    }
}
