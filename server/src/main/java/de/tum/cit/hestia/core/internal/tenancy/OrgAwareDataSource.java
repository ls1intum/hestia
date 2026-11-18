package de.tum.cit.hestia.core.internal.tenancy;

import com.zaxxer.hikari.HikariDataSource;
import de.tum.cit.hestia.core.OrgContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * The application's connection pool. Every connection handed out carries the current organisation
 * in the session setting {@code hestia.org_id}, which the row-level security policies of every
 * table compare with {@code org_id}; without an organisation the setting is empty and no row is
 * visible. A subclass of the pool rather than a wrapper, so Spring Boot still recognises it.
 */
public class OrgAwareDataSource extends HikariDataSource {

    @Override
    public Connection getConnection() throws SQLException {
        return withOrganisation(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return withOrganisation(super.getConnection(username, password));
    }

    private static Connection withOrganisation(Connection connection) throws SQLException {
        String orgId = OrgContext.current().map(UUID::toString).orElse("");
        try (PreparedStatement statement = connection.prepareStatement("select set_config('hestia.org_id', ?, false)")) {
            statement.setString(1, orgId);
            statement.execute();
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }
}
