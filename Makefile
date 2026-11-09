.PHONY: run-ui run-service run-publisher run-publisher-rabbitmq run-publisher-kafka run-performance open-allocation-swagger test-e2e build test up up-rabbitmq up-postgres up-redis stop-redis up-ollama down logs

run-ui:
	cd stockio-api/src/main/kotlin/nl/stockio/ui && \
		bash -c '. "$${NVM_DIR:-$$HOME/.nvm}/nvm.sh" --no-use && nvm use && exec npm run dev'

run-service:
	./stockio-service/run-service.sh

run-publisher-rabbitmq:
	ALLOCATION_RABBITMQ_PUBLISHER_ENABLED=true ./gradlew :stockio-test-harness:bootRun

run-publisher-kafka:
	ALLOCATION_KAFKA_PUBLISHER_ENABLED=true ./gradlew :stockio-test-harness:bootRun

run-performance:
	./gradlew :stockio-test-harness:performanceRun --no-daemon

test-e2e:
	cd stockio-test-harness && \
		bash -c '. "$${NVM_DIR:-$$HOME/.nvm}/nvm.sh" --no-use && nvm use && exec npm run test:e2e'

compile-service:
	./gradlew :stockio-service:compileKotlin --no-daemon

build:
	./gradlew clean build --no-daemon

test:
	./gradlew test --no-daemon

up:
	docker compose -f infra/docker/docker-compose.yml up -d

down:
	docker compose -f infra/docker/docker-compose.yml down

up-rabbitmq:
	docker compose -f infra/docker/docker-compose.yml up -d --wait rabbitmq

up-postgres:
	docker compose -f infra/docker/docker-compose.yml up -d --wait postgres

up-redis:
	docker compose -f infra/docker/docker-compose.yml up -d --wait redis

stop-redis:
	docker compose -f infra/docker/docker-compose.yml stop redis

up-ollama:
	docker compose -f infra/docker/docker-compose.yml up -d --wait ollama
	docker compose -f infra/docker/docker-compose.yml run --rm ollama-pull-model

postgres-logs:
	docker compose -f infra/docker/docker-compose.yml ps -a postgres && docker compose -f infra/docker/docker-compose.yml logs --tail 100 postgres

redis-logs:
	docker compose -f infra/docker/docker-compose.yml ps -a redis && docker compose -f infra/docker/docker-compose.yml logs --tail 100 redis

redis-cli:
	docker compose -f infra/docker/docker-compose.yml exec redis redis-cli
