//! MCP JSON-RPC message handling, independent of the transport.

use serde_json::{json, Map, Value};

use crate::tickets::{TicketStore, ToolError};

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
    let customer = json!({"type": "string", "description": "Customer name, e.g. acme"});
    json!([
        {
            "name": "getTicket",
            "description": "Get a support ticket of a customer by its id, e.g. T-1001. Fails with a message if the customer has no such ticket.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "customer": customer,
                    "id": {"type": "string", "description": "Ticket id such as T-1001"}
                },
                "required": ["customer", "id"],
                "additionalProperties": false
            }
        },
        {
            "name": "listOpenTickets",
            "description": "List all open (status OPEN or IN_PROGRESS) tickets of a customer.",
            "inputSchema": {
                "type": "object",
                "properties": {"customer": customer},
                "required": ["customer"],
                "additionalProperties": false
            }
        },
        {
            "name": "listTickets",
            "description": "List all tickets of a customer, optionally filtered by status.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "customer": customer,
                    "status": {"type": "string", "description": "Optional status filter: OPEN, IN_PROGRESS or RESOLVED"}
                },
                "required": ["customer"],
                "additionalProperties": false
            }
        },
        {
            "name": "listCustomers",
            "description": "List the names of all known customers.",
            "inputSchema": {
                "type": "object",
                "properties": {},
                "required": [],
                "additionalProperties": false
            }
        },
        {
            "name": "createTicket",
            "description": "Create a new OPEN support ticket for a known customer and return it. The ticket id is assigned by the server.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "customer": customer,
                    "subject": {"type": "string", "description": "Short description of the problem"},
                    "priority": {"type": "string", "description": "Priority: LOW, MEDIUM or HIGH"}
                },
                "required": ["customer", "subject", "priority"],
                "additionalProperties": false
            }
        }
    ])
}

/// Handles one JSON-RPC message (or a batch). Returns the response to send back on the SSE
/// stream, or `None` for notifications and client responses, which need no answer.
pub fn handle(store: &TicketStore, message: &Value) -> Option<Value> {
    match message {
        Value::Array(batch) if !batch.is_empty() => {
            let responses: Vec<Value> = batch.iter().filter_map(|m| handle_one(store, m)).collect();
            (!responses.is_empty()).then_some(Value::Array(responses))
        }
        _ => handle_one(store, message),
    }
}

/// The JSON-RPC error sent when a POSTed body is not valid JSON.
pub fn parse_error() -> Value {
    error(Value::Null, PARSE_ERROR, "Parse error", None)
}

fn handle_one(store: &TicketStore, message: &Value) -> Option<Value> {
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
        "tools/call" => call_tool(store, id, params),
        _ => error(
            id,
            METHOD_NOT_FOUND,
            "Method not found",
            Some(json!(method)),
        ),
    })
}

