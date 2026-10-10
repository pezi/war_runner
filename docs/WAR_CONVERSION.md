# Converting JVM WAR applications for Android

This is the WAR conversion guide for **WAR Runner**, intended for humans and
coding agents. This public repository contains the demo sources, packaging
tools and catalog. **The Flutter/Android host and its tests are maintained in a
separate, private project.** Host class names below describe the runtime contract;
they are not files available in this checkout. You can build WARs here without
the private source and test them using the installed app's web admin.

Findings were checked during the Jetty 12.1 / Vaadin 25 conversion
on **2026-09-17**. They describe the versions below, not a promise that arbitrary
WARs, Java libraries, or future releases will work on Android.

The objective is to preserve upstream application code and behavior. Adapt the
deployment boundary first; change a view or library only when a demonstrated
Android incompatibility requires it. Compilation, D8 success, and an HTTP 200
from the landing page are three useful checks, but none proves the app works.

## Read these files first

| Responsibility | Source of truth |
| --- | --- |
| WAR-to-DEX packaging | [package_android_war.py](../scripts/package_android_war.py) |
| Minimal Vaadin example | [Button demo POM](../vaadin-demo/pom.xml), [DemoServlet](../vaadin-demo/src/main/java/app/flutterdev/vaadindemo/DemoServlet.java) |
| Complex upstream application | [Official demo POM](../vaadin-official-demo/pom.xml), [OfficialDemoServlet](../vaadin-official-demo/src/main/java/com/vaadin/demo/OfficialDemoServlet.java), [port notes](../vaadin-official-demo/README.md) |
| Bookstore with authentication and forms | [Bookstore POM](../vaadin-bookstore-demo/pom.xml), [port notes](../vaadin-bookstore-demo/README.md) |
| Database access without Spring (H2 over JDBC), persistent data directory | [Address book POM](../vaadin-addressbook-demo/pom.xml), [PersonRepository](../vaadin-addressbook-demo/src/main/java/app/flutterdev/addressbook/PersonRepository.java), [AddressBookServlet](../vaadin-addressbook-demo/src/main/java/app/flutterdev/addressbook/AddressBookServlet.java), [notes](../vaadin-addressbook-demo/README.md) |

For maintainers with the private app checkout, the corresponding host sources
are listed below. Paths are relative to that app's root, locally `warrunner/`,
not this public repository:

| Responsibility | Private app source |
| --- | --- |
| Host dependencies, Android API floor, release settings | `android/app/build.gradle.kts` |
| Foreground lifecycle and Flutter bridge | `JettyService.kt`, `MainActivity.kt` |
| Deployment, extraction, DEX loading, start/stop | `JettyHost.kt`, `AndroidJettyRunner.java` |
| Per-WAR data directory, export/import/reset | `ApplicationData.kt`, `AndroidJettyRunner.java` |
| Resource URLs exposed by the DEX loader | `WarClassLoader.kt` |
| Supported `web.xml` subset | `AndroidWebXml.java` |

Kotlin classes are under `android/app/src/main/kotlin/app/flutterdev/jettyrunner/`;
Java classes are under `android/app/src/main/java/app/flutterdev/jettyrunner/`.

## Tested compatibility matrix

| Layer | Current choice | Why it matters |
| --- | --- | --- |
| Android host | Jetty **12.1.14**, `org.eclipse.jetty.ee11:jetty-ee11-servlet` | EE11 supplies Servlet **6.1** for Vaadin 25. The Jetty version alone does not select the servlet environment. |
| Host source / bytecode target | Java **17** | Separate from the JDK and bytecode level of the WAR build. |
| Vaadin WARs | All four **25.2.8** (Flow **25.2.9**), Java release **21**, provided Servlet API **6.1.0** | The button and official demos moved from the upstream 25.1.5 to 25.2.8: Flow 25.1 asks the servlet context for app shell stylesheets without a leading slash, which Jetty 12 rejects with a logged `MalformedURLException` before Flow retries. Use JDK 21+ to rebuild. |
| Hello World WAR | Java release **11**, provided Servlet API **5.0.0** | This particular older servlet works in the EE11 host. This is not a guarantee for every Servlet 5 application. |
| Android minimum | **API 35 / Android 15** | Flow uses Java 21 APIs such as `List.getFirst()`. D8 does not make the complete JDK available on Android. |
| WAR conversion tools | Build-Tools **36.0.0**, Platform **36**, Python **3.9+**, Maven | The Vaadin packager passes `--min-api 35` and the Android platform JAR to D8. Hello's simpler script still emits API 34-compatible DEX; the APK minimum is 35. |
| Browser resources | Button/official/address book: matching **25.2.8** `vaadin-prod-bundle` and Aura. Bookstore: **25.2.8** production frontend, Lumo and original CSS imports. | The bookstore requires Node/npm at WAR build time. A server/frontend version mismatch or missing theme files can survive a successful Java build. |

