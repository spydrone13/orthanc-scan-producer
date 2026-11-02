# orthanc-scan-producer — Class Diagram

orthanc-scan-producer is a Spring Boot service between **orthanc-scan-ui** and **RabbitMQ**. It takes lot scans over `POST /api/scans` and checks each one against the lot state and stage catalog that it fetches from **orthanc-scan-consumer**. Scans that pass are published to the scans exchange. It also proxies and caches the lot-stage catalog for the UI (`GET /api/lot-stages`) and serves the UI build itself. Every call to the consumer goes through a Resilience4j circuit breaker, so the plant can keep scanning when the consumer is down.

```mermaid
classDiagram
    direction LR

    namespace root {
        class OrthancScanProducerApplication {
            <<SpringBootApplication>>
            +main(String[] args)$ void
        }
        class ConsumerCircuitBreakerConfig {
            <<Configuration>>
            ~lotLookupCircuitBreaker(Settings) CircuitBreaker
            ~lotStagesCircuitBreaker(Settings) CircuitBreaker
            +breaker(String name, Settings settings)$ CircuitBreaker
        }
        class Settings {
            <<record>>
            +float failureRateThreshold
            +int slidingWindowSize
            +int minimumNumberOfCalls
            +Duration waitDurationInOpenState
            +int permittedCallsInHalfOpenState
        }
        class ConsumerCircuitsHealthIndicator {
            <<Component>>
            -List~CircuitBreaker~ circuitBreakers
            +health() Health
        }
    }

    namespace scan {
        class ScanController {
            <<RestController>>
            -ScanService scanService
            +postScan(ScanRecord record, String idempotencyKey) ScanResponse
        }
        class ScanService {
            <<Service>>
            -RabbitTemplate rabbitTemplate
            -ScanRejectionService scanRejectionService
            -String exchange
            -String routingKey
            -ConcurrentHashMap accepted
            +submit(ScanRecord record) ScanResponse
        }
        class ScanRejectionService {
            <<Service>>
            ~String LOT_ON_HOLD$
            ~String LOT_LOCATION_MISMATCH$
            ~String INVALID_NEXT_STAGE$
            ~String WIP_LOCATION_NOT_ALLOWED$
            -LotClient lotClient
            -StageCatalog stageCatalog
            +rejection(ScanRecord record) Optional~ScanResponse~
            -lotRejection(ScanRecord, LotState, Optional stages)$ Optional~ScanResponse~
            -isMismatch(ScanRecord, LotState)$ boolean
            -mismatchRejection(ScanRecord, LotState, Map stages)$ ScanResponse
            -routeRejection(ScanRecord, Map stages)$ Optional~ScanResponse~
        }
        class LotClient {
            <<Component>>
            -Duration TIMEOUT$
            -RestClient restClient
            -CircuitBreaker circuitBreaker
            +find(String lotId) Optional~LotState~
            -fetch(String lotId) Optional~LotState~
        }
        class LotState {
            <<record>>
            +String currentStage
            +String wipLocation
            +boolean onHold
            +String status
            +LastScan lastScan
        }
        class LastScan {
            <<record>>
            +String clientId
            +String userName
            +Instant at
        }
        class ScanRecord {
            <<record>>
            +String clientId
            +String userName
            +String currentStage
            +String lotId
            +String destinationStage
            +String destinationWipLocation
            +ScanType scanType
            +String note
            +String correctionReason
            +Boolean locationConfirmed
            +confirmsLocation() boolean
        }
        class ScanResponse {
            <<record>>
            +String clientId
            +String userName
            +String currentStage
            +String lotId
            +String destinationStage
            +String destinationWipLocation
            +ScanType scanType
            +String note
            +String correctionReason
            +Boolean locationConfirmed
            +String errorCode
            +String errorMessage
            +RecordedLocation recorded
            +accepted(ScanRecord)$ ScanResponse
            +rejected(ScanRecord, String code, String msg)$ ScanResponse
            +mismatch(ScanRecord, String code, String msg, RecordedLocation)$ ScanResponse
        }
        class RecordedLocation {
            <<record>>
            +String stage
            +String wipLocation
            +String scannedBy
            +Instant scannedAt
        }
        class ScanType {
            <<enumeration>>
            TRANSITIONAL
            INFORMATIONAL
        }
        class ApiExceptionHandler {
            <<RestControllerAdvice>>
            ~brokerUnavailable(AmqpException) Map
        }
        class RabbitConfig {
            <<Configuration>>
            ~scansExchange(String) DirectExchange
            ~scansQueue(String, String, String, Duration) Queue
            ~scansBinding(Queue, DirectExchange, String) Binding
            ~scansDeadLetterExchange(String) DirectExchange
            ~scansDeadLetterQueue(String, Duration) Queue
            ~scansDeadLetterBinding(Queue, DirectExchange) Binding
            ~jsonMessageConverter() MessageConverter
        }
        class WebConfig {
            <<Configuration>>
            -String[] allowedOrigins
            +addCorsMappings(CorsRegistry) void
        }
    }

    namespace stage {
        class LotStageController {
            <<RestController>>
            -LotStageClient lotStageClient
            +getLotStages() ResponseEntity~String~
        }
        class LotStageClient {
            <<Component>>
            -Duration TIMEOUT$
            -RestClient restClient
            -Path cacheFile
            -CircuitBreaker circuitBreaker
            -String cached
            +stages() Optional~String~
            -save(String json) void
            -load() Optional~String~
        }
        class StageCatalog {
            <<Component>>
            -Duration MAX_AGE$
            -LotStageClient lotStageClient
            -Clock clock
            -Snapshot snapshot
            +json() Optional~String~
            +stages() Optional~Map~
            -refresh() Optional~Snapshot~
            -parse(String json)$ Optional~Map~
        }
        class Stage {
            <<record>>
            +String description
            +List~String~ nextStages
            +Map wipLocations
            +Map nextWipLocations
        }
        class WipLocation {
            <<record>>
            +String description
        }
        class Snapshot {
            <<record>>
            +String json
            +Map stages
            +Instant fetchedAt
        }
    }

    namespace web {
        class UiConfig {
            <<Configuration>>
            -String STATIC$
            +addViewControllers(ViewControllerRegistry) void
            +addResourceHandlers(ResourceHandlerRegistry) void
        }
    }

    namespace external {
        class RabbitTemplate {
            <<external>>
            +convertAndSend(String exchange, String routingKey, Object msg) void
        }
        class CircuitBreaker {
            <<external>>
            +executeSupplier(Supplier) T
            +getState() State
            +getMetrics() Metrics
        }
        class HealthIndicator {
            <<interface>>
            +health() Health
        }
        class WebMvcConfigurer {
            <<interface>>
        }
    }

    %% root
    ConsumerCircuitBreakerConfig *-- Settings : binds app.consumer.circuit-breaker.*
    ConsumerCircuitBreakerConfig ..> CircuitBreaker : creates lot-lookup / lot-stages
    ConsumerCircuitsHealthIndicator ..|> HealthIndicator
    ConsumerCircuitsHealthIndicator --> "*" CircuitBreaker : reports

    %% scan
    ScanController --> ScanService
    ScanController ..> ScanRecord : request body
    ScanService --> ScanRejectionService
    ScanService --> RabbitTemplate : publishes ScanRecord
    ScanService o-- "*" ScanResponse : accepted (idempotency cache)
    ScanRejectionService --> LotClient
    ScanRejectionService --> StageCatalog
    LotClient --> CircuitBreaker : lotLookupCircuitBreaker
    ScanRejectionService ..> ScanResponse : builds rejections
    LotClient ..> LotState : GET /api/lots/lotId
    LotState *-- "0..1" LastScan
    ScanRecord --> ScanType
    ScanResponse --> ScanType
    ScanResponse *-- "0..1" RecordedLocation
    ScanResponse ..> ScanRecord : echoes
    WebConfig ..|> WebMvcConfigurer

    %% stage
    LotStageController --> StageCatalog : json()
    StageCatalog --> LotStageClient
    LotStageClient --> CircuitBreaker : lotStagesCircuitBreaker
    StageCatalog *-- "0..1" Snapshot
    Snapshot o-- "*" Stage
    Stage o-- "*" WipLocation

    %% web
    UiConfig ..|> WebMvcConfigurer

    note for Settings "Nested in ConsumerCircuitBreakerConfig"
    note for LastScan "Nested in LotState"
    note for RecordedLocation "Nested in ScanResponse"
    note for Stage "Nested in StageCatalog"
```

