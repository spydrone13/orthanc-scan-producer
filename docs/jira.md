# Jira tickets: orthanc-scan-producer

Tickets for building orthanc-scan-producer by hand, story by story. This repo is the reference implementation:
each ticket points at the files that already do the job, so you can check behavior and copy the tests' scenarios.

- **What it is:** a Spring Boot API (port 3000) on the plant network. orthanc-scan-ui posts scans to it, it validates
  them, and it publishes them to RabbitMQ, where the off-site orthanc-scan-consumer processes them.
- **What it depends on:** the consumer, for lot lookups (`GET /api/lots/{lotId}`) and the stage catalog
  (`GET /api/lot-stages`), and RabbitMQ on the plant network.
- **Main design rule:** the plant keeps scanning when the consumer can't be reached. Lot lookups fail open, the
  catalog is cached on disk, the UI is served from the jar, and scans wait in RabbitMQ.

Keys are placeholders (`PROD-N`); Jira assigns the real ones. Paths under **Reference** are relative to the repo root,
with `…/` standing for `src/main/java/com/spydrone/orthanc_scan_producer/`.

---

## Epic: Scan Producer

**Summary:** Scan Producer service

**Description:** A Spring Boot API that accepts lot scans from orthanc-scan-ui, checks them against the consumer's lot
records and the lot-stage catalog, and publishes accepted scans to the RabbitMQ exchange `orthanc.scans`. It also
serves the UI and the stage catalog to the plant. Scanning must keep working while the off-site consumer is
unreachable.

**Definition of done for every ticket in the epic:**
- [ ] Unit tests cover the acceptance criteria and run with `./mvnw test`, without RabbitMQ or the consumer running
- [ ] New properties have defaults in `application.properties` and are mentioned in the README
- [ ] Code reviewed and merged to `main`

---

### PROD-1: Project scaffold

**Type:** Task  **Epic:** Scan Producer  **Points:** 1  **Depends on:** none

**Description:** Create the Spring Boot project that the other tickets build on.

**Acceptance criteria:**
- [ ] Maven project `com.spydrone:orthanc-scan-producer` with the Maven wrapper (`mvnw`, `mvnw.cmd`)
- [ ] Parent `spring-boot-starter-parent` 4.1.1 and Java 25
- [ ] Dependencies: `spring-boot-starter-amqp`, `spring-boot-starter-webmvc`, `spring-boot-starter-validation`;
      test scope: `spring-boot-starter-amqp-test`, `spring-boot-starter-webmvc-test`
- [ ] `spring.application.name=orthanc-scan-producer`, `server.port=3000`
- [ ] A context-loads test passes with `./mvnw test`

**Reference:** `pom.xml`, `…/OrthancScanProducerApplication.java`, `src/test/.../OrthancScanProducerApplicationTests.java`

---

### PROD-2: Configuration and secrets handling

**Type:** Task  **Epic:** Scan Producer  **Points:** 2  **Depends on:** PROD-1

**Description:** Defaults in `application.properties` run everything against localhost. Developers override them with
two personal files that must never be committed or shipped: one for RabbitMQ credentials and one for local overrides.

**Acceptance criteria:**
- [ ] `application.properties` defaults: RabbitMQ `localhost:5672` as `guest`/`guest`,
      `app.consumer.base-url=http://localhost:3001`
- [ ] `spring.config.import=optional:classpath:application-secrets.properties` loads the secrets file when it exists,
      with or without a profile
- [ ] `application-local.properties` is loaded with the `local` profile
- [ ] Precedence: `application-local.properties` > `application-secrets.properties` > `application.properties`
- [ ] Both files are listed in `.gitignore`
- [ ] Both files are excluded from the jar by `maven-jar-plugin` `<excludes>`. Check with `jar tf` on the packaged jar.

**Reference:** `src/main/resources/application.properties`, `pom.xml` (maven-jar-plugin), `.gitignore`

**Notes / gotchas:**
- If a developer deletes one of these files, Maven's copy in `target/classes` keeps loading until `./mvnw clean`.
- A renamed file is no longer git-ignored. Tell developers to comment lines out instead of renaming.

---

