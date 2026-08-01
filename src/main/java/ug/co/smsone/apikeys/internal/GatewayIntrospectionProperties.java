package ug.co.smsone.apikeys.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The shared secret the API gateway presents when introspecting a key ({@code app.gateway.*}). It is
 * the gateway ↔ platform trust boundary for the internal introspection endpoint — the same idea as
 * the Kill Bill callback token.
 */
@ConfigurationProperties("app.gateway")
record GatewayIntrospectionProperties(String introspectionSecret) {
}
