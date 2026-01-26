package org.example.order.lifecycle.processor.utils;

import org.example.order.fix.model.ExecutionReport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.example.order.lifecycle.processor.util.JsonUtils.readJsonToObject;

/**
 * Utility class providing helper methods for test data generation and manipulation.
 * This class cannot be instantiated.
 */
public final class TestUtils {

    private TestUtils() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /**
     * Generates an ExecutionReport object by reading and parsing a JSON file from the classpath.
     *
     * @param tokenToReplace Optional token to be replaced in the JSON template. Can be null if no replacement is needed.
     * @param path           The classpath-relative path to the JSON template file
     * @return A populated ExecutionReport object
     * @throws IOException              If there is an error reading the file or parsing the JSON
     * @throws NullPointerException     If the path is null or the resource doesn't exist
     * @throws IllegalArgumentException If the JSON cannot be parsed into an ExecutionReport
     */
    public static ExecutionReport generateExecutionReportMessage(String tokenToReplace, String path)
            throws IOException {
        String json = generateRawExecutionReportMessage(tokenToReplace, path);
        return readJsonToObject(json, ExecutionReport.class);
    }

    /**
     * Reads a JSON file from the classpath and optionally replaces a token in its content.
     *
     * @param tokenToReplace Optional token to be replaced in the JSON content. Can be null if no replacement is needed.
     * @param path           The classpath-relative path to the JSON file
     * @return The JSON content as a String, with token replaced if specified
     * @throws IOException          If there is an error reading the file
     * @throws NullPointerException If the path is null or the resource doesn't exist
     */
    public static String generateRawExecutionReportMessage(String tokenToReplace, String path) throws IOException {
        String jsonContent = new String(
                Objects.requireNonNull(
                        TestUtils.class.getClassLoader().getResourceAsStream(Objects.requireNonNull(path, "Path cannot be null"))
                ).readAllBytes(),
                StandardCharsets.UTF_8
        );

        return tokenToReplace != null ? jsonContent.formatted(tokenToReplace) : jsonContent;
    }

}
