package com.example.requestlog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class QuestionRepository {

  private static final String COLUMNS = "id, text, created_at";

  private final JdbcClient jdbc;

  QuestionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Inserts the question, or returns the stored one if the same text already exists (also when it
   * is inserted concurrently: {@code ON CONFLICT DO NOTHING} keeps the PostgreSQL transaction
   * usable, where a caught duplicate-key error would abort it).
   */
  @Transactional
  Question save(String text) {
    Optional<Question> existing = findByText(text);
    if (existing.isPresent()) {
      return existing.get();
    }
    jdbc.sql("INSERT INTO question_catalog (text) VALUES (:text) ON CONFLICT DO NOTHING")
        .param("text", text)
        .update();
    return findByText(text).orElseThrow();
  }

  Optional<Question> findByText(String text) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM question_catalog WHERE text = :text")
        .param("text", text)
        .query(QuestionRepository::map)
        .optional();
  }

  List<Question> findAll() {
    return jdbc.sql("SELECT " + COLUMNS + " FROM question_catalog ORDER BY id")
        .query(QuestionRepository::map)
        .list();
  }

  private static Question map(ResultSet rs, int row) throws SQLException {
    return new Question(
        rs.getLong("id"), rs.getString("text"), rs.getTimestamp("created_at").toInstant());
  }
}
