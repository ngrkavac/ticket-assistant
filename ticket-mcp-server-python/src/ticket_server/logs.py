"""Logging setup: plain text by default, one JSON object per line with LOG_FORMAT=json (T4.1).

The JSON lines use the same Elastic Common Schema fields as the Java servers' ``ecs`` structured
logging (``@timestamp``, ``log.level``, ``log.logger``, ``message``, ``service.name``), so one log
pipeline parses both. Tool arguments and results are never logged.
"""

import json
import logging
import sys
from collections.abc import Mapping
from datetime import UTC, datetime
from typing import Any

SERVICE_NAME = "ticket-mcp-server-python"
TEXT_FORMAT = "%(asctime)s %(levelname)s %(name)s %(message)s"


class EcsJsonFormatter(logging.Formatter):
    """Formats a record as a single-line ECS JSON object."""

    def format(self, record: logging.LogRecord) -> str:
        entry: dict[str, Any] = {
            "@timestamp": datetime.fromtimestamp(record.created, UTC).isoformat(
                timespec="milliseconds"
            ),
            "log": {"level": record.levelname, "logger": record.name},
            "process": {"pid": record.process, "thread": {"name": record.threadName}},
            "service": {"name": SERVICE_NAME},
            "message": record.getMessage(),
            "ecs": {"version": "8.11"},
        }
        if record.exc_info:
            exc_type = record.exc_info[0]
            entry["error"] = {
                "type": exc_type.__name__ if exc_type else None,
                "message": str(record.exc_info[1]),
                "stack_trace": self.formatException(record.exc_info),
            }
        return json.dumps(entry, ensure_ascii=False, default=str)


def configure_logging(env: Mapping[str, str]) -> None:
    """Configures the root logger (uvicorn's loggers propagate to it) from LOG_FORMAT."""
    log_format = env.get("LOG_FORMAT", "text").strip().lower()
    if log_format not in ("text", "json"):
        raise ValueError(f"LOG_FORMAT must be 'text' or 'json', not {log_format!r}")
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(
        EcsJsonFormatter() if log_format == "json" else logging.Formatter(TEXT_FORMAT)
    )
    root = logging.getLogger()
    root.handlers[:] = [handler]
    root.setLevel(logging.INFO)
    for name in ("uvicorn", "uvicorn.error", "uvicorn.access"):
        uvicorn_logger = logging.getLogger(name)
        uvicorn_logger.handlers.clear()
        uvicorn_logger.propagate = True
