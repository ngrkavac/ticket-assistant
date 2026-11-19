package com.example.requestlog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class RequestLogRepository {

  private static final String COLUMNS =
      "id, request_id, question, answer, status, error_type, duration_ms, customer, created_at";

  private final JdbcClient jdbc;

  RequestLogRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Inserts the entry. Idempotent per request id: a retried call returns the row that is already
   * stored instead of failing. Uses {@code ON CONFLICT DO NOTHING} rather than catching a
   * duplicate-key error, because on PostgreSQL a failed statement aborts the whole transaction (no
   * conflict target, so the same SQL also runs on H2 in the local profile).
   */
  @Transactional
  RequestLogEntry save(
      String requestId,
      String question,
      String answer,
      String status,
      String errorType,
      long durationMs,
      String customer) {
    jdbc.sql(
            """
            INSERT INTO request_log
              (request_id, question, answer, status, error_type, duration_ms, customer)
            VALUES
              (:requestId, :question, :answer, :status, :errorType, :durationMs, :customer)
            ON CONFLICT DO NOTHING
            """)
        .param("requestId", requestId)
        .param("question", question)
        .param("answer", answer)
        .param("status", status)
        .param("errorType", errorType)
        .param("durationMs", durationMs)
        .param("customer", customer)
        .update();
    return findByRequestId(requestId).orElseThrow();
  }

  Optional<RequestLogEntry> findByRequestId(String requestId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM request_log WHERE request_id = :requestId")
        .param("requestId", requestId)
        .query(RequestLogRepository::map)
        .optional();
  }

  /** Newest first; only the given customer's requests when customer is not null. */
  List<RequestLogEntry> findRecent(int limit, String customer) {
    String where = customer == null ? "" : " WHERE customer = :customer";
    var sql =
        jdbc.sql(
                "SELECT "
                    + COLUMNS
                    + " FROM request_log"
                    + where
                    + " ORDER BY created_at DESC, id DESC LIMIT :limit")
            .param("limit", limit);
    if (customer != null) {
      sql = sql.param("customer", customer);
    }
    return sql.query(RequestLogRepository::map).list();
  }

  private static RequestLogEntry map(ResultSet rs, int row) throws SQLException {
    Timestamp created = rs.getTimestamp("created_at");
    return new RequestLogEntry(
        rs.getLong("id"),
        rs.getString("request_id"),
        rs.getString("question"),
        rs.getString("answer"),
        rs.getString("status"),
        rs.getString("error_type"),
        rs.getLong("duration_ms"),
        rs.getString("customer"),
        created.toInstant());
  }
}
