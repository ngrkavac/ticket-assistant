-- Ticket storage (T2.1). Runs on PostgreSQL (compose, production) and on H2 in PostgreSQL mode
-- (the "local" profile). Demo data is not part of the schema: it lives in db/seed and is applied
-- only in the "local" profile.

-- Known customers. Ids are lower-case; the tools lower-case the customer name before every lookup.
-- There is no tool to add customers: operators insert them (e.g. INSERT INTO customer (id) VALUES ('acme')).
CREATE TABLE customer (
    id         VARCHAR(100) PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT customer_id_lower_case CHECK (id = LOWER(id))
);

-- Ticket numbers are global (not per customer) and exposed as "T-<number>", e.g. T-1001.
CREATE SEQUENCE ticket_number_seq START WITH 1001;

CREATE TABLE ticket (
    id         BIGINT PRIMARY KEY,
    customer   VARCHAR(100) NOT NULL REFERENCES customer (id),
    -- createTicket allows at most 200 code points. The column is wider because H2 (the "local"
    -- profile) counts UTF-16 chars, and a code point takes up to two of them.
    subject    VARCHAR(400) NOT NULL,
    status     VARCHAR(20) NOT NULL,
    priority   VARCHAR(10) NOT NULL,
    -- Who created the ticket. NULL for now: the ticket tools do not receive the caller's identity.
    created_by VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ticket_status_valid CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED')),
    CONSTRAINT ticket_priority_valid CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH'))
);

-- All list tools filter by customer and return tickets in id order.
CREATE INDEX ticket_customer_id_idx ON ticket (customer, id);
