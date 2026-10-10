package app.flutterdev.addressbook;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * Contacts in an H2 database, accessed over plain JDBC.
 * <p>
 * The Vaadin documentation persists the same model through Spring Data JPA. Spring and
 * Hibernate generate classes at run time, which Android's DEX runtime cannot load, so this
 * demo keeps the repository shape (find, search, save, delete) and talks to H2 directly.
 * One connection is shared and every call is synchronized.
 * <p>
 * On WAR Runner the database file lives in the web app's persistent data directory, so
 * contacts survive stop, start, update and rollback. The host's "Reset data" action empties
 * the directory and the next start seeds the five demo people again. Without a data directory,
 * on a plain JVM container, the database is in memory and lives as long as the web app runs.
 */
public final class PersonRepository implements AutoCloseable {
    private static final String MEMORY_URL = "jdbc:h2:mem:addressbook;DB_CLOSE_DELAY=-1";
    private final Connection connection;
    private final String location;

    /** Opens the database in {@code dataDirectory}, or in memory when it is {@code null}. */
    public PersonRepository(File dataDirectory) {
        String url;
        if (dataDirectory == null) {
            url = MEMORY_URL;
            location = "in memory";
        } else {
            // One MVStore file below the data directory; the host supplies an absolute path.
            // No shutdown hook: the servlet closes the database in destroy().
            url = "jdbc:h2:file:" + new File(dataDirectory, "addressbook").getAbsolutePath() + ";DB_CLOSE_ON_EXIT=FALSE";
            location = dataDirectory.getAbsolutePath();
        }
        try {
            // Driver.connect avoids DriverManager and the JNDI-backed H2 data sources,
            // neither of which is available on Android.
            connection = org.h2.Driver.load().connect(url, new Properties());
            createSchema();
            if (count() == 0) {
                seed();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot open the address book database " + location, failure);
        }
    }

    /** Where the database lives, for the startup log. */
    public String location() {
        return location;
    }

    private void createSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS person (
                        id IDENTITY PRIMARY KEY,
                        first_name VARCHAR(80) NOT NULL,
                        last_name VARCHAR(80) NOT NULL,
                        email VARCHAR(160) NOT NULL,
                        phone VARCHAR(40) NOT NULL,
                        street VARCHAR(120) NOT NULL,
                        postal_code VARCHAR(20) NOT NULL,
                        city VARCHAR(80) NOT NULL
                    )""");
        }
    }

    private void seed() throws SQLException {
        String[][] people = {
            {"Anna", "Berger", "anna.berger@example.com", "+43 660 123 4567", "Mariahilfer Straße 12", "1060", "Wien"},
            {"Lukas", "Hofer", "lukas.hofer@example.com", "+43 664 987 6543", "Landstraße 45", "4020", "Linz"},
            {"Maria", "Rossi", "maria.rossi@example.com", "+39 333 123 4567", "Via Roma 8", "20121", "Milano"},
            {"Jonas", "Schmidt", "jonas.schmidt@example.com", "+49 151 2345 6789", "Hauptstraße 3", "80331", "München"},
            {"Emma", "Novak", "emma.novak@example.com", "+420 601 234 567", "Vodičkova 21", "110 00", "Praha"},
        };
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO person (first_name, last_name, email, phone, street, postal_code, city) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            for (String[] person : people) {
                for (int column = 0; column < person.length; column++) {
                    insert.setString(column + 1, person[column]);
                }
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    public synchronized long count() {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM person")) {
            rows.next();
            return rows.getLong(1);
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot count contacts", failure);
        }
    }

    public synchronized List<Person> findAll() {
        return search("");
    }

    /** Contacts whose name, email, phone or address contains {@code query}, ordered by name. */
    public synchronized List<Person> search(String query) {
        String pattern = "%" + query.trim().toLowerCase() + "%";
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT id, first_name, last_name, email, phone, street, postal_code, city FROM person
                WHERE LOWER(first_name || ' ' || last_name) LIKE ? OR LOWER(email) LIKE ? OR LOWER(phone) LIKE ?
                   OR LOWER(street) LIKE ? OR LOWER(postal_code) LIKE ? OR LOWER(city) LIKE ?
                ORDER BY last_name, first_name""")) {
            for (int parameter = 1; parameter <= 6; parameter++) {
                select.setString(parameter, pattern);
            }
            try (ResultSet rows = select.executeQuery()) {
                List<Person> people = new ArrayList<>();
                while (rows.next()) {
                    people.add(read(rows));
                }
                return people;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot search contacts", failure);
        }
    }

    public synchronized Optional<Person> findById(long id) {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, first_name, last_name, email, phone, street, postal_code, city FROM person WHERE id = ?")) {
            select.setLong(1, id);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot load contact " + id, failure);
        }
    }

    /** Inserts a new contact or updates an existing one and returns it with its id set. */
    public synchronized Person save(Person person) {
        try {
            if (person.isNew()) {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO person (first_name, last_name, email, phone, street, postal_code, city) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    bind(insert, person);
                    insert.executeUpdate();
                    try (ResultSet keys = insert.getGeneratedKeys()) {
                        keys.next();
                        person.setId(keys.getLong(1));
                    }
                }
            } else {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE person SET first_name = ?, last_name = ?, email = ?, phone = ?, street = ?, postal_code = ?, city = ? WHERE id = ?")) {
                    bind(update, person);
                    update.setLong(8, person.getId());
                    update.executeUpdate();
                }
            }
            return person;
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot save contact", failure);
        }
    }

    public synchronized void delete(Person person) {
        if (person.isNew()) {
            return;
        }
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM person WHERE id = ?")) {
            delete.setLong(1, person.getId());
            delete.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot delete contact", failure);
        }
    }

    private static void bind(PreparedStatement statement, Person person) throws SQLException {
        statement.setString(1, person.getFirstName().trim());
        statement.setString(2, person.getLastName().trim());
        statement.setString(3, person.getEmail().trim());
        statement.setString(4, person.getPhone().trim());
        statement.setString(5, person.getStreet().trim());
        statement.setString(6, person.getPostalCode().trim());
        statement.setString(7, person.getCity().trim());
    }

    private static Person read(ResultSet rows) throws SQLException {
        return new Person(rows.getLong("id"), rows.getString("first_name"), rows.getString("last_name"),
                rows.getString("email"), rows.getString("phone"), rows.getString("street"),
                rows.getString("postal_code"), rows.getString("city"));
    }

    @Override
    public synchronized void close() {
        try (Statement statement = connection.createStatement()) {
            // Closes the database and releases its file lock, so a later start, import or
            // reset can use the data directory. An in-memory database is dropped.
            statement.execute("SHUTDOWN");
        } catch (SQLException ignored) {
            // The connection is closed either way.
        }
    }
}
