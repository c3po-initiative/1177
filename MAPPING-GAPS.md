# Mapping gaps and findings

Recorded after live exploration of `qa.journalen.inera.se` and the two sister services
(`bokadetider.at.1177.se`, `e-tjanster.at.1177.se`) with the QA test accounts —
`196507132758`, `19650623-2880`, `20080816-2390`, all with password `1234qwer`.

This file tracks **what's still missing** from the proxy and **what the upstream is
known to misbehave on** for the QA test users. The current resource map is in the
[main README](README.md).

## Coverage live-verified on `196507132758`

| FHIR | Source | Live count | Depth |
|---|---|---:|---|
| `Condition` | `journalcategories/diagnosis` | 4 | Deep |
| `MedicationStatement` | `journalcategories/medication` | 12 | Deep |
| `DocumentReference?category=clinical-note` | `journalcategories/careDocumentation` | 11 | Read deep |
| `DocumentReference` (timeline) | `journalcategories/journaloverview/polltimeline` | 50 | Skeleton |
| `Encounter` | timeline rows where `data-cy-journal-overview-item-type="CareContact"` | ~21 / 50 | Skeleton |
| `ServiceRequest` | `journalcategories/referralStatus` | 1 | Skeleton |
| `AllergyIntolerance` | `journalcategories/attentionSignals` | 1 (Överkänslighet: Fisk) | Skeleton |
| `AuditEvent` (default = patient self-access) | `LogsAndShare/JournalLog/PollUserAccessLogs` | 75+ | Skeleton |
| `AuditEvent?agent=clinician` | `LogsAndShare/JournalLog/PollJournalLogs` | 0 | Skeleton |
| `Consent` | `LogsAndShare/JournalBlock/Poll` | 0 | Skeleton |
| `Appointment` | `bokadetider/api/appointments` | 1 | Deep |
| `Communication` | `e-tjanster/api/core/inbox/message` | 381 | Deep (read inflates body) |
| `Patient/me/$summary` | aggregated IPS Bundle | 1 (19 entries) | Deep |
| `CarePlan` | `journalcategories/careplan` | 0 | Skeleton |
| `Immunization` | `journalcategories/vaccinationHistory` | 0 | Skeleton |
| `Observation?category=survey` | `journalcategories/functionalStatus` | 0 | Skeleton |
| `Observation?category=vital-signs` | `journalcategories/growthobservation` | — (upstream 500) | Skeleton |
| `Observation` (laboratory) | `journalcategories/laboratoryoutcome/poll` | — (upstream 500) | Skeleton |
| `DiagnosticReport` | `journalcategories/laboratoryoutcome/pollanalysisoverview` | — (upstream 500) | Skeleton |
| `RelatedPerson` | `Dashboard/GetLegalRepresentation` | 0 | Skeleton |

Smoke (after one warm-up call to absorb the upstream's async-loading state):

```bash
CRED='19650713-2758:1234qwer'
for r in Condition ServiceRequest AllergyIntolerance MedicationStatement Encounter \
         'DocumentReference?category=clinical-note' DocumentReference RelatedPerson \
         'Observation?category=vital-signs' 'Observation?category=laboratory' \
         'Observation?category=survey' DiagnosticReport CarePlan Immunization \
         AuditEvent Consent Appointment 'Communication?_count=1' Patient/me; do
  echo -n "$r: "
  curl -s -u "$CRED" "http://localhost:8080/fhir/$r" \
    | python3 -c "import json,sys; d=json.load(sys.stdin); \
                  print('total=' + str(d.get('total','?')) if d.get('resourceType')=='Bundle' \
                        else d.get('resourceType','?'))"
done
```

---

## Known broken upstream endpoints (not a proxy bug)

These all return generic HTTP 500 ("Ett oväntat fel har tyvärr uppstått") regardless of
request shape, even after visiting the corresponding `/JournalCategories/...` page first
to "warm up" the module:

```
POST /journalcategories/laboratoryoutcome/poll
POST /journalcategories/laboratoryoutcome/getallgraphableanalyses
POST /journalcategories/laboratoryoutcome/pollanalysisoverview
POST /journalcategories/laboratoryoutcome/pollCumulativeOverview
POST /journalcategories/growthobservation/poll
POST /search
POST /search/detailview
```

Things tried that did not help:

- visiting `/JournalCategories/LaboratoryOutcome` first
- toggling `OrderByEnum` between `DocumentTime` and `AnalysisDate`
- omitting `Take` (per OpenAPI spec for growth/lab-overview/cumulative)
- omitting `GetFiltersView`
- including/omitting `__RequestVerificationToken` header

The endpoints are reachable (cookies authenticate) but reject the request body for reasons
the error page won't reveal. Most likely a missing prerequisite JSON call (e.g. an
"ExportConsent" / "AcceptTerms" / "GetAvailableAnalyses") that primes some server-side
state. Capturing a HAR that includes one of these categories' first XHR call after login
should resolve it; the existing HAR captures don't include them.

Once the upstream cooperates, **no FHIR-side work is needed** — `Observation`,
`DiagnosticReport`, and the search providers are wired up and just return empty.

Some `e-tjanster/api/core/inbox/message/{id}` reads also return 500 for individual legacy
threads (e.g. id `1517124`). Most ids work; this is upstream-side data corruption, not
something the proxy can recover from. The proxy surfaces these as 500 `OperationOutcome`.

---

