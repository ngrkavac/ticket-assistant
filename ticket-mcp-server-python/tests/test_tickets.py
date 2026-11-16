import json
import threading

import pytest

from ticket_server import tickets
from ticket_server.server import TOOLS, call
from ticket_server.tickets import (
    create_ticket,
    get_ticket,
    list_customers,
    list_open_tickets,
    list_tickets,
)


@pytest.fixture(autouse=True)
def _fresh_store():
    tickets.reset()
    yield
    tickets.reset()


def ids(ts):
    return [t.id for t in ts]


# --- seed data -----------------------------------------------------------------------------


def test_seed_has_ten_tickets_grouped_by_customer():
    assert ids(list_tickets("acme")) == ["T-1001", "T-1002", "T-1005", "T-1010"]
    assert ids(list_tickets("globex")) == ["T-1003", "T-1004", "T-1006"]
    assert ids(list_tickets("initech")) == ["T-1007", "T-1008", "T-1009"]
    t = get_ticket("initech", "T-1008")
    assert t.to_dict() == {
        "id": "T-1008",
        "customer": "initech",
        "subject": "Request new laptop for new hire",
        "status": "IN_PROGRESS",
        "priority": "MEDIUM",
    }


def test_list_customers_is_sorted():
    assert list_customers() == ["acme", "globex", "initech"]
    assert call("listCustomers", {}) == '["acme","globex","initech"]'


# --- getTicket -----------------------------------------------------------------------------


def test_finds_ticket_case_insensitively():
    assert "VPN" in get_ticket(" ACME ", " t-1001 ").subject


def test_ticket_of_another_customer_is_not_found():
    with pytest.raises(ValueError, match=r"^Ticket T-1003 not found$"):
        get_ticket("acme", "T-1003")


def test_unknown_customer_or_ticket_is_not_found():
    with pytest.raises(ValueError, match=r"^Ticket t-1001 not found$"):
        get_ticket("nobody", " t-1001 ")
    with pytest.raises(ValueError, match=r"^Ticket T-9999 not found$"):
        get_ticket("acme", "T-9999")


def test_malformed_id_is_rejected():
    with pytest.raises(ValueError, match=r"^Invalid ticket id, expected format T-1234$"):
        get_ticket("acme", "1001; drop")
    with pytest.raises(ValueError, match="Invalid ticket id"):
        get_ticket("acme", None)


def test_get_ticket_requires_customer_before_checking_id():
    for customer in (None, "", "  "):
        with pytest.raises(ValueError, match=r"^Customer name is required$"):
            get_ticket(customer, "bad id")


# --- listOpenTickets -----------------------------------------------------------------------


def test_lists_only_open_tickets_of_customer():
    assert ids(list_open_tickets("acme")) == ["T-1001", "T-1005", "T-1010"]
    assert ids(list_open_tickets(" Initech ")) == ["T-1007", "T-1008"]


def test_open_tickets_of_unknown_customer_is_empty():
    assert list_open_tickets("nobody") == []


def test_blank_customer_is_rejected():
    with pytest.raises(ValueError, match=r"^Customer name is required$"):
        list_open_tickets(" ")


# --- listTickets ---------------------------------------------------------------------------


def test_list_tickets_filters_by_status():
    assert ids(list_tickets("acme", "OPEN")) == ["T-1001", "T-1005"]
    assert ids(list_tickets("acme", " in_progress ")) == ["T-1010"]
    assert ids(list_tickets("globex", "resolved")) == ["T-1006"]


def test_blank_or_missing_status_means_no_filter():
    everything = ids(list_tickets("acme"))
    assert ids(list_tickets("acme", None)) == everything
    assert ids(list_tickets("acme", "")) == everything
    assert ids(list_tickets("acme", "  ")) == everything


def test_invalid_status_is_rejected():
    msg = r"^Invalid status, expected one of OPEN, IN_PROGRESS, RESOLVED$"
    with pytest.raises(ValueError, match=msg):
        list_tickets("acme", "CLOSED")
    # Status is validated before the customer lookup.
    with pytest.raises(ValueError, match=msg):
        list_tickets("nobody", "CLOSED")


def test_list_tickets_validation_order_and_unknown_customer():
    with pytest.raises(ValueError, match=r"^Customer name is required$"):
        list_tickets(" ", "CLOSED")
    assert list_tickets("nobody") == []
    assert list_tickets("nobody", "OPEN") == []


# --- createTicket --------------------------------------------------------------------------


def test_create_ticket_assigns_next_global_id_and_normalizes():
    created = create_ticket(" ACME ", "  Keyboard broken  ", " high ")
    assert created.to_dict() == {
        "id": "T-1011",
        "customer": "acme",
        "subject": "Keyboard broken",
        "status": "OPEN",
        "priority": "HIGH",
    }
    assert create_ticket("globex", "Mouse broken", "low").id == "T-1012"
    assert get_ticket("acme", "T-1011") == created
    assert ids(list_open_tickets("acme")) == ["T-1001", "T-1005", "T-1010", "T-1011"]
    with pytest.raises(ValueError, match="not found"):
        get_ticket("globex", "T-1011")