## Key flows

### `POST /api/scans`
1. `ScanController` checks that the `Idempotency-Key` header (if present) matches `clientId`. If it doesn't, it returns 400.
2. `ScanService.submit` looks up `clientId` in its in-memory `accepted` map. On a hit, it returns the original response.
3. `ScanRejectionService.rejection` runs the checks in this order:
   - **Lot checks**: against the lot from `LotClient.find`. The client calls the consumer through its own `lot-lookup` circuit breaker. It returns empty if the lot is unknown, the consumer fails, or the circuit is open, and then these checks are skipped. Rejection codes:
     - `LOT_<STATUS>`: the lot isn't active
     - `LOT_ON_HOLD`: the lot is on hold and the scan moves it out of its stage
     - `LOT_LOCATION_MISMATCH`: the records have the lot at another stage and the operator hasn't confirmed its location. This response includes a `RecordedLocation`.
   - **Route checks**: against `StageCatalog`. Rejection codes:
     - `INVALID_NEXT_STAGE`
     - `WIP_LOCATION_NOT_ALLOWED`
   - The lot checks are skipped if the consumer can't be reached or the circuit is open. The route checks are skipped if no catalog has ever been fetched.
4. If no check rejects the scan, `RabbitTemplate.convertAndSend` publishes it and the response is stored in `accepted`. If RabbitMQ can't be reached, an `AmqpException` is thrown and `ApiExceptionHandler` turns it into a 503.

