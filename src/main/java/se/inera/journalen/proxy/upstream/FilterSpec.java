package se.inera.journalen.proxy.upstream;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mirrors the {@code fs} object every poll endpoint expects. All array fields are
 * required-empty by the upstream's JSON shape; we always send them explicitly.
 */
public class FilterSpec {
    public int skip = 0;
    /** {@code null} means "do not include Take in the request" (some endpoints reject it). */
    public Integer take = 50;
    public String orderDirection = "Descending";
    public String orderByEnum = "DocumentTime";
    /** {@code null} means "do not include GetFiltersView in the request". */
    public Boolean getFiltersView = false;

    public static FilterSpec of(int skip, int take) {
        FilterSpec s = new FilterSpec();
        s.skip = skip;
        s.take = take;
        return s;
    }

    /**
     * Minimal request shape required by endpoints whose schema lacks
     * {@code Take} and {@code GetFiltersView} (growth, lab-overview, lab-cumulative,
     * lab-graphable-analyses).
     */
    public static FilterSpec ofSkipOnly(int skip) {
        FilterSpec s = new FilterSpec();
        s.skip = skip;
        s.take = null;
        s.getFiltersView = null;
        return s;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Skip", skip);
        if (take != null) m.put("Take", take);
        for (String name : ARRAY_FIELDS) m.put(name, Collections.emptyList());
        m.put("OrderDirection", orderDirection);
        m.put("OrderByEnum", orderByEnum);
        m.put("FilterArrays", Collections.emptyMap());
        if (getFiltersView != null) m.put("GetFiltersView", getFiltersView);
        return m;
    }

    private static final List<String> ARRAY_FIELDS = List.of(
            "AuthorName", "Type", "InformationType", "CareUnit",
            "VaccineName", "VaccineDisease", "MedicationName", "OngoingTreatment",
            "LoggedPersonName", "LoggedPersonRole", "LoggedPersonCareProvider"
    );
}
