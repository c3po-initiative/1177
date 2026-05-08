package se.inera.journalen.proxy.server;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.interceptor.ResponseHighlighterInterceptor;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import se.inera.journalen.proxy.providers.AllergyIntoleranceResourceProvider;
import se.inera.journalen.proxy.providers.AppointmentResourceProvider;
import se.inera.journalen.proxy.providers.AuditEventResourceProvider;
import se.inera.journalen.proxy.providers.CarePlanResourceProvider;
import se.inera.journalen.proxy.providers.CommunicationResourceProvider;
import se.inera.journalen.proxy.providers.ConditionResourceProvider;
import se.inera.journalen.proxy.providers.ConsentResourceProvider;
import se.inera.journalen.proxy.providers.DiagnosticReportResourceProvider;
import se.inera.journalen.proxy.providers.DocumentReferenceResourceProvider;
import se.inera.journalen.proxy.providers.EncounterResourceProvider;
import se.inera.journalen.proxy.providers.ImmunizationResourceProvider;
import se.inera.journalen.proxy.providers.MedicationStatementResourceProvider;
import se.inera.journalen.proxy.providers.ObservationResourceProvider;
import se.inera.journalen.proxy.providers.PatientResourceProvider;
import se.inera.journalen.proxy.providers.RelatedPersonResourceProvider;
import se.inera.journalen.proxy.providers.ServiceRequestResourceProvider;

public class ProxyRestfulServer extends RestfulServer {

    private final String idpBase;
    private final String journalenBase;
    private final String bokadetiderBase;
    private final String etjansterBase;
    private SessionCache sessionCache;
    private BokadetiderSessionCache bokadetiderCache;
    private ETjansterSessionCache etjansterCache;
    private ScheduledExecutorService sweeper;

    public ProxyRestfulServer(String idpBase, String journalenBase) {
        this(idpBase, journalenBase,
                "https://bokadetider.at.1177.se",
                "https://e-tjanster.at.1177.se");
    }

    public ProxyRestfulServer(String idpBase, String journalenBase,
                              String bokadetiderBase, String etjansterBase) {
        super(FhirContext.forR4());
        this.idpBase = idpBase;
        this.journalenBase = journalenBase;
        this.bokadetiderBase = bokadetiderBase;
        this.etjansterBase = etjansterBase;
    }

    @Override
    protected void initialize() {
        setDefaultPrettyPrint(true);

        sessionCache = new SessionCache(Duration.ofMinutes(10));
        bokadetiderCache = new BokadetiderSessionCache(Duration.ofMinutes(10));
        etjansterCache = new ETjansterSessionCache(Duration.ofMinutes(10));
        sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "session-cache-sweeper");
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleAtFixedRate(() -> {
            sessionCache.sweep();
            bokadetiderCache.sweep();
            etjansterCache.sweep();
        }, 1, 1, TimeUnit.MINUTES);

        registerProviders(List.of(
                new PatientResourceProvider(),
                new ConditionResourceProvider(),
                new ServiceRequestResourceProvider(),
                new CarePlanResourceProvider(),
                new ImmunizationResourceProvider(),
                new ObservationResourceProvider(),
                new DiagnosticReportResourceProvider(),
                new DocumentReferenceResourceProvider(),
                new RelatedPersonResourceProvider(),
                new AllergyIntoleranceResourceProvider(),
                new MedicationStatementResourceProvider(),
                new EncounterResourceProvider(),
                new AuditEventResourceProvider(),
                new ConsentResourceProvider(),
                new AppointmentResourceProvider(bokadetiderCache, idpBase, bokadetiderBase),
                new CommunicationResourceProvider(etjansterCache, idpBase, etjansterBase)
        ));

        registerInterceptor(new PassthroughAuthInterceptor(idpBase, journalenBase, sessionCache));
        registerInterceptor(new ResponseHighlighterInterceptor());
    }

    @Override
    public void destroy() {
        if (sweeper != null) sweeper.shutdownNow();
        if (sessionCache != null) sessionCache.closeAll();
        if (bokadetiderCache != null) bokadetiderCache.closeAll();
        if (etjansterCache != null) etjansterCache.closeAll();
        super.destroy();
    }
}
