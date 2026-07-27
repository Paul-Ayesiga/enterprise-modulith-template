package ug.co.smsone.shared.web;

/**
 * One error in the envelope's {@code errors[]}. {@code status} is a string (JSON:API mandate),
 * {@code code} is a stable enum key clients can switch on, and {@code detail} is always a curated
 * string — never an exception message.
 */
public record ApiError(String id, String status, String code, String title, String detail, ApiSource source) {
}