def test_create_ticket_errors():
    with pytest.raises(ValueError, match=r"^Customer name is required$"):
        create_ticket(" ", "x", "LOW")
    with pytest.raises(ValueError, match=r"^Unknown customer Nobody$"):
        create_ticket("  Nobody ", "x", "LOW")
    with pytest.raises(ValueError, match=r"^Subject is required$"):
        create_ticket("acme", None, "LOW")
    with pytest.raises(ValueError, match=r"^Subject is required$"):
        create_ticket("acme", "   ", "LOW")
    with pytest.raises(ValueError, match=r"^Subject must be at most 200 characters$"):
        create_ticket("acme", "x" * 201, "LOW")
    for bad in (None, "", "URGENT"):
        with pytest.raises(
            ValueError, match=r"^Invalid priority, expected one of LOW, MEDIUM, HIGH$"
        ):
            create_ticket("acme", "x", bad)
    assert len(list_tickets("acme")) == 4  # nothing was created


def test_create_ticket_validation_order():
    with pytest.raises(ValueError, match="Customer name is required"):
        create_ticket(None, None, None)
    with pytest.raises(ValueError, match="Unknown customer nobody"):
        create_ticket("nobody", None, None)
    with pytest.raises(ValueError, match="Subject is required"):
        create_ticket("acme", "", "BAD")
    with pytest.raises(ValueError, match="at most 200"):
        create_ticket("acme", "x" * 201, "BAD")


def test_subject_length_counts_code_points_after_trim():
    emoji = "\U0001f600"
    assert create_ticket("acme", emoji * 200, "LOW").subject == emoji * 200
    assert create_ticket("acme", "  " + "x" * 200 + "  ", "LOW").subject == "x" * 200
    with pytest.raises(ValueError, match="at most 200"):
        create_ticket("acme", emoji * 201, "LOW")


def test_concurrent_creates_get_unique_ids():
    results = []

    def worker():
        for _ in range(25):
            results.append(create_ticket("initech", "Load test", "LOW").id)

    threads = [threading.Thread(target=worker) for _ in range(8)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert len(set(results)) == 200
    assert max(int(i[2:]) for i in results) == 1210


def test_reset_restores_seed():
    create_ticket("acme", "x", "LOW")
    tickets.reset()
    assert len(list_tickets("acme")) == 4
    assert create_ticket("acme", "x", "LOW").id == "T-1011"


# --- Java semantics ------------------------------------------------------------------------


def test_whitespace_and_digits_follow_java_semantics():
    with pytest.raises(ValueError, match="Invalid ticket id"):
        get_ticket("acme", " T-1001")  # NBSP is not trimmed by Java
    with pytest.raises(ValueError, match="Invalid ticket id"):
        get_ticket("acme", "T-١٠٠١")  # Arabic-Indic digits are not \\d in Java
    with pytest.raises(ValueError, match="required"):
        list_open_tickets(" ")  # EM SPACE is Java whitespace
    assert list_open_tickets(" acme") == []
    with pytest.raises(ValueError, match="Unknown customer  acme"):
        create_ticket(" acme", "x", "LOW")


# --- server wire format --------------------------------------------------------------------


def test_tool_result_is_compact_json_in_field_order():
    text = call("getTicket", {"customer": "globex", "id": "T-1003"})
    assert text.startswith('{"id":"T-1003","customer":"globex",')
    assert list(json.loads(text)) == ["id", "customer", "subject", "status", "priority"]


def test_create_ticket_tool_result():
    text = call(
        "createTicket", {"customer": "Initech", "subject": "Fan noise", "priority": "medium"}
    )
    assert text == (
        '{"id":"T-1011","customer":"initech","subject":"Fan noise",'
        '"status":"OPEN","priority":"MEDIUM"}'
    )


def test_list_tickets_tool_result():
    assert json.loads(call("listTickets", {"customer": "globex", "status": "OPEN"})) == [
        get_ticket("globex", "T-1004").to_dict()
    ]
    assert len(json.loads(call("listTickets", {"customer": "globex"}))) == 3


def test_scalar_arguments_are_coerced_to_strings_like_java():
    assert call("listOpenTickets", {"customer": 5}) == "[]"
    with pytest.raises(ValueError, match="Invalid ticket id"):
        call("getTicket", {"customer": "acme", "id": 1001})
    with pytest.raises(ValueError, match="Invalid status"):
        call("listTickets", {"customer": "acme", "status": 1})
    with pytest.raises(ValueError, match="Unknown customer true"):
        call("createTicket", {"customer": True, "subject": "x", "priority": "LOW"})
    with pytest.raises(ValueError, match="Invalid priority"):
        call("createTicket", {"customer": "acme", "subject": 7, "priority": 1})


def test_tool_schemas():
    schemas = {t.name: t.inputSchema for t in TOOLS}
    assert list(schemas) == [
        "getTicket",
        "listOpenTickets",
        "listTickets",
        "listCustomers",
        "createTicket",
    ]
    expected = {
        "getTicket": (["customer", "id"], ["customer", "id"]),
        "listOpenTickets": (["customer"], ["customer"]),
        "listTickets": (["customer", "status"], ["customer"]),
        "listCustomers": ([], []),
        "createTicket": (["customer", "subject", "priority"], ["customer", "subject", "priority"]),
    }
    for name, (props, required) in expected.items():
        schema = schemas[name]
        assert schema["type"] == "object"
        assert list(schema["properties"]) == props
        assert schema["required"] == required
        assert schema["additionalProperties"] is False
    descriptions = {t.name: t.description for t in TOOLS}
    assert descriptions["getTicket"] == (
        "Get a support ticket of a customer by its id, e.g. T-1001. "
        "Fails with a message if the customer has no such ticket."
    )
    assert descriptions["createTicket"] == (
        "Create a new OPEN support ticket for a known customer and return it. "
        "The ticket id is assigned by the server."
    )
    assert schemas["listTickets"]["properties"]["status"]["description"] == (
        "Optional status filter: OPEN, IN_PROGRESS or RESOLVED"
    )
