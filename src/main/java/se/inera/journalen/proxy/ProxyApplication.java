package se.inera.journalen.proxy;

import jakarta.servlet.http.HttpServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.inera.journalen.proxy.server.ProxyRestfulServer;

public final class ProxyApplication {

    private static final Logger log = LoggerFactory.getLogger(ProxyApplication.class);

    public static void main(String[] args) throws Exception {
        int port = parseInt(System.getenv("PROXY_PORT"), 8080);
        String idp = envOr("JOURNALEN_IDP_URL", "https://idp.qa.invanar-idp.inera.se");
        String journalen = envOr("JOURNALEN_BASE_URL", "https://qa.journalen.inera.se");
        String bokadetider = envOr("BOKADETIDER_BASE_URL", "https://bokadetider.at.1177.se");
        String etjanster = envOr("ETJANSTER_BASE_URL", "https://e-tjanster.at.1177.se");

        Server server = new Server(port);
        ServletContextHandler ctx = new ServletContextHandler();
        ctx.setContextPath("/");
        server.setHandler(ctx);

        HttpServlet fhir = new ProxyRestfulServer(idp, journalen, bokadetider, etjanster);
        ctx.addServlet(new ServletHolder(fhir), "/fhir/*");

        log.info("Starting 1177 Journalen FHIR proxy on http://localhost:{}/fhir", port);
        log.info("Upstream IDP:         {}", idp);
        log.info("Upstream Journalen:   {}", journalen);
        log.info("Upstream Bokadetider: {}", bokadetider);
        log.info("Upstream e-tjänster:  {}", etjanster);
        server.start();
        server.join();
    }

    private ProxyApplication() {}

    private static int parseInt(String s, int fallback) {
        try { return s == null ? fallback : Integer.parseInt(s); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static String envOr(String name, String fallback) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
