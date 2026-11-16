//! Ticket domain logic. Backed by in-memory demo data (same as the Java implementation).
//!
//! String handling deliberately follows Java semantics (`String.trim()`, `String.isBlank()`,
//! ASCII-only `\d`) so every implementation accepts and rejects exactly the same inputs.

use serde::Serialize;
use std::fmt;

/// Field order is the JSON field order clients see.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Ticket {
    pub id: &'static str,
    pub customer: &'static str,
    pub subject: &'static str,
    pub status: &'static str,
    pub priority: &'static str,
}

const fn ticket(
    id: &'static str,
    customer: &'static str,
    subject: &'static str,
    status: &'static str,
    priority: &'static str,
) -> Ticket {
    Ticket {
        id,
        customer,
        subject,
        status,
        priority,
    }
}

pub const TICKETS: [Ticket; 4] = [
    ticket(
        "T-1001",
        "acme",
        "VPN drops every 10 minutes",
        "OPEN",
        "HIGH",
    ),
    ticket("T-1002", "acme", "Cannot reset password", "RESOLVED", "LOW"),
    ticket(
        "T-1003",
        "globex",
        "Laptop not charging",
        "IN_PROGRESS",
        "MEDIUM",
    ),
    ticket(
        "T-1004",
        "globex",
        "Printer offline on floor 3",
        "OPEN",
        "LOW",
    ),
];

/// A tool failure whose message is returned to the MCP client as an `isError` result.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ToolError(pub String);

impl fmt::Display for ToolError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for ToolError {}

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

/// Returns the ticket with the given id; fails if the id is malformed or unknown.
pub fn get_ticket(id: Option<&str>) -> Result<&'static Ticket, ToolError> {
    let id = id.map(java_trim).filter(|id| is_valid_id(id));
    let Some(id) = id else {
        return Err(ToolError(
            "Invalid ticket id, expected format T-1234".into(),
        ));
    };
    let wanted = id.to_ascii_uppercase();
    TICKETS
        .iter()
        .find(|t| t.id == wanted)
        .ok_or_else(|| ToolError(format!("Ticket {id} not found")))
}

/// Returns the customer's tickets that are not RESOLVED, sorted by id.
pub fn list_open_tickets(customer: Option<&str>) -> Result<Vec<&'static Ticket>, ToolError> {
    let Some(customer) = customer.filter(|c| !java_is_blank(c)) else {
        return Err(ToolError("Customer name is required".into()));
    };
    let wanted = java_trim(customer).to_lowercase();
    let mut open: Vec<_> = TICKETS
        .iter()
        .filter(|t| t.customer.to_lowercase() == wanted && t.status != "RESOLVED")
        .collect();
    open.sort_by_key(|t| t.id);
    Ok(open)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn finds_ticket_case_insensitively() {
        assert!(get_ticket(Some(" t-1001 "))
            .unwrap()
            .subject
            .contains("VPN"));
    }

    #[test]
    fn lists_only_open_tickets_of_customer() {
        let ids: Vec<_> = list_open_tickets(Some("acme"))
            .unwrap()
            .iter()
            .map(|t| t.id)
            .collect();
        assert_eq!(ids, ["T-1001"]);
    }

    #[test]
    fn unknown_ticket_fails() {
        assert!(get_ticket(Some("T-9999"))
            .unwrap_err()
            .0
            .contains("not found"));
    }

    #[test]
    fn malformed_id_is_rejected() {
        assert!(get_ticket(Some("1001; drop"))
            .unwrap_err()
            .0
            .contains("Invalid ticket id"));
        assert!(get_ticket(None).is_err());
    }

    #[test]
    fn blank_customer_is_rejected() {
        assert!(list_open_tickets(Some(" "))
            .unwrap_err()
            .0
            .contains("required"));
    }

    #[test]
    fn whitespace_and_digits_follow_java_semantics() {
        assert!(get_ticket(Some("\u{a0}T-1001")).is_err()); // NBSP is not trimmed by Java
        assert!(get_ticket(Some("T-\u{661}\u{660}\u{660}\u{661}")).is_err()); // not \d in Java
        assert!(get_ticket(Some("T-123456789")).is_err());
        assert!(list_open_tickets(Some("\u{2003}")).is_err()); // EM SPACE is Java whitespace
        assert_eq!(
            list_open_tickets(Some("\u{a0}acme")).unwrap(),
            Vec::<&Ticket>::new()
        );
        let ids: Vec<_> = list_open_tickets(Some(" GLOBEX "))
            .unwrap()
            .iter()
            .map(|t| t.id)
            .collect();
        assert_eq!(ids, ["T-1003", "T-1004"]);
    }
}
