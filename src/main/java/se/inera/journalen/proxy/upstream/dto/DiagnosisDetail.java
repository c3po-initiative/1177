package se.inera.journalen.proxy.upstream.dto;

/**
 * Diagnosis-specific extras on top of the shared {@link JournalDetail} shell.
 */
public class DiagnosisDetail extends JournalDetail {
    /** "Persisterande förmaksflimmer" — the text after "Diagnos:" in {@link JournalDetail#title}. */
    public String headingName;
    /** Text under the "Huvuddiagnos" row; falls back to {@link #headingName}. */
    public String mainDiagnosis;
}
