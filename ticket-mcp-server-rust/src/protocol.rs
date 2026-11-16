//! MCP JSON-RPC message handling, independent of the transport.

use serde_json::{json, Map, Value};

use crate::tickets::{get_ticket, list_open_tickets, ToolError};

pub const SERVER_NAME: &str = "ticket-server";
pub const SERVER_VERSION: &str = "1.0.0";
/// The protocol revision that defines the HTTP+SSE transport; the Java server answers with it too.
pub const PROTOCOL_VERSION: &str = "2024-11-05";

const PARSE_ERROR: i64 = -32700;
const INVALID_REQUEST: i64 = -32600;
const METHOD_NOT_FOUND: i64 = -32601;
const INVALID_PARAMS: i64 = -32602;

/// Tool definitions; descriptions and schemas match the Java `@Tool` annotations.
pub fn tools() -> Value {
    json!([
        {
            "name": "getTicket",
            "description": "Get a support ticket by its id, e.g. T-1001. Fails with a message if it does not exist.",
            "inputSchema": {
                "type": "object",
                "properties": {"id": {"type": "string", "description": "Ticket id such as T-1001"}},
                "required": ["id"],
                "additionalProperties": false
            }
        },
        {
            "name": "listOpenTickets",
            "description": "List all open (status OPEN or IN_PROGRESS) tickets of a customer.",
            "inputSchema": {
                "type": "object",
                "properties": {"customer": {"type": "string", "description": "Customer name, e.g. acme"}},
                "required": ["customer"],
                "additionalProperties": false
            }
        }
    ])
}

/// Handles one JSON-RPC message (or a batch). Returns the response to send back on the SSE
/// stream, or `None` for notifications and client responses, which need no answer.
pub fn handle(message: &Value) -> Option<Value> {
    match message {
        Value::Array(batch) if !batch.is_empty() => {
            let responses: Vec<Value> = batch.iter().filter_map(handle_one).collect();
            (!responses.is_empty()).then_some(Value::Array(responses))
        }
        _ => handle_one(message),
    }
}

/// The JSON-RPC error sent when a POSTed body is not valid JSON.
pub fn parse_error() -> Value {
    error(Value::Null, PARSE_ERROR, "Parse error", None)
}

fn handle_one(message: &Value) -> Option<Value> {
    let Some(object) = message.as_object() else {
        return Some(error(Value::Null, INVALID_REQUEST, "Invalid Request", None));
    };
    let Some(method) = object.get("method").and_then(Value::as_str) else {
        // A response to a server request (this server sends none) or garbage: nothing to answer.
        return None;
    };
    // A request without an id is a notification (e.g. notifications/initialized).
    let id = object.get("id")?.clone();
    let empty = Map::new();
    let params = object
        .get("params")
        .and_then(Value::as_object)
        .unwrap_or(&empty);

    Some(match method {
        "initialize" => success(
            id,
            json!({
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {"tools": {"listChanged": false}},
                "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION}
            }),
        ),
        "ping" => success(id, json!({})),
        "tools/list" => success(id, json!({"tools": tools()})),
        "tools/call" => call_tool(id, params),
        _ => error(
            id,
            METHOD_NOT_FOUND,
            "Method not found",
            Some(json!(method)),
        ),
    })
}

fn call_tool(id: Value, params: &Map<String, Value>) -> Value {
    let Some(name) = params.get("name").and_then(Value::as_str) else {
        return error(
            id,
            INVALID_PARAMS,
            "Invalid params",
            Some(json!("Missing tool name")),
        );
    };
    let empty = Map::new();
    let arguments = params
        .get("arguments")
        .and_then(Value::as_object)
        .unwrap_or(&empty);

    let outcome: Result<String, ToolError> = match name {
        "getTicket" => get_ticket(as_string(arguments.get("id")).as_deref()).map(|t| to_json(&t)),
        "listOpenTickets" => {
            list_open_tickets(as_string(arguments.get("customer")).as_deref()).map(|t| to_json(&t))
        }
        // Same JSON-RPC error the Java (Spring AI) server sends for an unknown tool.
        _ => {
            return error(
                id,
                INVALID_PARAMS,
                "Unknown tool: invalid_tool_name",
                Some(json!(format!("Tool not found: {name}"))),
            )
        }
    };
    let (text, is_error) = match outcome {
        Ok(text) => (text, false),
        Err(e) => (e.0, true),
    };
    success(
        id,
        json!({"content": [{"type": "text", "text": text}], "isError": is_error}),
    )
}

