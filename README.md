# 1177 Journalen FHIR Proxy

A read-only **HAPI FHIR R4** server (HAPI 8.8.1) that fronts three Swedish 1177 services and
exposes them as standard FHIR. Clients ask the proxy in FHIR; the proxy logs in to each service
on the caller's behalf, scrapes the responses, and reshapes them into FHIR resources.

```
                        ┌─► qa.journalen.inera.se          (journal records — HTML in JSON)
GET /fhir/X  ──Basic──► ProxyApplication ──► idp.qa.invanar-idp.inera.se   (shared IDP)
                        ├─► bokadetider.at.1177.se/api    (appointments — JSON)
                        └─► e-tjanster.at.1177.se/api     (inbox messages — JSON)
```

It is read-only, single-user-per-request, and intended for local development /
experimentation against the Inera **QA** environment. There is no production hardening.

---

## Why this exists

1177 Journalen has no public API. Internally it speaks an undocumented ASP.NET MVC dialect
that wraps **server-rendered HTML fragments** in JSON envelopes. Authentication runs through
SAML SPs that all federate against the same Inera IDP but each demand their own
Shibboleth-style login dance. Two sister services (`bokadetider` for appointments and
`e-tjanster` for the patient inbox) speak modern JSON REST but live behind separate
Shibboleth SPs.

This proxy bridges the gap. It speaks FHIR on one side; on the other it speaks all three
upstream protocols and joins their data into one coherent FHIR API per authenticated patient.

---

## What it does, end-to-end

For each FHIR request:

1. **Auth gate** (`PassthroughAuthInterceptor`). HTTP Basic. Username = personnummer (12
   digits, hyphenated `19650713-2758` is normalized). Password is the portal password.
2. **Session lookup** (`SessionCache`). Hash of `id:pw` keys an in-memory map (10-min TTL,
   sweeper). The journalen `InvanarClient` is logged in eagerly at the auth gate; bokadetider
   and e-tjanster clients are logged in **lazily** the first time their resource type is
   requested (`BokadetiderSessionCache`, `ETjansterSessionCache`).
3. **Login flow** is a SAML dance against the **same IDP**, with three different SPs:
   1. GET the SP's login URL (`/`, `/Shibboleth.sso/Login`) — manual redirect-following until
      we land on `idp.qa.invanar-idp.inera.se/Citizen?...&id=<authId>`. The `authId` is
      captured.
   2. POST `idp.../no-auth/Citizen/login` with `{"identifier","password"}` plus the literal
      header `id: <authId>`. **Without that header the IDP returns 412 with "Non-matching
      session ID for new authentications".** Returns `{success:true, redirectUrl:"..."}`.
   3. GET the `redirectUrl` → an HTML page with an auto-submitting SAML `<form>`.
   4. **HTML-unescape** SAMLResponse and RelayState (RelayState contains `&#x3a;` entities
      that must decode to `:`), then POST them form-encoded to the SP's ACS:
      - journalen: `qa.journalen.inera.se/AuthServices/Acs`
      - bokadetider: `bokadetider.at.1177.se/Shibboleth.sso/SAML2/POST`
      - e-tjanster: `e-tjanster.at.1177.se/Shibboleth.sso/SAML2/POST`
   5. Follow the 302 chain manually (HttpClient5's auto-redirect doesn't engage with
      `executeOpen`) so cookies settle on the right path. Without this final warmup,
      journalen calls return `{"HasTimedOut":true}`.
4. **Resource provider** translates the FHIR query into either:
   - `POST /journalcategories/{category}/{poll|detailview}` (journalen),
   - `GET /api/appointments` (bokadetider), or
   - `GET /api/core/inbox/message[/{id}]` (e-tjanster).
5. **Parsing**:
   - Journal-category list rows are `<li>` elements containing `data-id`+`data-date`. Both
     `nc-list-post` (category lists) and `nc-journal-overview-list__item` (timeline) match
     a single `li:has([data-id])` selector.
   - Detailviews share an outer shell (`h3.nc-heading__information-type`,
     `.nc-document-timestamp`, "Antecknad av" row, "Osignerad …" alert). `JournalDetail` is
     the generic shell; `DiagnosisDetail` and `MedicationDetail` extend it with type-specific
     rows.
   - Bokadetider and e-tjanster responses are plain JSON.
