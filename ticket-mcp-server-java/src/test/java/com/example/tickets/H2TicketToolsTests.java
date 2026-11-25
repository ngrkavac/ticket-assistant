package com.example.tickets;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * The tool suite on in-memory H2 in PostgreSQL mode, as used by the {@code local} profile. Its own
 * database name keeps it apart from other tests that start the {@code local} profile.
 */
@ActiveProfiles("local")
@TestPropertySource(
    properties =
        "spring.datasource.url=jdbc:h2:mem:tickets-tools;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class H2TicketToolsTests extends AbstractTicketToolsTests {}
