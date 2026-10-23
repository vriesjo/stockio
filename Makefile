.PHONY: run-api run-service build test up up-rabbitmq up-postgres up-ollama down logs

run-api:
	./gradlew :stock-api:bootRun

run-service:
	./gradlew :stock-service:bootRun

build:
	./gradlew clean build --no-daemon

test:
	./gradlew test --no-daemon

up:
	docker compose -f infra/docker/docker-compose.yml up -d

up-rabbitmq:
	docker compose -f infra/docker/docker-compose.yml up -d --wait rabbitmq

up-postgres:
	docker compose -f infra/docker/docker-compose.yml up -d --wait postgres

up-ollama:
	docker compose -f infra/docker/docker-compose.yml up -d --wait ollama

down:
	docker compose -f infra/docker/docker-compose.yml down

logs:
	docker compose -f infra/docker/docker-compose.yml ps -a postgres && docker compose -f infra/docker/docker-compose.yml logs --tail 100 postgres

