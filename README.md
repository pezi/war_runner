# WAR Runner demo applications and build tools

![WAR Runner](images/intro.jpg "WAR Runner")

[![Get it on Google Play](https://img.shields.io/badge/Google_Play-Download-27875F?logo=google-play&logoColor=white)](https://play.google.com/store/apps/details?id=app.flutterdev.jettyrunner)

This public repository, [pezi/war_runner](https://github.com/pezi/war_runner),
contains the demo application sources, Android WAR packaging tools, conversion
guide, and downloadable WAR catalog for **WAR Runner**.

**The WAR Runner Flutter/Android app is a separate, non public project. Its source
and app tests are not included here.** You can build the demo WARs from this
repository without the app source or Flutter. Running them on Android requires
the WAR Runner app on Android **15+ (API 35+)**. The app embeds Jetty
**12.1.13 EE11 / Servlet 6.1** and downloads WARs at runtime; its APK contains
no WAR files.

```text
├── servlet/                # Hello World servlet
├── vaadin-demo/            # Vaadin button demo
├── vaadin-official-demo/    # Official Vaadin demo port
├── vaadin-bookstore-demo/   # Vaadin Bookstore port
├── vaadin-addressbook-demo/ # Address book on an in-memory H2 database
├── scripts/                # Build, DEX packaging and catalog generation
├── docs/WAR_CONVERSION.md  # Conversion contract and validation history
└── war_repository/         # demos.json, generated wars.json and Android WARs
```

## Build

Clone the public repository and run the commands below from its root (the
directory containing this README):

```sh
git clone https://github.com/pezi/war_runner.git
cd war_runner
```

The maintainer's local checkout is named `warrunner_github/`; the directory name
does not affect the build.

Requirements: Maven, JDK **21+** for Vaadin (**11+** for Hello World), Python
**3.9+**, Android SDK Build-Tools **36.0.0**, and Android SDK Platform **36**.
The bookstore also builds its production frontend and needs Node.js/npm;
Vaadin installs a supported Node version if necessary.

Set the Android SDK location for a standalone checkout:

```sh
export ANDROID_SDK_ROOT=/absolute/path/to/your/android-sdk
```

The build scripts use `ANDROID_SDK_ROOT`, then `ANDROID_HOME`. For maintainers
with the private app checked out alongside this repository, they also accept
`sdk.dir` in `../warrunner/android/local.properties` as a fallback. That private
checkout is optional.

`ANDROID_BUILD_TOOLS` and `ANDROID_COMPILE_API` overrides select other installed
tool/platform versions; `ANDROID_COMPILE_API` applies to the Vaadin packager.

Build whichever demos you need, then regenerate the catalog:

```sh
bash scripts/build_war.sh                  # Hello World
bash scripts/build_vaadin_war.sh           # Vaadin button
bash scripts/build_official_vaadin_war.sh  # Official Vaadin demo
bash scripts/build_bookstore_war.sh        # Vaadin Bookstore
bash scripts/build_addressbook_war.sh      # Vaadin Address Book (H2)
python3 scripts/generate_war_catalog.py
```

Scripts locate sources and outputs relative to their own location. From another
directory, invoke the script by its path, for example:

```sh
bash /path/to/war_runner/scripts/build_war.sh
```

| Application | Standard JVM WAR | Android distribution WAR |
| --- | --- | --- |
| Hello World | `servlet/target/hello.war` | `war_repository/hello.war` |
| Vaadin button | `vaadin-demo/target/vaadin-demo.war` | `war_repository/vaadin-demo.war` |
| Official Vaadin demo | `vaadin-official-demo/target/vaadin-official-demo.war` | `war_repository/vaadin-official-demo.war` |
| Vaadin Bookstore | `vaadin-bookstore-demo/target/vaadin-bookstore-demo.war` | `war_repository/vaadin-bookstore-demo.war` |
| Vaadin Address Book | `vaadin-addressbook-demo/target/vaadin-addressbook-demo.war` | `war_repository/vaadin-addressbook-demo.war` |

The Android distribution WAR is **DEX-only by default**: it contains executable
Android bytecode, required resources and `WEB-INF/web.xml`, without the original
JVM `.class` files or dependency JARs. WAR Runner does not execute JVM bytecode.
The Maven WAR in `target/` always retains the conventional JVM contents.

DEX is stored differently in the two packaging layouts:

```text
hello.war
└── classes.dex

vaadin-*.war
└── WEB-INF/android/runtime.jar
    ├── classes.dex              # classes2.dex, etc. when needed
    └── ...                      # merged classpath resources and notices
```

For example, open `WEB-INF/android/runtime.jar` inside
`vaadin-official-demo.war` as a ZIP archive to find its DEX. Unpacking just the
outer WAR does not reveal a loose `.dex` file. Vaadin application resources,
dependency resources, service-provider files, frontend assets and license
notices are merged into this runtime JAR; the redundant `WEB-INF/classes/` and
`WEB-INF/lib/` trees are omitted from the default Android WAR.

Pass **`--include-jvm`** to any of the five build scripts to retain the original
JVM contents alongside DEX in the Android distribution WAR:

```sh
bash scripts/build_war.sh --include-jvm
bash scripts/build_official_vaadin_war.sh --include-jvm
```

This writes to the same `war_repository/` output path. Run the script without
the option to restore the smaller DEX-only build, then regenerate the catalog.
The Python Vaadin packager also accepts `--include-jvm` when called directly.

`package_android_war.py` and its adjacent `AndroidVaadinBytecode.java` package
the Vaadin runtime and compatibility fixes.
Read the [conversion guide](docs/WAR_CONVERSION.md) before adding another WAR or
upgrading dependencies. Arbitrary desktop WARs need Android conversion first.

## Run a WAR in the app

For the published demos, open **Web Server** in WAR Runner, select a demo, let
it download, and tap **Start servlet**. Open the displayed address in a browser
on the phone or on the same Wi-Fi / LAN. Only one WAR runs at a time; stop it
before switching. HTTP uses port **8080** by default; optional HTTPS uses
**8443** with a self-signed certificate. Ports can be changed in Settings.

To try a locally built WAR without publishing it, enable **Web admin** in the
app's Settings with a password, open its HTTPS address (default port **9443**),
and upload the Android distribution WAR. Give the uploaded file a unique name,
such as `my-vaadin-demo.war`: catalog filenames and existing upload filenames
are reserved. Start the uploaded application from the web admin or the app.

Bookstore sample logins are **admin / admin** for editing and **user / user**
for browsing. Bookstore changes and address book contacts are kept in memory
and reset when their WAR is restarted.

## Deploy from the command line

Use the public Python CLI to upload a locally built Android WAR, start it,
check readiness, and retrieve logs from the installed app:

```sh
python3 scripts/war_runner.py --url https://192.168.1.42:9443 --insecure \
  deploy war_repository/hello.war
```

Enable web admin in the app first; the CLI prompts for its password. This
example accepts the app's self-signed certificate for a trusted development
connection. Python 3.9+ is sufficient, with no third-party packages or private
app source. See the [deployment CLI guide](docs/DEPLOYMENT_CLI.md) for certificate
pinning, ADB forwarding, repeated deployments, `status`, and `logs --follow`.

## Publish

The app and web admin fetch
[`war_repository/wars.json`](https://raw.githubusercontent.com/pezi/war_runner/refs/heads/main/war_repository/wars.json)
and the WAR binaries beside it from the public repository's `main` branch.

- `war_repository/demos.json` is the editable list of IDs, names, filenames and
  descriptions. It is input to the generator, not the app's download catalog.
- `war_repository/wars.json` is the generated catalog with sizes and SHA-256
  checksums.
- `war_repository/*.war` are the Android-compatible distribution binaries.

For maintainers publishing an update:

1. Build the affected WARs and update `demos.json` if their metadata changes.
2. Run `python3 scripts/generate_war_catalog.py`. Every WAR listed in
   `demos.json` must exist; the checkout already includes the published binaries.
3. Review the changes and commit the updated WARs, metadata and generated
   catalog together to the public repository, then push to its `main` branch.
   A push to a separate private development repository does not publish them.
4. Use **Refresh demos** in the app, download the update, and stop/start the WAR.
   GitHub's raw endpoint may cache files, so updates can take time to appear.

Publishing uses Git; there is no `scripts/publish_wars.sh` in this repository.
Catalog additions need no app rebuild if the WAR follows the supported
conversion contract. If the catalog is unavailable, the app can reuse its last
valid catalog and matching downloaded WARs offline.

### Sync from the private workspace

When maintaining a private `warrunner_github/` checkout with the public Git
checkout inside `github/`, run this from the private repository root:

```sh
bash scripts/sync_github.sh --dry-run  # Preview copies, updates and deletions
bash scripts/sync_github.sh            # Apply the sync to github/
git -C github status --short
git -C github diff
```

The script resolves both directories from its own location, so it can also be
invoked by its path from another working directory. It requires Bash, Git,
rsync and an existing, separate Git checkout at `github/`.

It copies tracked working files, including uncommitted edits, and new files
that Git does not ignore. This includes the WAR binaries and `.gitignore`.
Ignored, untracked build outputs are omitted; tracked files remain included
even if an ignore rule matches them. The nested `github/` directory is always
excluded from the source. Destination files absent from this selection are
removed, and source versions replace edits made only in the public checkout.
The public checkout's `.git` metadata is preserved.

The script does not build WARs, regenerate `wars.json`, commit or push. Generate
the catalog before syncing changed binaries, then review and publish the public
checkout separately. This workflow is for the private workspace; a standalone
public checkout does not need another nested `github/` directory.

## Validation

The [conversion guide](docs/WAR_CONVERSION.md#verification-and-completion)
describes packaging checks, device/browser checks and historical results.
Flutter, Gradle and app integration test commands in that guide are explicitly
for maintainers with the private app source. Public contributors can build
WARs here and check their behavior using the installed app's web admin.

The packaging tools and deployment CLI have standalone tests:

```sh
python3 -m unittest discover -s tests -v
```

They cover both packaging modes, resource preservation, failed-conversion
handling and the deployment protocol. They need Python and OpenSSL, but no
Flutter, Android SDK, or app source.
