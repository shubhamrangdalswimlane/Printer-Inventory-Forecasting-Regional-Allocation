package com.pifa.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards against forgetting to register a new rule file: every .drl under src/main/resources must be
 * listed in {@link RulesFacade#DRL_FILES}, otherwise it would silently never be loaded.
 */
class DrlRegistrationTest {

    @Test
    void everyDrlFileIsRegistered() throws IOException {
        Path root = Path.of("src/main/resources");
        try (Stream<Path> files = Files.walk(root)) {
            // Normalise Windows separators so the comparison works on every OS.
            List<String> onDisk = files.filter(p -> p.toString().endsWith(".drl"))
                .map(p -> root.relativize(p).toString().replace(File.separatorChar, '/')).sorted().toList();
            assertThat(RulesFacade.DRL_FILES).containsExactlyInAnyOrderElementsOf(onDisk);
        }
    }
}
