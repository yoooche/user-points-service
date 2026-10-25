# Help

## Before you start

- Run every command from the project directory.
- On Windows, use Git Bash or WSL: the commands below are written for bash and do not work in PowerShell or cmd.
- Give Docker at least 6 GB of memory. The stack uses about 4 GB, and the first image build needs about 1 GB more;
  with less, the RocketMQ broker gets killed for lack of memory.
- `docker-compose` and `docker compose` both work. Some Linux installs only have `docker compose`.
- These ports must be free:

  | Port | Service |
  | --- | --- |
  | 3306 | MySQL |
  | 6379 | Redis |
  | 9876 | RocketMQ name server |
  | 10909, 10911 | RocketMQ broker |
  | 8088 | RocketMQ console |
  | 8080 | The service, run from source (mode A) |
  | 8081 | The service, run in Docker (mode B) |

## Run the service

There are two ways to run it. Use one at a time: both share the same database, and two running copies of the service
would both publish the outbox and send each event twice.

### A. Infrastructure in Docker, service from source

```bash
docker-compose up -d
./mvnw spring-boot:run
```

- The spec says `./mvn`; the wrapper script is `./mvnw`. It needs JDK 21.
- `application.yaml` already points at `localhost`, so nothing needs editing.
- Swagger UI: http://localhost:8080/user-points-service/swagger-ui/index.html

### B. Everything in Docker

```bash
docker-compose -f docker-compose.yaml -f docker-compose.app.yaml up -d --build
```

- The first build downloads the Maven dependencies and takes a few minutes.
- The service listens on port 8081, so it does not clash with a copy started from an IDE on 8080. Set `APP_PORT` to
  change it.
- Swagger UI: http://localhost:8081/user-points-service/swagger-ui/index.html
- `docker-compose.app.yaml` also points the broker at `broker-docker.conf`. The broker tells clients its address through
  the name server; `broker.conf` gives `127.0.0.1`, which only a service on the host can use, and `broker-docker.conf`
  gives the compose service name, which only containers can use.
- To go back to mode A, run `docker-compose -f docker-compose.yaml -f docker-compose.app.yaml rm -sf app` and then
  `docker-compose up -d`, which restarts the broker with `broker.conf`.

### Reset the database

`init.sql` only runs when the MySQL volume is empty. After it changes, recreate the volume:

```bash
docker-compose down -v && docker-compose up -d
```

## Try it

Set the base URL for the mode in use:

```bash
BASE=http://localhost:8080/user-points-service   # mode A
BASE=http://localhost:8081/user-points-service   # mode B
```

```bash
# Add points (201)
curl -i -X POST $BASE/points -H 'Content-Type: application/json' -H 'X-Request-Id: demo-1' \
  -d '{"userId":"user_123","amount":100,"reason":"signup_bonus"}'

# Total (200), served from Redis after the first read
curl $BASE/points/user_123

# Top 10
curl $BASE/points/leaderboard

# Change the reason of a record; use the id returned by the POST (200)
curl -X PUT $BASE/points/1 -H 'Content-Type: application/json' -d '{"reason":"corrected"}'

# Delete the user (204), then a 404 on the next read
curl -i -X DELETE $BASE/points/user_123
```

### Check that the event was published

1. The outbox empties right after the commit:

   ```bash
   docker exec mysql mysql -utaskuser -ptaskpass taskdb -e "SELECT COUNT(*) FROM outbox_message"
   ```

2. Find the message by its keys, the `userId` or the `X-Request-Id`:
   - **Mode B:** RocketMQ console at http://localhost:8088 → Message → topic `user-points-topic` → search by key.
   - **Mode A:** the console cannot read messages, because the broker gives it `127.0.0.1`. Use the admin tool inside
     the broker instead:

     ```bash
     docker exec rocketmq-broker sh -c 'cd /home/rocketmq/rocketmq-5.1.4/bin && \
       sh mqadmin queryMsgByKey -n rocketmq-namesrv:9876 -t user-points-topic -k demo-1'
     ```

3. Messages that RocketMQ refused for their content are kept in `outbox_dead_letter`; the README explains how to replay
   them.

## Apple Silicon: enable Rosetta for RocketMQ

The RocketMQ images are `amd64` only. Without Rosetta the broker starts very slowly and producers fail with `No route info of this topic`.

1. Install Rosetta:

   ```bash
   softwareupdate --install-rosetta --agree-to-license
   ```

2. Enable Rosetta and give the VM at least 8 GiB of memory:
   - **Colima:** `colima stop && colima start --edit`, then set `vmType: vz`, `rosetta: true`, `memory: 8`
   - **Docker Desktop:** Settings → General → **Use Rosetta for x86_64/amd64 emulation on Apple Silicon**

3. Recreate the containers:

   ```bash
   docker-compose down && docker-compose up -d
   ```
