package net.fabricmc.stitch.tinyv2;

import net.fabricmc.stitch.commands.tinyv2.CommandMergeTinyV2;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class MergeTest {
	private static final Path DIR = new File(MergeTest.class.getClassLoader().getResource("merge").getPath()).toPath().toAbsolutePath();

	@Test
	public void test() throws Exception {
		Path expectedOutput = DIR.resolve("expected.tiny");
		Path output = Files.createTempFile("stitch-merge-result-", ".tiny");
		Path inputA = DIR.resolve("input-a.tiny");
		Path inputB = DIR.resolve("input-b.tiny");
		Path inputC = DIR.resolve("input-c.tiny");
		new CommandMergeTinyV2().run(new String[]{inputA.toString(), inputB.toString(), inputC.toString(), output.toString()});

		String expectedOutputContent = new String(Files.readAllBytes(expectedOutput), StandardCharsets.UTF_8).replace("\r\n", "\n");
		String outputContent = new String(Files.readAllBytes(output), StandardCharsets.UTF_8).replace("\r\n", "\n");
		assertEquals(expectedOutputContent, outputContent);
	}
}
