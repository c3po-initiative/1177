package se.inera.journalen.proxy.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import se.inera.journalen.proxy.upstream.ETjansterClient;

/** Lazy session cache for the e-tjanster portal. Mirrors {@link BokadetiderSessionCache}. */
public class ETjansterSessionCache {

    private static final Logger log = LoggerFactory.getLogger(ETjansterSessionCache.class);

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final Duration ttl;

    public ETjansterSessionCache(Duration ttl) {
        this.ttl = ttl;
    }

    public ETjansterClient acquire(String identifier, String password, ClientFactory factory) throws IOException {
        String key = key(identifier, password);
        Entry existing = entries.get(key);
        if (existing != null && !existing.isExpired(ttl)) return existing.client;
        synchronized (this) {
            existing = entries.get(key);
            if (existing != null && !existing.isExpired(ttl)) return existing.client;
            if (existing != null) {
                quietlyClose(existing.client);
                entries.remove(key);
            }
            ETjansterClient fresh = factory.create();
            fresh.login(identifier, password);
            entries.put(key, new Entry(fresh, Instant.now()));
            log.info("Logged in e-tjanster session for {} (ttl {}s)", redact(identifier), ttl.toSeconds());
            return fresh;
        }
    }

    public void sweep() {
        Instant now = Instant.now();
        entries.entrySet().removeIf(e -> {
            if (e.getValue().isExpired(ttl, now)) {
                quietlyClose(e.getValue().client);
                return true;
            }
            return false;
        });
    }

    public void closeAll() {
        entries.values().forEach(e -> quietlyClose(e.client));
        entries.clear();
    }

    public interface ClientFactory {
        ETjansterClient create();
    }

    private static String key(String id, String pw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(("etj:" + id + ":" + pw).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return id;
        }
    }

    private static String redact(String identifier) {
        return identifier == null || identifier.length() < 4 ? "***" : identifier.substring(0, 4) + "****";
    }

    private static void quietlyClose(ETjansterClient c) {
        try { c.close(); } catch (IOException ignored) {}
    }

    private record Entry(ETjansterClient client, Instant loggedInAt) {
        boolean isExpired(Duration ttl) { return isExpired(ttl, Instant.now()); }
        boolean isExpired(Duration ttl, Instant now) {
            return loggedInAt.plus(ttl).isBefore(now);
        }
    }
}
