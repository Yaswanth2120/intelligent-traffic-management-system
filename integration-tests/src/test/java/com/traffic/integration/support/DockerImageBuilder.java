package com.traffic.integration.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the platform's real production Docker images (same Dockerfiles used by
 * .github/workflows/container-publish.yml) so the integration suite runs the
 * actual services, not a mock or a hand-rolled test double.
 */
public final class DockerImageBuilder {

    private static final Logger log = LoggerFactory.getLogger(DockerImageBuilder.class);

    private DockerImageBuilder() {
    }

    /** Repo root: overridable via TRAFFIC_PLATFORM_ROOT for non-standard checkouts. */
    public static Path repoRoot() {
        String override = System.getenv("TRAFFIC_PLATFORM_ROOT");
        if (override != null && !override.isBlank()) {
            return Paths.get(override).toAbsolutePath().normalize();
        }
        Path candidate = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (Files.exists(candidate.resolve("pom.xml")) && Files.exists(candidate.resolve("gateway-service"))) {
            return candidate;
        }
        Path parent = candidate.getParent();
        if (parent != null && Files.exists(parent.resolve("pom.xml")) && Files.exists(parent.resolve("infra"))) {
            return parent;
        }
        throw new IllegalStateException(
                "Could not locate repository root from user.dir=" + candidate
                        + ". Set TRAFFIC_PLATFORM_ROOT explicitly.");
    }

    /**
     * Runs `docker build -f <dockerfilePath> -t <tag> <repoRoot>` (build context is
     * always the repo root, matching every Dockerfile in this repo, which COPYs
     * sibling-module pom.xml files for the Maven reactor).
     */
    public static String build(String dockerfileRelativePath, String tag) {
        Path root = repoRoot();
        Path dockerfile = root.resolve(dockerfileRelativePath);
        if (!Files.exists(dockerfile)) {
            throw new IllegalStateException("Dockerfile not found: " + dockerfile);
        }

        List<String> command = List.of("docker", "build", "-f", dockerfile.toString(), "-t", tag, root.toString());
        log.info("Building image {} from {}", tag, dockerfile);

        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            StringBuilder output = new StringBuilder();
            try (var reader = process.inputReader()) {
                reader.lines().forEach(line -> {
                    output.append(line).append('\n');
                    log.debug("[docker build {}] {}", tag, line);
                });
            }
            boolean finished = process.waitFor(Duration.ofMinutes(10).toSeconds(), java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("docker build timed out for " + tag);
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("docker build failed for " + tag + ":\n" + output);
            }
            log.info("Built image {}", tag);
            return tag;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to run docker build for " + tag, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while building " + tag, e);
        }
    }
}