### PROD-3: RabbitMQ topology

**Type:** Story  **Epic:** Scan Producer  **Points:** 3  **Depends on:** PROD-2

**Description:** Declare the exchange, queue and dead-letter setup that scans flow through. The consumer declares the
same objects. Whichever app starts first creates them.

**Acceptance criteria:**
- [ ] Direct exchange from `app.scans.exchange` (default `orthanc.scans`)
- [ ] Durable queue from `app.scans.queue` (default `orthanc.scans`), with arguments:
      dead-letter exchange `app.scans.dead-letter-exchange` (default `orthanc.scans.dlx`) and dead-letter routing key
      equal to the DLQ name
- [ ] Queue bound to the exchange with `app.scans.routing-key` (default `scan.created`)
- [ ] Direct dead-letter exchange, with a durable dead-letter queue from `app.scans.dead-letter-queue` (default
      `orthanc.scans.dlq`) bound to it by the queue's own name
- [ ] Messages are serialized as JSON (`JacksonJsonMessageConverter` bean)
- [ ] All names come from properties, so a developer can point at a personal queue (see PROD-14)

**Reference:** `…/scan/RabbitConfig.java`

**Notes / gotchas:**
- The declaration, dead-letter arguments included, must match the consumer's exactly. If it doesn't, whichever app
  starts second fails to declare the queue.
- RabbitMQ won't redeclare an existing queue with different arguments. When the arguments change, delete the queue
  once on each broker.

---

### PROD-4: POST /api/scans: request contract and publish

**Type:** Story  **Epic:** Scan Producer  **Points:** 5  **Depends on:** PROD-3

**Description:** The endpoint the UI calls for every scan. It validates the request, publishes it to RabbitMQ and
echoes it back. Validation failures and broker outages need distinct responses, because the UI keeps and resends
scans that fail with 503.

**Acceptance criteria:**
- [ ] `POST /api/scans` accepts a JSON `ScanRecord`:

      | Field                    | Required | Notes                                              |
      |--------------------------|----------|----------------------------------------------------|
      | `clientId`               | yes      | Client-generated id; also the idempotency key      |
      | `userName`               | yes      |                                                    |
      | `currentStage`           | yes      | Stage where the scan happens                       |
      | `lotId`                  | yes      |                                                    |
      | `destinationStage`       | yes      |                                                    |
      | `destinationWipLocation` | no       |                                                    |
      | `scanType`               | no       | `transitional` or `informational`                  |
      | `note`                   | no       |                                                    |
      | `correctionReason`       | no       | Max 2000 characters (see PROD-8)                   |

- [ ] A blank required field, or a `correctionReason` over 2000 characters, returns 400
- [ ] An optional `Idempotency-Key` header that doesn't equal `clientId` returns 400
      `"Idempotency-Key must match clientId"`
- [ ] A valid scan is published to `app.scans.exchange` with `app.scans.routing-key` and returns 200 with a
      `ScanResponse` that echoes every field. Null fields are left out of the JSON.
- [ ] `ScanResponse` also has `errorCode`, `errorMessage` and `recorded` for rejections (used by PROD-7, 8 and 11).
      A rejection is still a 200.
- [ ] When the broker can't be reached (any `AmqpException`), the response is 503
      `{"message": "Scan service temporarily unavailable"}`
- [ ] Controller tests use MockMvc. Service tests mock `RabbitTemplate`.

**Reference:** `…/scan/ScanController.java`, `ScanRecord.java`, `ScanResponse.java`, `ScanType.java`,
`ApiExceptionHandler.java`, `ScanService.submit`; tests `ScanControllerTest`, `ScanServiceTest`

---

### PROD-5: Idempotent scan submission

**Type:** Story  **Epic:** Scan Producer  **Points:** 2  **Depends on:** PROD-4

**Description:** The UI resends scans it isn't sure were recorded. A resend of an accepted scan must not publish the
scan a second time.

**Acceptance criteria:**
- [ ] Accepted responses are kept by `clientId`. A repeat `clientId` returns the stored response and publishes nothing.
- [ ] Concurrent duplicates are race-safe: store with `putIfAbsent` and return whichever response won
- [ ] Rejected scans are not stored, so a resend is checked again (for example after a hold is lifted)
- [ ] A failed publish (503) is not stored, so the resend publishes

