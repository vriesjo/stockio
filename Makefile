.PHONY: run-ui run-service run-publisher run-publisher-rabbitmq run-publisher-kafka run-performance open-allocation-swagger test-e2e build test up up-rabbitmq up-postgres up-ollama down logs

run-ui:
	cd stockio-api/src/main/kotlin/nl/stockio/ui && \
		bash -c '. "$${NVM_DIR:-$$HOME/.nvm}/nvm.sh" --no-use && nvm use && exec npm run dev'

run-service:
	ALLOCATION_KAFKA_ENABLED=true ./gradlew :stockio-service:bootRun

run-publisher: run-publisher-rabbitmq

run-publisher-rabbitmq:
	ALLOCATION_RABBITMQ_PUBLISHER_ENABLED=true ./gradlew :stockio-test-harness:bootRun

run-publisher-kafka:
	ALLOCATION_KAFKA_PUBLISHER_ENABLED=true ./gradlew :stockio-test-harness:bootRun

run-performance:
	./gradlew :stockio-test-harness:performanceRun --no-daemon

open-allocation-swagger:
	./stockio-test-harness/performance/open-allocation-swagger.sh

test-e2e:
	cd stockio-test-harness && \
		bash -c '. "$${NVM_DIR:-$$HOME/.nvm}/nvm.sh" --no-use && nvm use && exec npm run test:e2e'

compile-service:
	./gradlew :stockio-service:compileKotlin --no-daemon

complete-build:
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
	docker compose -f infra/docker/docker-compose.yml run --rm ollama-pull-model

down:
	docker compose -f infra/docker/docker-compose.yml down

logs:
	docker compose -f infra/docker/docker-compose.yml ps -a postgres && docker compose -f infra/docker/docker-compose.yml logs --tail 100 postgres
