# Building NordChat

Requirements:

- JDK 25
- Maven 3.9+

Run:

```powershell
.\build.ps1 -MavenCommand "C:\path\to\mvn.cmd"
```

The script runs `mvn clean verify` and checks the exact release JAR. The production artifact is `target/NordChat-2.0.1.jar`.
