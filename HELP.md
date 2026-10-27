# Running the service

Requires JDK 21+ and Docker. Run commands from the repository root.
Use `docker compose` instead of `docker-compose` if needed; on Windows, use Git Bash or WSL.

## Start

```bash
docker-compose up -d
./mvnw spring-boot:run
```

Docker runs MySQL, Redis and RocketMQ; the Java service runs locally on port **8080**.
The Maven wrapper is `./mvnw`, not the `./mvn` command in the original README.

- Swagger: [http://localhost:8080/user-points-service/swagger-ui/index.html](http://localhost:8080/user-points-service/swagger-ui/index.html)
- RocketMQ Console: [http://localhost:8088](http://localhost:8088). Under Message, select `user-points-topic`
  and search by the `userId` key to inspect an added-points event.
- Optional sample data: `scripts/seed.sh`

The broker advertises `127.0.0.1:10911` for the locally running Java service. Console shares the broker's network
namespace so that address also reaches the broker from Console. Its port 8080 is published as host port 8088
by the broker service. Broker messages and topic metadata are stored in the `rocketmq_store` volume.

`POST /points` requires `Idempotency-Key`: use a new key for each add and reuse it when retrying that operation.

## Apple Silicon: enable Rosetta

The RocketMQ images used here are amd64-only. On Apple Silicon, the broker can start very slowly without Rosetta,
and producers may report `No route info of this topic` while it is unavailable.

Install Rosetta if needed:

```bash
softwareupdate --install-rosetta --agree-to-license
```

- **Docker Desktop:** use the Apple Virtualization framework and enable **Use Rosetta for x86_64/amd64 emulation**.
- **Colima:** restart with Rosetta enabled:

  ```bash
  colima stop
  colima start --vm-type vz --vz-rosetta --memory 8
  ```

Then recreate the containers (existing database volumes are preserved):

```bash
docker-compose down
docker-compose up -d
```
