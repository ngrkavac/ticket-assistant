//! Ticket domain logic. Backed by an in-memory, customer-keyed store seeded with demo data
//! (same as the Java implementation).
//!
//! String handling deliberately follows Java semantics (`String.trim()`, `String.isBlank()`,
//! ASCII-only `\d`) so every implementation accepts and rejects exactly the same inputs.

use serde::Serialize;
use std::collections::BTreeMap;
use std::fmt;
use std::sync::{PoisonError, RwLock};

/// Field order is the JSON field order clients see.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Ticket {
    pub id: String,
    pub customer: String,
    pub subject: String,
    pub status: String,
    pub priority: String,
}

impl Ticket {
    fn new(id: &str, customer: &str, subject: &str, status: &str, priority: &str) -> Self {
        Ticket {
            id: id.into(),
            customer: customer.into(),
            subject: subject.into(),
            status: status.into(),
            priority: priority.into(),
        }
    }
}

const SEED: [[&str; 5]; 10] = [
    [
        "T-1001",
        "acme",
        "VPN drops every 10 minutes",
        "OPEN",
        "HIGH",
    ],
    ["T-1002", "acme", "Cannot reset password", "RESOLVED", "LOW"],
    [
        "T-1003",
        "globex",
        "Laptop not charging",
        "IN_PROGRESS",
        "MEDIUM",
    ],
    [
        "T-1004",
        "globex",
        "Printer offline on floor 3",
        "OPEN",
        "LOW",
    ],
    [
        "T-1005",
        "acme",
        "Outlook keeps asking for credentials",
        "OPEN",
        "MEDIUM",
    ],
    ["T-1006", "globex", "Monitor flickering", "RESOLVED", "LOW"],
    [
        "T-1007",
        "initech",
        "Shared drive not mapping after login",
        "OPEN",
        "HIGH",
    ],
    [
        "T-1008",
        "initech",
        "Request new laptop for new hire",
        "IN_PROGRESS",
        "MEDIUM",
    ],
    [
        "T-1009",
        "initech",
        "Wi-Fi slow in meeting room B",
        "RESOLVED",
        "LOW",
    ],
    [
        "T-1010",
        "acme",
        "MFA codes not arriving by SMS",
        "IN_PROGRESS",
        "HIGH",
    ],
];

const STATUSES: [&str; 3] = ["OPEN", "IN_PROGRESS", "RESOLVED"];
const PRIORITIES: [&str; 3] = ["LOW", "MEDIUM", "HIGH"];
const MAX_SUBJECT_LENGTH: usize = 200;

/// A tool failure whose message is returned to the MCP client as an `isError` result.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ToolError(pub String);

impl fmt::Display for ToolError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for ToolError {}

fn fail<T>(message: impl Into<String>) -> Result<T, ToolError> {
    Err(ToolError(message.into()))
}

/// Java `String.trim()`: strips only chars <= U+0020 (`str::trim` would also strip NBSP etc.).
fn java_trim(value: &str) -> &str {
    value.trim_matches(|c: char| c <= ' ')
}

/// Java `Character.isWhitespace()`: Unicode space/line/paragraph separators except the
/// no-break spaces, plus the ASCII controls TAB, LF, VT, FF, CR and FS..US.
fn java_is_whitespace(c: char) -> bool {
    matches!(
        c,
        '\t' | '\n'
            | '\u{0B}'
            | '\u{0C}'
            | '\r'
            | '\u{1C}'..='\u{1F}'
            | ' '
            | '\u{1680}'
            | '\u{2000}'..='\u{2006}'
            | '\u{2008}'..='\u{200A}'
            | '\u{2028}'
            | '\u{2029}'
            | '\u{205F}'
            | '\u{3000}'
    )
}

/// Java `String.isBlank()`.
fn java_is_blank(value: &str) -> bool {
    value.chars().all(java_is_whitespace)
}

/// Matches `(?i)T-\d{1,8}` with Java's ASCII-only `\d`.
fn is_valid_id(id: &str) -> bool {
    match id.as_bytes() {
        [b'T' | b't', b'-', digits @ ..] => {
            (1..=8).contains(&digits.len()) && digits.iter().all(u8::is_ascii_digit)
        }
        _ => false,
    }
}

/// The numeric part of a stored ticket id (`T-1001` -> 1001), used for ordering.
fn id_number(id: &str) -> u64 {
    id.get(2..).and_then(|n| n.parse().ok()).unwrap_or(0)
}

/// Returns the trimmed, non-blank argument, or `None` for a missing or blank one.
fn non_blank(value: Option<&str>) -> Option<&str> {
    value.filter(|v| !java_is_blank(v)).map(java_trim)
}

