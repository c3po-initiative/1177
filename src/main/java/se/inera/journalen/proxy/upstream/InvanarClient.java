package se.inera.journalen.proxy.upstream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.hc.core5.http.NameValuePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import se.inera.journalen.proxy.upstream.dto.PollEnvelope;

/**
 * One instance per inbound proxy request. Holds a private cookie jar and CSRF token
 * which are discarded when {@link #close()} is called.
 *
 * The portal uses two hosts: an IDP for the {@code /no-auth/Citizen/login} call, and
 * the main {@code qa.journalen.inera.se} host for everything else. The IDP sets
 * cookies that are shared via {@link BasicCookieStore} across follow-up requests, and
 * the main host issues an ASP.NET {@code __RequestVerificationToken} cookie + matching
 * hidden form value that must be echoed as a header on each POST.
 */
public class InvanarClient implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(InvanarClient.class);

    private static final Pattern CSRF_INPUT = Pattern.compile(
            "<input[^>]*name=\"__RequestVerificationToken\"[^>]*value=\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern AUTH_ID_QUERY = Pattern.compile("[?&]id=([^&]+)");
    private static final Pattern SAML_FORM_ACTION =
            Pattern.compile("<form[^>]+action=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern SAML_RESPONSE =
            Pattern.compile("name=\"SAMLResponse\"\\s+value=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern SAML_RELAY_STATE =
            Pattern.compile("name=\"RelayState\"\\s+value=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    private static final String USER_AGENT = "Mozilla/5.0 (compatible; JournalenFhirProxy/0.1)";

    private static final SecureRandom RNG = new SecureRandom();

    private final String idpBase;
    private final String journalenBase;
    private final BasicCookieStore cookieStore = new BasicCookieStore();
    private final CloseableHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private String csrfToken;

    public InvanarClient(String idpBase, String journalenBase) {
        this.idpBase = stripTrailingSlash(idpBase);
        this.journalenBase = stripTrailingSlash(journalenBase);
        this.http = HttpClients.custom()
                .setDefaultCookieStore(cookieStore)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setRedirectsEnabled(true)
                        .build())
                .build();
    }

    /**
     * Performs the full SAML-mediated login dance:
     * <ol>
     *   <li>GET {@code journalenBase/} → 302 chain to {@code idpBase/Citizen?...&id=&lt;authId&gt;}</li>
     *   <li>POST {@code idpBase/no-auth/Citizen/login} JSON, with the {@code id} header set to
     *       the {@code authId} from step 1 → returns {@code {success:true, redirectUrl}}</li>
     *   <li>GET the {@code redirectUrl} → returns an HTML page with an auto-submitting SAML form
     *       targeted at {@code journalenBase/AuthServices/Acs}</li>
     *   <li>POST the form-encoded SAMLResponse+RelayState to the ACS endpoint
     *       (NOT following redirects) → 302 to {@code /} with session cookies</li>
     *   <li>GET {@code journalenBase/} once to let ASP.NET bind the SAML auth to its session</li>
     * </ol>
     */
    public void login(String identifier, String password) throws IOException {
        // 1) Bootstrap: follow journalen → IDP redirect chain to capture the auth-id query param.
        String authId;
        HttpGet bootstrap = new HttpGet(journalenBase + "/");
        bootstrap.setHeader("User-Agent", USER_AGENT);
        bootstrap.setHeader("Accept", "text/html,application/xhtml+xml");
        try (ClassicHttpResponse resp = http.executeOpen(null, bootstrap, null)) {
            EntityUtils.consume(resp.getEntity());
            // After auto-redirects, the protocol context exposes the final URL via the request URI
            // header is not available here — we rely on the response chain having visited
            // the IDP page. Fall back to cookies for the auth-id discovery if needed.
            int code = resp.getCode();
            if (code >= 400) {
                throw new UpstreamException(code, "Bootstrap GET failed: HTTP " + code);
            }
        }
        // The bootstrap call landed on a page that redirected through idpBase/Citizen?...&id=AUTHID,
        // but we can't access the final URL from executeOpen without a custom interceptor. Instead,
        // we re-issue the bootstrap as a non-redirect-following request to capture the Location.
        authId = bootstrapAuthId();

        // 2) POST credentials to the IDP with the captured auth-id as the `id` header.
        Map<String, String> credentials = new LinkedHashMap<>();
        credentials.put("identifier", identifier);
        credentials.put("password", password);

        HttpPost loginPost = new HttpPost(idpBase + InvanarEndpoints.LOGIN);
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
                throw new UpstreamException(code, "Login HTTP " + code + ": " + truncate(body));
            }
            JsonNode json = mapper.readTree(body == null ? "{}" : body);
            if (!json.path("success").asBoolean(false)) {
                throw new UpstreamException(401, "IDP rejected credentials");
            }
            redirectUrl = json.path("redirectUrl").asText(null);
            if (redirectUrl == null || redirectUrl.isBlank()) {
                throw new UpstreamException(500, "IDP login succeeded but returned no redirectUrl");
            }
        }

        // 3) GET the redirectUrl. The IDP returns an HTML page with an auto-submitting SAML form.
        String samlAction;
        String samlResponse;
        String relayState;
        HttpGet samlPage = new HttpGet(redirectUrl);
        samlPage.setHeader("User-Agent", USER_AGENT);
        samlPage.setHeader("Accept", "text/html,application/xhtml+xml");
        try (ClassicHttpResponse resp = http.executeOpen(null, samlPage, null)) {
            int code = resp.getCode();
            String html = readBody(resp);
            if (code >= 400) {
                throw new UpstreamException(code, "SAML page HTTP " + code);
            }
            samlAction = matchOrNull(SAML_FORM_ACTION, html);
            samlResponse = matchOrNull(SAML_RESPONSE, html);
            relayState = matchOrNull(SAML_RELAY_STATE, html);
            if (samlAction == null || samlResponse == null) {
                throw new UpstreamException(500, "SAML form fields missing from IDP response");
            }
            samlAction = htmlUnescape(samlAction);
        }

        // 4) POST the SAML response to the SP. Critically, do NOT follow redirects with HttpClient's
        // built-in chaser here — its handling of POST → 302 → re-issue can confuse the SP. We POST,
        // accept the 302, then issue a separate GET on the Location.
        HttpPost acs = new HttpPost(samlAction);
        acs.setHeader("User-Agent", USER_AGENT);
        acs.setHeader("Accept", "text/html,application/xhtml+xml");
        acs.setConfig(RequestConfig.custom().setRedirectsEnabled(false).build());
        List<NameValuePair> form = new ArrayList<>();
        form.add(new BasicNameValuePair("SAMLResponse", samlResponse));
        if (relayState != null) form.add(new BasicNameValuePair("RelayState", relayState));
        acs.setEntity(new UrlEncodedFormEntity(form, StandardCharsets.UTF_8));
        try (ClassicHttpResponse resp = http.executeOpen(null, acs, null)) {
            int code = resp.getCode();
            EntityUtils.consume(resp.getEntity());
            if (code != 302 && code >= 400) {
                throw new UpstreamException(code, "SAML ACS POST HTTP " + code);
            }
        }

        // 5) GET / on journalen to bind the SAML auth to the session, manually following
        // redirects (typically / → /Dashboard). Without this, subsequent journal-category calls
        // return {"HasTimedOut":true}.
        String warmupHtml = followGet(journalenBase + "/", 5);
        if (warmupHtml != null) {
            csrfToken = extractCsrfToken(warmupHtml); // may be null; not strictly required
        }
    }

    /** GET that manually follows redirects up to {@code maxHops} hops, returning the final body. */
    private String followGet(String url, int maxHops) throws IOException {
        String location = url;
        for (int hop = 0; hop < maxHops; hop++) {
            HttpGet g = new HttpGet(location);
            g.setHeader("User-Agent", USER_AGENT);
            g.setHeader("Accept", "text/html,application/xhtml+xml");
            g.setConfig(RequestConfig.custom().setRedirectsEnabled(false).build());
            try (ClassicHttpResponse resp = http.executeOpen(null, g, null)) {
                int code = resp.getCode();
                String body = readBody(resp);
                if (code >= 300 && code < 400) {
                    var loc = resp.getFirstHeader("Location");
                    if (loc == null) {
                        throw new UpstreamException(code, "Redirect with no Location at " + location);
                    }
                    location = absolute(location, loc.getValue());
                    continue;
                }
                if (code >= 400) {
                    throw new UpstreamException(code, "GET " + location + " HTTP " + code);
                }
                return body;
            }
        }
        throw new UpstreamException(500, "Too many redirects starting from " + url);
    }

    /**
     * Issues a single non-redirect-following GET against {@code journalenBase} to extract the
     * {@code id=&lt;authId&gt;} query parameter that the SP→IDP redirect produces.
     */
    private String bootstrapAuthId() throws IOException {
        // Walk the redirect chain manually until we land on an idp.../Citizen?...&id=... URL.
        String location = journalenBase + "/";
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
                        throw new UpstreamException(code, "Redirect with no Location during bootstrap");
                    }
                    String next = loc.getValue();
                    location = absolute(location, next);
                    Matcher m = AUTH_ID_QUERY.matcher(location);
                    if (m.find()) return m.group(1);
                } else if (code == 200) {
                    Matcher m = AUTH_ID_QUERY.matcher(location);
                    if (m.find()) return m.group(1);
                    throw new UpstreamException(500, "Bootstrap landed on " + location + " with no auth id");
                } else {
                    throw new UpstreamException(code, "Bootstrap HTTP " + code);
                }
            }
        }
        throw new UpstreamException(500, "Too many redirects during bootstrap");
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
        return s.replace("&amp;", "&")
                .replace("&#x3a;", ":")
                .replace("&#x2f;", "/")
                .replace("&#x3D;", "=")
                .replace("&quot;", "\"");
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    private static String readBody(ClassicHttpResponse resp) throws IOException {
        try {
            return EntityUtils.toString(resp.getEntity());
        } catch (ParseException e) {
            throw new IOException("Could not read response body", e);
        }
    }

    /** Generic POST returning a parsed envelope. */
    public PollEnvelope postJson(String path, Object body) throws IOException {
        HttpPost post = new HttpPost(journalenBase + path);
        post.setHeader("Accept", "*/*");
        post.setHeader("X-Requested-With", "XMLHttpRequest");
        if (csrfToken != null) {
            post.setHeader("__RequestVerificationToken", csrfToken);
        }
        post.setEntity(new StringEntity(
                mapper.writeValueAsString(body == null ? Map.of() : body),
                ContentType.APPLICATION_JSON));

        try (ClassicHttpResponse resp = http.executeOpen(null, post, null)) {
            int code = resp.getCode();
            String text = readBody(resp);
            if (code >= 400) {
                throw new UpstreamException(code, "POST " + path + " failed: HTTP " + code);
            }
            if (text == null || text.isBlank()) {
                return new PollEnvelope();
            }
            return mapper.readValue(text, PollEnvelope.class);
        }
    }

    /** POSTs to a poll endpoint with the standard "fs" filter envelope. */
    public PollEnvelope poll(String path, FilterSpec fs) throws IOException {
        return postJson(path, Map.of("fs", fs.toMap()));
    }

    /** POSTs to a detailview endpoint with the simple {"id": "<uuid>"} body. */
    public PollEnvelope detail(String path, String id) throws IOException {
        return postJson(path, Map.of("id", id));
    }

    @Override
    public void close() throws IOException {
        cookieStore.clear();
        csrfToken = null;
        http.close();
    }

    static String extractCsrfToken(String html) {
        if (html == null) return null;
        Matcher m = CSRF_INPUT.matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private static String randomCorrelationId() {
        byte[] buf = new byte[20];
        RNG.nextBytes(buf);
        StringBuilder sb = new StringBuilder("_");
        for (byte b : buf) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    public static class UpstreamException extends IOException {
        public final int statusCode;
        public UpstreamException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }
}
