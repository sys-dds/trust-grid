package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class TrustGridIdentityGuardrailTest {

    private static final List<String> DENIED_TERMS = List.of(
            word("d", "e", "p", "l", "o", "y", "f", "o", "r", "g", "e"),
            word("d", "e", "p", "l", "o", "y", "-", "f", "o", "r", "g", "e"),
            word("s", "y", "n", "c", "f", "o", "r", "g", "e"),
            word("s", "y", "n", "c", "-", "f", "o", "r", "g", "e"),
            word("k", "a", "y", "l", "e", "d", "g", "e", "r"),
            word("k", "a", "y", "-", "l", "e", "d", "g", "e", "r"),
            word("m", "a", "t", "c", "h", "g", "r", "a", "p", "h"),
            word("m", "a", "t", "c", "h", "g", "r", "a", "p", "h", "-", "r", "a", "n", "k", "i", "n", "g", "-", "p", "l", "a", "t", "f", "o", "r", "m"),
            word("d", "i", "s", "t", "r", "i", "b", "u", "t", "e", "d", "-", "l", "i", "n", "k", "-", "p", "l", "a", "t", "f", "o", "r", "m"),
            word("l", "i", "n", "k", "-", "p", "l", "a", "t", "f", "o", "r", "m"),
            word("s", "h", "o", "r", "t", " ", "l", "i", "n", "k"),
            join("redi", "rect"),
            join("ranking", " platform"),
            join("recom", "mender"),
            join("led", "ger"),
            join("pay", "out"),
            join("es", "crow"),
            join("roll", "out"),
            join("roll", "back"),
            join("deployment", " control plane"),
            join("realtime", " collaboration")
    );

    @Test
    void productionSourceConfigAndDocsUseTrustGridIdentityOnly() throws IOException {
        Path repoRoot = Path.of("").toAbsolutePath().resolve("../..").normalize();
        List<Path> roots = List.of(
                repoRoot.resolve("apps/api/src/main/java"),
                repoRoot.resolve("apps/api/src/main/resources"),
                repoRoot.resolve("infra/docker-compose"),
                repoRoot.resolve("README.md"),
                repoRoot.resolve(".env.example"),
                repoRoot.resolve(".github/workflows")
        );

        List<String> matches = roots.stream()
                .filter(Files::exists)
                .flatMap(this::textFiles)
                .flatMap(this::denyListMatches)
                .toList();

        assertThat(matches).isEmpty();
    }

    private Stream<Path> textFiles(Path path) {
        try {
            if (Files.isRegularFile(path)) {
                return Stream.of(path);
            }
            return Files.walk(path)
                    .filter(Files::isRegularFile)
                    .filter(candidate -> !candidate.toString().contains("/target/"))
                    .filter(candidate -> !candidate.toString().contains("/.git/"))
                    .filter(candidate -> !candidate.getFileName().toString().endsWith(".jar"));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to scan " + path, exception);
        }
    }

    private Stream<String> denyListMatches(Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            return DENIED_TERMS.stream()
                    .filter(content::contains)
                    .map(term -> path + " contains " + term);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + path, exception);
        }
    }

    private static String join(String first, String second) {
        return first + second;
    }

    private static String word(String... parts) {
        return String.join("", parts);
    }
}
