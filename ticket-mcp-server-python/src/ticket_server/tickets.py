"""Ticket domain logic. Backed by in-memory demo data (same as the Java implementation)."""

import re
import unicodedata
from dataclasses import asdict, dataclass

# [0-9] rather than \d: Java's \d is ASCII-only, Python's matches any Unicode digit.
_ID_PATTERN = re.compile(r"T-[0-9]{1,8}", re.IGNORECASE)


def java_trim(value: str) -> str:
    """Java String.trim(): strips only chars <= U+0020 (str.strip() would also strip NBSP etc.)."""
    return value.strip("".join(chr(c) for c in range(0x21)))


def _java_is_whitespace(ch: str) -> bool:
    """Java Character.isWhitespace(): Unicode separators except no-break spaces, plus controls."""
    if ch in "\t\n\u000b\f\r\u001c\u001d\u001e\u001f":
        return True
    return unicodedata.category(ch) in ("Zs", "Zl", "Zp") and ch not in "\u00a0\u2007\u202f"


def java_is_blank(value: str) -> bool:
    """Java String.isBlank()."""
    return all(_java_is_whitespace(ch) for ch in value)


@dataclass(frozen=True)
class Ticket:
    # Field order matters: it is the JSON field order clients see.
    id: str
    customer: str
    subject: str
    status: str
    priority: str

    def to_dict(self) -> dict[str, str]:
        return asdict(self)


TICKETS: dict[str, Ticket] = {
    t.id: t
    for t in (
        Ticket("T-1001", "acme", "VPN drops every 10 minutes", "OPEN", "HIGH"),
        Ticket("T-1002", "acme", "Cannot reset password", "RESOLVED", "LOW"),
        Ticket("T-1003", "globex", "Laptop not charging", "IN_PROGRESS", "MEDIUM"),
        Ticket("T-1004", "globex", "Printer offline on floor 3", "OPEN", "LOW"),
    )
}


def get_ticket(ticket_id: str | None) -> Ticket:
    """Return the ticket with the given id; raises ValueError if malformed or unknown."""
    if not isinstance(ticket_id, str) or not _ID_PATTERN.fullmatch(java_trim(ticket_id)):
        raise ValueError("Invalid ticket id, expected format T-1234")
    ticket = TICKETS.get(java_trim(ticket_id).upper())
    if ticket is None:
        raise ValueError(f"Ticket {java_trim(ticket_id)} not found")
    return ticket


def list_open_tickets(customer: str | None) -> list[Ticket]:
    """Return the customer's tickets that are not RESOLVED, sorted by id."""
    if not isinstance(customer, str) or java_is_blank(customer):
        raise ValueError("Customer name is required")
    wanted = java_trim(customer).lower()
    return sorted(
        (t for t in TICKETS.values() if t.customer.lower() == wanted and t.status != "RESOLVED"),
        key=lambda t: t.id,
    )
