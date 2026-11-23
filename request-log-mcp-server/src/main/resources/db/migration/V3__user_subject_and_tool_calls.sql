-- Who asked (the JWT "sub" of the caller) and which tools the assistant called for the request.
-- Both are optional: older rows and callers that do not send them have NULL.
ALTER TABLE request_log ADD COLUMN user_subject VARCHAR(255);

-- JSON array of {tool, customer, outcome, resultId?}, validated by RequestLogTools (at most 10000
-- characters). Stored as text so the same schema runs on H2 in the local profile.
ALTER TABLE request_log ADD COLUMN tool_calls TEXT;
