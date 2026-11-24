# Paper and Folia compatibility

NordChat 2.0.0 supports Paper and Folia on Java 25. Player-bound actions use entity schedulers, global coordination uses the global-region scheduler, and SQLite work runs on owned background executors without blocking region callbacks.

Run `mvn clean verify` before deployment. Preserve the current `plugins/NordChat` directory and replace only the plugin JAR while the server is stopped.
