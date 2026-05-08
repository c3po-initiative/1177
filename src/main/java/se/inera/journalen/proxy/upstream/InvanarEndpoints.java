package se.inera.journalen.proxy.upstream;

public final class InvanarEndpoints {
    private InvanarEndpoints() {}

    public static final String LOGIN = "/no-auth/Citizen/login";

    public static final String DIAGNOSIS_POLL = "/journalcategories/diagnosis/poll";
    public static final String DIAGNOSIS_DETAIL = "/journalcategories/diagnosis/detailview";

    public static final String REFERRAL_POLL = "/journalcategories/referralStatus/poll";
    public static final String REFERRAL_DETAIL = "/journalcategories/referralStatus/detailview";

    public static final String CAREPLAN_POLL = "/journalcategories/careplan/poll";

    public static final String VACCINATION_POLL = "/journalcategories/vaccinationHistory/poll";

    public static final String GROWTH_POLL = "/journalcategories/growthobservation/poll";

    public static final String LAB_POLL = "/journalcategories/laboratoryoutcome/poll";
    public static final String LAB_OVERVIEW = "/journalcategories/laboratoryoutcome/pollanalysisoverview";
    public static final String LAB_GRAPHABLE = "/journalcategories/laboratoryoutcome/getallgraphableanalyses";

    public static final String JOURNAL_TIMELINE = "/journalcategories/journaloverview/polltimeline";
    public static final String JOURNAL_DETAIL = "/journalcategories/journaloverview/detailview";

    public static final String LEGAL_REPRESENTATION = "/Dashboard/GetLegalRepresentation";

    public static final String ATTENTION_SIGNALS_POLL = "/journalcategories/attentionSignals/poll";
    public static final String ATTENTION_SIGNALS_DETAIL = "/journalcategories/attentionSignals/detailview";

    public static final String MEDICATION_POLL = "/journalcategories/medication/poll";
    public static final String MEDICATION_DETAIL = "/journalcategories/medication/detailview";

    public static final String CARE_DOCUMENTATION_POLL = "/journalcategories/careDocumentation/poll";
    public static final String CARE_DOCUMENTATION_DETAIL = "/journalcategories/careDocumentation/detailview";

    public static final String FUNCTIONAL_STATUS_POLL = "/journalcategories/functionalStatus/poll";
    public static final String FUNCTIONAL_STATUS_DETAIL = "/journalcategories/functionalStatus/detailview";

    /** Patient's own access logs (every time they viewed their journal). */
    public static final String USER_ACCESS_LOGS = "/LogsAndShare/JournalLog/PollUserAccessLogs";
    /** Clinician access logs (every time staff read the patient's journal). */
    public static final String JOURNAL_LOGS = "/LogsAndShare/JournalLog/PollJournalLogs";
    /** Active privacy blocks ("spärrar") on the patient's journal. */
    public static final String JOURNAL_BLOCKS = "/LogsAndShare/JournalBlock/Poll";
}
