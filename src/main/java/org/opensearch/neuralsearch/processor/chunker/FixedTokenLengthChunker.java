/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.processor.chunker;

import java.io.IOException;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import java.util.stream.Collectors;

import lombok.extern.log4j.Log4j2;
import org.opensearch.action.admin.indices.analyze.AnalyzeAction;
import org.opensearch.index.analysis.AnalysisRegistry;
import static org.opensearch.action.admin.indices.analyze.TransportAnalyzeAction.analyze;

/**
 * The implementation {@link FieldChunker<String>} for fixed token length algorithm.
 */
@Log4j2
public class FixedTokenLengthChunker implements FieldChunker {

    public static final String TOKEN_LIMIT_FIELD = "token_limit";
    public static final String OVERLAP_RATE_FIELD = "overlap_rate";
    public static final String MAX_TOKEN_COUNT_FIELD = "max_token_count";
    public static final String TOKENIZER_FIELD = "tokenizer";

    // default values for each parameter
    private static final int DEFAULT_TOKEN_LIMIT = 500;
    private static final BigDecimal DEFAULT_OVERLAP_RATE = new BigDecimal("0");
    private static final int DEFAULT_MAX_TOKEN_COUNT = 10000;
    private static final String DEFAULT_TOKENIZER = "standard";

    private static final BigDecimal OVERLAP_RATE_UPPER_BOUND = new BigDecimal("0.5");

    private final AnalysisRegistry analysisRegistry;

    public FixedTokenLengthChunker(AnalysisRegistry analysisRegistry) {
        this.analysisRegistry = analysisRegistry;
    }

    private void validatePositiveIntegerParameter(Map<String, Object> parameters, String fieldName) {
        // this method validate that parameter is a positive integer
        if (!parameters.containsKey(fieldName)) {
            // all parameters are optional
            return;
        }
        if (!(parameters.get(fieldName) instanceof Number)) {
            throw new IllegalArgumentException(
                    "fixed length parameter [" + fieldName + "] cannot be cast to [" + Number.class.getName() + "]"
            );
        }
        if (((Number) parameters.get(fieldName)).intValue() <= 0) {
            throw new IllegalArgumentException("fixed length parameter [" + fieldName + "] must be positive");
        }
    }

    private List<String> tokenize(String content, String tokenizer, int maxTokenCount) {
        AnalyzeAction.Request analyzeRequest = new AnalyzeAction.Request();
        analyzeRequest.text(content);
        analyzeRequest.tokenizer(tokenizer);
        try {
            AnalyzeAction.Response analyzeResponse = analyze(analyzeRequest, analysisRegistry, null, maxTokenCount);
            return analyzeResponse.getTokens().stream().map(AnalyzeAction.AnalyzeToken::getTerm).collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException("Fixed token length algorithm meet with exception: " + e);
        }
    }

