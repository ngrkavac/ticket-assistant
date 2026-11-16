package com.example.requestlog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class RequestLogRepository {

  private static final String COLUMNS =
      "id, request_id, question, answer, status, error_type, duration_ms, created_at";

  private final JdbcClient jdbc;

  RequestLogRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Inserts the entry. Idempotent per request id: a retried call returns the row that is already
   * stored instead of failing.
   */
  @Transactional
  RequestLogEntry save(
      String requestId,
      String question,
      String answer,
      String status,
      String errorType,
      long durationMs) {
    try {
      jdbc.sql(
              """
              INSERT INTO request_log (request_id, question, answer, status, error_type, duration_ms)
              VALUES (:requestId, :question, :answer, :status, :errorType, :durationMs)
              """)
          .param("requestId", requestId)
          .param("question", question)
          .param("answer", answer)
          .param("status", status)
          .param("errorType", errorType)
          .param("durationMs", durationMs)
          .update();
    } catch (DuplicateKeyException e) {
      // already stored by an earlier attempt
    }
    return findByRequestId(requestId).orElseThrow();
  }

  Optional<RequestLogEntry> findByRequestId(String requestId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM request_log WHERE request_id = :requestId")
        .param("requestId", requestId)
        .query(RequestLogRepository::map)
        .optional();
  }

  List<RequestLogEntry> findRecent(int limit) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM request_log ORDER BY created_at DESC, id DESC LIMIT :limit")
        .param("limit", limit)
        .query(RequestLogRepository::map)
        .list();
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
        created.toInstant());
  }
}