/// Validates the customer argument and returns its trimmed form.
fn required_customer(customer: Option<&str>) -> Result<&str, ToolError> {
    match non_blank(customer) {
        Some(customer) => Ok(customer),
        None => fail("Customer name is required"),
    }
}

fn sorted_by_id<'a>(tickets: impl Iterator<Item = &'a Ticket>) -> Vec<Ticket> {
    let mut tickets: Vec<Ticket> = tickets.cloned().collect();
    tickets.sort_by_key(|t| id_number(&t.id));
    tickets
}

/// Customer (lowercase) -> ticket id (uppercase) -> ticket.
type Tickets = BTreeMap<String, BTreeMap<String, Ticket>>;

/// Thread-safe in-memory ticket store, keyed by customer.
#[derive(Debug)]
pub struct TicketStore {
    by_customer: RwLock<Tickets>,
}

impl Default for TicketStore {
    fn default() -> Self {
        Self::new()
    }
}

impl TicketStore {
    /// A store seeded with the demo tickets.
    pub fn new() -> Self {
        let mut by_customer = Tickets::new();
        for [id, customer, subject, status, priority] in SEED {
            by_customer.entry(customer.to_string()).or_default().insert(
                id.to_string(),
                Ticket::new(id, customer, subject, status, priority),
            );
        }
        TicketStore {
            by_customer: RwLock::new(by_customer),
        }
    }