    /**
     * Validate the chunked passages for fixed token length algorithm
     *
     * @param parameters a map containing parameters, containing the following parameters
     * 1. tokenizer the analyzer tokenizer in opensearch, please check https://opensearch.org/docs/latest/analyzers/tokenizers/index/
     * 2. token_limit the token limit for each chunked passage
     * 3. overlap_rate the overlapping degree for each chunked passage, indicating how many token comes from the previous passage
     * 4. max_token_count the max token limit for the tokenizer
     * @throws IllegalArgumentException If max_token_count and token_limit is not a positive integer
     * @throws IllegalArgumentException If overlap_rate < 0  or overlap_rate > 0.5
     * @throws IllegalArgumentException If tokenizer is not a string
     */
    @Override
    public void validateParameters(Map<String, Object> parameters) {
        validatePositiveIntegerParameter(parameters, TOKEN_LIMIT_FIELD);
        validatePositiveIntegerParameter(parameters, MAX_TOKEN_COUNT_FIELD);

        if (parameters.containsKey(OVERLAP_RATE_FIELD)) {
            if (!(parameters.get(OVERLAP_RATE_FIELD) instanceof Number)) {
                throw new IllegalArgumentException(
                        "fixed length parameter [" + OVERLAP_RATE_FIELD + "] cannot be cast to [" + Number.class.getName() + "]"
                );
            }
            BigDecimal overlap_rate = new BigDecimal(String.valueOf(parameters.get(OVERLAP_RATE_FIELD)));
            if (overlap_rate.compareTo(BigDecimal.ZERO) < 0 || overlap_rate.compareTo(OVERLAP_RATE_UPPER_BOUND) > 0) {
                throw new IllegalArgumentException(
                        "fixed length parameter [" + OVERLAP_RATE_FIELD + "] must be between 0 and " + OVERLAP_RATE_UPPER_BOUND
                );
            }
        }

        if (parameters.containsKey(TOKENIZER_FIELD) && !(parameters.get(TOKENIZER_FIELD) instanceof String)) {
            throw new IllegalArgumentException(
                    "fixed length parameter [" + TOKENIZER_FIELD + "] cannot be cast to [" + String.class.getName() + "]"
            );
        }
    }

    /**
     * Return the chunked passages for fixed token length algorithm
     *
     * @param content input string
     * @param parameters a map containing parameters, containing the following parameters
     * 1. tokenizer the analyzer tokenizer in opensearch, please check https://opensearch.org/docs/latest/analyzers/tokenizers/index/
     * 2. token_limit the token limit for each chunked passage
     * 3. overlap_rate the overlapping degree for each chunked passage, indicating how many token comes from the previous passage
     * 4. max_token_count the max token limit for the tokenizer
     */
    @Override
    public List<String> chunk(String content, Map<String, Object> parameters) {
        // prior to chunking, parameters have been validated
        int tokenLimit = DEFAULT_TOKEN_LIMIT;
        BigDecimal overlap_rate = new BigDecimal(String.valueOf(DEFAULT_OVERLAP_RATE));
        int maxTokenCount = DEFAULT_MAX_TOKEN_COUNT;

        String tokenizer = DEFAULT_TOKENIZER;

        if (parameters.containsKey(TOKEN_LIMIT_FIELD)) {
            tokenLimit = ((Number) parameters.get(TOKEN_LIMIT_FIELD)).intValue();
        }
        if (parameters.containsKey(OVERLAP_RATE_FIELD)) {
            overlap_rate = new BigDecimal(String.valueOf(parameters.get(OVERLAP_RATE_FIELD)));
        }
        if (parameters.containsKey(MAX_TOKEN_COUNT_FIELD)) {
            maxTokenCount = ((Number) parameters.get(MAX_TOKEN_COUNT_FIELD)).intValue();
        }
        if (parameters.containsKey(TOKENIZER_FIELD)) {
            tokenizer = (String) parameters.get(TOKENIZER_FIELD);
        }

        List<String> tokens = tokenize(content, tokenizer, maxTokenCount);
        List<String> passages = new ArrayList<>();

        String passage;
        int startToken = 0;
        BigDecimal overlapTokenNumberBigDecimal = overlap_rate.multiply(new BigDecimal(String.valueOf(tokenLimit)))
            .setScale(0, RoundingMode.DOWN);
        int overlapTokenNumber = overlapTokenNumberBigDecimal.intValue();
        ;
        // overlapTokenNumber must be smaller than the token limit
        overlapTokenNumber = Math.min(overlapTokenNumber, tokenLimit - 1);

        while (startToken < tokens.size()) {
            if (startToken + tokenLimit >= tokens.size()) {
                // break the loop when already cover the last token
                passage = String.join(" ", tokens.subList(startToken, tokens.size()));
                passages.add(passage);
                break;
            } else {
                passage = String.join(" ", tokens.subList(startToken, startToken + tokenLimit));
                passages.add(passage);
            }
            startToken += tokenLimit - overlapTokenNumber;
        }
        return passages;
    }
}
