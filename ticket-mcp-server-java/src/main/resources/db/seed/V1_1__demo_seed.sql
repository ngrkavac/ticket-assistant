-- Demo data, applied only in the "local" profile (spring.flyway.locations in
-- application-local.properties). It is the data set the ticket MCP contract tests expect.
-- Versioned V<n>_1 so it runs right after the schema version it was written for; later schema
-- migrations (V2, V3, ...) still sort after it.
INSERT INTO customer (id) VALUES ('acme'), ('globex'), ('initech');

INSERT INTO ticket (id, customer, subject, status, priority) VALUES
    (1001, 'acme', 'VPN drops every 10 minutes', 'OPEN', 'HIGH'),
    (1002, 'acme', 'Cannot reset password', 'RESOLVED', 'LOW'),
    (1003, 'globex', 'Laptop not charging', 'IN_PROGRESS', 'MEDIUM'),
    (1004, 'globex', 'Printer offline on floor 3', 'OPEN', 'LOW'),
    (1005, 'acme', 'Outlook keeps asking for credentials', 'OPEN', 'MEDIUM'),
    (1006, 'globex', 'Monitor flickering', 'RESOLVED', 'LOW'),
    (1007, 'initech', 'Shared drive not mapping after login', 'OPEN', 'HIGH'),
    (1008, 'initech', 'Request new laptop for new hire', 'IN_PROGRESS', 'MEDIUM'),
    (1009, 'initech', 'Wi-Fi slow in meeting room B', 'RESOLVED', 'LOW'),
    (1010, 'acme', 'MFA codes not arriving by SMS', 'IN_PROGRESS', 'HIGH');

-- New tickets continue after the seed: the first one created is T-1011.
ALTER SEQUENCE ticket_number_seq RESTART WITH 1011;
