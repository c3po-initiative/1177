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
| `Patient/me` (demographics) | personnummer | 1 | **Deep** — birthDate + gender derived from the 12-digit form |
| `Condition` | `journalcategories/diagnosis` | 4 | **Deep** |
| `MedicationStatement` | `journalcategories/medication` | 12 | **Deep** |
| `AllergyIntolerance` | `journalcategories/attentionSignals` | 1 (Överkänslighet: Fisk) | **Deep read** |
| `ServiceRequest` | `journalcategories/referralStatus` | 1 | **Deep read** (status timeline as notes) |
| `Encounter` | timeline rows where `data-cy-journal-overview-item-type="CareContact"` | ~21 / 50 | **Deep read** (period, participant, class, serviceProvider) |
| `DocumentReference?category=clinical-note` | `journalcategories/careDocumentation` | 11 | **Deep read** |
| `DocumentReference` (timeline) | `journalcategories/journaloverview/polltimeline` | 50 | Skeleton search, deep read |
| `AuditEvent` (default = patient self-access) | `LogsAndShare/JournalLog/PollUserAccessLogs` | 75+ | **Deep search** (agent.who.identifier + self-access policy) |
| `AuditEvent?agent=clinician` | `LogsAndShare/JournalLog/PollJournalLogs` | 0 | Skeleton (same mapper applies) |
| `Consent` | `LogsAndShare/JournalBlock/Poll` | 0 | Skeleton |
| `Appointment` | `bokadetider/api/appointments` | 1 | **Deep** |
| `Communication` | `e-tjanster/api/core/inbox/message` | 381 | **Deep** (read inflates body) |
| `Patient/me/$summary` | aggregated IPS Bundle | 1 (19+ entries) | **Deep** |
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

(The previous list of skeletons has now been deepened in place — see the live-coverage
table above. What remains in this section is the residual.)

### `Patient/me.name`

The personnummer drives `birthDate` and `gender` cleanly, but the patient's *name* still
isn't populated. `/Dashboard/GetLegalRepresentation` HTML carries it as the row marked
`LegalRepresentationBadge` ("Visas"). One extra POST per `Patient` read would fill in
`Patient.name[0].text`. Likely worth a small per-session cache so the read isn't quadratic.

### `RelatedPerson` from the same legal-representation HTML

The non-`LegalRepresentationBadge` rows in `Dashboard/GetLegalRepresentation` are the
*delegated* journals (other patients who've shared their journal with this user) — a
direct mapping to `RelatedPerson` with the patient as `RelatedPerson.patient` and the
displayed name as `RelatedPerson.name`. We just need a non-empty test account to verify
the row markup before wiring the deep parse.

### `AuditEvent?agent=clinician`

The default (self-access) endpoint is now deep. The clinician-access endpoint
(`PollJournalLogs`) returns 0 for our test users so the row markup is unverified, but
the same `.AccessedBy` parser should apply — the difference is just whose name is in the
text. Confirm and split into `agent.who.display` + `agent.requestor=true` once we have
data.

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
