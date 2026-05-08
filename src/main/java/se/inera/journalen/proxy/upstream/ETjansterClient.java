package se.inera.journalen.proxy.upstream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicNameValuePair;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP client for the {@code e-tjanster.at.1177.se} services portal — host of the
 * patient inbox under {@code /api/core/inbox/message}.
 *
 * Login dance is identical to {@link BokadetiderClient}: the SP exposes
 * {@code /Shibboleth.sso/Login}, which redirects to the same Inera IDP we already speak.
 * The IDP returns a SAML form that POSTs to {@code /Shibboleth.sso/SAML2/POST} on this SP.
 * Same RelayState HTML-unescape requirement.
 */
public class ETjansterClient implements Closeable {

    private static final Pattern AUTH_ID_QUERY = Pattern.compile("[?&]id=([^&]+)");
    private static final Pattern SAML_FORM_ACTION =
            Pattern.compile("<form[^>]+action=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern SAML_RESPONSE =
            Pattern.compile("name=\"SAMLResponse\"\\s+value=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern SAML_RELAY_STATE =
            Pattern.compile("name=\"RelayState\"\\s+value=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    private static final String USER_AGENT = "Mozilla/5.0 (compatible; JournalenFhirProxy/0.1)";

    private final String idpBase;
    private final String spBase;
    private final BasicCookieStore cookieStore = new BasicCookieStore();
    private final CloseableHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public ETjansterClient(String idpBase, String spBase) {
        this.idpBase = stripTrailingSlash(idpBase);
        this.spBase = stripTrailingSlash(spBase);
        this.http = HttpClients.custom()
                .setDefaultCookieStore(cookieStore)
                .setDefaultRequestConfig(RequestConfig.custom().setRedirectsEnabled(true).build())
                .build();
    }

    public void login(String identifier, String password) throws IOException {
        String authId = bootstrapAuthId();

        Map<String, String> credentials = new LinkedHashMap<>();
        credentials.put("identifier", identifier);
        credentials.put("password", password);

        HttpPost loginPost = new HttpPost(idpBase + "/no-auth/Citizen/login");
        loginPost.setHeader("User-Agent", USER_AGENT);
        loginPost.setHeader("Accept", "application/json, text/plain, */*");
        loginPost.setHeader("Content-Type", "application/json");
        loginPost.setHeader("xhr", "true");
        loginPost.setHeader("id", authId);
        loginPost.setEntity(new StringEntity(
                mapper.writeValueAsString(credentials), ContentType.APPLICATION_JSON));

        String redirectUrl;
        try (ClassicHttpResponse resp = http.executeOpen(null, loginPost, null)) {
            int code = resp.getCode();
            String body = readBody(resp);
            if (code >= 400) {
                throw new InvanarClient.UpstreamException(code, "Login HTTP " + code);
            }
            JsonNode json = mapper.readTree(body == null ? "{}" : body);
            if (!json.path("success").asBoolean(false)) {
                throw new InvanarClient.UpstreamException(401, "IDP rejected credentials");
            }
            redirectUrl = json.path("redirectUrl").asText(null);
            if (redirectUrl == null || redirectUrl.isBlank()) {
                throw new InvanarClient.UpstreamException(500, "Login succeeded but no redirectUrl");
            }
        }

        HttpGet samlPage = new HttpGet(redirectUrl);
        samlPage.setHeader("User-Agent", USER_AGENT);
        samlPage.setHeader("Accept", "text/html,application/xhtml+xml");
        String samlAction;
        String samlResponse;
        String relayState;
        try (ClassicHttpResponse resp = http.executeOpen(null, samlPage, null)) {
            int code = resp.getCode();
            String html = readBody(resp);
            if (code >= 400) {
                throw new InvanarClient.UpstreamException(code, "SAML page HTTP " + code);
            }
            samlAction = matchOrNull(SAML_FORM_ACTION, html);
            samlResponse = matchOrNull(SAML_RESPONSE, html);
            relayState = matchOrNull(SAML_RELAY_STATE, html);
            if (samlAction == null || samlResponse == null) {
                throw new InvanarClient.UpstreamException(500, "SAML form fields missing");
            }
            samlAction = htmlUnescape(samlAction);
            samlResponse = htmlUnescape(samlResponse);
            relayState = relayState != null ? htmlUnescape(relayState) : null;
        }

        HttpPost acs = new HttpPost(samlAction);
        acs.setHeader("User-Agent", USER_AGENT);
        acs.setHeader("Accept", "text/html,application/xhtml+xml");
        acs.setConfig(RequestConfig.custom().setRedirectsEnabled(false).build());
        List<NameValuePair> form = new ArrayList<>();
        form.add(new BasicNameValuePair("SAMLResponse", samlResponse));
        if (relayState != null) form.add(new BasicNameValuePair("RelayState", relayState));
        acs.setEntity(new UrlEncodedFormEntity(form, StandardCharsets.UTF_8));
        String location;
        try (ClassicHttpResponse resp = http.executeOpen(null, acs, null)) {
            int code = resp.getCode();
            EntityUtils.consume(resp.getEntity());
            if (code != 302 && code >= 400) {
                throw new InvanarClient.UpstreamException(code, "SAML ACS POST HTTP " + code);
            }
            location = resp.getFirstHeader("Location") != null
                    ? resp.getFirstHeader("Location").getValue() : null;
        }

        // Follow the redirect chain after the SAML POST. e-tjanster goes through one or two hops
        // before landing on the inbox page; we need to walk them so cookies (incl. the
        // _shibsession_*) settle on the right path.
        String next = location;
        for (int hop = 0; hop < 5 && next != null; hop++) {
            String url = absolute(samlAction, next);
            HttpGet g = new HttpGet(url);
            g.setHeader("User-Agent", USER_AGENT);
            g.setConfig(RequestConfig.custom().setRedirectsEnabled(false).build());
            try (ClassicHttpResponse resp = http.executeOpen(null, g, null)) {
                int code = resp.getCode();
                EntityUtils.consume(resp.getEntity());
                if (code >= 300 && code < 400) {
                    next = resp.getFirstHeader("Location") != null
                            ? resp.getFirstHeader("Location").getValue() : null;
                } else {
                    next = null;
                }
            }
        }
    }

    /** GET a JSON endpoint, returning the parsed Jackson tree. */
    public JsonNode getJson(String path) throws IOException {
        HttpGet get = new HttpGet(spBase + path);
        get.setHeader("User-Agent", USER_AGENT);
        get.setHeader("Accept", "application/json");
        try (ClassicHttpResponse resp = http.executeOpen(null, get, null)) {
            int code = resp.getCode();
            String body = readBody(resp);
            if (code >= 400) {
                throw new InvanarClient.UpstreamException(code, "GET " + path + " HTTP " + code);
            }
            return body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
        }
    }

    @Override
    public void close() throws IOException {
        cookieStore.clear();
        http.close();
    }

    private String bootstrapAuthId() throws IOException {
        String location = spBase + "/Shibboleth.sso/Login";
        for (int hop = 0; hop < 8; hop++) {
            HttpGet g = new HttpGet(location);
            g.setHeader("User-Agent", USER_AGENT);
            g.setHeader("Accept", "text/html,application/xhtml+xml");
            g.setConfig(RequestConfig.custom().setRedirectsEnabled(false).build());
            try (ClassicHttpResponse resp = http.executeOpen(null, g, null)) {
                int code = resp.getCode();
                EntityUtils.consume(resp.getEntity());
                if (code >= 300 && code < 400) {
                    var loc = resp.getFirstHeader("Location");
                    if (loc == null) {
                        throw new InvanarClient.UpstreamException(code, "Redirect with no Location during bootstrap");
                    }
                    location = absolute(location, loc.getValue());
                    Matcher m = AUTH_ID_QUERY.matcher(location);
                    if (m.find()) return m.group(1);
                } else if (code == 200) {
                    Matcher m = AUTH_ID_QUERY.matcher(location);
                    if (m.find()) return m.group(1);
                    throw new InvanarClient.UpstreamException(500, "Bootstrap landed on " + location + " with no auth id");
                } else {
                    throw new InvanarClient.UpstreamException(code, "Bootstrap HTTP " + code);
                }
            }
        }
        throw new InvanarClient.UpstreamException(500, "Too many redirects during bootstrap");
    }

    private static String absolute(String base, String maybeRelative) {
        if (maybeRelative.startsWith("http://") || maybeRelative.startsWith("https://")) return maybeRelative;
        try {
            return new java.net.URI(base).resolve(maybeRelative).toString();
        } catch (java.net.URISyntaxException e) {
            return maybeRelative;
        }
    }

    private static String matchOrNull(Pattern p, String s) {
        if (s == null) return null;
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String htmlUnescape(String s) {
        if (s == null) return null;
        return s.replace("&amp;", "&")
                .replace("&#x3a;", ":")
                .replace("&#x2f;", "/")
                .replace("&#x3D;", "=")
                .replace("&#x3d;", "=")
                .replace("&quot;", "\"");
    }

    private static String readBody(ClassicHttpResponse resp) throws IOException {
        try {
            return EntityUtils.toString(resp.getEntity());
        } catch (ParseException e) {
            throw new IOException("Could not read response body", e);
        }
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