**Reference:** `ScanService.submit` (the `accepted` map)

**Notes / gotchas:** The store is in memory and per instance. It is lost on restart and doesn't work across instances.
Replace it with a shared store (Redis or a DB) before running more than one producer. Raise a follow-up ticket if
that's planned.

---

### PROD-6: Lot lookup client (fail-open)

**Type:** Story  **Epic:** Scan Producer  **Points:** 3  **Depends on:** PROD-2

**Description:** Before publishing, the producer asks the consumer for the lot's state. The consumer runs off-site,
so a slow or unreachable consumer must never block scanning. The consumer checks holds again when it processes the scan.

**Acceptance criteria:**
- [ ] `GET {app.consumer.base-url}/api/lots/{lotId}` maps the response to `LotState`:
      `currentStage`, `wipLocation`, `onHold`, `status`, `lastScan { clientId, userName, at }`
- [ ] Connect and read timeouts are 2 seconds each
- [ ] A 404 returns "no lot" (empty)
- [ ] Any other error or timeout returns empty and logs a warning
      ("Could not look up lot …; accepting scan without a hold check")
- [ ] When the lookup is empty, every lot check (PROD-7 and PROD-8) is skipped
- [ ] Tests use `MockRestServiceServer` to cover found, 404, 5xx and timeout

**Reference:** `…/scan/LotClient.java`, `LotState.java`; test `LotClientTest`

---

### PROD-7: Lot status and hold validation

**Type:** Story  **Epic:** Scan Producer  **Points:** 3  **Depends on:** PROD-4, PROD-6

**Description:** Reject scans the consumer would refuse anyway, so the operator sees the reason right away and nothing
is published.

**Acceptance criteria:**
- [ ] A lot whose `status` is not null and not `active` is rejected with `errorCode` `LOT_<STATUS>` (uppercased, for
      example `LOT_CANCELED`) and `errorMessage` "Lot {lotId} is {status}". A null status counts as active.
- [ ] A lot with `onHold=true` is rejected with `LOT_ON_HOLD` ("Lot {lotId} is on hold") when the scan moves it
      out of its stage, meaning `destinationStage` ≠ the stage it is leaving. A scan within its stage is allowed.
- [ ] The stage being left is the lot's recorded stage, or the scan's `currentStage` when the location doesn't match
      (see PROD-8)
- [ ] Rejections return 200 with the error fields, aren't published and aren't stored (PROD-5)
- [ ] Check order: status, then hold, then location mismatch (PROD-8), then route (PROD-11)

**Reference:** `ScanService.lotRejection`; `ScanServiceTest`

---

### PROD-8: Lot location mismatch and operator correction

**Type:** Story  **Epic:** Scan Producer  **Points:** 5  **Depends on:** PROD-7, PROD-10

**Description:** When the records have the lot at a different stage than the one it's being scanned at, show the
operator where the records have it and who logged it there. The operator can confirm the lot really is here and
resend the scan with a `correctionReason`, which corrects the record.

**Acceptance criteria:**
- [ ] A mismatch is when the recorded `currentStage` is not null, is not the scan's `currentStage`, and is not the
      scan's `destinationStage`. A recorded stage equal to the destination means the same move was scanned twice,
      which is not a mismatch.
- [ ] A mismatch with a blank `correctionReason` is rejected with `LOT_LOCATION_MISMATCH` and a
      `recorded { stage, wipLocation, scannedBy, scannedAt }` object. `scannedBy` and `scannedAt` come from
      `lastScan` and are left out when it is unknown.
- [ ] Message wording uses stage descriptions from the catalog (or the id when there is none):
  - Missed scan (the recorded stage's `next-stages` include the scan's stage):
    "Lot {lot} was never logged out of {recorded}{by}. Confirm it is here at {here} to correct the record."
  - Otherwise: "Records show lot {lot} at {recorded}{by}, not {here}. Confirm it is here to correct the record."
  - `{by}` = " (logged by {userName} at yyyy-MM-dd HH:mm)" in the server's time zone, or empty when there's no `lastScan`
