package com.example.tickets;

/** A support ticket as returned by the tools. The field order is part of the tool contract. */
public record Ticket(String id, String customer, String subject, String status, String priority) {

  /** The external id of ticket number {@code n}, e.g. {@code T-1001}. */
  static String formatId(long number) {
    return "T-" + number;
  }
}
