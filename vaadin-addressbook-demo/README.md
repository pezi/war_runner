# Vaadin Address Book demo

A small contact manager: search, add, edit and delete people. No login. The
data lives in an **H2 database file inside the web app's persistent data
directory** that WAR Runner provides, so contacts survive stop, start, update
and rollback. The first start, and every start after **Reset data** in the web
admin, seeds five demo contacts. On a JVM container without that directory the
database is in memory and lasts as long as the web app runs.

## Persistence without Spring

The [Vaadin persistence guide](https://vaadin.com/docs/latest/building-apps/forms-data/persistence/add-spring-data)
stores the same kind of `Person` through Spring Data JPA. Spring Boot and
Hibernate generate classes at run time and scan the classpath, which Android's
DEX runtime does not support, so this demo keeps the guide's shape and swaps
the implementation:

| Guide | This demo |
| --- | --- |
| `@Entity Person` | `Person` bean with explicit getters and setters |
| `PersonRepository extends JpaRepository` | `PersonRepository` over plain JDBC (`find`, `search`, `save`, `delete`) |
| Spring constructor injection into the view | `AddressBookServlet` creates one repository and hands it to `AddressBookView` through a custom `Instantiator` |
| `application.properties` data source | `jdbc:h2:file:<data directory>/addressbook` opened with `org.h2.Driver.connect`; `jdbc:h2:mem:addressbook` without a data directory |

The H2 connection is opened through the driver directly. `DriverManager` and
H2's JNDI-backed data sources are avoided because `javax.naming` is missing on
Android. The form uses explicit `Binder` bindings instead of
`Binder(Person.class)` because Android has no `java.beans.Introspector`.

## Persistent data directory

`AddressBookServlet.dataDirectory` resolves WAR Runner's
[storage contract](../docs/WAR_CONVERSION.md#8-store-persistent-data-in-the-host-provided-data-directory):
the `warrunner.dataDirectory` servlet context attribute, then the context init
parameter, then the system property. `PersonRepository` opens
`addressbook.mv.db` below it with `DB_CLOSE_ON_EXIT=FALSE` and closes the
database with `SHUTDOWN` in `destroy()`, which releases H2's file lock so the
host can import, reset or start again. The table is created with
`IF NOT EXISTS` and seeded only when empty, so existing contacts are kept. The
web admin's **Export data** downloads the database file; **Import data**
restores one.

## Build

From the public repository root, using the [shared build requirements](../README.md#build):

```sh
bash scripts/build_addressbook_war.sh
python3 scripts/generate_war_catalog.py
```

The WAR uses Vaadin 25.2.8 with the pre-built production bundle and the Aura
theme, like the button demo.
