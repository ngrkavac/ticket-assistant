package com.example.assistant;

import org.springframework.security.access.AccessDeniedException;

/** The caller's token does not list the requested customer (K3). Mapped to 403. */
class CustomerNotAllowedException extends AccessDeniedException {

  CustomerNotAllowedException() {
    super("Not allowed for this customer");
  }
}
