import json

import pytest

from ticket_server.server import call
from ticket_server.tickets import get_ticket, list_open_tickets


def test_finds_ticket_case_insensitively():
    assert "VPN" in get_ticket(" t-1001 ").subject


def test_lists_only_open_tickets_of_customer():
    assert [t.id for t in list_open_tickets("acme")] == ["T-1001"]


def test_unknown_ticket_fails():
    with pytest.raises(ValueError, match="not found"):
        get_ticket("T-9999")


def test_malformed_id_is_rejected():
    with pytest.raises(ValueError, match="Invalid ticket id"):
        get_ticket("1001; drop")
    with pytest.raises(ValueError):
        get_ticket(None)


def test_blank_customer_is_rejected():
    with pytest.raises(ValueError, match="required"):
        list_open_tickets(" ")


def test_tool_result_is_compact_json_in_field_order():
    text = call("getTicket", {"id": "T-1003"})
    assert text.startswith('{"id":"T-1003","customer":"globex",')
    assert list(json.loads(text)) == ["id", "customer", "subject", "status", "priority"]


def test_whitespace_and_digits_follow_java_semantics():
    with pytest.raises(ValueError, match="Invalid ticket id"):
        get_ticket("\u00a0T-1001")  # NBSP is not trimmed by Java
    with pytest.raises(ValueError, match="Invalid ticket id"):
        get_ticket("T-\u0661\u0660\u0660\u0661")  # Arabic-Indic digits are not \\d in Java
    with pytest.raises(ValueError, match="required"):
        list_open_tickets("\u2003")  # EM SPACE is Java whitespace
    assert list_open_tickets("\u00a0acme") == []


def test_scalar_arguments_are_coerced_to_strings_like_java():
    assert call("listOpenTickets", {"customer": 5}) == "[]"
    with pytest.raises(ValueError, match="Invalid ticket id"):
        call("getTicket", {"id": 1001})