## Skeleton resources that could be deepened

### CareContact → `Encounter`

`Encounter` is currently skeleton. The shared `parseJournalDetail` already extracts type,
timestamp, author and care unit (the same shell every detailview uses). What's missing:

- read the "Dag & tid: onsdag 19 november 2025 klockan 14:43" row to set
  `Encounter.period.start`,
- map the visit-type Swedish string ("Mottagningsbesök", "Distanskontakt",
  "Hembesök", …) to FHIR `Encounter.class` codes.

### `AllergyIntolerance` aria-label deep parse

The list-row's `aria-label` is structured: "Datum 2019-09-11, orsak Överkänslighet: Fisk,
vårdenhet ..., Aktuell." Split into:

- `code.text` = allergen ("Fisk")
- `reaction[0].manifestation[0].text` = reaction type ("Överkänslighet")
- `clinicalStatus` = `active` for "Aktuell" / `resolved` for "Inaktuell"
- `recordedDate` from "Datum"

Plus the `attentionSignals/detailview` page would carry a richer DiagnosisDetail-like
shell.

### `Patient/me` demographics

`Patient/me` currently exposes only the personnummer in `identifier`. The portal renders
the patient's name (and sometimes birth date) on every page header. Scraping the
homepage HTML once per session and caching `Patient.name[0]` and `Patient.birthDate`
would make the resource useful to FHIR clients that don't already know who they're
talking to.

### `AuditEvent` agent typing

Currently every audit row maps to a generic agent with `name=display`. The two endpoints
imply two distinct agent shapes:

- `PollUserAccessLogs` → patient self-access. Set
  `agent.type` = `IRCP` (Information Recipient) +
  `agent.who.reference = Patient/me`.
- `PollJournalLogs` → clinician access. The `.AccessedBy` text usually carries the
  clinician's name, role, and care unit — split into
  `agent.who.display` + `agent.altId` + a separate
  `agent[].requestor=true` for the care-unit organization.

---

## External services discovered but not yet exposed

### `at.sob.1177.se/sob-resident-web/rest/processes` → `Task`?

Discovered in HAR 10. Returns 12.5 KB of JSON (body not captured, structure unknown).
Likely candidate for FHIR `Task` (active care processes — referrals being processed,
appointment requests pending response, etc.).

This is a fourth Shibboleth SP (`at.sob.1177.se`). The same login dance should work
since it lives under the same Inera IDP. To wire it up:

1. Capture a single HAR of an authenticated session navigating to
   `at.sob.1177.se/sob-resident-web/rest/processes` with response bodies enabled.
2. Add `SobResidentClient` (parallel to `BokadetiderClient` and `ETjansterClient`).
3. Add `TaskResourceProvider` + `TaskMapper`.

### `at-pep.1177.se`

Looks like a PEP (Policy Enforcement Point) reverse proxy in front of `at.sob`. Probably
not directly callable as JSON.

---

## Operational gaps

### "DataIsLoading" two-stage poll

Several upstream endpoints return
`{"DataIsLoading": true, "TotalNumberOfRows": null, "PartialView": ""}` on the very first
poll within a session, then the real result on the second call. The proxy currently
returns the empty first-poll result, which appears to FHIR clients as a transient
`total=0` (or as a missing entry on a `read`). Demonstrated on:

- `MedicationStatement` (search returned `total=0` on first hit, `total=12` on retry)
- `AllergyIntolerance` (similar)

A small retry loop in `SkeletonProviderSupport.pollAndMap` (e.g. up to 3 retries with
250 ms backoff while `env.dataIsLoading == true`) would smooth this out.

### Maternity history `NotAuthorized`

`/journalcategories/maternityHistory/poll` returns
`{"NotAuthorized": true, "TotalNumberOfRows": null, "PartialView": ""}` for users where
it's not applicable (e.g. the male test patient). The proxy currently surfaces this as
an empty `Observation` Bundle; mapping it to a 403 `OperationOutcome` would be more
honest. For now the resource type isn't exposed at all.

### Refactor: unify the three Shibboleth clients

`InvanarClient`, `BokadetiderClient`, and `ETjansterClient` share 95% of their code (the
SAML dance). Worth extracting into a `ShibbolethSpClient` base class with the dance as
shared logic and the SP-specific paths as constructor parameters. Keeps the surface area
small as we add a fourth SP (`at.sob.1177.se`).

---

## Things that are *not* gaps — confirmed-working discoveries

- The journal-overview timeline uses **different `<li>` markup** than category list
  pages (`nc-journal-overview-list__item` vs `nc-list-post`). The unified
  `li:has([data-id])` selector handles both — verified live across CareContact,
  MedicationHistory, CareDocumentation, Diagnosis, Referral, and AlertInformation rows.
- The `/journalcategories/journaloverview/detailview` endpoint serves detail HTML for
  **every item-type in the timeline**, so cross-category reads through that single
  endpoint work as a fallback when a category-specific detailview returns empty.
- IDs are session-scoped — confirmed across all categories. The session caches make this
  invisible to clients.
- `bokadetider.at.1177.se` and `e-tjanster.at.1177.se` use the **same Inera IDP** as
  journalen, just with different Shibboleth SPs. The same identifier+password login flow
  works for all three — just point at the right SP base URL and ACS path.
- HAPI 8.8.1 is API-compatible with our 7.4 code except for the `Consent` provision class
  rename and stricter narrative XHTML validation.