    fn read(&self) -> std::sync::RwLockReadGuard<'_, Tickets> {
        self.by_customer
            .read()
            .unwrap_or_else(PoisonError::into_inner)
    }

    /// Returns the customer's ticket with the given id; fails if the id is malformed or the
    /// customer has no such ticket (tickets of other customers are reported as not found).
    pub fn get_ticket(
        &self,
        customer: Option<&str>,
        id: Option<&str>,
    ) -> Result<Ticket, ToolError> {
        let customer = required_customer(customer)?;
        let Some(id) = id.map(java_trim).filter(|id| is_valid_id(id)) else {
            return fail("Invalid ticket id, expected format T-1234");
        };
        self.read()
            .get(&customer.to_lowercase())
            .and_then(|tickets| tickets.get(&id.to_ascii_uppercase()))
            .cloned()
            .ok_or_else(|| ToolError(format!("Ticket {id} not found")))
    }

    /// Returns the customer's tickets that are not RESOLVED, sorted by id.
    pub fn list_open_tickets(&self, customer: Option<&str>) -> Result<Vec<Ticket>, ToolError> {
        let customer = required_customer(customer)?;
        let store = self.read();
        let Some(tickets) = store.get(&customer.to_lowercase()) else {
            return Ok(Vec::new());
        };
        Ok(sorted_by_id(
            tickets.values().filter(|t| t.status != "RESOLVED"),
        ))
    }

    /// Returns all tickets of the customer, optionally only those with the given status,
    /// sorted by id.
    pub fn list_tickets(
        &self,
        customer: Option<&str>,
        status: Option<&str>,
    ) -> Result<Vec<Ticket>, ToolError> {
        let customer = required_customer(customer)?;
        let status = match non_blank(status) {
            None => None,
            Some(status) => {
                let status = status.to_uppercase();
                if !STATUSES.contains(&status.as_str()) {
                    return fail("Invalid status, expected one of OPEN, IN_PROGRESS, RESOLVED");
                }
                Some(status)
            }
        };
        let store = self.read();
        let Some(tickets) = store.get(&customer.to_lowercase()) else {
            return Ok(Vec::new());
        };
        Ok(sorted_by_id(tickets.values().filter(|t| {
            status.as_deref().is_none_or(|status| t.status == status)
        })))
    }

    /// Returns the names of all known customers, sorted.
    pub fn list_customers(&self) -> Vec<String> {
        self.read().keys().cloned().collect()
    }

    /// Creates an OPEN ticket for a known customer with the next free id and returns it.
    pub fn create_ticket(
        &self,
        customer: Option<&str>,
        subject: Option<&str>,
        priority: Option<&str>,
    ) -> Result<Ticket, ToolError> {
        let customer = required_customer(customer)?;
        let mut store = self
            .by_customer
            .write()
            .unwrap_or_else(PoisonError::into_inner);
        let key = customer.to_lowercase();
        if !store.contains_key(&key) {
            return fail(format!("Unknown customer {customer}"));
        }
        let Some(subject) = non_blank(subject) else {
            return fail("Subject is required");
        };
        if subject.chars().count() > MAX_SUBJECT_LENGTH {
            return fail("Subject must be at most 200 characters");
        }
        let priority = priority.map(|p| java_trim(p).to_uppercase());
        let Some(priority) = priority.filter(|p| PRIORITIES.contains(&p.as_str())) else {
            return fail("Invalid priority, expected one of LOW, MEDIUM, HIGH");
        };
        let next = store
            .values()
            .flat_map(BTreeMap::keys)
            .map(|id| id_number(id))
            .max()
            .unwrap_or(1000)
            + 1;
        let ticket = Ticket::new(&format!("T-{next}"), &key, subject, "OPEN", &priority);
        store
            .get_mut(&key)
            .expect("customer checked above")
            .insert(ticket.id.clone(), ticket.clone());
        Ok(ticket)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ids(tickets: Result<Vec<Ticket>, ToolError>) -> Vec<String> {
        tickets.unwrap().into_iter().map(|t| t.id).collect()
    }

    fn err<T: fmt::Debug>(result: Result<T, ToolError>) -> String {
        result.unwrap_err().0
    }

    #[test]
    fn seed_data_has_ten_tickets_of_three_customers() {
        let store = TicketStore::new();
        assert_eq!(store.list_customers(), ["acme", "globex", "initech"]);
        assert_eq!(
            ids(store.list_tickets(Some("acme"), None)),
            ["T-1001", "T-1002", "T-1005", "T-1010"]
        );
        assert_eq!(
            ids(store.list_tickets(Some("globex"), None)),
            ["T-1003", "T-1004", "T-1006"]
        );
        assert_eq!(
            ids(store.list_tickets(Some("initech"), None)),
            ["T-1007", "T-1008", "T-1009"]
        );
        assert_eq!(
            store.get_ticket(Some("initech"), Some("T-1008")).unwrap(),
            Ticket::new(
                "T-1008",
                "initech",
                "Request new laptop for new hire",
                "IN_PROGRESS",
                "MEDIUM"
            )
        );
        assert_eq!(
            store.get_ticket(Some("acme"), Some("T-1010")).unwrap(),
            Ticket::new(
                "T-1010",
                "acme",
                "MFA codes not arriving by SMS",
                "IN_PROGRESS",
                "HIGH"
            )
        );
    }

    #[test]
    fn finds_ticket_case_insensitively() {
        let store = TicketStore::new();
        let ticket = store.get_ticket(Some(" ACME "), Some(" t-1001 ")).unwrap();
        assert_eq!(ticket.subject, "VPN drops every 10 minutes");
    }

    #[test]
    fn ticket_of_another_customer_is_not_found() {
        let store = TicketStore::new();
        assert_eq!(
            err(store.get_ticket(Some("acme"), Some(" t-1003 "))),
            "Ticket t-1003 not found"
        );
        assert_eq!(
            err(store.get_ticket(Some("umbrella"), Some("T-1001"))),
            "Ticket T-1001 not found"
        );
        assert_eq!(
            err(store.get_ticket(Some("acme"), Some("T-9999"))),
            "Ticket T-9999 not found"
        );
    }

    #[test]
    fn get_ticket_validates_customer_before_id() {
        let store = TicketStore::new();
        assert_eq!(
            err(store.get_ticket(None, Some("bad"))),
            "Customer name is required"
        );
        assert_eq!(
            err(store.get_ticket(Some("acme"), Some("1001; drop"))),
            "Invalid ticket id, expected format T-1234"
        );
        assert_eq!(
            err(store.get_ticket(Some("acme"), None)),
            "Invalid ticket id, expected format T-1234"
        );
    }

    #[test]
    fn lists_only_open_tickets_of_customer() {
        let store = TicketStore::new();
        assert_eq!(
            ids(store.list_open_tickets(Some("acme"))),
            ["T-1001", "T-1005", "T-1010"]
        );
        assert_eq!(
            ids(store.list_open_tickets(Some("initech"))),
            ["T-1007", "T-1008"]
        );
        assert!(ids(store.list_open_tickets(Some("umbrella"))).is_empty());
    }

    #[test]
    fn blank_customer_is_rejected_by_every_tool() {
        let store = TicketStore::new();
        let required = "Customer name is required";
        assert_eq!(err(store.list_open_tickets(Some(" "))), required);
        assert_eq!(err(store.list_open_tickets(None)), required);
        assert_eq!(err(store.list_tickets(Some("\t"), None)), required);
        assert_eq!(err(store.get_ticket(Some(""), Some("T-1001"))), required);
        assert_eq!(
            err(store.create_ticket(None, Some("x"), Some("LOW"))),
            required
        );
    }

    #[test]
    fn list_tickets_filters_by_status() {
        let store = TicketStore::new();
        assert_eq!(
            ids(store.list_tickets(Some("acme"), Some(" in_progress "))),
            ["T-1010"]
        );
        assert_eq!(
            ids(store.list_tickets(Some("acme"), Some("OPEN"))),
            ["T-1001", "T-1005"]
        );
        assert_eq!(
            ids(store.list_tickets(Some("globex"), Some("resolved"))),
            ["T-1006"]
        );
        assert_eq!(ids(store.list_tickets(Some("acme"), Some(" "))).len(), 4);
        assert!(ids(store.list_tickets(Some("umbrella"), Some("OPEN"))).is_empty());
    }

    #[test]
    fn list_tickets_rejects_unknown_status_even_for_unknown_customer() {
        let store = TicketStore::new();
        let invalid = "Invalid status, expected one of OPEN, IN_PROGRESS, RESOLVED";
        assert_eq!(
            err(store.list_tickets(Some("acme"), Some("CLOSED"))),
            invalid
        );
        assert_eq!(
            err(store.list_tickets(Some("umbrella"), Some("CLOSED"))),
            invalid
        );
        assert_eq!(
            err(store.list_tickets(None, Some("CLOSED"))),
            "Customer name is required"
        );
    }

    #[test]
    fn create_ticket_assigns_next_ids() {
        let store = TicketStore::new();
        let created = store
            .create_ticket(
                Some(" ACME "),
                Some("  Keyboard broken \n"),
                Some(" medium "),
            )
            .unwrap();
        assert_eq!(
            created,
            Ticket::new("T-1011", "acme", "Keyboard broken", "OPEN", "MEDIUM")
        );
        assert_eq!(
            store.get_ticket(Some("acme"), Some("T-1011")).unwrap(),
            created
        );
        assert_eq!(
            ids(store.list_open_tickets(Some("acme"))),
            ["T-1001", "T-1005", "T-1010", "T-1011"]
        );
        let second = store
            .create_ticket(Some("initech"), Some("Mouse"), Some("low"))
            .unwrap();
        assert_eq!(second.id, "T-1012");
        assert_eq!(
            err(store.get_ticket(Some("acme"), Some("T-1012"))),
            "Ticket T-1012 not found"
        );
        // A fresh store is independent.
        assert_eq!(
            TicketStore::new()
                .create_ticket(Some("globex"), Some("x"), Some("HIGH"))
                .unwrap()
                .id,
            "T-1011"
        );
    }

    #[test]
    fn create_ticket_validates_in_order() {
        let store = TicketStore::new();
        assert_eq!(
            err(store.create_ticket(Some(" Umbrella "), None, None)),
            "Unknown customer Umbrella"
        );
        assert_eq!(
            err(store.create_ticket(Some("acme"), Some("\u{2003}"), None)),
            "Subject is required"
        );
        assert_eq!(
            err(store.create_ticket(Some("acme"), None, Some("LOW"))),
            "Subject is required"
        );
        let too_long = "ü".repeat(201);
        assert_eq!(
            err(store.create_ticket(Some("acme"), Some(&too_long), None)),
            "Subject must be at most 200 characters"
        );
        let invalid = "Invalid priority, expected one of LOW, MEDIUM, HIGH";
        assert_eq!(
            err(store.create_ticket(Some("acme"), Some("x"), None)),
            invalid
        );
        assert_eq!(
            err(store.create_ticket(Some("acme"), Some("x"), Some("URGENT"))),
            invalid
        );
        // Nothing was created by the failed attempts.
        assert_eq!(ids(store.list_tickets(Some("acme"), None)).len(), 4);
    }

    #[test]
    fn subject_of_exactly_200_code_points_is_accepted() {
        let store = TicketStore::new();
        let subject = format!(" {} ", "😀".repeat(200));
        let created = store
            .create_ticket(Some("globex"), Some(&subject), Some("HIGH"))
            .unwrap();
        assert_eq!(created.subject.chars().count(), 200);
    }

    #[test]
    fn ids_sort_numerically() {
        let mut tickets = [
            Ticket::new("T-1000", "a", "s", "OPEN", "LOW"),
            Ticket::new("T-999", "a", "s", "OPEN", "LOW"),
        ];
        let sorted = sorted_by_id(tickets.iter());
        assert_eq!(sorted[0].id, "T-999");
        tickets.reverse();
        assert_eq!(sorted_by_id(tickets.iter())[0].id, "T-999");
    }

    #[test]
    fn whitespace_and_digits_follow_java_semantics() {
        let store = TicketStore::new();
        let get = |id: &str| store.get_ticket(Some("acme"), Some(id));
        assert!(get("\u{a0}T-1001").is_err()); // NBSP is not trimmed by Java
        assert!(get("T-\u{661}\u{660}\u{660}\u{661}").is_err()); // not \d in Java
        assert!(get("T-123456789").is_err());
        assert!(get("T-01001").is_err()); // exact id match, not numeric
        assert!(store.list_open_tickets(Some("\u{2003}")).is_err()); // EM SPACE is Java whitespace
        assert!(ids(store.list_open_tickets(Some("\u{a0}acme"))).is_empty());
        assert_eq!(
            ids(store.list_open_tickets(Some(" GLOBEX "))),
            ["T-1003", "T-1004"]
        );
    }
}
