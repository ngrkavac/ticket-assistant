import json
import logging
import sys

import pytest

from ticket_server.logs import EcsJsonFormatter, configure_logging


@pytest.fixture(autouse=True)
def restore_root_logger():
    root = logging.getLogger()
    handlers, level = root.handlers[:], root.level
    yield
    root.handlers[:] = handlers
    root.setLevel(level)


def test_json_lines_use_the_ecs_fields_of_the_java_servers(capsys):
    configure_logging({"LOG_FORMAT": "json"})

    logging.getLogger("ticket_server.test").warning("token %s", "missing")

    entry = json.loads(capsys.readouterr().out.strip())
    assert entry["log"] == {"level": "WARNING", "logger": "ticket_server.test"}
    assert entry["message"] == "token missing"
    assert entry["service"] == {"name": "ticket-mcp-server-python"}
    assert entry["ecs"] == {"version": "8.11"}
    assert entry["@timestamp"].endswith("+00:00")


def test_uvicorn_logs_go_through_the_same_handler(capsys):
    configure_logging({"LOG_FORMAT": "json"})

    logging.getLogger("uvicorn.error").info("Started server process")

    lines = capsys.readouterr().out.strip().splitlines()
    assert len(lines) == 1
    assert json.loads(lines[0])["log"]["logger"] == "uvicorn.error"


def test_exceptions_are_one_line_with_the_stack_trace():
    try:
        raise ValueError("boom")
    except ValueError:
        record = logging.LogRecord("x", logging.ERROR, __file__, 1, "failed", None, None)
        record.exc_info = sys.exc_info()

    line = EcsJsonFormatter().format(record)

    assert "\n" not in line
    error = json.loads(line)["error"]
    assert error["type"] == "ValueError"
    assert error["message"] == "boom"
    assert "Traceback" in error["stack_trace"]


def test_text_is_the_default(capsys):
    configure_logging({})

    logging.getLogger("ticket_server.test").info("hello")

    out = capsys.readouterr().out
    assert "INFO ticket_server.test hello" in out
    with pytest.raises(json.JSONDecodeError):
        json.loads(out)


def test_an_unknown_format_fails_fast():
    with pytest.raises(ValueError, match="LOG_FORMAT"):
        configure_logging({"LOG_FORMAT": "xml"})
