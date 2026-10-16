package com.llamamc.llama.registry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Timeout(120)
class BlockStateGeneratorTest {
    @Test
    void writesTheCommittedTableForTheFixtureReport(@TempDir Path directory) throws Exception {
        assumeTrue(pythonAvailable(), "python3 is required to run scripts/generate_block_states.py");
        var script = Path.of("..", "scripts", "generate_block_states.py").toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(script), "Missing generator script: " + script);
        var input = resource("/block-states/blocks.json");
        var output = directory.resolve("blocks.tsv");

        var process = new ProcessBuilder("python3", "-I", script.toString(), input.toString(),
                "--output", output.toString())
                .redirectErrorStream(true)
                .start();
        var log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Generator did not finish: " + log);
        assertEquals(0, process.exitValue(), log);

        assertEquals(Files.readAllLines(resource("/block-states/blocks.tsv")), Files.readAllLines(output));
    }

    private static boolean pythonAvailable() throws InterruptedException {
        try {
            var process = new ProcessBuilder("python3", "--version")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException error) {
            return false;
        }
    }

    private static Path resource(String name) throws Exception {
        var url = BlockStateGeneratorTest.class.getResource(name);
        assertNotNull(url, "Missing test resource " + name);
        return Path.of(url.toURI());
    }
}
