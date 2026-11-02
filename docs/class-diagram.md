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
            -CircuitBreaker lotLookupCircuitBreaker
            +rejection(ScanRecord record) Optional~ScanResponse~
            -findLot(String lotId) Optional~LotState~
            -lotRejection(ScanRecord, LotState, Optional stages)$ Optional~ScanResponse~
            -isMismatch(ScanRecord, LotState)$ boolean
            -mismatchRejection(ScanRecord, LotState, Map stages)$ ScanResponse
            -routeRejection(ScanRecord, Map stages)$ Optional~ScanResponse~
        }
        class LotClient {
            <<Component>>
            -Duration TIMEOUT$
            -RestClient restClient
            +find(String lotId) Optional~LotState~
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
            +stages() Optional~Map~
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
    ScanRejectionService --> CircuitBreaker : lotLookupCircuitBreaker
    ScanRejectionService ..> ScanResponse : builds rejections
    LotClient ..> LotState : GET /api/lots/lotId
    LotState *-- "0..1" LastScan
    ScanRecord --> ScanType
    ScanResponse --> ScanType
    ScanResponse *-- "0..1" RecordedLocation
    ScanResponse ..> ScanRecord : echoes
    WebConfig ..|> WebMvcConfigurer

    %% stage
    LotStageController --> LotStageClient
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
   - **Lot checks**: `LotClient.find`, called through the `lot-lookup` circuit breaker. Rejection codes:
     - `LOT_<STATUS>`: the lot isn't active
     - `LOT_ON_HOLD`: the lot is on hold and the scan moves it out of its stage
     - `LOT_LOCATION_MISMATCH`: the records have the lot at another stage and the operator hasn't confirmed its location. This response includes a `RecordedLocation`.
   - **Route checks**: against `StageCatalog`. Rejection codes:
     - `INVALID_NEXT_STAGE`
     - `WIP_LOCATION_NOT_ALLOWED`
   - The lot checks are skipped if the consumer can't be reached or the circuit is open. The route checks are skipped if no catalog has ever been fetched.
4. If no check rejects the scan, `RabbitTemplate.convertAndSend` publishes it and the response is stored in `accepted`. If RabbitMQ can't be reached, an `AmqpException` is thrown and `ApiExceptionHandler` turns it into a 503.

### `GET /api/lot-stages`
`LotStageController` calls `LotStageClient.stages()`. The client fetches the catalog from the consumer through the `lot-stages` circuit breaker. If that fails, it falls back to the in-memory copy, then to the on-disk cache file. If none of these has a catalog, the response is 503. `StageCatalog` parses the same JSON for scan checks and keeps a parsed `Snapshot` for 1 minute.

```mermaid
sequenceDiagram
    autonumber
    actor UI as orthanc-scan-ui
    participant Ctrl as LotStageController
    participant Client as LotStageClient
    participant CB as CircuitBreaker (lot-stages)
    participant Consumer as orthanc-scan-consumer
    participant Disk as Cache file (app.lot-stages.cache-file)

    UI->>Ctrl: GET /api/lot-stages
    Ctrl->>Client: stages()
    Client->>CB: executeSupplier(fetch)

    alt Circuit CLOSED or HALF_OPEN and consumer answers
        CB->>Consumer: GET /api/lot-stages (2 s connect/read timeout)
        Consumer-->>CB: 200 JSON catalog
        CB-->>Client: json (call recorded as success)
        opt json is not null
            Client->>Client: cached = json
            Client->>Disk: save(json): write temp file, then atomic move
            Client-->>Ctrl: Optional.of(json)
        end
    else Consumer fails (timeout, unreachable, 5xx)
        CB->>Consumer: GET /api/lot-stages
        Consumer--xCB: RestClientException
        CB-->>Client: RestClientException (failure recorded)
        Client->>Client: log.warn("serving the cached copy")
    else Circuit OPEN
        CB-->>Client: CallNotPermittedException (consumer not called)
    end

    Note over Client,Disk: Fallback when the consumer gave no JSON
    alt in-memory cached is set
        Client-->>Ctrl: Optional.of(cached)
    else cached is null
        Client->>Disk: load()
        alt cache file readable
            Disk-->>Client: json
            Client->>Client: cached = json
            Client-->>Ctrl: Optional.of(json)
        else IOException (no file yet)
            Client-->>Ctrl: Optional.empty()
        end
    end

    alt catalog present
        Ctrl-->>UI: 200 OK (application/json)
    else empty
        Ctrl-->>UI: 503 Service Unavailable
    end

    Note over Ctrl,Client: StageCatalog.stages() (used by scan checks) calls the same<br/>LotStageClient.stages() path, but only when its 1-minute Snapshot has expired.
```

### Health
`ConsumerCircuitsHealthIndicator` reports each circuit breaker's state and counters at `/actuator/health/circuits`. It always reports UP, because an open circuit means the producer is falling back as designed.
