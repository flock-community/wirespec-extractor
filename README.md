# wirespec-extractor

[![Maven plugin (Maven Central)](https://img.shields.io/maven-central/v/community.flock.wirespec.extractor/wirespec-extractor-maven-plugin?label=maven-plugin)](https://central.sonatype.com/artifact/community.flock.wirespec.extractor/wirespec-extractor-maven-plugin)
[![Gradle plugin (Maven Central)](https://img.shields.io/maven-central/v/community.flock.wirespec.extractor/community.flock.wirespec.extractor.gradle.plugin?label=gradle-plugin)](https://central.sonatype.com/artifact/community.flock.wirespec.extractor/community.flock.wirespec.extractor.gradle.plugin)

A Maven and Gradle plugin that scans a JVM application's compiled classes and emits
[Wirespec](https://wirespec.io) (`.ws`) files describing its API. It reads Spring MVC /
WebFlux controllers and functional routes, JAX-RS / OpenAPI resources, and Ktor server
and client code for HTTP endpoints, plus Kafka, JMS, RabbitMQ, Pulsar, and Spring
Integration listeners and producers as messaging channels — along with the DTO types
they reference.

## Usage (Maven)

Drop the plugin into `pom.xml` with `<extensions>true</extensions>` and it
auto-binds to `process-classes`:

```xml
<build>
  <plugins>
    <plugin>
      <groupId>community.flock.wirespec.extractor</groupId>
      <artifactId>wirespec-extractor-maven-plugin</artifactId>
      <version>0.0.15</version>
      <extensions>true</extensions>
      <configuration>
        <!-- optional — defaults to ${project.build.directory}/wirespec -->
        <output>${project.build.directory}/wirespec</output>
        <!-- optional — only scan classes under this package -->
        <basePackage>com.acme.api</basePackage>
        <!-- optional — all default to true. Disable a path to extract only the others. -->
        <extractSpring>true</extractSpring>
        <extractOpenApi>true</extractOpenApi>
        <extractKtor>true</extractKtor>
        <!-- optional — jar packaging. jarEnabled and jarPath belong together:
             jarEnabled bundles the .ws files into a jar attached under the
             `wirespec` classifier so `mvn install`/`deploy` publishes it, and
             jarPath is the directory inside that jar the .ws files live under
             (default: the project artifactId). -->
        <jarEnabled>false</jarEnabled>
        <jarPath>${project.artifactId}</jarPath>
      </configuration>
    </plugin>
  </plugins>
</build>
```

`mvn package` (or any goal from `process-classes` onward) writes `.ws`
files into `target/wirespec/`. To trigger the goal directly:

```bash
mvn wirespec:extract
```

### Publishing the `.ws` files as a jar

With `<jarEnabled>true</jarEnabled>`, the plugin also bundles the emitted
`.ws` files into `target/<finalName>-wirespec.jar` and attaches it to the
project, so `mvn install` / `mvn deploy` publishes it alongside the main
artifact under the `wirespec` classifier. Inside the jar the files live under a
per-project package directory set by `jarPath` (default: the project's
`artifactId`) — e.g. `com/acme/api/UserController.ws` — so several such jars
never collide by path when placed on one classpath.

Consume it with `<classifier>wirespec</classifier>` on the dependency.

## Usage (Gradle)

> **Plugin resolution.** The Gradle plugin is published to **Maven Central**,
> not the Gradle Plugin Portal. Gradle's `plugins { id(...) }` block only checks
> the Plugin Portal by default, so you must add `mavenCentral()` to plugin
> resolution. Put this at the **top** of `settings.gradle.kts`, **before** any
> `include(...)` or `dependencyResolutionManagement {}` block:
>
> ```kotlin
> pluginManagement {
>     repositories {
>         mavenCentral()
>         gradlePluginPortal()
>     }
> }
> ```
>
> Without this snippet the build fails with
> `Plugin [id: 'community.flock.wirespec.extractor', version: '...'] was not found in any of the following sources`.

### Kotlin project

```kotlin
plugins {
    kotlin("jvm") version "2.1.20"
    id("community.flock.wirespec.extractor") version "0.0.15"
}

dependencies {
    implementation("org.springframework:spring-web:6.1.14")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // So Spring's @PathVariable/@RequestParam (and the extractor) can
        // recover parameter names that have no explicit value().
        freeCompilerArgs.add("-java-parameters")
    }
}

wirespecExtractor {
    // optional — defaults to build/wirespec
    // outputDir.set(layout.buildDirectory.dir("wirespec"))

    // optional — only scan classes under this package
    basePackage.set("com.acme.api")

    // optional — all default to true. Disable a path to extract only the others.
    // extractSpring.set(true)    // Spring MVC controllers, DSL routes, messaging
    // extractOpenApi.set(true)   // JAX-RS resources + swagger annotations
    // extractKtor.set(true)      // Ktor server routing + client calls

    // optional — jar packaging. jarEnabled and jarPath belong together:
    // jarEnabled bundles the .ws files into a `-wirespec`-classified jar and, when
    // `maven-publish` is applied, adds it to the project's publications (default false);
    // jarPath is the directory inside that jar the .ws files live under
    // (default: the project name / artifactId).
    // jarEnabled.set(true)
    // jarPath.set("com/acme/api")
}
```

### Java project

```kotlin
plugins {
    java
    id("community.flock.wirespec.extractor") version "0.0.15"
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    implementation("org.springframework:spring-web:6.1.14")
}

// So Spring's @PathVariable/@RequestParam (and the extractor) can recover
// parameter names that have no explicit value().
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}

wirespecExtractor {
    basePackage.set("com.acme.api")

    // optional — all default to true. Disable a path to extract only the others.
    // extractSpring.set(true)
    // extractOpenApi.set(true)
    // extractKtor.set(true)
}
```

Applying the plugin alongside any JVM source plugin auto-wires
`extractWirespec` into `assemble`, so `gradle build` (or `gradle assemble`)
writes `.ws` files into `build/wirespec/`. To trigger it directly:

```bash
./gradlew extractWirespec
```

### Publishing the `.ws` files as a jar

Set `jarEnabled.set(true)` and the plugin registers a `wirespecJar` task that
bundles the emitted `.ws` files into a `-wirespec`-classified jar (built as part
of `assemble`). When `maven-publish` is applied, the jar is added to the
project's `MavenPublication`s, so `./gradlew publish` ships it alongside the main
artifact. Inside the jar the files live under a per-project package directory set
by `jarPath` (default: the project name / artifactId) —
e.g. `com/acme/api/UserController.ws` — so several such jars never collide by
path on one classpath.

> ⚠️ **Compile with `-parameters` / `-java-parameters`.** Without it the
> JVM erases method parameter names, and `@PathVariable Long id` —
> declared without an explicit `value` — comes out as `arg0`. Both
> fixture builds in this repo set the flag, and you should too.

## What it extracts

- `@RestController` / `@Controller` (with `@ResponseBody`) classes
- The `@RequestMapping` family (`@GetMapping`, `@PostMapping`, etc.)
- Path / query / header / cookie parameters; `@RequestBody`
- Response types, including unwrapping of `ResponseEntity`, `Mono`, `Flux`,
  `Optional`, `Callable`, `DeferredResult`
- The success status code — from `@ResponseStatus`, or a status set
  programmatically on a returned `ResponseEntity`
  (`ResponseEntity.status(HttpStatus.CREATED)`, `ResponseEntity.created(...)`,
  `new ResponseEntity<>(body, HttpStatus.CREATED)`, …). See
  [Success status code](#success-status-code).
- Multiple response statuses via springdoc `@ApiResponses` /
  `@ApiResponse` — one Wirespec response per declared status. See
  [Multiple responses](#multiple-responses).
- DTO classes referenced by endpoints, with Jackson (`@JsonProperty`,
  `@JsonIgnore`), Bean Validation (`@NotNull`, `@Size`, `@Pattern`, `@Min`,
  `@Max`), and springdoc `@Schema` awareness
- Nullability for both DTO fields and endpoint parameters — from Kotlin types,
  `Optional<T>`, Spring's `required` flag, and `@Nullable` / `@NonNull`
  annotations (JSR-305, JetBrains, Spring, JSpecify — including JSpecify's
  `@NullMarked` scopes). See [Nullability](#nullability).
- Kotlin `@JvmInline value class` wrappers, flattened to the value they wrap
  rather than emitted as one-field types. See
  [Kotlin value classes](#kotlin-value-classes).
- Spring functional-DSL routes — WebFlux Kotlin `router { }` / `coRouter { }`,
  Spring MVC Kotlin `router { }`, and the Java fluent
  `RouterFunctions.route()` builder. See
  [Functional DSL routes](#functional-dsl-routes).
- JAX-RS resources — classes routed with `@Path` / `@GET` / `@POST` / … where
  the OpenAPI detail (parameters, request body, responses, schemas, operation
  names) is driven entirely by swagger/OpenAPI annotations. See
  [JAX-RS resources](#jax-rs-resources-swagger-driven).
- Messaging listeners and producers — Spring Kafka, JMS, RabbitMQ, Spring
  Pulsar, and Spring Integration — emitted as Wirespec `channel` definitions.
  See [Messaging extraction](#messaging-extraction-kafka-jms-rabbitmq-pulsar-spring-integration).
- Ktor server routing trees (`routing { route("/users") { get { … } } }`) and
  Ktor client request calls (`client.post("/users") { setBody(dto) }.body()`).
  See [Ktor extraction](#ktor-extraction-server--client).

### Generic types

Wirespec has no concept of generic type parameters. The extractor flattens
every concrete generic instantiation it encounters into its own named
wirespec type with the type arguments substituted:

| Java/Kotlin                  | Wirespec                                       |
| ---------------------------- | ---------------------------------------------- |
| `Page<UserDto>`              | `type UserDtoPage`                             |
| `Wrapper<Int>`               | `type IntegerWrapper`                          |
| `Pair<UserDto, OrderDto>`    | `type UserDtoOrderDtoPair`                     |
| `Page<Wrapper<UserDto>>`     | `type UserDtoWrapper`, `type UserDtoWrapperPage` |
| `ApiResponse<List<UserDto>>` | `type UserDtoListApiResponse`                  |

Names are composed by reading type arguments innermost-first then the generic
class's simple name. `List` and `Map` are wirespec-native containers: they
stay as `T[]` and `{T}` at use sites and only contribute a `List` / `Map`
suffix when they appear inside another generic's type arguments.

The extractor fails the build (with a pointer to the offending controller
method) when it encounters:

- a raw generic at a reference site (`fun list(): Page` — no type argument),
- a wildcard argument (`Page<*>`, `Page<?>`),
- a class extending a generic parent without arguments
  (`class UserPage : Page`).

This monomorphization rule means controller signatures must always bind
their generic parameters concretely.

### Kotlin value classes

A Kotlin `@JvmInline value class` is a compile-time wrapper, and Jackson
serializes it as the value it wraps. The extractor does the same: it flattens
the wrapper away instead of emitting a one-field type for it.

```kotlin
@JvmInline value class UserId(val value: String)

@GetMapping("/users/by-user-id/{id}")
fun findByUserId(@PathVariable id: UserId): List<UserId>
```

```wirespec
endpoint FindByUserId GET /users/by-user-id/{id: String} -> {
  200 -> String[]
}
```

No `UserId` definition is emitted, and the name `UserId` stays free for another
type to claim. Flattening is recursive (a value class wrapping a value class
resolves to the innermost value) and works for any underlying type — a
primitive, a DTO (which is still emitted and referenced), a collection, or a
generic value class's bound type argument.

Because a value class in a signature makes the Kotlin compiler mangle the JVM
method name (`findByUserId-_mjp9w0`), the `-<hash>` suffix is stripped when
deriving endpoint, channel, and property names.

Note that the compiler already erases value classes at most *field* positions,
so `val id: UserId` reaches the extractor as a plain `String` either way. The
wrapper survives — and is flattened here — where it stays boxed: generic
arguments (`List<UserId>`, `ResponseEntity<UserId>`) and nullable positions
(`UserId?`).

### Nullability

Every emitted field and parameter is marked nullable (`T?`) or non-null (`T`).

**DTO fields** default to nullable, and are resolved in priority order:

1. Java primitives → non-null.
2. `Optional<T>` → nullable, unwrapped to the inner `T`.
3. Kotlin nullability (`String?` vs `String`) via `@Metadata`.
4. `@Nullable` / `@NonNull` annotations — JSR-305 (`javax`/`jakarta`), JetBrains,
   Spring, Android, FindBugs, Checker Framework, and **JSpecify**. Both
   declaration- and `TYPE_USE`-targeted annotations are read (JSpecify's are
   `TYPE_USE`-only).
5. `@NotNull` / `@NotBlank` / `@Schema(required = true)` → non-null.
6. JSpecify `@NullMarked` in scope (the field/method, an enclosing class, or the
   package — reversible with `@NullUnmarked`) → unannotated references become
   non-null.

Nullability that is inherent to a type — `Optional<T>`, or a value class over a
nullable value — is never narrowed away by a non-null declaration site, since
the declaration cannot make such a value guaranteed present.

**Endpoint parameters** (`@PathVariable`, `@RequestParam`, `@RequestHeader`,
`@CookieValue`, `@RequestBody`) instead default to **non-null** — Spring treats
them as required. A parameter becomes nullable when it is typed `Optional<T>`,
annotated `@Nullable`, declared with a Kotlin nullable type, or marked optional
to Spring (`required = false` or a `defaultValue`). An explicit annotation or a
Kotlin nullable type wins over the `required` flag; Java primitives are always
non-null.

### Success status code

For an endpoint without `@ApiResponse`(s), the single response's status is
resolved in this order:

1. **`@ResponseStatus`** on the handler (or a meta-annotation) — used verbatim.
2. **A status set on the returned `ResponseEntity`**, read from the compiled
   method body. All of these yield `201`:

   ```kotlin
   ResponseEntity.status(HttpStatus.CREATED).body(campaign)   // enum
   ResponseEntity.status(201).body(campaign)                  // int literal
   ResponseEntity.created(uri).body(campaign)                 // factory
   ResponseEntity(campaign, HttpStatus.CREATED)               // constructor
   ```

   The factory methods `ok`/`created`/`accepted`/`noContent`/`badRequest`/
   `notFound`/`unprocessableEntity`/`internalServerError` are recognized, as is
   `status(HttpStatus.X)` / `status(<int>)`. `suspend` handlers work too — their
   body compiles into the same method, so the builder call is still visible.
3. **The signature default** — `204` when the effective return type is
   `void`/`Unit`, otherwise `200`.

Body-derived detection is deliberately conservative: it reports a status only
when the handler builds a single, unambiguous **2xx** `ResponseEntity`. A method
that returns several different success statuses down different branches falls
through to the default — declare those variants with `@ApiResponse` instead.

### Multiple responses

Spring/springdoc lets a handler declare multiple response variants. The
extractor reads `io.swagger.v3.oas.annotations.responses.ApiResponses` (and
standalone `@ApiResponse`) and emits one Wirespec `Response` per entry:

```kotlin
@GetMapping("/users/{id}")
@ApiResponses(
    ApiResponse(responseCode = "200"),
    ApiResponse(
        responseCode = "404",
        content = [Content(schema = Schema(implementation = ErrorDto::class))],
    ),
)
fun getUser(@PathVariable id: String): UserDto = ...
```

Behavior:

- One Wirespec response per `@ApiResponse`. Status comes from `responseCode`.
- Body comes from `content[].schema.implementation` (or
  `content[].array.schema.implementation` → list).
- An `@ApiResponse` without a `content` schema falls back to the method's
  return type **only** when its status matches the natural success status
  (e.g. `200` for value-returning, `204` for `void`); otherwise it is
  emitted body-less.
- Non-numeric `responseCode`s (`"default"`, `"2XX"`) are skipped with a
  warning.
- Without any `@ApiResponse`(s): one response, with the status resolved as
  described in [Success status code](#success-status-code).

### Functional DSL routes

Any class with at least one method returning
`org.springframework.web.reactive.function.server.RouterFunction` (WebFlux) or
`org.springframework.web.servlet.function.RouterFunction` (Spring MVC) is
treated as a virtual controller. Its simple class name becomes the
`<Name>.ws` file. Routes are discovered by **static bytecode inspection** —
no Spring context is booted, and no DSL code is executed at extract time.

Recognised:

- HTTP-method calls: `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`, `OPTIONS`.
- Nesting prefixes via `String.nest { }` / `RequestPredicate.nest { }`
  (Kotlin) and `Builder.nest(predicate, c)` / `Builder.path(prefix, c)` (Java).
- Handler references — `handler::method` (Kotlin, compiled to a
  `FunctionReferenceImpl` subclass) and `handler::method` (Java, compiled to
  an `INVOKEDYNAMIC LambdaMetafactory`).
- Request body inference: scans the resolved handler's bytecode for
  `ServerRequest.bodyToMono(Class)` / `bodyToFlux(Class)` / `body(Class)` and
  uses the captured class literal as the Wirespec request body.

Responses default to a single `200` with no body. To declare typed responses,
annotate the handler method with springdoc `@ApiResponses` / `@ApiResponse` —
those are read by the same code path as annotated controllers.

```kotlin
class RouterConfig {
    fun routes(h: UserHandler): RouterFunction<ServerResponse> = router {
        "/users".nest {
            GET("", h::list)
            POST("", h::create)
            "/{id}".nest {
                GET("", h::getOne)
                DELETE("", h::delete)
            }
        }
    }
}
```

**Limitations:**

- Request bodies are only detected for `Class<T>` overloads of
  `bodyToMono` / `bodyToFlux` / `body`. The
  `ParameterizedTypeReference<T>` overloads — and bodies passed via
  `BodyExtractors` — fall back to no body.
- Response bodies can't be inferred from `ServerResponse.bodyValue(...)`
  builder chains; use `@ApiResponse(content = ...)` on the handler to declare
  them.
- Lambda bodies in DSL handler positions (`GET("/x") { req -> ... }`) are
  recognised but only their *paths* and HTTP methods are extracted — the
  lambda has no `Method` to inspect for `@ApiResponse` or
  `request.bodyToMono` calls.
- Predicates other than `path()` / `nest()` (`accept(...)`, `contentType(...)`,
  `headers(...)`) are ignored for path purposes.

### JAX-RS resources (swagger-driven)

Alongside Spring controllers, the extractor discovers **JAX-RS root resources** —
classes annotated with `@Path` (either the `jakarta.ws.rs` or the legacy
`javax.ws.rs` namespace). This is the classic non-Spring OpenAPI stack
(Jersey / RESTEasy / Quarkus).

Routing is read from JAX-RS annotations; **everything else is driven by
swagger/OpenAPI annotations**:

- **Path + HTTP method** come from class- and method-level `@Path` plus the
  `@GET` / `@POST` / `@PUT` / `@PATCH` / `@DELETE` / `@HEAD` / `@OPTIONS`
  annotations (discovered via the JAX-RS `@HttpMethod` meta-annotation, so
  custom HTTP-method annotations work too). Swagger annotations carry no URL
  path, which is why routing must come from JAX-RS.
- **Parameters** come from `@PathParam` / `@QueryParam` / `@HeaderParam` /
  `@CookieParam`, or from a swagger `@Parameter(in = …)` when no JAX-RS binding
  annotation is present. Path params default to required (non-null); query,
  header, and cookie params default to optional (nullable) unless
  `@Parameter(required = true)`, a `@NotNull`, or a non-null Kotlin type says
  otherwise.
- **Request body** is the swagger `@RequestBody`-annotated parameter (its
  `@Content` schema wins when declared), or the lone JAX-RS entity parameter —
  the method argument with no binding/injected (`@Context`, `@BeanParam`, …)
  annotation.
- **Responses** are read by the same code path as annotated Spring controllers:
  swagger `@ApiResponses` / `@ApiResponse` produce one Wirespec response per
  declared status, falling back to the method signature otherwise. See
  [Multiple responses](#multiple-responses).
- **Operation name** is the swagger `@Operation(operationId = …)` when present
  (PascalCased), else the method name. `@Operation(hidden = true)` methods are
  skipped.

```kotlin
@Path("/users")
class UserResource {
    @GET
    @Operation(operationId = "listUsers")
    fun list(@QueryParam("active") active: Boolean?): List<UserDto> = …

    @GET
    @Path("/{id}")
    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(
            responseCode = "404",
            content = [Content(schema = Schema(implementation = ErrorDto::class))],
        ),
    )
    fun getOne(@PathParam("id") id: String): UserDto = …
}
```

JAX-RS annotations are read reflectively by fully-qualified name, so — like the
messaging scanners — the extractor needs no JAX-RS API on its own classpath and
cleanly no-ops on projects that don't use JAX-RS. Resources are emitted to a
`<ResourceName>.ws` file, the same convention used for controllers.

The Spring and JAX-RS/OpenAPI paths run independently and can be toggled via
`extractSpring` / `extractOpenApi` (both default `true`) — set one to `false` to
extract only the other. See the [Maven](#usage-maven) and [Gradle](#usage-gradle)
configuration above.

### Messaging extraction (Kafka, JMS, RabbitMQ, Pulsar, Spring Integration)

In addition to HTTP endpoints, the extractor discovers messaging listeners and
producers and emits them as Wirespec `channel` definitions. Extraction is driven
by a per-broker descriptor table, so the same scanners cover **Spring Kafka,
JMS, RabbitMQ, Spring Pulsar, and Spring Integration**:

| Broker            | Listener annotation(s)                 | Producer call                        |
| ----------------- | -------------------------------------- | ------------------------------------ |
| Kafka             | `@KafkaListener`, `@KafkaHandler`      | `KafkaTemplate.send(...)`            |
| JMS               | `@JmsListener`                         | `JmsTemplate.convertAndSend(...)`   |
| RabbitMQ          | `@RabbitListener`, `@RabbitHandler`    | `RabbitTemplate.convertAndSend(...)` |
| Pulsar            | `@PulsarListener`                      | `PulsarTemplate.send/sendAsync(...)` |
| Spring Integration | `@ServiceActivator`                   | — (listener-only)                    |

- **Consumers**: methods annotated with the broker's listener annotation, and —
  for Kafka and Rabbit — methods annotated with the class-level multi-handler
  annotation (`@KafkaHandler` / `@RabbitHandler`). The payload type is taken from
  the `@Payload` parameter, or the single non-meta parameter (Spring `@Header` /
  `@Headers` arguments and broker meta types such as `Acknowledgment`,
  `jakarta.jms.Message`, `amqp.core.Message`, or the Pulsar `Consumer` are
  ignored). Spring's `Message<T>` and `List<T>` (batch) wrappers, plus
  broker-specific record wrappers (Kafka `ConsumerRecord<K, V>`, Pulsar
  `Message<T>` / `Messages<T>`), are unwrapped to the value type.

- **Producers**: methods that call the broker's send method. For generic
  templates (Kafka, Pulsar) the value type is recovered from the
  `KafkaTemplate<K, V>` / `PulsarTemplate<T>` field's generic signature; for
  non-generic templates (JMS, Rabbit) it is recovered from the send-call
  argument type. Each enclosing method produces one channel (suffixed with the
  value type when a method sends more than one type). Spring Integration is
  listener-only — it has no producer template.

Channels are named after the handler/sender method (`onOrderCreated` →
`OnOrderCreated`) and grouped into the `.ws` file of the owning class —
the same convention used for HTTP endpoints. Destination names (topics, queues,
exchanges) are not read; they are typically property placeholders at extract
time. The broker libraries do not need to be on the extractor's classpath —
each broker is matched by fully-qualified name and cleanly no-ops in projects
that don't use it.

**Out of scope (v1):** `@SendTo` on listener return values; producers
passing pre-built records (`ProducerRecord<K, V>`, `Message<?>`) to the send
call; keys, headers, consumer groups, and destination-to-channel name
resolution.

### Ktor extraction (server + client)

Alongside the Spring/JAX-RS paths, the extractor discovers **Ktor** endpoints —
both the server routing tree and the client request DSL. Like the Spring
functional DSL, routes are found by **static bytecode inspection**: no Ktor
application is booted and no DSL code runs at extract time. Ktor is read entirely
by fully-qualified name, so it needs no Ktor API on the extractor's classpath and
cleanly no-ops on projects that don't use Ktor.

**Server.** Any class whose bytecode builds a routing tree
(`io.ktor.server.routing` — `routing { }`, `route(...)`, `get`/`post`/…) is
treated as a virtual controller; its simple class name becomes the `<Name>.ws`
file (a top-level `fun Application.module()` lands in its `…Kt` file facade).

- HTTP-method builders: `get`, `post`, `put`, `patch`, `delete`, `head`,
  `options`, with the path taken from `route("/prefix") { }` nesting and/or an
  inline `get("/path") { }` argument. Ktor path parameters (`{id}`, `{id?}`,
  `{id...}`) become Wirespec path variables.
- Query and header parameters: recovered heuristically from
  `call.request.queryParameters["k"]`, `call.request.headers["k"]`, and
  `call.request.header("k")` inside the handler body. Keys must be string
  literals. Values default to `String?`; an immediately-applied conversion
  (`?.toInt()`, `?.toBoolean()`, `?.toLong()`, …) upgrades the type.
- Request body: recovered from `call.receive<T>()`.
- Responses: `call.respond(value)` yields a `200` with the value's type;
  `call.respond(HttpStatusCode.Created, value)` and `call.respond(status)` use
  the declared status (`201`, `204`, …). Multiple `respond` calls in one handler
  become multiple responses.
- Endpoints are named from method + path (`GET /users/{id}` → `GetUsersById`)
  since the handler lambda is anonymous.

```kotlin
fun Application.userRoutes() {
    routing {
        route("/users") {
            get { call.respond(userService.all()) }
            post {
                val dto = call.receive<UserDto>()
                call.respond(HttpStatusCode.Created, userService.create(dto))
            }
            get("/{id}") { call.respond(userService.find(call.parameters["id"]!!)) }
        }
    }
}
```

**Client.** Any class that issues `HttpClient` requests is emitted to a
`<Client>.ws` file, with one endpoint per calling method
(`suspend fun listUsers()` → `ListUsers`).

- HTTP verb from `client.get/post/put/patch/delete/head/options(...)`.
- URL from the request's literal path string.
- Request body from `setBody(value)`; response body from `.body<T>()`.

```kotlin
class UserClient(private val client: HttpClient) {
    suspend fun listUsers(): List<UserDto> = client.get("/users").body()
    suspend fun createUser(dto: UserDto): UserDto =
        client.post("/users") { setBody(dto) }.body()
}
```

**Limitations (v1):**

- Routing config lambdas are followed when the Kotlin compiler emits them as
  `invokedynamic` (the default since Kotlin 2.0); handler bodies in DSL position
  (`get { … }`) are read as generated suspend-lambda classes.
- Query / header parameters are recovered only when the lookup key is a string
  literal; dynamic keys, values read via `call.parameters[...]` (which merges
  path and query), and cookie parameters are not extracted. Required-vs-optional
  is not inferred — recovered params are always emitted nullable, matching the
  `String?` the lookup returns.
- Response/request bodies are recovered from the reified `receive` / `respond` /
  `setBody` / `body` type arguments; bodies passed via non-reified overloads are
  not read.
- Client URLs are read from the literal path string. Interpolated paths
  (`"/users/$id"`) are only partially recovered (the static portion), so dynamic
  segments may be missing.

### Known limitations (v1)

- `@Controller` classes with handler methods directly annotated with
  `@ResponseBody`. Meta-annotated variants (e.g., custom annotations that are
  themselves annotated with `@ResponseBody`) are not detected in v1.

See [docs/superpowers/specs/2026-05-12-wirespec-extractor-design.md](docs/superpowers/specs/2026-05-12-wirespec-extractor-design.md)
for the full design and current scope limitations.

## Output layout

- One `<ControllerName>.ws` per controller — endpoints, plus DTO/enum/refined
  types referenced only by that controller.
- One shared `types.ws` — DTO/enum/refined types referenced by two or more
  controllers. Omitted when all types are controller-local.
- The output directory is treated as a generated artifact: existing `*.ws`
  files are deleted on each run; non-`.ws` files are left alone.