6. **Mapper** builds the FHIR resource. Sanitized HTML is preserved in `text.div` (rewritten
   to XHTML so HAPI's strict narrative parser accepts it).
7. The Bundle is serialized by HAPI; total is set explicitly to whatever the upstream says
   (HAPI otherwise auto-populates `total` from `_count`, silently misreporting empty results).

---

## Resource mapping

17 FHIR resource types are exposed (excluding `OperationDefinition`).

| Upstream endpoint | FHIR resource | Depth |
|---|---|---|
| Personnummer used for login | `Patient/me` and `Patient/{personnummer}` | **Deep** — `identifier`, plus `birthDate` and `gender` derived from the personnummer (positions 1–8 = `YYYYMMDD`, position 11 even=female / odd=male; samordningsnummer with `day+60` are normalized) |
| `Patient/{id}/$summary` | International Patient Summary (uv-ips) Document Bundle | **Deep** — Composition + Patient + Conditions + MedicationStatements + AllergyIntolerances + Immunizations + Observations, sectioned per the IPS LOINC catalog. Pattern follows [dhroxy](../dhroxy). All resources tagged with `Meta.profile` from `http://hl7.org/fhir/uv/ips/StructureDefinition/...-uv-ips`. |
| `POST /journalcategories/diagnosis/{poll,detailview}` | `Condition` | **Deep** — `code.text`, `recordedDate` (Europe/Stockholm), `recorder.display="<name> (<role>)"`, `verificationStatus` (`confirmed` if signed, `provisional` if "Osignerad diagnos"), care unit in `note[0]`, sanitized HTML in `text.div`. |
| `POST /journalcategories/medication/{poll,detailview}` | `MedicationStatement` | **Deep** — `medicationCodeableConcept` carries both an ATC code (`http://www.whocc.no/atc`, e.g. `N02AB03 / Fentanyl`) and the Swedish NPL product id (`urn:oid:1.2.752.96.1.1.18`). `effectivePeriod` from "Behandlingsperiod"; `reasonCode.text` from "Ordinationsorsak"; `dosage.text` from "Doseringsanvisning"; `dosage.route.text` from "Tänkt administrationssätt"; `informationSource.display` from prescriber. |
| `POST /journalcategories/careDocumentation/{poll,detailview}` | `DocumentReference?category=clinical-note` | Search: skeleton. **Read: deep** — `type.text` from heading, `date` from timestamp, `author.display="<name> (<role>)"`, `custodian.display=<care unit>`, `docStatus=preliminary` for "Osignerad anteckning". |
| `POST /journalcategories/journaloverview/{polltimeline,detailview}` | `DocumentReference` (default; the full timeline) and `Encounter` (rows with `data-cy-journal-overview-item-type="CareContact"`) | DocumentReference: skeleton search, deep read. Encounter: **deep read** — `type.text` from the visit heading; `class` mapped to v3-ActCode (Mottagningsbesök → AMB, Hembesök → HH, Distanskontakt → VR, Akutbesök → EMER, Inskrivning/vårdtillfälle → IMP); `period.start` from the "Dag & tid" row (Swedish prose date parser handles "onsdag 19 november 2025 klockan 14:43"); `participant.individual.display` from "Ansvarig för kontakten"; `serviceProvider.display` from the care unit. |
| `POST /journalcategories/referralStatus/{poll,detailview}` | `ServiceRequest` | **Deep read** — `code.text` from heading, `authoredOn`, `requester.display` from "Skickad av", and the "Remissens status" timeline preserved as `note[]` (one annotation per status change with the date set as `time`). Latest status text drives the FHIR `status` field. |
| `POST /journalcategories/careplan/poll` | `CarePlan` | Skeleton |
| `POST /journalcategories/vaccinationHistory/poll` | `Immunization` | Skeleton |
| `POST /journalcategories/attentionSignals/{poll,detailview}` | `AllergyIntolerance` | **Deep read** — `code.text` is the allergen ("Fisk"); reaction-type heading goes into `reaction[0].manifestation`; `clinicalStatus` from "Aktuell"; `verificationStatus` from "Visshetsgrad" (Bekräftad → confirmed); `criticality` from "Allvarlighetsgrad" (Allvarlig → high, Besvärande/Lindrig → low); validity window + signed-by preserved as notes. |
| `POST /journalcategories/functionalStatus/poll` | `Observation?category=survey` | Skeleton |
| `POST /journalcategories/growthobservation/poll` | `Observation?category=vital-signs` | Skeleton (upstream returns 500 for QA test users) |
| `POST /journalcategories/laboratoryoutcome/poll` | `Observation` (default = laboratory) | Skeleton (upstream returns 500 for QA test users) |
| `POST /journalcategories/laboratoryoutcome/pollanalysisoverview` | `DiagnosticReport` | Skeleton (upstream returns 500 for QA test users) |
| `POST /Dashboard/GetLegalRepresentation` | `RelatedPerson` | Skeleton |
| `POST /LogsAndShare/JournalLog/PollUserAccessLogs` | `AuditEvent` (default — patient self-access) | **Deep search** — `agent.who.identifier` carries the personnummer parsed out of the `.AccessedBy` text, `agent.name` is the visible name, self-access is tagged via `agent.policy = "self-access"`. |
| `POST /LogsAndShare/JournalLog/PollJournalLogs` | `AuditEvent?agent=clinician` | Skeleton (no live data on QA test users; same parser applies if data appears) |
| `POST /LogsAndShare/JournalBlock/Poll` | `Consent` (privacy blocks / "spärrar"; `provision.type=deny`) | Skeleton |
| `GET https://bokadetider.at.1177.se/api/appointments` | `Appointment` | **Deep** — `start` (parsed Europe/Stockholm), `serviceType.text`, `description`, two participants (Patient + Location with display=facilityName, identifier=facility HSA-ID). |
| `GET https://e-tjanster.at.1177.se/api/core/inbox/message[/{id}]` | `Communication` | **Deep** — search returns one Communication per inbox row; read fetches the full thread and inflates `payload[0].contentString` with the message HTML. `sender` (facility + HSA-ID), `sent`, `received` (when read), `topic.text`, `category.text` from `threadLabel`. |

**Skeleton** = `id`+`subject`+effective date + sanitized narrative HTML in `text.div`. The
type-specific code field gets the row's `aria-label` text. Fully valid FHIR; no parsed fields
beyond identification.

**Deep** = structured fields populated by parsing the detailview HTML or the JSON response.
Six resource types are deep today: `Condition`, `MedicationStatement`, `DocumentReference`
(read of clinical-note), `Appointment`, `Communication`, plus the `Patient/$summary`
operation. Adding deep parsing for another category means: extend `JournalDetail` with a
sub-DTO if needed → write selector logic in `PartialViewParser` → write a mapper → add
`@Read` to the provider.

See [`MAPPING-GAPS.md`](MAPPING-GAPS.md) for what's still skeleton or known to misbehave.

---

## Authentication

- **HTTP Basic** on every request. Username = personnummer; password = portal password. The
  proxy strips hyphens and `+` from the username so `19650713-2758` and `196507132758` both
  work.
- The proxy never persists credentials. The plaintext password is held in
  `RequestDetails.userData` for the duration of one request so secondary SP logins
  (bokadetider, e-tjanster) can run lazily; cookies live inside per-credentials clients in
  the three caches for at most 10 minutes.
- `401 WWW-Authenticate: Basic realm="1177 Journalen"` when credentials are missing or
  rejected. Other upstream failures surface as 500 `OperationOutcome`.
- This proxy assumes the QA portal's **identifier+password test mode**. Real production Inera
  access uses BankID — not implemented.

---

## Build and run

Java 17+ (developed against Java 21). Maven 3.9+.

```bash
mvn -q test                        # unit tests (33 tests, fixtures from HAR captures)
mvn -q package                     # produces a shaded uber-jar in target/
PROXY_PORT=8080 java -jar target/journalen-fhir-proxy-0.1.0-SNAPSHOT.jar
# or
PROXY_PORT=8080 mvn -q exec:java   # run from sources
```

### Configuration (environment variables)

| Name | Default | Purpose |
|---|---|---|
| `PROXY_PORT` | `8080` | HTTP port |
| `JOURNALEN_BASE_URL` | `https://qa.journalen.inera.se` | Upstream Journalen host |
| `JOURNALEN_IDP_URL` | `https://idp.qa.invanar-idp.inera.se` | Shared Inera IDP |
| `BOKADETIDER_BASE_URL` | `https://bokadetider.at.1177.se` | Bokadetider SP (Appointment) |
| `ETJANSTER_BASE_URL` | `https://e-tjanster.at.1177.se` | E-tjänster SP (Communication / inbox) |

---

## Using it

The QA portal supports the test accounts shown in the QA fixture sheet
(e.g. `196507132758`, `19650623-2880`, `20080816-2390`, all with password `1234qwer`).
Many endpoints will return data for the first two accounts and almost nothing for the third
(it's a child).

```bash
CRED='19650713-2758:1234qwer'

# CapabilityStatement (no auth required)
curl -s http://localhost:8080/fhir/metadata | jq '[.rest[0].resource[].type] | sort'

# IPS Patient summary — single Document Bundle aggregating all categories
curl -s -u "$CRED" 'http://localhost:8080/fhir/Patient/me/$summary'

# Search Conditions, then deep-read one
curl -s -u "$CRED" 'http://localhost:8080/fhir/Condition?_count=10'
curl -s -u "$CRED" "http://localhost:8080/fhir/Condition/<uuid>"

# Deep MedicationStatement (ATC + NPL coding, dosage, period, reason)
curl -s -u "$CRED" 'http://localhost:8080/fhir/MedicationStatement?_count=5'
curl -s -u "$CRED" "http://localhost:8080/fhir/MedicationStatement/<uuid>"

# Bokadetider appointments
curl -s -u "$CRED" 'http://localhost:8080/fhir/Appointment'

# E-tjänster inbox
curl -s -u "$CRED" 'http://localhost:8080/fhir/Communication?_count=10'
curl -s -u "$CRED" 'http://localhost:8080/fhir/Communication/1538835'

# Access logs (the patient's own logins to the journal)
curl -s -u "$CRED" 'http://localhost:8080/fhir/AuditEvent?_count=5'

# Skeleton resources — id/date/subject/narrative populated
curl -s -u "$CRED" 'http://localhost:8080/fhir/AllergyIntolerance'
curl -s -u "$CRED" 'http://localhost:8080/fhir/ServiceRequest'
curl -s -u "$CRED" 'http://localhost:8080/fhir/DocumentReference?category=clinical-note'
curl -s -u "$CRED" 'http://localhost:8080/fhir/DocumentReference'
curl -s -u "$CRED" 'http://localhost:8080/fhir/Encounter'
curl -s -u "$CRED" 'http://localhost:8080/fhir/Observation?category=vital-signs'
curl -s -u "$CRED" 'http://localhost:8080/fhir/Patient/me'
```

### Browser access

A request with `Accept: text/html` (e.g. opening the URL in a browser tab) returns a
syntax-highlighted JSON view via HAPI's `ResponseHighlighterInterceptor`. The browser will
display the auth prompt once.

---

## Implementation map

```
src/main/java/se/inera/journalen/proxy/
├── ProxyApplication.java               embedded Jetty bootstrap; reads PROXY_PORT and the
│                                       three upstream URLs from env
├── server/
│   ├── ProxyRestfulServer.java         registers all 16 IResourceProviders + interceptors;
│   │                                   owns the three session caches and the sweeper
│   ├── PassthroughAuthInterceptor.java HTTP Basic → SessionCache.acquire(); normalizes
│   │                                   hyphenated personnummer; stashes password in
│   │                                   RequestDetails.userData for lazy secondary logins
│   ├── AuthContext.java                typed accessors for client/identifier/password
│   ├── SessionCache.java               keyed by SHA-256(creds), TTL 10 min — InvanarClient
│   ├── BokadetiderSessionCache.java    ditto for BokadetiderClient
│   └── ETjansterSessionCache.java      ditto for ETjansterClient
│
├── upstream/
│   ├── InvanarClient.java              journalen SAML dance + JSON POSTs
│   ├── BokadetiderClient.java          bokadetider SAML dance + getJson()
│   ├── ETjansterClient.java            e-tjänster SAML dance + getJson()
│   ├── InvanarEndpoints.java           journalen path constants
│   ├── FilterSpec.java                 .of(skip,take) full filter; .ofSkipOnly(skip) for
│   │                                   growth/lab-overview/lab-cumulative endpoints
│   ├── PartialViewParser.java          Jsoup. parseListRows / parseJournalDetail /
│   │                                   parseDiagnosisDetail / parseMedicationDetail
│   └── dto/                            PollEnvelope, ListRow, JournalDetail (base),
│                                       DiagnosisDetail, MedicationDetail
│
├── ips/
│   ├── IpsProfiles.java                uv-ips profile URLs + LOINC section codes
│   ├── PatientSummaryData.java         DTO aggregating one patient's clinical resources
│   ├── PatientSummaryService.java      Parallel fetch (CompletableFuture) via the cached
│   │                                   InvanarClient
│   └── PatientSummaryMapper.java       Document Bundle: Composition first, LOINC sections,
│                                       urn:uuid fullUrls, Meta.profile on every resource
│
├── providers/
│   ├── PatientResourceProvider.java         Patient/{id}, Patient/me, Patient/{id}/$summary
│   ├── ConditionResourceProvider.java       deep search + read
│   ├── MedicationStatementResourceProvider  deep read; skeleton search
│   ├── DocumentReferenceResourceProvider    deep read (clinical-note); search by category
│   ├── ObservationResourceProvider          search by category=laboratory|vital-signs|survey
│   ├── EncounterResourceProvider            timeline-filtered (CareContact rows only)
│   ├── AuditEventResourceProvider           default = patient self-access; agent=clinician
│   ├── ConsentResourceProvider              custom OrderByEnum for JournalBlock/Poll
│   ├── AppointmentResourceProvider          lazy bokadetider login + JSON map
│   ├── CommunicationResourceProvider        lazy e-tjänster login + JSON list/detail
│   ├── SkeletonProviderSupport.java         shared pollAndMap() with skipOnly flag
│   └── {ServiceRequest|CarePlan|Immunization|DiagnosticReport|RelatedPerson|
│        AllergyIntolerance}ResourceProvider
│
└── mapping/
    ├── ConditionMapper.java                fromListRow() + fromDetail()
    ├── MedicationStatementMapper.java      fromDetail() — ATC + NPL codings
    ├── AppointmentMapper.java              fromJson()
    ├── CommunicationMapper.java            fromJson()
    ├── SkeletonMappers.java                static methods per skeleton type
    ├── DateUtil.java                       ISO date / "YYYY-MM-DD HH:mm[:ss]" / /Date(ms)/
    │                                       all anchored to Europe/Stockholm
    ├── PaginationUtil.java                 _count/_offset ↔ Skip/Take, link[rel=next]
    └── NarrativeUtil.java                  Jsoup safelist + XHTML output for text.div
```

---

## Things that bit me, recorded so they don't bite again

- **The IDP login requires a per-session correlation `id`.** The `qa.journalen.inera.se →
  idp/Citizen?...&id=<authId>` redirect generates it; the value must be echoed as the
  literal HTTP header `id: <authId>` on the credentials POST. Generating a fresh random id
  gets a 412 with "Non-matching session ID for new authentications".

- **The login response's `redirectUrl` is a SAML-form page, not a redirect.** It serves an
  HTML body with `<form action="…ACS" method="POST">` whose `body onload` auto-submits.
  The proxy must scrape `SAMLResponse` and `RelayState` from this HTML, then POST them.

- **The Shibboleth SPs (bokadetider, e-tjänster) HTML-encode RelayState** as `&#x3a;`.
  These must be unescaped to `:` before form-encoding the POST body, or the SP returns 500.

- **Apache HttpClient 5's `executeOpen` does not follow redirects** even when
  `RequestConfig` has `setRedirectsEnabled(true)`. Only the higher-level
  `execute(handler)` honours it. The proxy follows redirects manually to keep control over
  the 302 chain.

- **The journalen session needs an explicit warmup GET on `/` after the SAML POST.** Without
  it, every journal-category call returns `{"HasTimedOut":true}` despite the session
  cookies being set.

- **Resource UUIDs are session-scoped.** Each fresh login generates new ids for the same
  underlying records. Search→read across two logins fails with upstream 500. The session
  caches keep a logged-in client warm so a client's burst of FHIR calls reuses one session
  and one set of ids.

- **The `fs` request shape varies per endpoint.** Most accept `Skip`+`Take`+`GetFiltersView`,
  but growth, lab-overview, lab-cumulative, and lab-graphable-analyses reject `Take` and
  `GetFiltersView`. `FilterSpec.ofSkipOnly()` plus the `skipOnly` flag on
  `SkeletonProviderSupport.pollAndMap` handle this.

- **The Behandlingsperiod row** ("treatment period") looks like `2025-11-11 - -`. Splitting
  on `\s*-\s*` is wrong — that matches the date's internal hyphens too. Split on the
  literal ` - ` (space-dash-space) instead.

- **Detail-view rows often have an `iu-sr-only` screen-reader span** that duplicates the
  visible text in a different format ("Från X till saknas" vs "X - -"). Strip
  `.iu-sr-only` before reading the row's text or you get garbled values.

- **HAPI auto-populates `Bundle.total` from the request's `_count` if you leave it unset.**
  This silently lies about empty result sets. Always
  `bundle.setTotal(n != null ? n : 0)`.

- **HAPI 8.x's narrative parser is strict XHTML.** `Jsoup.clean(html, safelist)` returns
  HTML4-style markup with unclosed `<br>`, which throws "Malformed XHTML: Found </div>
  expecting </br>". Fix is to set Jsoup output `syntax(Document.OutputSettings.Syntax.xml)`
  + `escapeMode(Entities.EscapeMode.xhtml)` so `<br>` and friends self-close.

- **HAPI 8.x renamed `Consent.provisionComponent` → `Consent.ProvisionComponent`** (proper
  Java casing). The 7.x → 8.x bump otherwise compiled cleanly for our usage.

- **List row markup has at least two shapes.** Category list pages use
  `<li class="nc-list-post">` with a child `<button data-id data-date>`. The journal
  overview timeline uses `<li class="nc-journal-overview-list__item"
  data-cy-type="journal-overview-list-item">`. The parser keys on `li:has([data-id])` to
  cover both. Audit-log rows have `<div class="nc-list-log-post" data-id>` instead of a
  button — covered by the same selector.

- **Some upstream message IDs return 500.** E-tjänster's
  `/api/core/inbox/message/{id}` returns HTTP 500 for legacy/empty threads (e.g. id
  `1517124`) while neighbouring ids work fine. The proxy surfaces these as 500
  `OperationOutcome` rather than 404 — they're upstream errors, not missing resources.

---

## Out of scope

- Writes (POST/PUT/DELETE). The server returns 405 for them.
- Code-system translation for free-text Swedish codes — we map ICD-10/SNOMED CT/LOINC
  on `MedicationStatement` (ATC + NPL) but `Condition` and friends are still Swedish text
  only. The hooks for it are `ConditionMapper.fromDetail` etc.
- BankID, Sambi, real OAuth — only identifier+password test mode.
- Persistence, distributed caching, multi-tenancy, rate limiting, TLS termination.
- The journalen `/search` and `/search/detailview` cross-category endpoints (they return
  upstream 500 — see `MAPPING-GAPS.md`).
- The `at.sob.1177.se/sob-resident-web/rest/processes` endpoint (active care processes —
  candidate for FHIR `Task`); discovered in HAR 10 but not yet wired up.
- Production-grade logging hygiene. Personnummer is partially redacted at log-time but
  Apache HttpClient debug logging would still leak headers if you raised its log level.

---

## Tests

```bash
mvn -q test
```

33 tests. None hit the network; they exercise parsers, mappers, the in-process server, and
the IPS bundle. End-to-end verification against the live QA portal is manual (see
"Using it").

- `PartialViewParserTest` — diagnosis-poll list, signed/unsigned diagnosis-detail,
  referralStatus list, journal-overview timeline.
- `JournalDetailTest` — generic detailview shell + clinical-note deep mapping.
- `ConditionMapperTest` — deep-mapping output (code text, recorder, timestamp,
  verificationStatus) against captured fixtures.
- `MedicationStatementMapperTest` — Matrifen/Eliquis/Levaxin detail parsing; ATC + NPL
  codings; effectivePeriod; reasonCode; dosage.
- `AuditEventMapperTest` — user-access-log row → AuditEvent (Stockholm-local timestamp;
  agent.name from `.AccessedBy`; entity.what = patient).
- `PatientSummaryMapperTest` — IPS Document Bundle: type=document, Composition first, five
  LOINC sections in order, no-known fallbacks, urn:uuid fullUrl resolution, profile tags.
- `DateUtilTest` — ISO dates, Swedish-local datetimes with and without seconds, .NET
  `/Date(ms)/`.
- `InvanarClientTest` — CSRF token regex extraction.
- `CapabilityStatementTest` — boots a real Jetty + RestfulServer, hits `/fhir/metadata`,
  asserts the registered resource types.
