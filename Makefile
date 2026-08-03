# Local dev convenience wrapper — run `make` (or `make help`) to list targets.
#
# Infra (Postgres, Keycloak, Valkey, SeaweedFS, Mailpit, otel-lgtm) runs via Docker Compose.
# `make run` starts the app, which AUTO-STARTS that stack for you (Spring Boot Docker Compose
# support) — so the whole local environment is one command. Use `make up` when you only want the
# infra (e.g. running the app from your IDE).

SHELL := /usr/bin/env bash

COMPOSE_FILE := docker/docker-compose.yml
ENV_FILE     := docker/.env
COMPOSE      := docker compose -f $(COMPOSE_FILE) --env-file $(ENV_FILE)
# Readiness gate for `fresh`: bootRun's own Compose wait races a stack that is still starting,
# so we block on the realm being importable before handing over.
KEYCLOAK_READY_URL := http://localhost:$(shell grep -E '^KEYCLOAK_PORT=' $(ENV_FILE) 2>/dev/null | cut -d= -f2 || echo 8081)/realms/smsone/.well-known/openid-configuration

# App port + non-ServiceConnection endpoints are derived from the mapped ports in docker/.env
# (falling back to clean-machine defaults). Keycloak needs BOTH the issuer URI (token validation)
# and the base URL (Admin API / provisioning) pointed at the mapped port. Sourced fresh per run.
# IDENTITY_DEV_BOOTSTRAP_ENABLED seeds the platform admin's app_user row locally (dev-only; the
# property defaults to false so nothing is seeded outside these targets).
RUN_ENV = set -a; . $(ENV_FILE); set +a; \
	SERVER_PORT=$${SERVER_PORT:-8080} \
	IDENTITY_DEV_BOOTSTRAP_ENABLED=true \
	KEYCLOAK_ISSUER_URI=http://localhost:$${KEYCLOAK_PORT:-8081}/realms/smsone \
	KEYCLOAK_URL=http://localhost:$${KEYCLOAK_PORT:-8081} \
	S3_ENDPOINT=http://localhost:$${S3_PORT:-8333} \
	KILLBILL_URL=http://localhost:$${KILLBILL_PORT:-8082} \
	SMTP_HOST=localhost SMTP_PORT=$${SMTP_PORT:-1025}

# Gateway env: the edge fronts the modulith and shares its infra. Sourced from docker/.env so the
# mapped ports match the running stack. SERVER_PORT in docker/.env is the MODULITH's port, and Spring
# maps SERVER_PORT -> server.port, so we FIRST capture it as MODULITH_PORT (for the proxy target +
# seam URIs), THEN rebind SERVER_PORT/MANAGEMENT_SERVER_PORT to the gateway's own ports — otherwise the
# gateway would try to bind the modulith's port. Run `make run` first, then `make gateway` in a 2nd terminal.
GATEWAY_RUN_ENV = set -a; . $(ENV_FILE); set +a; \
	MODULITH_PORT=$${SERVER_PORT:-8080}; \
	SERVER_PORT=$${GATEWAY_PORT:-8090} \
	MANAGEMENT_SERVER_PORT=$${GATEWAY_ADMIN_PORT:-9090} \
	VALKEY_HOST=localhost VALKEY_PORT=$${VALKEY_PORT:-6379} \
	MODULITH_URI=http://localhost:$$MODULITH_PORT \
	GATEWAY_INTROSPECTION_URI=http://localhost:$$MODULITH_PORT/internal/gateway/api-key/introspect \
	GATEWAY_AUDIT_URI=http://localhost:$$MODULITH_PORT/internal/gateway/audit \
	GATEWAY_QUOTA_URI=http://localhost:$$MODULITH_PORT/internal/gateway/quota \
	KEYCLOAK_JWKS=http://localhost:$${KEYCLOAK_PORT:-8081}/realms/smsone/protocol/openid-connect/certs

.DEFAULT_GOAL := help
.PHONY: help env pull up down restart ps logs run seed gateway gateway-build gateway-test token build test openapi nuke clean fresh