- [ ] A mismatch with a `correctionReason` passes this check and is published with the reason (the status and hold
      checks still apply)
- [ ] With no catalog, the messages fall back to stage ids and always use the "Records show" wording

**Reference:** `ScanService.isMismatch`, `ScanService.mismatchRejection`, `ScanResponse.RecordedLocation`; `ScanServiceTest`

---

### PROD-9: GET /api/lot-stages passthrough with disk cache

**Type:** Story  **Epic:** Scan Producer  **Points:** 3  **Depends on:** PROD-2

**Description:** The UI needs the lot-stage catalog to show stage choices. The producer passes the consumer's catalog
through and keeps the last good copy on disk, so it's still available after a restart while the consumer is unreachable.

**Acceptance criteria:**
- [ ] `GET /api/lot-stages` returns the consumer's `GET /api/lot-stages` JSON unchanged, as `application/json`
- [ ] Connect and read timeouts are 2 seconds each
- [ ] Each successful fetch is saved to `app.lot-stages.cache-file` (default `./data/lot-stages.json`). The file is
      written to a temp file in the same directory and moved into place with `ATOMIC_MOVE`, and missing directories
      are created.
- [ ] When the fetch fails, the endpoint serves the in-memory copy, then the disk copy, and returns 503 if neither exists
- [ ] A failed save logs a warning and doesn't fail the request
- [ ] Tests cover a fresh fetch, falling back to memory, falling back to disk after a restart, and 503

**Reference:** `…/stage/LotStageClient.java`, `LotStageController.java`; tests `LotStageClientTest`, `LotStageControllerTest`

---

### PROD-10: Stage catalog model with 1-minute cache

**Type:** Story  **Epic:** Scan Producer  **Points:** 2  **Depends on:** PROD-9

**Description:** Parse the catalog into a model the scan checks can use, and keep it for a minute so scans don't
each wait on the consumer.

**Acceptance criteria:**
- [ ] Catalog JSON is a map of stage id to `Stage`:
      `description`, `next-stages` (list of stage ids), `wip-locations` (map of id to `{ description }`),
      `next-wip-locations` (map of destination stage id to a list of allowed WIP ids)
- [ ] Unknown fields are ignored. Missing lists and maps become empty.
- [ ] A parsed snapshot is reused for 60 seconds (injectable `Clock` for tests)
- [ ] When the fetch or parse fails, the last snapshot is used. With no snapshot, the result is empty and route
      validation is skipped.
- [ ] Invalid JSON logs a warning and doesn't throw

**Reference:** `…/stage/StageCatalog.java`; test `StageCatalogTest`

---

### PROD-11: Route validation (next stage and WIP location)

**Type:** Story  **Epic:** Scan Producer  **Points:** 3  **Depends on:** PROD-4, PROD-10

**Description:** Reject moves the stage graph doesn't allow.

**Acceptance criteria:**
- [ ] Runs only when no lot check rejected the scan and a catalog is available
- [ ] Always allowed: `destinationStage` = `currentStage`, or a `currentStage` that isn't in the catalog (the
      catalog may be older than the scan station's)
- [ ] A `destinationStage` not in the current stage's `next-stages` is rejected with `INVALID_NEXT_STAGE`:
      "{to} is not a next stage of {from}"
- [ ] When `destinationWipLocation` is set (trimmed), it is rejected with `WIP_LOCATION_NOT_ALLOWED` if it isn't
      defined in the destination stage's `wip-locations`, or if the current stage's `next-wip-locations` has an
      entry for the destination that doesn't list it. Message: "Lots from {from} can't go to {wip} in {to}".
- [ ] No `next-wip-locations` entry for the destination means any WIP location defined on that stage is allowed
- [ ] Names in messages use descriptions, falling back to ids

**Reference:** `ScanService.routeRejection`; `ScanServiceTest`

---

### PROD-12: CORS for the dev UI

**Type:** Task  **Epic:** Scan Producer  **Points:** 1  **Depends on:** PROD-1

**Description:** In development the UI runs on `ng serve` (port 4200) and calls the producer across origins.

