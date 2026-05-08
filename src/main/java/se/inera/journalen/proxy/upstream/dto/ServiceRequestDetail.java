package se.inera.journalen.proxy.upstream.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Referral-status-specific extras on top of the shared {@link JournalDetail} shell.
 * Source: {@code /journalcategories/referralStatus/detailview}.
 *
 * Adds {@link #sender} (the "Skickad av" row) and {@link #statusTimeline}, a chronological
 * list of status updates extracted from the "Remissens status" expandable list.
 */
public class ServiceRequestDetail extends JournalDetail {
    /** "Skickad av" — the sending care unit. */
    public String sender;
    /** Each entry: a single status update on the referral. */
    public final List<StatusEntry> statusTimeline = new ArrayList<>();

    public static class StatusEntry {
        public String date;     // "2025-10-09"
        public String status;   // "Accepterad"
        public String byUnit;   // "Av Dialysmottagning Falun"

        public StatusEntry(String date, String status, String byUnit) {
            this.date = date; this.status = status; this.byUnit = byUnit;
        }
    }
}
