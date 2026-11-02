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
