COMPOSE := docker compose --project-directory . -f deploy/compose.dev.yml
PYTHON ?= $(if $(wildcard .venv/bin/python),.venv/bin/python,python3)

.PHONY: up app down agent-build test-agent replay alerts test test-python test-engine test-ui test-go test-config test-helm test-infra integration smoke logs ps consume deploy plan destroy azure-plan azure-deploy azure-destroy

up:
	$(COMPOSE) up -d --wait --wait-timeout 180

app:
	$(COMPOSE) --profile app up -d --build

down:
	$(COMPOSE) --profile app --profile agent down

test: test-python test-agent test-engine test-ui test-go test-config test-helm test-infra

agent-build:
	$(MAKE) -C agent

test-agent: agent-build
	$(PYTHON) -m unittest discover -s agent/tests

replay:
	$(PYTHON) tools/replay.py rules/fixtures/connection.jsonl --fresh

alerts:
	$(COMPOSE) exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic alerts --from-beginning

test-config:
	$(COMPOSE) config --quiet
	$(COMPOSE) --profile app config --quiet
	CORE_PRIVATE_IP=10.42.0.10 POSTGRES_PASSWORD=validation ENGINE_IMAGE=example/engine:test UI_IMAGE=example/ui:test docker compose --project-directory . -f deploy/compose.core.yml config --quiet
	NODE_ID=node-a KAFKA_BOOTSTRAP_SERVERS=10.42.0.10:9094 docker compose --project-directory . -f deploy/compose.node.yml --profile agent config --quiet

test-python:
	$(PYTHON) tools/validate_rules.py
	$(PYTHON) tools/sync_helm_rules.py --check
	$(PYTHON) -m unittest discover -s tools/tests
	$(PYTHON) -m unittest discover -s ml/tests

test-engine:
	cd engine && ./mvnw -B -ntp verify

test-ui:
	cd ui && npm test && npm run build

test-go:
	cd tools/eventstorm && go test ./...

test-helm:
	helm lint deploy/helm/cross-node-ids --set postgres.existingSecret=validation --set kafka.bootstrapServers=kafka.example:9094 --set kafka.existingTlsSecret=validation

test-infra:
	terraform fmt -check -recursive deploy/terraform
	terraform -chdir=deploy/terraform validate
	terraform -chdir=deploy/terraform/azure validate

integration:
	bash scripts/app-integration.sh

smoke: up
	bash scripts/docker-smoke.sh

logs:
	$(COMPOSE) logs -f --tail=100

ps:
	$(COMPOSE) ps -a

consume:
	$(COMPOSE) exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic events.raw --from-beginning

plan:
	terraform -chdir=deploy/terraform init -input=false
	terraform -chdir=deploy/terraform plan

deploy:
	terraform -chdir=deploy/terraform init -input=false
	terraform -chdir=deploy/terraform apply

destroy:
	terraform -chdir=deploy/terraform destroy

azure-plan:
	terraform -chdir=deploy/terraform/azure init -input=false
	terraform -chdir=deploy/terraform/azure plan

azure-deploy:
	terraform -chdir=deploy/terraform/azure init -input=false
	terraform -chdir=deploy/terraform/azure apply

azure-destroy:
	terraform -chdir=deploy/terraform/azure destroy
