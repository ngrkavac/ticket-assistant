"""Ticket domain logic: in-memory, customer-keyed demo store (same data/contract as Java)."""

import re
import threading
import unicodedata
from collections.abc import Iterable
from dataclasses import asdict, dataclass

# [0-9] rather than \d: Java's \d is ASCII-only, Python's matches any Unicode digit.
_ID_PATTERN = re.compile(r"T-[0-9]{1,8}", re.IGNORECASE)

STATUSES = frozenset({"OPEN", "IN_PROGRESS", "RESOLVED"})
PRIORITIES = frozenset({"LOW", "MEDIUM", "HIGH"})
MAX_SUBJECT_LENGTH = 200


def java_trim(value: str) -> str:
    """Java String.trim(): strips only chars <= U+0020 (str.strip() would also strip NBSP etc.)."""
    return value.strip("".join(chr(c) for c in range(0x21)))


def _java_is_whitespace(ch: str) -> bool:
    """Java Character.isWhitespace(): Unicode separators except no-break spaces, plus controls."""
    if ch in "\t\n\u000b\f\r\u001c\u001d\u001e\u001f":
        return True
    return unicodedata.category(ch) in ("Zs", "Zl", "Zp") and ch not in "   "


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


_SEED: tuple[Ticket, ...] = (
    Ticket("T-1001", "acme", "VPN drops every 10 minutes", "OPEN", "HIGH"),
    Ticket("T-1002", "acme", "Cannot reset password", "RESOLVED", "LOW"),
    Ticket("T-1003", "globex", "Laptop not charging", "IN_PROGRESS", "MEDIUM"),
    Ticket("T-1004", "globex", "Printer offline on floor 3", "OPEN", "LOW"),
    Ticket("T-1005", "acme", "Outlook keeps asking for credentials", "OPEN", "MEDIUM"),
    Ticket("T-1006", "globex", "Monitor flickering", "RESOLVED", "LOW"),
    Ticket("T-1007", "initech", "Shared drive not mapping after login", "OPEN", "HIGH"),
    Ticket("T-1008", "initech", "Request new laptop for new hire", "IN_PROGRESS", "MEDIUM"),
    Ticket("T-1009", "initech", "Wi-Fi slow in meeting room B", "RESOLVED", "LOW"),
    Ticket("T-1010", "acme", "MFA codes not arriving by SMS", "IN_PROGRESS", "HIGH"),
)


def _customer_key(customer: str | None) -> str:
    if not isinstance(customer, str) or java_is_blank(customer):
        raise ValueError("Customer name is required")
    return java_trim(customer).lower()


class TicketStore:
    """Lowercase customer -> (ticket id -> ticket). All access is guarded by one lock.

    Ids and customers are returned in string order, like the Java server's TreeMaps.
    """

    def __init__(self, tickets: Iterable[Ticket] = _SEED) -> None:
        self._lock = threading.Lock()
        self._by_customer: dict[str, dict[str, Ticket]] = {}
        for t in tickets:
            self._by_customer.setdefault(t.customer.lower(), {})[t.id] = t

    def _tickets_of(self, key: str) -> list[Ticket]:
        with self._lock:
            tickets = self._by_customer.get(key, {})
            return [tickets[i] for i in sorted(tickets)]

    def get_ticket(self, customer: str | None, ticket_id: str | None) -> Ticket:
        """Return the customer's ticket; raises ValueError if malformed or not theirs."""
        key = _customer_key(customer)
        if not isinstance(ticket_id, str) or not _ID_PATTERN.fullmatch(java_trim(ticket_id)):
            raise ValueError("Invalid ticket id, expected format T-1234")
        trimmed = java_trim(ticket_id)
        with self._lock:
            ticket = self._by_customer.get(key, {}).get(trimmed.upper())
        if ticket is None:
            raise ValueError(f"Ticket {trimmed} not found")
        return ticket

    def list_tickets(self, customer: str | None, status: str | None = None) -> list[Ticket]:
        """Return the customer's tickets, optionally of one status, sorted by id."""
        key = _customer_key(customer)
        wanted = None
        if isinstance(status, str) and not java_is_blank(status):
            wanted = java_trim(status).upper()
            if wanted not in STATUSES:
                raise ValueError("Invalid status, expected one of OPEN, IN_PROGRESS, RESOLVED")
        return [t for t in self._tickets_of(key) if wanted is None or t.status == wanted]

    def list_open_tickets(self, customer: str | None) -> list[Ticket]:
        """Return the customer's tickets that are not RESOLVED, sorted by id."""
        key = _customer_key(customer)
        return [t for t in self._tickets_of(key) if t.status != "RESOLVED"]

    def list_customers(self) -> list[str]:
        """Return the names of all known customers, sorted."""
        with self._lock:
            return sorted(self._by_customer)

    def create_ticket(
        self, customer: str | None, subject: str | None, priority: str | None
    ) -> Ticket:
        """Create an OPEN ticket for a known customer with the next global id."""
        key = _customer_key(customer)
        with self._lock:
            tickets = self._by_customer.get(key)
            if tickets is None:
                raise ValueError(f"Unknown customer {java_trim(customer)}")
            if not isinstance(subject, str) or java_is_blank(subject):
                raise ValueError("Subject is required")
            trimmed_subject = java_trim(subject)
            # len() counts code points, like Java's codePointCount.
            if len(trimmed_subject) > MAX_SUBJECT_LENGTH:
                raise ValueError("Subject must be at most 200 characters")
            normalized = java_trim(priority).upper() if isinstance(priority, str) else None
            if normalized not in PRIORITIES:
                raise ValueError("Invalid priority, expected one of LOW, MEDIUM, HIGH")
            max_id = max(
                (int(i[2:]) for ts in self._by_customer.values() for i in ts),
                default=1000,
            )
            created = Ticket(f"T-{max_id + 1}", key, trimmed_subject, "OPEN", normalized)
            tickets[created.id] = created
            return created


_store = TicketStore()


def reset() -> None:
    """Restore the seed data (used by tests)."""
    global _store
    _store = TicketStore()


def get_ticket(customer: str | None, ticket_id: str | None) -> Ticket:
    return _store.get_ticket(customer, ticket_id)


def list_open_tickets(customer: str | None) -> list[Ticket]:
    return _store.list_open_tickets(customer)


def list_tickets(customer: str | None, status: str | None = None) -> list[Ticket]:
    return _store.list_tickets(customer, status)


def list_customers() -> list[str]:
    return _store.list_customers()


def create_ticket(customer: str | None, subject: str | None, priority: str | None) -> Ticket:
    return _store.create_ticket(customer, subject, priority)