fn call_tool(store: &TicketStore, id: Value, params: &Map<String, Value>) -> Value {
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
    let arg = |key: &str| as_string(arguments.get(key));

    let outcome: Result<String, ToolError> = match name {
        "getTicket" => store
            .get_ticket(arg("customer").as_deref(), arg("id").as_deref())
            .map(|t| to_json(&t)),
        "listOpenTickets" => store
            .list_open_tickets(arg("customer").as_deref())
            .map(|t| to_json(&t)),
        "listTickets" => store
            .list_tickets(arg("customer").as_deref(), arg("status").as_deref())
            .map(|t| to_json(&t)),
        "listCustomers" => Ok(to_json(&store.list_customers())),
        "createTicket" => store
            .create_ticket(
                arg("customer").as_deref(),
                arg("subject").as_deref(),
                arg("priority").as_deref(),
            )
            .map(|t| to_json(&t)),
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

    fn handle(message: &Value) -> Option<Value> {
        super::handle(&TicketStore::new(), message)
    }

    fn call_on(store: &TicketStore, name: &str, arguments: Value) -> Value {
        super::handle(
            store,
            &request("tools/call", json!({"name": name, "arguments": arguments})),
        )
        .unwrap()
    }

    fn call(name: &str, arguments: Value) -> Value {
        call_on(&TicketStore::new(), name, arguments)
    }

    fn text(response: &Value) -> &str {
        response["result"]["content"][0]["text"].as_str().unwrap()
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
    fn lists_all_tools_with_exact_schemas() {
        let response = handle(&request("tools/list", json!({}))).unwrap();
        let tools = response["result"]["tools"].as_array().unwrap();
        let names: Vec<_> = tools.iter().map(|t| t["name"].as_str().unwrap()).collect();
        assert_eq!(
            names,
            [
                "getTicket",
                "listOpenTickets",
                "listTickets",
                "listCustomers",
                "createTicket"
            ]
        );
        let expected_required = [
            json!(["customer", "id"]),
            json!(["customer"]),
            json!(["customer"]),
            json!([]),
            json!(["customer", "subject", "priority"]),
        ];
        for (tool, required) in tools.iter().zip(expected_required) {
            assert_eq!(tool["inputSchema"]["type"], "object");
            assert_eq!(tool["inputSchema"]["required"], required);
            assert_eq!(tool["inputSchema"]["additionalProperties"], false);
        }
    }

    #[test]
    fn schema_properties_keep_parameter_order() {
        let tools = tools();
        let keys = |i: usize| -> Vec<String> {
            tools[i]["inputSchema"]["properties"]
                .as_object()
                .unwrap()
                .keys()
                .cloned()
                .collect()
        };
        assert_eq!(keys(0), ["customer", "id"]);
        assert_eq!(keys(2), ["customer", "status"]);
        assert_eq!(keys(4), ["customer", "subject", "priority"]);
        // The serialized form (what clients see) keeps the same order.
        let create = tools[4]["inputSchema"]["properties"].to_string();
        let pos = |k: &str| create.find(&format!("\"{k}\"")).unwrap();
        assert!(pos("customer") < pos("subject") && pos("subject") < pos("priority"));
    }

    #[test]
    fn get_ticket_returns_compact_json_in_field_order() {
        let result = &call("getTicket", json!({"customer": "GLOBEX", "id": "t-1003"}))["result"];
        assert_eq!(result["isError"], false);
        assert_eq!(
            result["content"][0]["text"],
            r#"{"id":"T-1003","customer":"globex","subject":"Laptop not charging","status":"IN_PROGRESS","priority":"MEDIUM"}"#
        );
    }

    #[test]
    fn tool_failures_are_error_results() {
        let result = &call("getTicket", json!({"customer": "acme", "id": "T-9999"}))["result"];
        assert_eq!(result["isError"], true);
        assert_eq!(result["content"][0]["text"], "Ticket T-9999 not found");
        let response = call("getTicket", json!({"customer": "acme", "id": "T-1003"}));
        assert_eq!(text(&response), "Ticket T-1003 not found");
        let response = call("getTicket", json!({"customer": "acme"}));
        assert_eq!(text(&response), "Invalid ticket id, expected format T-1234");
        let response = call("getTicket", json!({"id": "T-1001"}));
        assert_eq!(text(&response), "Customer name is required");
        let response = call("listTickets", json!({"customer": "nobody", "status": "x"}));
        assert_eq!(
            text(&response),
            "Invalid status, expected one of OPEN, IN_PROGRESS, RESOLVED"
        );
        let response = call("createTicket", json!({"customer": " Umbrella "}));
        assert_eq!(text(&response), "Unknown customer Umbrella");
    }

    #[test]
    fn list_tools_return_json_arrays() {
        assert_eq!(
            text(&call("listCustomers", json!({}))),
            r#"["acme","globex","initech"]"#
        );
        let response = call(
            "listTickets",
            json!({"customer": "initech", "status": "resolved"}),
        );
        assert_eq!(
            text(&response),
            r#"[{"id":"T-1009","customer":"initech","subject":"Wi-Fi slow in meeting room B","status":"RESOLVED","priority":"LOW"}]"#
        );
        let response = call("listTickets", json!({"customer": "nobody"}));
        assert_eq!(text(&response), "[]");
    }

    #[test]
    fn create_ticket_is_visible_to_later_calls_on_the_same_store() {
        let store = TicketStore::new();
        let response = call_on(
            &store,
            "createTicket",
            json!({"customer": "Initech", "subject": " Badge reader broken ", "priority": "high"}),
        );
        assert_eq!(response["result"]["isError"], false);
        let created = r#"{"id":"T-1011","customer":"initech","subject":"Badge reader broken","status":"OPEN","priority":"HIGH"}"#;
        assert_eq!(text(&response), created);
        let response = call_on(
            &store,
            "getTicket",
            json!({"customer": "initech", "id": "T-1011"}),
        );
        assert_eq!(text(&response), created);
        // A different store (another router) does not see it.
        let response = call("getTicket", json!({"customer": "initech", "id": "T-1011"}));
        assert_eq!(text(&response), "Ticket T-1011 not found");
    }

    #[test]
    fn scalar_arguments_are_coerced_like_java() {
        assert_eq!(text(&call("listOpenTickets", json!({"customer": 5}))), "[]");
        assert_eq!(
            call("getTicket", json!({"customer": "acme", "id": 1001}))["result"]["isError"],
            true
        );
        assert_eq!(
            text(&call(
                "createTicket",
                json!({"customer": "acme", "subject": "x", "priority": 1})
            )),
            "Invalid priority, expected one of LOW, MEDIUM, HIGH"
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
