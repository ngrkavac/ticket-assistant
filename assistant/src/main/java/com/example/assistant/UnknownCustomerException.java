package com.example.assistant;

/** The request names a customer the ticket server does not know. Mapped to 400. */
class UnknownCustomerException extends RuntimeException {

  UnknownCustomerException() {
    super("Unknown customer");
  }
}