The diagrams below show one scenario each. A rejection is a 200 response with an `errorCode`. It is never published or stored, so a resend is checked again. `StageCatalog.stages()` is shown as a single call; see the [lot-stages scenarios](#get-apilot-stages) for what happens inside it.

1. [Accepted and published](#1-accepted-and-published): the happy path
2. [Repeat send](#2-repeat-send-idempotent): idempotent
3. [Bad request](#3-bad-request-400): 400
4. [Lot rejected: inactive or on hold](#4-lot-rejected-inactive-or-on-hold)
5. [Location mismatch, then confirmed](#5-location-mismatch-then-confirmed)
6. [Route rejected](#6-route-rejected)
7. [Consumer unavailable](#7-consumer-unavailable-fail-open): fail open
8. [Broker unavailable](#8-broker-unavailable-503): 503

#### 1. Accepted and published
The scan is new, the lot is active, not on hold and at the scan's stage, and the route is allowed.

```mermaid
sequenceDiagram
    autonumber
    actor UI as orthanc-scan-ui
    participant Ctrl as ScanController
    participant Svc as ScanService
    participant Rej as ScanRejectionService
    participant Catalog as StageCatalog
    participant Lots as LotClient
    participant Consumer as orthanc-scan-consumer
    participant Rabbit as RabbitMQ (RabbitTemplate)

    UI->>Ctrl: POST /api/scans (Idempotency-Key = clientId)
    Ctrl->>Ctrl: @Valid ScanRecord, key matches clientId
    Ctrl->>Svc: submit(record)
    Svc->>Svc: accepted.get(clientId): none
    Svc->>Rej: rejection(record)
    Rej->>Catalog: stages()
    Catalog-->>Rej: stages by id
    Rej->>Lots: find(lotId)
    Lots->>Consumer: GET /api/lots/lotId (lot-lookup breaker, 2 s timeout)
    Consumer-->>Lots: 200 LotState (active, not on hold, at currentStage)
    Lots-->>Rej: Optional.of(lot)
    Rej->>Rej: lot checks pass
    Rej->>Rej: route checks pass
    Rej-->>Svc: Optional.empty()
    Svc->>Rabbit: convertAndSend(exchange, routingKey, record)
    Svc->>Svc: accepted.putIfAbsent(clientId, ScanResponse.accepted)
    Svc-->>Ctrl: ScanResponse (no errorCode)
    Ctrl-->>UI: 200 OK
```

#### 2. Repeat send (idempotent)
The UI sends a scan again after it was already accepted, for example because it never received the first response.

```mermaid
sequenceDiagram
    autonumber
    actor UI as orthanc-scan-ui
    participant Ctrl as ScanController
    participant Svc as ScanService

    UI->>Ctrl: POST /api/scans (same clientId as an accepted scan)
    Ctrl->>Svc: submit(record)
    Svc->>Svc: accepted.get(clientId): found
    Svc-->>Ctrl: original ScanResponse
    Ctrl-->>UI: 200 OK (same response as the first send)
    Note over Svc: No checks, no second publish.<br/>The accepted map is in memory and per instance.
```

#### 3. Bad request (400)
The request is malformed. The service is never called.

```mermaid
sequenceDiagram
    autonumber
    actor UI as orthanc-scan-ui
    participant Ctrl as ScanController

    UI->>Ctrl: POST /api/scans
    alt @Valid fails (blank required field, correctionReason over 2000 chars)
        Ctrl-->>UI: 400 Bad Request (MethodArgumentNotValidException)
    else Idempotency-Key present but not equal to clientId
        Ctrl-->>UI: 400 Bad Request ("Idempotency-Key must match clientId")
    end
```

#### 4. Lot rejected: inactive or on hold
The consumer has the lot, but it isn't active, or it is on hold and the scan would move it out of its stage.

```mermaid
sequenceDiagram
    autonumber
    actor UI as orthanc-scan-ui
    participant Ctrl as ScanController
    participant Svc as ScanService
    participant Rej as ScanRejectionService
    participant Catalog as StageCatalog
    participant Lots as LotClient
    participant Consumer as orthanc-scan-consumer

    UI->>Ctrl: POST /api/scans
    Ctrl->>Svc: submit(record)
    Svc->>Svc: accepted.get(clientId): none
    Svc->>Rej: rejection(record)
    Rej->>Catalog: stages()
    Catalog-->>Rej: stages by id
    Rej->>Lots: find(lotId)
    Lots->>Consumer: GET /api/lots/lotId
    Consumer-->>Lots: 200 LotState
    Lots-->>Rej: Optional.of(lot)
    alt status is not "active"
        Rej-->>Svc: rejected: LOT_CANCELED, LOT_COMPLETED, ... ("Lot L1 is canceled")
    else onHold and destinationStage differs from the lot's stage
        Rej-->>Svc: rejected: LOT_ON_HOLD ("Lot L1 is on hold")
    end
    Svc-->>Ctrl: ScanResponse with errorCode
    Ctrl-->>UI: 200 OK (errorCode, errorMessage)
    Note over Svc: Not published, not stored.<br/>The UI shows the scan as rejected, not resendable.
```

#### 5. Location mismatch, then confirmed
The records have the lot at another stage than the scan's, and not at its destination either. The operator confirms the lot is really here, and the UI sends the scan again.

```mermaid
sequenceDiagram
    autonumber
    actor UI as orthanc-scan-ui
    participant Ctrl as ScanController
    participant Svc as ScanService
    participant Rej as ScanRejectionService
    participant Catalog as StageCatalog
    participant Lots as LotClient
    participant Consumer as orthanc-scan-consumer
    participant Rabbit as RabbitMQ (RabbitTemplate)

    rect rgba(200, 120, 0, 0.12)
        Note over UI,Rabbit: Phase 1: first send, no confirmation
        UI->>Ctrl: POST /api/scans (locationConfirmed unset)
        Ctrl->>Svc: submit(record)
        Svc->>Rej: rejection(record)
        Rej->>Catalog: stages()
        Catalog-->>Rej: stages by id
        Rej->>Lots: find(lotId)
        Lots->>Consumer: GET /api/lots/lotId
        Consumer-->>Lots: 200 LotState (currentStage elsewhere, lastScan)
        Lots-->>Rej: Optional.of(lot)
        Rej->>Rej: isMismatch: true, confirmsLocation(): false
        alt recorded stage leads to the scan's stage (missed scan)
            Rej->>Rej: "Lot L1 was never logged out of X (logged by u at t). Confirm it is here..."
        else logged somewhere else entirely
            Rej->>Rej: "Records show lot L1 at X (logged by u at t), not Y. Confirm it is here..."
        end
        Rej-->>Svc: mismatch: LOT_LOCATION_MISMATCH + recorded (stage, wipLocation, scannedBy, scannedAt)
        Svc-->>Ctrl: ScanResponse
        Ctrl-->>UI: 200 OK (LOT_LOCATION_MISMATCH, recorded)
    end

    UI->>UI: operator confirms the lot is here (optionally gives a reason)

    rect rgba(0, 150, 80, 0.12)
        Note over UI,Rabbit: Phase 2: resend with the same clientId
        UI->>Ctrl: POST /api/scans (locationConfirmed = true, correctionReason)
        Ctrl->>Svc: submit(record)
        Svc->>Svc: accepted.get(clientId): none (rejections aren't stored)
        Svc->>Rej: rejection(record)
        Rej->>Catalog: stages()
        Rej->>Lots: find(lotId)
        Lots->>Consumer: GET /api/lots/lotId
        Consumer-->>Lots: 200 LotState
        Lots-->>Rej: Optional.of(lot)
        Rej->>Rej: confirmsLocation(): true, mismatch accepted
        Rej->>Rej: route checks pass
        Rej-->>Svc: Optional.empty()
        Svc->>Rabbit: convertAndSend(exchange, routingKey, record)
        Svc->>Svc: accepted.putIfAbsent(clientId, ScanResponse.accepted)
        Ctrl-->>UI: 200 OK (accepted)
    end
    Note over Rabbit: The consumer moves the lot to the scan's stage first,<br/>then applies the scan
```

#### 6. Route rejected
The lot checks pass, or the lot is unknown, but the move isn't allowed by the stage catalog.

```mermaid
sequenceDiagram
    autonumber
    actor UI as orthanc-scan-ui
    participant Ctrl as ScanController
    participant Svc as ScanService
    participant Rej as ScanRejectionService
    participant Catalog as StageCatalog
    participant Lots as LotClient

    UI->>Ctrl: POST /api/scans
    Ctrl->>Svc: submit(record)
    Svc->>Svc: accepted.get(clientId): none
    Svc->>Rej: rejection(record)
    Rej->>Catalog: stages()
    Catalog-->>Rej: stages by id
    Rej->>Lots: find(lotId)
    Lots-->>Rej: lot that passes the lot checks, or Optional.empty()
    Rej->>Rej: routeRejection(record, stages)
    alt destination is not in the current stage's next-stages
        Rej-->>Svc: rejected: INVALID_NEXT_STAGE ("Y is not a next stage of X")
    else WIP location unknown in the destination, or not in next-wip-locations
        Rej-->>Svc: rejected: WIP_LOCATION_NOT_ALLOWED ("Lots from X can't go to W in Y")
    end
    Svc-->>Ctrl: ScanResponse with errorCode
    Ctrl-->>UI: 200 OK (errorCode, errorMessage)
    Note over Rej: Route checks don't apply to moves within the scan's stage,<br/>scans from a stage the catalog doesn't know, or when no catalog was ever fetched
```

#### 7. Consumer unavailable (fail open)
The lot can't be looked up. The scan is still checked against the cached stage catalog and published.

```mermaid
sequenceDiagram
    autonumber
    actor UI as orthanc-scan-ui
    participant Ctrl as ScanController
    participant Svc as ScanService
    participant Rej as ScanRejectionService
    participant Catalog as StageCatalog
    participant Lots as LotClient
    participant Consumer as orthanc-scan-consumer
    participant Rabbit as RabbitMQ (RabbitTemplate)

    UI->>Ctrl: POST /api/scans
    Ctrl->>Svc: submit(record)
    Svc->>Svc: accepted.get(clientId): none
    Svc->>Rej: rejection(record)
    Rej->>Catalog: stages()
    Catalog-->>Rej: stages by id (cached copy)
    Rej->>Lots: find(lotId)
    alt circuit closed
        Lots->>Consumer: GET /api/lots/lotId
        Consumer--xLots: timeout, connection refused or 5xx
        Lots->>Lots: failure recorded, log.warn("scans skip the lot checks")
    else circuit open
        Lots->>Lots: CallNotPermittedException (consumer not called)
    end
    Lots-->>Rej: Optional.empty()
    Rej->>Rej: lot checks skipped
    Rej->>Rej: route checks pass
    Rej-->>Svc: Optional.empty()
    Svc->>Rabbit: convertAndSend(exchange, routingKey, record)
    Svc->>Svc: accepted.putIfAbsent(clientId, ScanResponse.accepted)
    Ctrl-->>UI: 200 OK (accepted)
    Note over Rabbit: The consumer flags anything the skipped lot checks let through
```

#### 8. Broker unavailable (503)
The scan passes the checks, but RabbitMQ can't be reached.

```mermaid
sequenceDiagram
    autonumber
    actor UI as orthanc-scan-ui
    participant Ctrl as ScanController
    participant Svc as ScanService
    participant Rej as ScanRejectionService
    participant Rabbit as RabbitMQ (RabbitTemplate)
    participant Adv as ApiExceptionHandler

    UI->>Ctrl: POST /api/scans
    Ctrl->>Svc: submit(record)
    Svc->>Svc: accepted.get(clientId): none
    Svc->>Rej: rejection(record)
    Rej-->>Svc: Optional.empty() (checks pass)
    Svc->>Rabbit: convertAndSend(exchange, routingKey, record)
    Rabbit--xSvc: AmqpException (broker unreachable)
    Svc--xCtrl: AmqpException propagates
    Ctrl--xAdv: AmqpException
    Adv-->>UI: 503 {"message": "Scan service temporarily unavailable"}
    Note over UI,Svc: Nothing stored in accepted, so the UI keeps the scan<br/>and retries with the same clientId
```

### `GET /api/lot-stages`
`LotStageController` gets the catalog from `StageCatalog.json()`, which is also where the scan checks get their stages (`StageCatalog.stages()`). Both go through the same `refresh()`, so the UI and the scan checks always see the same catalog and share one fetch a minute.

The catalog keeps one `Snapshot` (the raw JSON plus its parsed stages) for 1 minute. After that, the next call asks `LotStageClient.stages()` again. The client fetches the catalog from the consumer through the `lot-stages` circuit breaker. If that fails, it falls back to its in-memory copy, then to the on-disk cache file.

The UI gets the consumer's JSON exactly as it was sent, even if the catalog can't parse it. If no catalog has ever been fetched, the response is 503.

The diagrams below show one scenario each:

1. [Cached snapshot](#1-cached-snapshot): most requests
2. [Refresh from the consumer](#2-refresh-from-the-consumer)
3. [Consumer fails](#3-consumer-fails): served from the client's fallback copy
4. [Circuit open](#4-circuit-open): consumer not called, served from the fallback copy
5. [No catalog anywhere](#5-no-catalog-anywhere-503): 503
6. [Catalog JSON can't be parsed](#6-catalog-json-cant-be-parsed)

#### 1. Cached snapshot
The snapshot is less than a minute old. Nothing below the catalog is called.

```mermaid
sequenceDiagram
    autonumber
    actor UI as orthanc-scan-ui
    participant Ctrl as LotStageController
    participant Catalog as StageCatalog

    UI->>Ctrl: GET /api/lot-stages
    Ctrl->>Catalog: json()
    Catalog->>Catalog: refresh(): snapshot younger than 1 minute
    Catalog-->>Ctrl: Optional.of(snapshot.json)
    Ctrl-->>UI: 200 OK (application/json)
```

#### 2. Refresh from the consumer
The snapshot has expired or doesn't exist yet, the circuit is closed or half-open, and the consumer answers.

```mermaid
sequenceDiagram
    autonumber
    actor UI as orthanc-scan-ui
    participant Ctrl as LotStageController
    participant Catalog as StageCatalog
    participant Client as LotStageClient
    participant CB as CircuitBreaker (lot-stages)
    participant Consumer as orthanc-scan-consumer
    participant Disk as Cache file

    UI->>Ctrl: GET /api/lot-stages
    Ctrl->>Catalog: json()
    Catalog->>Catalog: refresh(): snapshot expired
    Catalog->>Client: stages()
    Client->>CB: executeSupplier(fetch)
    CB->>Consumer: GET /api/lot-stages (2 s connect/read timeout)
    Consumer-->>CB: 200 JSON catalog
    CB-->>Client: json (call recorded as success)
    Client->>Client: cached = json
    Client->>Disk: save(json): write temp file, then atomic move
    Client-->>Catalog: Optional.of(json)
    Catalog->>Catalog: parse(json)
    Catalog->>Catalog: snapshot = Snapshot(json, stages, now)
    Catalog-->>Ctrl: Optional.of(json)
    Ctrl-->>UI: 200 OK (application/json)
```

#### 3. Consumer fails
The snapshot has expired, and the consumer times out, can't be reached, or answers 5xx. 4xx answers are thrown too, but the circuit breaker doesn't count them as failures.

```mermaid
sequenceDiagram
    autonumber
    actor UI as orthanc-scan-ui
    participant Ctrl as LotStageController
    participant Catalog as StageCatalog
    participant Client as LotStageClient
    participant CB as CircuitBreaker (lot-stages)
    participant Consumer as orthanc-scan-consumer
    participant Disk as Cache file

    UI->>Ctrl: GET /api/lot-stages
    Ctrl->>Catalog: json()
    Catalog->>Catalog: refresh(): snapshot expired
    Catalog->>Client: stages()
    Client->>CB: executeSupplier(fetch)
    CB->>Consumer: GET /api/lot-stages
    Consumer--xCB: timeout, connection refused or 5xx
    CB-->>Client: RestClientException (failure recorded)
    Client->>Client: log.warn("serving the cached copy")
    alt in-memory cached is set
        Client-->>Catalog: Optional.of(cached)
    else cached is null (e.g. after a restart)
        Client->>Disk: load()
        Disk-->>Client: json
        Client->>Client: cached = json
        Client-->>Catalog: Optional.of(json)
    end
    Catalog->>Catalog: parse(json)
    Catalog->>Catalog: snapshot = Snapshot(json, stages, now)
    Catalog-->>Ctrl: Optional.of(json)
    Ctrl-->>UI: 200 OK (last known catalog)
    Note over CB: Enough failures in the sliding window open the circuit (see scenario 4)
```

#### 4. Circuit open
The snapshot has expired and the circuit breaker is open. The consumer isn't called, and the fallback copy is served without waiting on a timeout.

```mermaid
sequenceDiagram
    autonumber
    actor UI as orthanc-scan-ui
    participant Ctrl as LotStageController
    participant Catalog as StageCatalog
    participant Client as LotStageClient
    participant CB as CircuitBreaker (lot-stages)
    participant Disk as Cache file

    UI->>Ctrl: GET /api/lot-stages
    Ctrl->>Catalog: json()
    Catalog->>Catalog: refresh(): snapshot expired
    Catalog->>Client: stages()
    Client->>CB: executeSupplier(fetch)
    CB-->>Client: CallNotPermittedException (consumer not called)
    alt in-memory cached is set
        Client-->>Catalog: Optional.of(cached)
    else cached is null (e.g. after a restart)
        Client->>Disk: load()
        Disk-->>Client: json
        Client->>Client: cached = json
        Client-->>Catalog: Optional.of(json)
    end
    Catalog->>Catalog: snapshot = Snapshot(json, stages, now)
    Catalog-->>Ctrl: Optional.of(json)
    Ctrl-->>UI: 200 OK (last known catalog)
    Note over CB: After waitDurationInOpenState, the next call goes through HALF_OPEN<br/>and probes the consumer (scenario 2 or 3)
```

#### 5. No catalog anywhere (503)
No catalog is available from any source. For example: a first start while the consumer is down, with no cache file on disk yet.

```mermaid
sequenceDiagram
    autonumber
    actor UI as orthanc-scan-ui
    participant Ctrl as LotStageController
    participant Catalog as StageCatalog
    participant Client as LotStageClient
    participant CB as CircuitBreaker (lot-stages)
    participant Disk as Cache file

    UI->>Ctrl: GET /api/lot-stages
    Ctrl->>Catalog: json()
    Catalog->>Catalog: refresh(): no snapshot
    Catalog->>Client: stages()
    Client->>CB: executeSupplier(fetch)
    CB-->>Client: RestClientException or CallNotPermittedException
    Client->>Client: cached is null
    Client->>Disk: load()
    Disk--xClient: IOException (no file)
    Client-->>Catalog: Optional.empty()
    Catalog-->>Ctrl: Optional.empty() (no old snapshot)
    Ctrl-->>UI: 503 Service Unavailable
    Note over Catalog: Snapshot left unset, so the next request tries again
```

#### 6. Catalog JSON can't be parsed
The consumer returns a catalog that `StageCatalog` can't read. The UI still gets it as-is, while scan checks keep using the last stages that could be read.

```mermaid
sequenceDiagram
    autonumber
    actor UI as orthanc-scan-ui
    participant Ctrl as LotStageController
    participant Catalog as StageCatalog
    participant Client as LotStageClient

    UI->>Ctrl: GET /api/lot-stages
    Ctrl->>Catalog: json()
    Catalog->>Catalog: refresh(): snapshot expired
    Catalog->>Client: stages()
    Note over Client: fetched and saved as in scenario 2
    Client-->>Catalog: Optional.of(json)
    Catalog->>Catalog: parse(json) fails, log.warn
    Catalog->>Catalog: snapshot = Snapshot(json, previous stages or null, now)
    Catalog-->>Ctrl: Optional.of(json)
    Ctrl-->>UI: 200 OK (consumer's JSON as-is)
    Note over Catalog: stages() returns the previous parsed stages,<br/>or empty if none was ever read (route checks skipped)
```

### Health
`ConsumerCircuitsHealthIndicator` reports each circuit breaker's state and counters at `/actuator/health/circuits`. It always reports UP, because an open circuit means the producer is falling back as designed.
