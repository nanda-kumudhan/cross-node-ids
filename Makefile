COMPOSE := docker compose --project-directory . -f deploy/compose.dev.yml

.PHONY: up down test smoke logs ps consume deploy destroy

up:
	$(COMPOSE) up -d --wait --wait-timeout 180

down:
	$(COMPOSE) down

test:
	$(COMPOSE) config --quiet

smoke: up
	bash scripts/docker-smoke.sh

logs:
	$(COMPOSE) logs -f --tail=100

ps:
	$(COMPOSE) ps -a

consume:
	$(COMPOSE) exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic events.raw --from-beginning

deploy destroy:
	@printf 'Cloud deployment is not configured yet.\n' >&2
	@exit 1