/// Coerces a JSON argument to a string the way Spring AI does for a `String` parameter.
fn as_string(value: Option<&Value>) -> Option<String> {
    match value? {
        Value::Null => None,
        Value::String(s) => Some(s.clone()),
        other => Some(other.to_string()),
    }
}

fn to_json<T: serde::Serialize>(value: &T) -> String {
    serde_json::to_string(value).expect("ticket data always serializes")
}

fn success(id: Value, result: Value) -> Value {
    json!({"jsonrpc": "2.0", "id": id, "result": result})
}

fn error(id: Value, code: i64, message: &str, data: Option<Value>) -> Value {
    let mut err = json!({"code": code, "message": message});
    if let Some(data) = data {
        err["data"] = data;
    }
    json!({"jsonrpc": "2.0", "id": id, "error": err})
}

#[cfg(test)]
mod tests {
    use super::*;

    fn request(method: &str, params: Value) -> Value {
        json!({"jsonrpc": "2.0", "id": 7, "method": method, "params": params})
    }

    fn call(name: &str, arguments: Value) -> Value {
        handle(&request(
            "tools/call",
            json!({"name": name, "arguments": arguments}),
        ))
        .unwrap()
    }

    #[test]
    fn initialize_reports_server_info() {
        let response = handle(&request("initialize", json!({}))).unwrap();
        assert_eq!(response["id"], 7);
        assert_eq!(response["result"]["serverInfo"]["name"], "ticket-server");
        assert_eq!(response["result"]["serverInfo"]["version"], "1.0.0");
        assert_eq!(response["result"]["protocolVersion"], PROTOCOL_VERSION);
    }

    #[test]
    fn notifications_get_no_response() {
        let notification = json!({"jsonrpc": "2.0", "method": "notifications/initialized"});
        assert_eq!(handle(&notification), None);
    }

    #[test]
    fn lists_both_tools() {
        let response = handle(&request("tools/list", json!({}))).unwrap();
        let names: Vec<_> = response["result"]["tools"]
            .as_array()
            .unwrap()
            .iter()
            .map(|t| t["name"].as_str().unwrap())
            .collect();
        assert_eq!(names, ["getTicket", "listOpenTickets"]);
    }

    #[test]
    fn get_ticket_returns_compact_json_in_field_order() {
        let result = &call("getTicket", json!({"id": "t-1003"}))["result"];
        assert_eq!(result["isError"], false);
        assert_eq!(
            result["content"][0]["text"],
            r#"{"id":"T-1003","customer":"globex","subject":"Laptop not charging","status":"IN_PROGRESS","priority":"MEDIUM"}"#
        );
    }

    #[test]
    fn tool_failures_are_error_results() {
        let result = &call("getTicket", json!({"id": "T-9999"}))["result"];
        assert_eq!(result["isError"], true);
        assert_eq!(result["content"][0]["text"], "Ticket T-9999 not found");
        let result = &call("getTicket", json!({}))["result"];
        assert_eq!(
            result["content"][0]["text"],
            "Invalid ticket id, expected format T-1234"
        );
    }

    #[test]
    fn scalar_arguments_are_coerced_like_java() {
        assert_eq!(
            call("listOpenTickets", json!({"customer": 5}))["result"]["content"][0]["text"],
            "[]"
        );
        assert_eq!(
            call("getTicket", json!({"id": 1001}))["result"]["isError"],
            true
        );
    }

    #[test]
    fn unknown_tool_and_method_are_json_rpc_errors() {
        let response = call("deleteTicket", json!({}));
        assert_eq!(response["error"]["code"], INVALID_PARAMS);
        assert_eq!(response["error"]["data"], "Tool not found: deleteTicket");
        let response = handle(&request("resources/list", json!({}))).unwrap();
        assert_eq!(response["error"]["code"], METHOD_NOT_FOUND);
    }

    #[test]
    fn batches_return_only_request_responses() {
        let batch = json!([
            {"jsonrpc": "2.0", "method": "notifications/initialized"},
            {"jsonrpc": "2.0", "id": 1, "method": "ping"}
        ]);
        assert_eq!(
            handle(&batch).unwrap(),
            json!([{"jsonrpc": "2.0", "id": 1, "result": {}}])
        );
    }
}
