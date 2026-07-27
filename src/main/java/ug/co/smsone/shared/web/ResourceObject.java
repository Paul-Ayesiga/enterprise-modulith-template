package ug.co.smsone.shared.web;

/** JSON:API-style resource object: {@code {id, type, attributes}}. */
public record ResourceObject(String id, String type, Object attributes) {
}