**Acceptance criteria:**
- [ ] `/api/**` allows the origins in `app.cors.allowed-origins` (comma-separated, default `http://localhost:4200`)
- [ ] Allowed methods are `GET` and `POST`. Allowed headers are `Content-Type` and `Idempotency-Key`.
- [ ] A preflight from the dev origin succeeds, and one from any other origin is refused

**Reference:** `…/scan/WebConfig.java`

---

### PROD-13: Bundle and serve the UI from the jar

**Type:** Story  **Epic:** Scan Producer  **Points:** 3  **Depends on:** PROD-1

**Description:** Plant browsers load the UI from the producer, so the UI still works when the plant loses its
internet connection.

**Acceptance criteria:**
- [ ] `maven-resources-plugin` copies `${ui.dist}` (default `../orthanc-scan-ui/dist/orthanc-scan-ui/browser`, which
      can be overridden with `-Dui.dist=`) to `static/` in the `prepare-package` phase
- [ ] When the UI folder is missing, the build still succeeds and produces a jar without the UI
- [ ] `/` serves `index.html`. `/scan-lot`, `/scan-lot/**` and `/admin` forward to `index.html`, so reloads and
      bookmarks work.
- [ ] `index.html` is sent with `Cache-Control: no-cache`. Hashed `/*.js` and `/*.css` are sent with
      `max-age=31536000, public, immutable`.
- [ ] `/api/**` is unaffected

**Reference:** `…/web/UiConfig.java`, `pom.xml` (copy-ui execution)

**Notes / gotchas:** Add new UI routes to the forward list, or a reload on them returns 404.

---

### PROD-14: Developer and deployment documentation

**Type:** Task  **Epic:** Scan Producer  **Points:** 2  **Depends on:** PROD-1 to PROD-13

**Description:** A README that lets a new developer run the producer locally and lets ops deploy it in the plant.

**Acceptance criteria:**
- [ ] Configuration files: what goes in each, precedence, why they must not be renamed
- [ ] The three local modes:
  1. Test RabbitMQ and the shared `orthanc.scans` queue, processed by the test consumer
  2. Test RabbitMQ with a personal queue, routing key, DLX and DLQ (`orthanc.scans.<name>`,
     `scan.created.<name>`, …), processed by a local consumer
  3. Everything local: `docker run -it --rm --name rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:4-management`
- [ ] Explains that mode 2 needs a **different routing key**, not only a different queue name. Otherwise the
      personal queue receives copies of everyone's scans.
- [ ] Explains that personal queues are durable and must be deleted by hand, and that queues created before the DLQ
      change must be deleted once
- [ ] Running tests
- [ ] Plant deployment: a table of what happens offline (UI, lot stages, hold check, scans), build order (UI first,
      then `./mvnw package`), running the jar, setting `app.lot-stages.cache-file` to persistent disk, and the 503
      before the first successful catalog fetch

**Reference:** `README.md`

---

## Dependencies

| Ticket  | Depends on        |
|---------|-------------------|
| PROD-1  | none              |
| PROD-2  | PROD-1            |
| PROD-3  | PROD-2            |
| PROD-4  | PROD-3            |
| PROD-5  | PROD-4            |
| PROD-6  | PROD-2            |
| PROD-7  | PROD-4, PROD-6    |
| PROD-8  | PROD-7, PROD-10   |
| PROD-9  | PROD-2            |
| PROD-10 | PROD-9            |
| PROD-11 | PROD-4, PROD-10   |
| PROD-12 | PROD-1            |
| PROD-13 | PROD-1            |
| PROD-14 | PROD-1 to PROD-13 |

## Suggested sprints

| Sprint | Tickets                                   | Points | Outcome                                             |
|--------|-------------------------------------------|--------|-----------------------------------------------------|
| 1      | PROD-1, 2, 3, 4, 5, 12                    | 14     | Scans published to RabbitMQ from the dev UI         |
| 2      | PROD-6, 7, 9, 10                          | 11     | Hold and status checks; catalog available offline   |
| 3      | PROD-8, 11, 13, 14                        | 13     | Full validation, UI bundled, ready for the plant    |

Total: 38 points.
