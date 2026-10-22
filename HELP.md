# Help

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
