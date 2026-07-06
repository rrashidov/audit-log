.PHONY: run stop clean logs

run:
	docker compose up --build -d

stop:
	docker compose down

clean:
	docker compose down -v

logs:
	docker compose logs -f app
