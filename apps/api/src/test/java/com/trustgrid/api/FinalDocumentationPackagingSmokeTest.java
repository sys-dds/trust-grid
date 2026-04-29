package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class FinalDocumentationPackagingSmokeTest {

    private final Path root = Path.of(System.getProperty("user.dir")).getParent().getParent();

    @Test
    void finalPackagingArtifactsExistAndSourceAvoidsCopiedProjectIdentity() throws Exception {
        assertThat(Files.readString(root.resolve("README.md"))).contains("TrustGrid", "Final Stop Line");
        assertThat(Files.exists(root.resolve("docs/final-stop-line.md"))).isTrue();
        assertThat(Files.list(root.resolve("docs/adr")).filter(path -> path.getFileName().toString().endsWith(".md")).count())
                .isEqualTo(10);
        assertThat(Files.list(root.resolve("docs/architecture")).filter(path -> path.getFileName().toString().endsWith(".md")).count())
                .isEqualTo(10);
        assertThat(Files.exists(root.resolve("docs/demo/demo-script.md"))).isTrue();
        assertThat(Files.exists(root.resolve("scripts/demo/trustgrid-demo.sh"))).isTrue();
        assertThat(Files.exists(root.resolve("docs/interview/interview-story-pack.md"))).isTrue();

        List<Path> diagramFiles = Files.list(root.resolve("docs/architecture")).toList();
        for (Path diagram : diagramFiles) {
            assertThat(Files.readString(diagram)).contains("```mermaid");
        }
        assertThat(Files.find(root.resolve("docs"), 6, (path, attrs) ->
                path.toString().endsWith(".png") || path.toString().endsWith(".svg")).count()).isZero();

        String sourceAndConfig = readAll(root.resolve("apps/api/src/main/java"))
                + readAll(root.resolve("apps/api/src/test/java"))
                + readAll(root.resolve(".github"))
                + readAll(root.resolve("infra"));
        for (String forbidden : forbiddenProjectTerms()) {
            assertThat(sourceAndConfig.toLowerCase()).doesNotContain(forbidden.toLowerCase());
        }
    }

    private String readAll(Path path) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (var stream = Files.find(path, 12, (file, attrs) -> attrs.isRegularFile())) {
            for (Path file : stream.toList()) {
                builder.append(Files.readString(file)).append('\n');
            }
        }
        return builder.toString();
    }

    private List<String> forbiddenProjectTerms() {
        return List.of(
                "deploy" + "forge",
                "deploy-" + "forge",
                "sync" + "forge",
                "sync-" + "forge",
                "match" + "graph",
                "kay" + "ledger",
                "distributed-" + "link-" + "platform",
                "link-" + "platform",
                "short " + "link",
                "re" + "direct"
        );
    }
}
