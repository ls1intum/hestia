package app;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises the V15 -> V16 data normalization against a real legacy schema. */
class MigrationV16IT extends AbstractIntegrationTest {

    @Test
    void migrationKeepsNewestAnswerRehomesGradesAndRemovesUnattachableGrades() throws Exception {
        String database = "migration_v16_" + UUID.randomUUID().toString().replace("-", "");
        String adminUrl = POSTGRES.getJdbcUrl();
        String migrationUrl = "jdbc:postgresql://" + POSTGRES.getHost() + ":"
            + POSTGRES.getMappedPort(5432) + "/" + database;

        try (Connection admin = connection(adminUrl); Statement statement = admin.createStatement()) {
            statement.execute("create database " + database);
        }

        try {
            Flyway.configure()
                .dataSource(migrationUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("15")
                .load()
                .migrate();

            UUID examId = UUID.randomUUID();
            UUID answeredTaskId = UUID.randomUUID();
            UUID unansweredTaskId = UUID.randomUUID();
            UUID olderAnswerId = UUID.randomUUID();
            UUID newerAnswerId = UUID.randomUUID();
            UUID attachedGradeId = UUID.randomUUID();
            UUID unattachedGradeId = UUID.randomUUID();

            try (Connection connection = connection(migrationUrl)) {
                insertLegacyRows(connection, examId, answeredTaskId, unansweredTaskId,
                    olderAnswerId, newerAnswerId, attachedGradeId, unattachedGradeId);
            }

            Flyway.configure()
                .dataSource(migrationUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();

            try (Connection connection = connection(migrationUrl)) {
                assertThat(answerIds(connection, answeredTaskId)).containsExactly(newerAnswerId);
                assertThat(singleUuid(connection,
                    "select answer_id from task_grades where id = ?", attachedGradeId))
                    .isEqualTo(newerAnswerId);
                assertThat(count(connection,
                    "select count(*) from task_grades where id = ?", unattachedGradeId)).isZero();

                assertThatThrownBy(() -> insertAnswer(connection, UUID.randomUUID(), answeredTaskId,
                    examId, OffsetDateTime.now().plusSeconds(1)))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo("23505");

                try (PreparedStatement delete = connection.prepareStatement(
                    "delete from task_answers where id = ?")) {
                    delete.setObject(1, newerAnswerId);
                    delete.executeUpdate();
                }
                assertThat(count(connection,
                    "select count(*) from task_grades where id = ?", attachedGradeId)).isZero();
            }
        } finally {
            try (Connection admin = connection(adminUrl); Statement statement = admin.createStatement()) {
                statement.execute("drop database if exists " + database + " with (force)");
            }
        }
    }

    private static Connection connection(String url) throws SQLException {
        return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void insertLegacyRows(
        Connection connection, UUID examId, UUID answeredTaskId, UUID unansweredTaskId,
        UUID olderAnswerId, UUID newerAnswerId, UUID attachedGradeId, UUID unattachedGradeId
    ) throws SQLException {
        try (PreparedStatement exam = connection.prepareStatement(
            "insert into exams (id, owner_id, status, source) values (?, ?, 'grading', 'manual')")) {
            exam.setObject(1, examId);
            exam.setObject(2, app.shared.DefaultUser.ID);
            exam.executeUpdate();
        }
        try (PreparedStatement task = connection.prepareStatement(
            "insert into tasks (id, exam_id, position, type) values (?, ?, ?, 'text')")) {
            task.setObject(1, answeredTaskId);
            task.setObject(2, examId);
            task.setInt(3, 0);
            task.executeUpdate();
            task.setObject(1, unansweredTaskId);
            task.setInt(3, 1);
            task.executeUpdate();
        }
        insertAnswer(connection, olderAnswerId, answeredTaskId, examId,
            OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        insertAnswer(connection, newerAnswerId, answeredTaskId, examId,
            OffsetDateTime.parse("2026-01-02T00:00:00Z"));
        try (PreparedStatement grade = connection.prepareStatement(
            "insert into task_grades (id, task_id, exam_id, score) values (?, ?, ?, 1)")) {
            grade.setObject(1, attachedGradeId);
            grade.setObject(2, answeredTaskId);
            grade.setObject(3, examId);
            grade.executeUpdate();
            grade.setObject(1, unattachedGradeId);
            grade.setObject(2, unansweredTaskId);
            grade.executeUpdate();
        }
    }

    private static void insertAnswer(Connection connection, UUID answerId, UUID taskId,
                                     UUID examId, OffsetDateTime createdAt) throws SQLException {
        try (PreparedStatement answer = connection.prepareStatement("""
            insert into task_answers (id, task_id, exam_id, provider, model, created_at)
            values (?, ?, ?, 'openai', 'gpt-5.5', ?)
            """)) {
            answer.setObject(1, answerId);
            answer.setObject(2, taskId);
            answer.setObject(3, examId);
            answer.setObject(4, createdAt);
            answer.executeUpdate();
        }
    }

    private static java.util.List<UUID> answerIds(Connection connection, UUID taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select id from task_answers where task_id = ? order by created_at")) {
            statement.setObject(1, taskId);
            try (ResultSet rows = statement.executeQuery()) {
                java.util.ArrayList<UUID> ids = new java.util.ArrayList<>();
                while (rows.next()) ids.add(rows.getObject(1, UUID.class));
                return ids;
            }
        }
    }

    private static UUID singleUuid(Connection connection, String sql, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getObject(1, UUID.class);
            }
        }
    }

    private static long count(Connection connection, String sql, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }
}
