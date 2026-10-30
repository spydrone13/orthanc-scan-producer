# orthanc-scan-producer

Spring Boot API (port 3000) that accepts scans from orthanc-scan-ui at `POST /api/scans` and publishes them to
the RabbitMQ exchange `orthanc.scans`, where orthanc-scan-consumer picks them up. Before publishing, the producer
asks the consumer whether the lot is on hold (`GET /api/lots/{lotId}`).

It also serves the UI itself and the lot-stage catalog (`GET /api/lot-stages`, passed through from the consumer).
Both are built to keep the plant scanning when the off-site consumer can't be reached. See
[Deploying in the plant](#deploying-in-the-plant).

## Configuration files

`src/main/resources/application.properties` holds the defaults, which are set up for a fully local run
(RabbitMQ on `localhost:5672` as `guest`, consumer on `http://localhost:3001`). Two more files, which you
create yourself in `src/main/resources/`, override them:

| File                             | Holds                                                | Loaded                                  |
|----------------------------------|------------------------------------------------------|-----------------------------------------|
| `application-secrets.properties` | Your RabbitMQ username and password                  | Always, if it exists                    |
| `application-local.properties`   | Local overrides: broker host, consumer URL, queue    | When the `local` profile is active      |

Both files are git-ignored and left out of the built jar, so they never get committed or shipped. Don't
rename them, or both protections stop applying. If a property is set in more than one place, the order is:
`application-local.properties`, then `application-secrets.properties`, then `application.properties`.
Keep credentials in the secrets file only.

### application-secrets.properties

Needed for modes 1 and 2. RabbitMQ's `guest` user can only log in from localhost, so you need a real user
on the test broker:

```properties
spring.rabbitmq.username=<your-dev-user>
spring.rabbitmq.password=<your-dev-password>
```

### Running with the local profile

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

In an IDE, set **Active profiles** to `local` in the run configuration.

## Local development

Choose one of three modes:

| Mode | RabbitMQ         | Queue                    | Consumer that processes your scans |
|------|------------------|--------------------------|------------------------------------|
| 1    | Test environment | `orthanc.scans` (shared) | Test environment consumer          |
| 2    | Test environment | Your own queue           | Your local consumer                |
| 3    | Local (Docker)   | `orthanc.scans`          | Your local consumer                |

Replace `<test-rabbitmq-host>` and `<test-consumer-url>` below with the test environment's values.

### Mode 1: test environment queue and consumer

Your scans go to the shared test queue and the test environment consumer processes them. You only run the
producer, and probably the UI.

Create `application-secrets.properties` (above) and `application-local.properties`:

```properties
spring.rabbitmq.host=<test-rabbitmq-host>
app.consumer.base-url=<test-consumer-url>
```

Then run with the `local` profile.

- Your scans change the shared test data that everyone else sees.
- Don't run a local consumer against `orthanc.scans` in this mode. It would compete with the test consumer and
  take some of the test environment's messages.

### Mode 2: test environment RabbitMQ, your own queue and local consumer

Your scans go through the test environment's broker into a queue only you use, and your local consumer
processes them.

Create `application-secrets.properties` (above) and `application-local.properties`:

```properties
spring.rabbitmq.host=<test-rabbitmq-host>
app.scans.queue=orthanc.scans.<your-name>
app.scans.routing-key=scan.created.<your-name>
app.scans.dead-letter-queue=orthanc.scans.dlq.<your-name>
app.scans.queue-expires=7d
```

Leave `app.consumer.base-url` out so lot lookups go to your local consumer on port 3001. Create the same two
files in orthanc-scan-consumer, with the **same** queue, routing key, dead-letter queue and expiry (see its
README). Run both apps with the `local` profile.

- **The routing key must be different from `scan.created`, not just the queue name.** The exchange delivers a
  message to every queue bound with its routing key. If your queue used `scan.created`, it would also receive
  copies of everyone's test scans, and the test consumer would still receive yours.
- Your dead-letter queue uses the shared dead-letter exchange `orthanc.scans.dlx`. Failed scans are routed by
  dead-letter queue name, so you only get your own.
- Whichever app starts first creates your two queues and binds them to `orthanc.scans` and
  `orthanc.scans.dlx`. Your RabbitMQ user needs configure, write and read permission on both queues, and write
  and read permission on both exchanges.
- RabbitMQ won't redeclare an existing queue with different arguments (dead-letter settings or expiry). If the
  apps fail to start with `PRECONDITION_FAILED - inequivalent arg`, check both apps have the same settings, then
  delete the queue once (below). See also the consumer's README, "Failed scans".

#### Removing your queues

With `app.scans.queue-expires` set, RabbitMQ deletes your queues by itself: each one is deleted, with any
messages in it, once it has gone 7 days with no consumer and neither app starting. Starting either app
recreates them and restarts the clock. If you leave the apps running for more than 7 days without opening the
consumer's `/dead-letters` page, your dead-letter queue can expire while they run. Restart either app to
recreate it.

To delete them now, for example to start clean, stop both apps and delete `orthanc.scans.<your-name>` and
`orthanc.scans.dlq.<your-name>`. Any messages in them are lost. Either:

- In the RabbitMQ management UI (`http://<test-rabbitmq-host>:15672`), open **Queues and Streams**, click the
  queue, then at the bottom of the page open **Delete** and click **Delete Queue**.
- Or with the HTTP API (`%2F` is the default virtual host `/`):

  ```bash
  curl -u <your-dev-user>:<your-dev-password> -X DELETE \
    "http://<test-rabbitmq-host>:15672/api/queues/%2F/orthanc.scans.<your-name>"
  curl -u <your-dev-user>:<your-dev-password> -X DELETE \
    "http://<test-rabbitmq-host>:15672/api/queues/%2F/orthanc.scans.dlq.<your-name>"
  ```

If you used mode 2 before queues could expire, your queues were created without an expiry, and the apps
fail to start with `PRECONDITION_FAILED - inequivalent arg 'x-expires'`. Delete both queues as above. Also
delete your old dead-letter exchange `orthanc.scans.dlx.<your-name>`, which is no longer used: open
**Exchanges**, click it, then **Delete this exchange**, or:

```bash
curl -u <your-dev-user>:<your-dev-password> -X DELETE \
  "http://<test-rabbitmq-host>:15672/api/exchanges/%2F/orthanc.scans.dlx.<your-name>"
```

### Mode 3: everything local

No extra files are needed; the defaults in `application.properties` point at `localhost`. If you have files
from another mode, comment out their lines (`#`) rather than renaming them, since a renamed file is no longer
git-ignored. The secrets file is loaded with or without the `local` profile, and test credentials won't work
against a local `guest` broker.

If you delete either file instead, run `./mvnw clean` too. Otherwise the copy Maven already put in
`target/classes` keeps being loaded.

Start RabbitMQ in Docker:

```bash
docker run -it --rm --name rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:4-management
```

Then start orthanc-scan-consumer, then this app:

```bash
./mvnw spring-boot:run
```

The management UI is at http://localhost:15672 (guest / guest).

## Running tests

```bash
./mvnw test
```

The tests don't need RabbitMQ, the consumer, or either of the files above.

### Code coverage

`./mvnw test` also writes a coverage report. Open `target/site/jacoco/index.html` for the per-class HTML report.
A `jacoco.xml` file is also written there for editor plugins and CI tools.

`./mvnw verify` (and anything after it, like `install`) also checks coverage, and fails if line or branch
coverage drops below the minimums set on the `jacoco-maven-plugin` in `pom.xml`.

## Deploying in the plant

The producer and RabbitMQ run on the plant network, and the consumer runs off-site. Browsers only talk to the
producer, so scanning keeps working when the plant loses its internet connection:

| Piece          | When the consumer can't be reached                                                         |
|----------------|--------------------------------------------------------------------------------------------|
| UI             | Served from this jar.                                                                      |
| Lot stages     | Served from the last copy fetched, saved at `app.lot-stages.cache-file`; right away while the circuit breaker is open (below). |
| Hold check     | Skipped after a 2 s timeout, or right away while the circuit breaker is open (below); the consumer checks holds again when it processes the scan. |
| Scans          | Published to the plant's RabbitMQ and kept there until the consumer reconnects.            |

Without circuit breakers, each scan and each catalog request would wait the full 2 s for the consumer during
an outage. Lot lookups and catalog fetches each have their own breaker, `lot-lookup` and `lot-stages`. Once
half of a breaker's last 10 calls have failed (from at least 5 calls), the producer stops making that call
for 30 s:
- With `lot-lookup` open, scans skip the lot checks immediately.
- With `lot-stages` open, the cached catalog is served immediately.

The first call after the 30 s asks the consumer again, and calls resume once it answers. Each change of
state is logged at WARN, for example `Circuit lot-stages CLOSED -> OPEN`. Both breakers share the
`app.consumer.circuit-breaker.*` properties in `application.properties`.

### Health checks

```bash
curl http://<plant-host>:3000/actuator/health
```

This shows every component with its details. It is `DOWN` (HTTP 503) when RabbitMQ is unreachable, since
scans can't be recorded then. Trimmed output, with the broker down:

```json
{"status":"DOWN","components":{
  "rabbit":{"status":"DOWN","details":{"error":"org.springframework.amqp.AmqpConnectException: java.net.ConnectException: Connection refused"}},
  "consumerCircuits":{"status":"UP","details":{"lot-lookup":{"state":"CLOSED",…},"lot-stages":{"state":"CLOSED",…}}},
  "diskSpace":{"status":"UP","details":{"total":…,"free":…,"threshold":10485760,"path":"…","exists":true}},
  "ping":{"status":"UP"},"livenessState":{"status":"UP"},"readinessState":{"status":"UP"},"ssl":{"status":"UP",…}}}
```

When the broker is up, `rabbit` shows `"status":"UP"` and the broker's `"version"`. The disk space details include
the working directory's path. Only the health endpoint is exposed.

For the circuit breakers alone:

```bash
curl http://<plant-host>:3000/actuator/health/circuits
```

```json
{"status":"UP","components":{"consumerCircuits":{"status":"UP","details":{
  "lot-lookup":{"state":"CLOSED","failureRate":-1.0,"bufferedCalls":0,"failedCalls":0,"notPermittedCalls":0},
  "lot-stages":{"state":"OPEN","failureRate":100.0,"bufferedCalls":5,"failedCalls":5,"notPermittedCalls":2}}}}}
```

| Field               | Meaning                                                                                  |
|---------------------|------------------------------------------------------------------------------------------|
| `state`             | `CLOSED`: calling the consumer. `OPEN`: not calling it, using the fallback. `HALF_OPEN`: probing it. |
| `failureRate`       | Percent of the last calls that failed; `-1` until there have been at least 5 calls.      |
| `bufferedCalls`     | Calls counted in the window (up to 10).                                                  |
| `failedCalls`       | Of those, how many failed.                                                               |
| `notPermittedCalls` | Calls skipped because the circuit was open, since startup.                               |

The status stays `UP` while a circuit is open: the producer is falling back as designed, so a monitoring
probe shouldn't treat it as down. Alert on `state` instead. This view stays `UP` when RabbitMQ is down; use
`/actuator/health` for that.

### Building

The jar carries the UI's production build. Build the UI first, then package:

```bash
cd ../orthanc-scan-ui && npm ci && npx ng build
cd ../orthanc-scan-producer && ./mvnw package
```

`./mvnw package` copies `../orthanc-scan-ui/dist/orthanc-scan-ui/browser` into the jar. If the UI build is
somewhere else, pass `-Dui.dist=<path>`. If the folder doesn't exist, the jar is built without a UI, so check
that `/` loads after deploying.

### Running

```bash
java -jar orthanc-scan-producer-0.0.1-SNAPSHOT.jar
```

Open `http://<plant-host>:3000/`. Set `app.lot-stages.cache-file` to a path on persistent disk; the default
`./data/lot-stages.json` is relative to the working directory. Until the producer has reached the consumer once,
it has no catalog to fall back on and `GET /api/lot-stages` returns 503. Browsers also keep the last catalog
they loaded, so a scanner that has used the app before still works in that case.

In development, `ng serve` on port 4200 talks to this app on port 3000 across origins, which
`app.cors.allowed-origins` allows. The bundled UI is same-origin and needs no CORS.
