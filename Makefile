BASE_URL ?= http://localhost:8080
ARGS ?=

.PHONY: up down test burst logs

up:            ## build and start app + Postgres
	docker compose up -d --build

down:
	docker compose down -v

test:          ## unit + integration + concurrency tests (needs Docker for Testcontainers)
	./mvnw -B test

burst:         ## make burst BASE_URL=https://... ARGS="--requests 20000"
	./burst.sh $(BASE_URL) $(ARGS)

logs:
	docker compose logs -f app