help: ## List available targets
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) \
		| sort \
		| awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-9s\033[0m %s\n", $$1, $$2}'

env: $(ENV_FILE) ## Create docker/.env from the example if it does not exist
$(ENV_FILE):
	@cp docker/.env.example $(ENV_FILE) && echo "Created $(ENV_FILE) from example — tweak ports if any clash."

pull: env ## Pre-pull all stack images (avoids the Colima pull-storm crash on first up)
	$(COMPOSE) pull

up: env ## Start the infra stack only, detached (Postgres, Keycloak, Valkey, SeaweedFS, Mailpit, otel-lgtm)
	$(COMPOSE) up -d

down: ## Stop the infra stack (keeps data volumes)
	$(COMPOSE) down

restart: down up ## Restart the infra stack

ps: ## Show stack status + published ports
	$(COMPOSE) ps

logs: ## Tail stack logs — `make logs S=keycloak` for a single service
	$(COMPOSE) logs -f $(S)

run: env ## Run the app + auto-started stack (Ctrl-C stops both); seeds the platform admin
	@$(RUN_ENV) ./gradlew :bootRun

seed: env ## Like `run`, but also seeds the demo org (acme, owner paul) + Kill Bill tenant & catalog
	@$(RUN_ENV) ORG_DEV_BOOTSTRAP_ENABLED=true BILLING_BOOTSTRAP=true ./gradlew :bootRun

killbill-init: env ## Create the Kill Bill 'smsone' tenant + simple catalog via the KB API (no app boot)
	@set -a; . $(ENV_FILE); set +a; bash scripts/killbill-init.sh

gateway: env ## Run the API gateway (:8090, admin :9090) fronting the modulith — `make run` first, this in a 2nd terminal
	@$(GATEWAY_RUN_ENV) ./gradlew :gateway:app:bootRun

gateway-build: ## Compile + assemble the gateway (skips tests)
	./gradlew :gateway:app:build -x test

gateway-test: ## Run the gateway test suite (real Valkey Testcontainer)
	./gradlew :gateway:app:test :gateway:core:test

token: ## Print a dev access token for the platform admin — `make token U=<user>` for another user
	@scripts/token.sh $(U)

build: ## Compile + assemble the app (skips tests) — modulith only; `gateway-build` for the edge
	./gradlew :build -x test

test: ## Run the modulith test suite (real Testcontainers) — `gateway-test` for the edge
	./gradlew :test

# Base URL baked into the Postman collection (HTTPie shows empty URLs for {{baseUrl}} variables).
# Default = the modulith direct; `make openapi POSTMAN_BASE=http://localhost:28090` for the gateway.
POSTMAN_BASE ?= http://localhost:28080

openapi: ## Regenerate docs/openapi/*.{yaml,json} + the Postman collection (HTTPie/Insomnia import this)
	./gradlew :exportOpenApi
	npx --yes openapi-to-postmanv2 -s docs/openapi/openapi.json -o docs/openapi/postman_collection.json -p \
		-O folderStrategy=Tags,requestNameSource=Fallback
	python3 scripts/postman-baseurl.py docs/openapi/postman_collection.json $(POSTMAN_BASE)

clean: ## Drop local build caches (keeps downloaded deps and Docker images — both slow to refetch)
	@./gradlew --stop >/dev/null 2>&1 || true
	@rm -rf build .gradle data
	@echo "Cleared build/ .gradle/ data/. Dependency jars and Docker images kept."

fresh: clean ## clean + wipe data volumes + wait for the stack + run. The one command after a bad state.
	@$(MAKE) nuke
	@$(MAKE) up
	@printf 'waiting for Keycloak to import the realm'
	@until curl -sf $(KEYCLOAK_READY_URL) >/dev/null 2>&1; do printf '.'; sleep 3; done; echo ' ready'
	@$(MAKE) run

nuke: ## Stop the stack AND wipe its data volumes (fresh DB/Keycloak/objects next up)
	$(COMPOSE) down -v
