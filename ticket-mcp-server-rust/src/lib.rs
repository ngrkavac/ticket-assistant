//! Ticket MCP server, Rust implementation.
//!
//! The wire contract (tool names, schemas, JSON payloads, error messages) mirrors the Java
//! implementation exactly; see `ticket-mcp-contract-tests` for the shared black-box suite.

pub mod auth;
pub mod protocol;
pub mod tickets;
pub mod transport;