The original official demo is pinned to
[`d362e98aa9c38b588fdcf9f0a74bccbda4da714a`](https://github.com/vaadin/vaadin-demo/tree/d362e98aa9c38b588fdcf9f0a74bccbda4da714a).
It uses Spring Boot 4.0.6 and starts a desktop JVM with `./mvnw spring-boot:run`.
That command is not an Android deployment mechanism.

The first Jetty 12 migration kept EE9 / Servlet 5 and Vaadin 24. Moving to EE11
made the upstream Vaadin 25 APIs usable. The Badge, ColorScheme and Lumo CSS
backports were then removed. A newer Jetty does not supply missing Android Java
APIs, a ZIP filesystem, or Spring's application context.

## Conversion procedure

### 1. Establish an upstream baseline

Record the repository URL, exact revision, license, Java/framework versions,
routes, static assets, services and normal desktop build command. Keep a clean
upstream checkout outside the port's source directory. In this repository,
`vaadin-demo/` is already the small button example; do not clone over it.

For a fresh comparison checkout, from the repository root:

```sh
git clone https://github.com/vaadin/vaadin-demo.git build/upstream-vaadin-demo
git -C build/upstream-vaadin-demo checkout d362e98aa9c38b588fdcf9f0a74bccbda4da714a
git diff --no-index build/upstream-vaadin-demo/src/main/java vaadin-official-demo/src/main/java
git diff --no-index build/upstream-vaadin-demo/src/main/resources/META-INF/resources vaadin-official-demo/src/main/resources/META-INF/resources
```

Reuse an existing checkout only after checking its revision and working tree.
`git diff --no-index` returns 1 when differences exist; that is expected for the
Java comparison. Preserve upstream notices and distinguish real functionality
from demo placeholders. The official demo's save/invite actions display sample
notifications; they are not database writes or outgoing invitations.

### 2. Inventory the deployment requirements

Inspect the POM, `WEB-INF/classes`, `WEB-INF/lib`, `web.xml`, service-provider
files, frontend assets and any external configuration. Identify dependencies on
Spring/CDI, servlet initializers, filters, listeners, security constraints,
JSP/JNDI, JavaBeans, virtual threads, native code, filesystem providers and newer
JDK APIs. Examine the paths actually exercised by the application, including
lazy view construction and static class initialization.

Choose the matching servlet environment before downgrading the application.
The host currently exposes Jakarta Servlet classes, not the older
`javax.servlet` namespace. Simply renaming a descriptor or changing its version
does not port a `javax.servlet` application.

Keep container-owned APIs (`jakarta.servlet-api`, and here `slf4j-api`) in Maven's
`provided` scope. Supply them as D8 classpath inputs, not program classes in the
WAR's runtime DEX. The APK supplies Jetty and the servlet API through the parent
class loader. Mixing separate copies can cause linkage and class-identity
problems. Use Jetty's BOM for core modules and pin the EE11 servlet module too.

### 3. Replace only the bootstrap that Android cannot supply

The current host uses a `ServletContextHandler` with the DEX class loader and an
explicit descriptor. It does not run desktop annotation/classpath scanning,
web-fragment processing, or automatic `ServletContainerInitializer` discovery.
`metadata-complete="true"` documents that explicit configuration; it does not
magically make framework startup happen.

For Vaadin, follow `DemoServlet` / `OfficialDemoServlet`, in this order:

1. Create a `VaadinServletContext` and initialize `Lookup` if absent.
2. Initialize the route registry with every route class **and the `@Layout`
   class**. Including `MainLayout` preserves upstream's automatic layout and
   avoids adding explicit layouts to every `@Route` and `@RouteAlias`.
3. Initialize the error navigation targets and the app shell class.
4. Call the normal servlet initialization. In the service factory, configure
   the supported Android executor and instantiator, then call `service.init()`.

The startup initializer classes are version-sensitive Vaadin internals. Review
their signatures and responsibilities when changing Flow versions; explicit
initialization is not a guarantee that the same adapter works on every release.

The official app has one stateless injected dependency, `SourceService`. Its
custom `DefaultInstantiator` supplies that dependency to the original view
constructors. Other types delegate to Vaadin's default instantiator. Adding a
route with an injected constructor requires updating both registration and
construction. This small adapter does **not** reproduce Spring transactions,
security, lifecycle callbacks, scopes, or arbitrary dependency injection.

For this demo, `Application` keeps its upstream app-shell annotations, Aura and
favicon; only the Spring launcher/annotation is removed. `SourceService` reads
bundled text using Java I/O instead of adding Spring for `ClassPathResource`.
For a new application that actually needs Spring services or security, evaluate
those requirements explicitly; do not remove them to make startup pass.

### 4. Use the descriptor subset the host implements

The root must be `web-app` in `https://jakarta.ee/xml/ns/jakartaee`, with version
**5.0** or **6.1** and `metadata-complete="true"`. Version 6.0 is not currently
accepted by this deliberately narrow parser, even though Jetty has broader
capabilities. Extend and test the parser if a new application requires it.

| Element | Implemented content |
| --- | --- |
| `display-name` | Context display name |
| `context-param` | `param-name`, `param-value` |
| `servlet` | `servlet-name`, `servlet-class`, repeated `init-param`, `async-supported`, `load-on-startup` |
| `servlet-mapping` | `servlet-name`, one or more `url-pattern` values |
| `session-config` | `session-timeout` in minutes; converted to seconds, nonpositive means no timeout |

Use the existing [Vaadin descriptor](../vaadin-demo/src/main/webapp/WEB-INF/web.xml)
as a complete template. It sets production mode, disables automatic servlet
registration, maps the custom servlet to `/*`, enables async support and sets a
session timeout. Hello World uses `/` and does not need Vaadin's configuration.

Unsupported elements, duplicate scalar fields, wrong namespaces, missing
required fields, DOCTYPEs and external entities fail instead of being silently
applied. This is not a complete XML schema validator or a general servlet
container configuration implementation. Filters, listeners, welcome files,
custom MIME mappings, security constraints and session cookie configuration
are not implemented. A port requiring any of them needs deliberate host work
and tests; silently dropping configuration is not a conversion strategy.

### 5. Produce JVM and Android artifacts separately

The Maven output at `<module>/target/<name>.war` retains JVM classes and library
JARs. The generated `war_repository/<name>.war` contains Android DEX and resources
and omits JVM bytecode by default. All five build scripts accept `--include-jvm`
to retain the original JVM contents alongside DEX at that same output path.
Publish it with the catalog; Flutter APKs contain no WARs and do not run Maven or D8.
Both artifacts contain the adapted application; the official demo is not the original Spring
Boot executable JAR. Retaining a conventional WAR layout does not replace
testing a separate desktop deployment if that becomes a requirement.

For a dependency/resource-heavy app, use `package_android_war.py`. It:

- Reads application classes/resources from `WEB-INF/classes` and dependency
  JARs from `WEB-INF/lib`.
- Rejects differing duplicate classes; merges `META-INF/services/*` provider
  lists instead of overwriting them. Provider names are deduplicated and sorted;
  review frameworks that depend on provider ordering or expect only one provider.
- Gives application resources precedence. Other duplicate resources use the
  first collected entry, with dependency JARs traversed in sorted order. This
  is **not** a general merger for framework configuration files; review any
  meaningful collisions when adding dependencies.
- Excludes `module-info.class`, multi-release `META-INF/versions` entries,
  original JAR manifests and signature files from the merged Android runtime.
  A library relying on a version-specific implementation needs separate review.
- Preserves license/notice files, including per-dependency copies.
- Optionally adapts Flow's `FrontendUtils` bytecode, then runs D8 with Android's
  platform JAR and provided dependencies on the classpath.
- Packages **all** `classes*.dex` files plus classpath resources in
  `WEB-INF/android/runtime.jar`. A particular build having one DEX file is not
  a reason to remove multidex support.
- By default, removes the original `WEB-INF/classes/` and `WEB-INF/lib/` trees
  after merging their required resources into the runtime JAR. JVM `.class`
  files are not needed by WAR Runner. Web-root assets and `WEB-INF/web.xml`
  retain their paths. `--include-jvm` preserves the original WAR entries instead.
- Writes sorted ZIP entries with fixed timestamps and replaces the destination
  after successful conversion. This improves reproducibility; exact bytes also
  depend on the JDK, Maven dependencies, D8 and other build tools.

The optional `--asm-jar` switch is specifically for the pinned Vaadin 25
workaround, not a generic option for unrelated WARs. It requires
`FrontendUtils.class` with the expected virtual-thread call pattern. The Maven
build supplies ASM 9.9 as a **build tool**, not an app runtime dependency.

The small Hello servlet instead puts a single `classes.dex` at the WAR root and
uses `InMemoryDexClassLoader`. That shortcut does not supply a merged dependency
or resource classpath. Use the full runtime-JAR path for a complex application.
Its build script likewise omits `.class` files unless `--include-jvm` is passed.

### 6. Preserve resource paths and Android loading requirements

`JettyHost` verifies the selected downloaded or uploaded WAR in app-private
storage, extracts its deployment files into code-cache storage and loads its
DEX before starting Jetty. When writing `runtime.jar`,
it opens the destination, marks it read-only **before writing**, then writes
through the already-open descriptor. Android's dynamic-code-loading rules
require this ordering. On redeployment it deletes the old read-only file and
creates a fresh one.

`WarClassLoader` extends `DexClassLoader` and exposes extracted classpath
resources as `file:` URLs. Android lacks the NIO `jar:` filesystem provider used
by these libraries. Merely retaining resources inside a JAR can therefore fail
even when class loading succeeds. Path checks use canonical paths to prevent
archive entries escaping their extraction directories.

There are three distinct resource locations:

| Resource type | Location / behavior |
| --- | --- |
| Public WAR files | Web root; highest precedence |
| Public dependency/application classpath files | Extracted `META-INF/resources`, combined with the web root by `AndroidJettyRunner` |
| Private classpath files | Remain available through the class loader, e.g. source text, service-provider descriptors and internal metadata |

Expose only `META-INF/resources`, not the whole extracted classpath. The host
protects `/WEB-INF` and `/META-INF` from HTTP access. Serving library public
resources is necessary for Aura CSS and its fonts. Application CSS/Prism/icons
are also copied into the official WAR web root by Maven, replacing Spring's
resource serving. Preserve all relative CSS imports and font/icon URLs.

For the button, official and address book demos, the matching
`vaadin-prod-bundle` is copied to
`WEB-INF/classes/META-INF/VAADIN` for Vaadin's resource handling. Keep production
mode enabled. These demos use standard bundled components and plain CSS; custom
frontend modules/add-ons may require a real Vaadin frontend build instead.
Preserve theme dependencies, source-viewer text, Prism JS/CSS and upstream
notices. The upstream `vaadin-featureflags.properties` (enabling Badge) is
dropped: Badge is a standard component since Vaadin 25.2, and 25.2 logs an
"Unsupported feature flag" warning for it.

### 7. Register a new selectable WAR

Build the Android WAR into `war_repository/` and add its ID, demo name,
filename and description to `war_repository/demos.json`. Generate the size and
SHA-256 fields with `python3 scripts/generate_war_catalog.py`, then commit and
publish the WARs and catalog to the public repository as described in
[Publish](../README.md#publish). For a local trial, use the
[web admin upload workflow](../README.md#run-a-war-in-the-app) instead.
The app loads `wars.json` at startup and downloads
selected WARs into private, non-backed-up storage. `WarSpec` validates metadata
and file integrity before Jetty loads it. No Flutter selector or native enum
changes are needed. Extend device tests for the new application's routes/assets.

The host supports **one WAR at a time**, always at `/`. HTTP defaults to port
`8080`; optional HTTPS defaults to `8443`. Ports are configurable in Settings.
Jetty binds to `0.0.0.0` so browsers on the Wi-Fi / LAN can use the device's
advertised IPv4 address. Loopback `127.0.0.1:8080` remains available for the
startup health check and local clients.
Stop before switching and reload browser sessions afterwards. Lifecycle work
runs on `JettyService`'s single native worker, not Android's UI thread. The service
is started and promoted to the foreground before loading the WAR, with a
`specialUse` declaration for the user-controlled local server and an ongoing
notification with a Stop action. The activity only binds for control/status;
unbinding, activity destruction and task removal must not stop a running WAR.
When stopped or startup fails, remove the notification and the service's started
state after queued operations finish. `START_NOT_STICKY` avoids silently
restarting lost sessions after process death. Optional notification permission
denial does not block the service. Keep `Server.start`,
`stop` and `destroy` explicit; do not invoke desktop Runner `main`, `System.exit`,
or a blocking server `join` on the UI thread. Failed startup must release the
port and partially created context. The bridge handles `Throwable` because
missing Java APIs can raise linkage errors rather than ordinary exceptions.

### 8. Store persistent data in the host-provided data directory

The host clears the extracted web root, the extracted classpath and Jetty's
temporary directory (`jakarta.servlet.context.tempdir`) on **every** start, and
Android may clear the code cache they live in at any time. Anything an
application writes there, and anything it keeps in static fields or an
in-memory database, is lost on stop, restart, update and rollback.

For data that must survive, the host gives every WAR one stable directory,
`no_backup/appdata/<id>`, created before each start. It is published through
three equivalent channels, all named `warrunner.dataDirectory`:

| Channel | Type | Lookup |
| --- | --- | --- |
| `ServletContext` attribute | `java.io.File` | `context.getAttribute("warrunner.dataDirectory")` |
| Context init parameter | `String` absolute path | `context.getInitParameter("warrunner.dataDirectory")` |
| System property | `String` absolute path | `System.getProperty("warrunner.dataDirectory")`, while the WAR runs |

Resolve it once at startup, in this order, and fall back to the temporary
directory so the same WAR still runs on a JVM container:

```java
static File dataDirectory(ServletContext context) {
    Object attribute = context.getAttribute("warrunner.dataDirectory");
    if (attribute instanceof File file) return file;
    String path = context.getInitParameter("warrunner.dataDirectory");
    if (path == null) path = System.getProperty("warrunner.dataDirectory");
    if (path != null) return new File(path);
    return (File) context.getAttribute(ServletContext.TEMPDIR); // JVM fallback: not persistent
}
```

For a JVM deployment, set the same `context-param` in `web.xml` or pass
`-Dwarrunner.dataDirectory=/var/lib/myapp`. The host applies its init parameter
**after** the descriptor, so a path written for the JVM never reaches the
device; the Console notes the override.

Rules for the directory:

- Write only below it, with relative paths. Never persist absolute paths: the
  directory moves with the application ID, not with the package.
- It persists across stop, start, restart, update and rollback. Rollback does
  not restore data, so keep schema changes backward compatible or migrate on
  startup. The web admin's **Reset data** and **Delete** remove it; **Export
  data** downloads it as a ZIP with relative paths and **Import data** replaces
  it with such a ZIP, both only while the application is stopped.
- Release file and database locks in `destroy()`, so a later start, an import,
  or a reset can proceed.

The address book demo is the reference adopter. `AddressBookServlet.dataDirectory`
resolves the contract as above but falls back to `null`, and `PersonRepository`
then chooses the database:

```java
String url = dataDirectory == null
        ? "jdbc:h2:mem:addressbook;DB_CLOSE_DELAY=-1"
        : "jdbc:h2:file:" + new File(dataDirectory, "addressbook").getAbsolutePath() + ";DB_CLOSE_ON_EXIT=FALSE";
```

`DB_CLOSE_ON_EXIT=FALSE` avoids H2's shutdown hook; `destroy()` issues `SHUTDOWN`
instead, which releases the file lock. The schema is created with
`IF NOT EXISTS` and seeded only when the table is empty, so an existing database
is kept and a reset directory is seeded again. The bookstore demo still keeps
its state in memory; adopting the directory there is a follow-up.

## Failure findings and the smallest working fixes

The first nine rows below capture runtime failures or code findings from the
port/migration; the packaging and stale-asset rows describe important failure
modes enforced or implied by the current build contract.

| Symptom / finding | Cause and resolution | Where the adjustment lives |
| --- | --- | --- |
| New Jetty, but Vaadin 25 still cannot use its required servlet API | Jetty 12 has multiple EE environments. Select EE11 / Servlet 6.1, then restore upstream APIs. | Android dependencies, imports and WAR POM/descriptors |
| `NoSuchMethodError: Thread.ofVirtual` during `FrontendUtils.<clinit>` | Flow 25.1.5 creates a virtual-thread executor during static initialization, even in production. Substitute a cached platform-thread executor before D8. A later service override cannot fix this earlier failure. | `scripts/AndroidVaadinBytecode.java` |
| Vaadin service's default executor also uses virtual threads | Override `createDefaultExecutor()` with the process-owned `ForkJoinPool.commonPool()`. Do not close the shared pool on WAR stop. This is separate from the static initializer fix. | All four custom Vaadin servlets |
| `NoClassDefFoundError: java.beans.Introspector` when constructing a view | `new Grid<>(Bean.class, false)` still performs JavaBeans introspection. Use `new Grid<>()` with the existing explicit columns. Preserve renderers, filters and data. | One constructor in each of four official views |
| `BeanValidationBinder` needs JavaBeans property discovery | Bookstore uses explicit `Binder` method references and equivalent model constraints for its product/category forms. Model annotations remain; Hibernate Validator is omitted. | `ProductForm`, `AdminView` in demo 4 |
| Archive/resource access needs an unavailable ZIP filesystem | Extract with `ZipFile`; use directory resources and `file:` classpath URLs. Avoid replacing the DEX loader with a JVM `WebAppClassLoader`. | Host and `WarClassLoader` |
| Jetty desktop descriptor parsing needs `javax.xml.catalog` | That JDK API is absent on Android. Use the scoped Android DOM descriptor reader with `ServletContextHandler`. | `AndroidWebXml` |
| Missing `javax.management.DynamicMBean` while creating Jetty resources | Jetty's resource lifecycle references JMX API classes even with JMX disabled. MX4J 3.0.2 supplies them; no remote JMX server is configured. | Host runtime dependency |
| `PatternSyntaxException` around a literal closing brace | Android's ICU regex rejects two Jetty patterns accepted on a desktop JVM. Escape only the known literal braces in `ServletPathMapping` and `CustomRequestLog`. | Gradle `JettyAndroidRegexVisitor` |
| CSS request returns **200 with HTML**, theme missing | Vaadin's navigation fallback can hide a missing asset. Expose WAR-root files and dependency `META-INF/resources`, then verify CSS MIME type and body. Aura and application CSS each need checking. | Host resource base and WAR packaging |
| D8 reports conflicting classes / runtime has duplicate APIs | Review dependency ownership and provided scope; do not blindly keep the first conflicting class. Merge service-provider resources explicitly. | POM and packager |
| Source was changed, but the device still serves old behavior | Rebuild and publish the WAR plus its new checksum catalog. Reopen the app to refresh the catalog, select/download the updated demo, stop/start the server, and refresh the browser. | Build/deployment workflow |

The service virtual-thread problem was identified in the pinned Flow source;
the preceding `FrontendUtils` failure was reproduced on the emulator. API 35's
`List.getFirst()` requirement was established by inspecting Flow's code/DEX and
the Android API reference; an older-device failure was not needed to establish
that floor. Keep this distinction between observed failures and source/API
evidence when documenting new findings.

Review both bytecode workarounds on every relevant upgrade:

- The Flow transformer touches only `FrontendUtils.<clinit>` in the Android
  input and requires **four** expected call replacements. A changed count fails
  the build. The JVM WAR's original dependency JAR is untouched. Do not bypass
  the check without reading the new upstream implementation.
- The Jetty transformer is scoped to two class names and two string patterns.
  It currently has **no replacement-count assertion**; a changed class/package
  or regex can silently stop matching. Inspect the new dependency and exercise
  actual requests, not just server startup. Gradle uses ASM 9.9.1 here, separately
  from the WAR packaging tool's ASM 9.9.

Removing JVM development tools and unused commercial components reduced the
runtime surface; it did not replace missing JDK APIs. Do not assume a dependency
is safe simply because Maven or D8 accepts it. JSP, JNDI, general Spring
integration, arbitrary add-ons and production security configurations remain
outside the tested scope. Push/WebSocket behavior is not established by these
HTTP/navigation tests, even though Vaadin/Atmosphere dependencies are present.

## Verification and completion

### Public WAR build and device checks

From this repository's root, rebuild the affected WARs and regenerate the
catalog. The commands below cover all five demos; run the builds you changed:

```sh
java -version
mvn -version
bash scripts/build_war.sh
bash scripts/build_vaadin_war.sh
bash scripts/build_official_vaadin_war.sh
bash scripts/build_bookstore_war.sh
bash scripts/build_addressbook_war.sh
python3 scripts/generate_war_catalog.py
```

Use the SDK setup in the [build instructions](../README.md#build). Maven and the
`java` command used by the source-file transformer must both have a suitable
JDK available. First builds need dependency downloads.

Inspect the WAR's DEX, resources and descriptor, then upload a uniquely named
copy through the [installed app's web admin](../README.md#run-a-war-in-the-app).
Exercise the routes, assets and browser interactions listed below. Building a
WAR does not require Flutter or access to the private host tests.

### Private app checks (maintainers only)

These commands require the separate private `warrunner/` checkout. They cannot
run from the public repository. Publish the updated WARs and catalog
**before** running the app's catalog-based integration tests; those tests
download published files, not local Maven outputs. Substitute the private
checkout path and an available Android device ID:

```sh
cd /path/to/private/warrunner
flutter analyze
flutter test
(cd android && ./gradlew :app:testDebugUnitTest)
(cd android && ./gradlew :app:connectedDebugAndroidTest)
flutter test integration_test/runner_test.dart -d <android-device-id>
flutter build apk --release -t lib/main.dart
```

Normal Flutter builds omit WARs and need no Maven/Node frontend build. The app
downloads WARs at runtime.

### Runtime verification checklist

Check in increasing depth:

1. **Packaging:** Confirm DEX exists; classpath resources, service-provider files,
   theme assets and notices survived; provided container APIs were not merged.
   Compare the downloaded WAR with the hosted catalog checksum when investigating a
   stale deployment. Publish the rebuilt WAR and catalog together; verify no WAR entries remain in the APK.
2. **Native lifecycle:** Start/stop repeatedly, switch all demos, verify status
   restoration, reject switching while running, occupy the port and retry after
   failure. Background, recreate and destroy/reopen the activity with HTTP and
   an existing Vaadin session still available. Stop from the notification with
   the activity closed and verify both port release and notification removal.
   Confirm private paths return 404. The native instrumentation suite covers
   service lifecycle for all five demos; the Flutter integration suite covers
   Hello World, the button demo, the official demo and the bookstore.
3. **HTTP assets:** Check frontend script bodies, CSS `Content-Type: text/css`,
   and real content rather than HTML fallback. Check referenced fonts, icons and
   stylesheet imports when themes change. A 200 status alone is insufficient.
4. **Server-side views:** Exercise Flow's bootstrap **and** navigation RPC for
   each route/alias, with its session cookies and security token. The initial
   HTML shell does not construct every view. Assert meaningful content; some
   Java linkage errors yield an incomplete response rather than a helpful
   navigation error message.
5. **Real browser:** Verify rendering and Java/JavaScript interaction together:
   button click, grid filtering/edit dialogs, notifications, settings tabs,
   source viewer/Prism and native light/dark themes. A text-field filter may apply
   on blur according to the unchanged upstream listener configuration.
6. **Minimum API and release:** Test the declared minimum, not only the newest
   emulator. Run a release build as well; success in Flutter widget tests says
   nothing about Android DEX linkage. Release shrinking is currently disabled
   because of reflection/dynamic loading. Release signing uses the local
   `android/key.properties` file, with a debug-key fallback when that file is
   absent. Maintainers should follow the Android release signing section of
   the private app's README.

The private app's integration test documents the Flow protocol used by the pinned
version; review it on Vaadin upgrades instead of weakening assertions to accept an empty
response. App-shell styles belong in the initial HTML; the original app does
not need duplicate CSS annotations on `MainLayout` just to satisfy a test that
only inspects navigation responses.

For a browser on the development computer and native diagnostics:

```sh
adb -s <android-device-id> forward tcp:18080 tcp:8080
# Start the selected demo in Flutter; browse http://127.0.0.1:18080/.
adb -s <android-device-id> logcat -d -s FlutterJettyRunner System.err AndroidRuntime
adb -s <android-device-id> forward --remove tcp:18080
```

Expected negative integration cases also produce logged errors (occupied port,
switching while running); correlate each stack with the test action. If an
emulator cannot install due to storage, use a separate disposable instance
rather than wiping existing user data. A timeout during concurrent emulator
startup/builds is not by itself proof of an application regression; investigate
the stack and rerun on a ready, adequately provisioned device.

With the Flutter toolchain used here, Android `flutter test --release` and
`flutter drive --release` were rejected. For release checks, use
`flutter run --release -d <android-device-id>` or install the release APK and
exercise it. If a temporary Dart entry point auto-starts a demo for a smoke test,
remove it and rebuild with **`-t lib/main.dart`** before delivering the normal
APK. Do not accidentally deliver the auto-start test build.

### DEX-only distribution validation, 2026-09-25

- Rebuilt all five Android distribution WARs with the default DEX-only mode
  and regenerated catalog sizes and SHA-256 checksums. No JVM `.class` files
  remain in the distributions or their nested runtime JARs. DEX, descriptors,
  classpath resources, service providers and notices were checked.
- Built Hello World and the Official Demo with `--include-jvm` too: every
  original JVM WAR file was preserved. The Official Demo's runtime JAR was
  byte-identical in the full and DEX-only distributions. All 28 standalone
  Python tests passed, including packaging/resource and failure-atomicity checks.
- Tested the exact local distribution files in the existing WAR Runner debug
  app on `emulator-5554`, Android 17 / API 37 ARM64. ADB and the attached Flutter
  debugger invoked the normal app start/stop channel; no catalog publication or
  Web Admin password change was needed. Each WAR passed two start/stop cycles.
- HTTP and Flow RPC checks covered Hello World, frontend JavaScript, Aura/Lumo
  CSS, protected paths, two button clicks per cycle, all six Official Demo
  routes (including the dashboard alias), source-viewer resources, Bookstore
  invalid/admin/user logins and Inventory/new/Admin/About, and Address Book's
  H2 database with five seeded contacts.
- Chrome checks additionally verified Address Book rendering and saving a new
  contact, plus Bookstore login, populated inventory and the new-product form.
  The Button demo responded to a browser click, and the Official Demo rendered
  its dashboard and syntax-highlighted source viewer. These checks used the
  same emulator through ADB port forwarding.

| Distribution | Previous bytes | DEX-only bytes | Reduction |
| --- | ---: | ---: | ---: |
| Hello World | 3,933 | 2,581 | 34.4% |
| Vaadin button | 22,925,104 | 9,493,568 | 58.6% |
| Official Vaadin demo | 27,327,501 | 10,613,168 | 61.2% |
| Vaadin Bookstore | 15,645,332 | 4,769,267 | 69.5% |
| Vaadin Address Book | 27,359,822 | 10,907,611 | 60.1% |

This run validates the installed debug app on API 37. It does not repeat the
historical minimum-API or release-APK checks below.

### Recorded validation, 2026-09-17

| Check | Result / scope |
| --- | --- |
| Flutter analysis | Clean |
| Widget tests | 8 passed; UI controls, demo selection, errors and reconnect |
| Native unit tests | 4 passed; descriptor settings/mappings, versions, unsupported configuration and external entities |
| Device integration | All three WARs passed on Android 15 / API 35 ARM64, including both Vaadin views, Aura CSS, all five official routes plus dashboard alias, restart/switch and port conflict recovery |
| Release browser | Official demo on Android 17 / API 37 ARM64, viewed in desktop Chrome through local forwarding; dashboard, product filter/edit/save notification, settings tabs, source highlighting and Aura light/dark verified |
| Upstream comparison | 12 of 18 upstream Java files identical, all 12 CSS files identical; four views differ only in a Grid constructor; other changes are app bootstrap/resource I/O plus the added servlet adapter |
| Final artifact | Normal-entry release APK built; all three bundled WARs compared byte-for-byte with their current repository assets |

This is a validation record for the converted revision, not a substitute for
rerunning affected checks after changes. APKs, Maven `target` directories and
comparison checkouts are generated outputs. The current public repository tracks
sources, build scripts, documentation, catalog metadata and the distribution
WAR binaries in `war_repository/`. Publish updated binaries and their checksum
catalog together. An edit to the official demo's own README also changes packaged resources because that POM embeds it.

### Demo 4 validation, 2026-09-17

- Bookstore is pinned to `a86110dab30778dac47ed5eeed7c606ee67937c3`, with
  Vaadin platform 25.2.8 / Flow 25.2.9. Its complete production frontend is built
  by Maven. 29 of 31 upstream application files are byte-identical; only
  `ProductForm` and `AdminView` change for Android's explicit bindings. The
  servlet adapter and descriptor are additions.
- Flutter analysis is clean, all 10 widget tests pass, and native descriptor
  tests pass. The complete four-WAR integration suite passes on a disposable
  Android 15 / API 35 ARM64 emulator, including two deployment cycles per demo,
  frontend/theme resources, private-path protection, port-conflict recovery,
  bookstore invalid/valid logins, administrator and browsing sessions, inventory,
  new-product form construction, Admin and About.
- A release bookstore build was checked in Chrome through ADB forwarding on
  Android 17 / API 37 ARM64: original Lumo/CSS and inventory rendering, login,
  product name/price/stock validation, product creation/filtering/reopening/update,
  category validation/create/delete, About and logout. The browsing login hides
  Admin and disables New product, matching upstream's sample role behavior.
- The normal-entry release APK was rebuilt and installed after removing the
  temporary browser-test entry point. All four WAR entries match their repository
  assets byte-for-byte.
- The existing Android 15 AVD was full; no user data was cleared. A fresh
  temporary AVD provided the successful minimum-API test.

### Foreground service validation, 2026-09-17

- `JettyService` now owns Jetty independently of the Flutter activity. Its
  started foreground lifetime survives activity backgrounding, recreation and
  destruction; the activity only binds for control and live state updates.
- Both lifecycle instrumentation tests pass on Android 15 / API 35 and Android
  17 / API 37 ARM64. They verify HTTP and an existing Vaadin UI session across
  background/recreate/destroy/reopen, the foreground notification, Stop through
  its actual PendingIntent with the activity closed, and occupied-port cleanup
  followed by a successful retry.
- Flutter analysis and all 11 widget tests pass, including notification-driven
  stopped-state updates. The four native descriptor tests pass. The full
  four-WAR Flutter integration suite also passes on Android 17 through the new
  service. The normal-entry release APK is rebuilt with all four original WARs.
- Debug-only AndroidX test versions are aligned with the instrumentation APK:
  Flutter's integration-test plugin also exports older test dependencies in the
  app runtime, which otherwise conflicts with AGP's consistent resolution.

## Handoff checklist for the next contributor or agent

- Read this guide, current build files and the affected servlet adapter first.
- Pin and compare upstream before editing; preserve styles, sample data,
  constructors and routes unless a demonstrated incompatibility requires a change.
- Classify each failure as build tooling, host/servlet environment, Android Java
  API, class/resource loading, framework bootstrap, or application behavior.
- For each remaining adaptation, record the exact version, triggering code or
  error, evidence, location of the fix and verification. Mark untested features
  explicitly rather than treating compilation as runtime support.
- Prefer the supported framework hook, then the Android packaging boundary;
  keep any unavoidable library transform narrow and version-reviewed.
- Rebuild affected assets, exercise real routes/assets/interactions, and deliver
  the normal app entry point. Recheck upstream diffs and remove obsolete shims.
- Keep this guide's matrix and validation scope aligned with the implementation.

## Primary references

- [Android started and bound services](https://developer.android.com/develop/background-work/services/bound-services)
- [Foreground service launch](https://developer.android.com/develop/background-work/services/fgs/launch)
- [Special-use foreground services](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use)
- [Notification permission and foreground services](https://developer.android.com/develop/ui/compose/notifications/notification-permission)

- [Jetty 12.1 EE11 changes](https://jetty.org/docs/jetty/12.1/programming-guide/migration/12.0-to-12.1.html)
- [Vaadin 25 upgrade requirements](https://vaadin.com/docs/latest/upgrading)
- [Vaadin application lifecycle](https://vaadin.com/docs/latest/flow/advanced/application-lifecycle)
- [Android `List.getFirst()` API level](https://developer.android.com/reference/java/util/List#getFirst())
- [Android dynamic code loading requirements](https://developer.android.com/about/versions/14/behavior-changes-14#safer-dynamic-code-loading)
- [Android `DexClassLoader`](https://developer.android.com/reference/dalvik/system/DexClassLoader)

The linked product documentation can evolve. For version-specific behavior,
inspect the source of the dependency actually resolved by the POM/Gradle build.

## Downloadable WAR distribution (September 2026)

The earlier verification records above describe builds that bundled WAR assets.
The current APK contains no WARs. Its catalog is fetched from
`https://raw.githubusercontent.com/pezi/war_runner/refs/heads/main/war_repository/wars.json`; binaries live beside it.
App startup fetches/caches metadata, selection downloads missing files with progress
and cancellation, and the service starts verified private-storage files. The
runner accepts catalog-defined demos without a hard-coded enum. See the
[WAR repository README](../README.md#publish) for publishing and offline behavior.
Device tests need internet access for their first download; the app can reuse
its last valid catalog and matching downloaded WARs afterward.
