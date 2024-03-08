/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.processor.chunker;

import org.apache.commons.lang3.StringUtils;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;

/**
 * The implementation {@link FieldChunker} for delimiter algorithm
 */
public class DelimiterChunker implements FieldChunker {

    public DelimiterChunker() {}

    public static String DELIMITER_FIELD = "delimiter";

    public static String DEFAULT_DELIMITER = ".";

    /**
     * Validate the chunked passages for delimiter algorithm
     *
     * @param parameters a map containing parameters, containing the following parameters
     * 1. A string as the paragraph split indicator
     * @throws IllegalArgumentException If delimiter is not a string or empty
     */
    @Override
    public void validateParameters(Map<String, Object> parameters) {
        if (parameters.containsKey(DELIMITER_FIELD)) {
            Object delimiter = parameters.get(DELIMITER_FIELD);
            if (!(delimiter instanceof String)) {
                throw new IllegalArgumentException("delimiter parameters: " + delimiter + " must be string.");
            } else if (StringUtils.isBlank(delimiter.toString())) {
                throw new IllegalArgumentException("delimiter parameters should not be empty.");
            }
        }
    }

    /**
     * Return the chunked passages for delimiter algorithm
     *
     * @param content input string
     * @param parameters a map containing parameters, containing the following parameters
     */
    @Override
    public List<String> chunk(String content, Map<String, Object> parameters) {
        String delimiter = DEFAULT_DELIMITER;
        if (parameters.containsKey(DELIMITER_FIELD)) {
            Object delimiterObject = parameters.get(DELIMITER_FIELD);
            delimiter = delimiterObject.toString();
        }
        List<String> chunkResult = new ArrayList<>();
        int start = 0;
        int end = content.indexOf(delimiter);

        while (end != -1) {
            chunkResult.add(content.substring(start, end + delimiter.length()));
            start = end + delimiter.length();
            end = content.indexOf(delimiter, start);
        }

        if (start < content.length()) {
            chunkResult.add(content.substring(start));
        }
        return chunkResult;
    }
}
