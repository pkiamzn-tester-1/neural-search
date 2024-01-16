/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.bwc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.MatchQueryBuilder;
import org.opensearch.neuralsearch.TestUtils;
import static org.opensearch.neuralsearch.TestUtils.NODES_BWC_CLUSTER;
import static org.opensearch.neuralsearch.TestUtils.SPARSE_ENCODING_PROCESSOR;
import static org.opensearch.neuralsearch.TestUtils.objectToFloat;
import static org.opensearch.neuralsearch.TestUtils.generateModelId;
import org.opensearch.neuralsearch.query.NeuralSparseQueryBuilder;

public class NeuralSparseSearchIT extends AbstractRestartUpgradeRestTestCase {
    private static final String PIPELINE_NAME = "nlp-ingest-pipeline-sparse";
    private static final String TEST_SPARSE_ENCODING_FIELD = "passage_embedding";
    private static final String TEST_TEXT_FIELD = "passage_text";
    private static final String TEXT_1 = "Hello world a b";
    private static final String TEXT_2 = "Hello planet";
    private static final List<String> TEST_TOKENS_1 = List.of("hello", "world", "a", "b", "c");
    private static final List<String> TEST_TOKENS_2 = List.of("hello", "planet", "a", "b", "c");
    private final Map<String, Float> testRankFeaturesDoc1 = TestUtils.createRandomTokenWeightMap(TEST_TOKENS_1);
    private final Map<String, Float> testRankFeaturesDoc2 = TestUtils.createRandomTokenWeightMap(TEST_TOKENS_2);

    // Test restart-upgrade test sparse embedding processor
    // Create Sparse Encoding Processor, Ingestion Pipeline and add document
    // Validate process , pipeline and document count in restart-upgrade scenario
    public void testSparseEncodingProcessor_E2EFlow() throws Exception {
        waitForClusterHealthGreen(NODES_BWC_CLUSTER);
        if (isRunningAgainstOldCluster()) {
            String modelId = uploadSparseEncodingModel();
            loadModel(modelId);
            createPipelineProcessor(modelId, PIPELINE_NAME);
            createIndexWithConfiguration(
                getIndexNameForTest(),
                Files.readString(Path.of(classLoader.getResource("processor/SparseIndexMappings.json").toURI())),
                PIPELINE_NAME
            );

            addSparseEncodingDoc(
                getIndexNameForTest(),
                "0",
                List.of(TEST_SPARSE_ENCODING_FIELD),
                List.of(testRankFeaturesDoc1),
                List.of(TEST_TEXT_FIELD),
                List.of(TEXT_1)
            );
        } else {
            Map<String, Object> pipeline = getIngestionPipeline(PIPELINE_NAME);
            assertNotNull(pipeline);
            String modelId = TestUtils.getModelId(pipeline, SPARSE_ENCODING_PROCESSOR);
            loadModel(modelId);
            addSparseEncodingDoc(
                getIndexNameForTest(),
                "1",
                List.of(TEST_SPARSE_ENCODING_FIELD),
                List.of(testRankFeaturesDoc2),
                List.of(TEST_TEXT_FIELD),
                List.of(TEXT_2)
            );
            validateTestIndex(modelId);
            deletePipeline(PIPELINE_NAME);
            deleteModel(modelId);
            deleteIndex(getIndexNameForTest());
        }

    }

    private void validateTestIndex(String modelId) throws Exception {
        int docCount = getDocCount(getIndexNameForTest());
        assertEquals(2, docCount);
        BoolQueryBuilder boolQueryBuilder = new BoolQueryBuilder();
        NeuralSparseQueryBuilder sparseEncodingQueryBuilder = new NeuralSparseQueryBuilder().fieldName(TEST_SPARSE_ENCODING_FIELD)
            .queryText(TEXT_1)
            .modelId(modelId);
        MatchQueryBuilder matchQueryBuilder = new MatchQueryBuilder(TEST_TEXT_FIELD, TEXT_1);
        boolQueryBuilder.should(sparseEncodingQueryBuilder).should(matchQueryBuilder);
        Map<String, Object> response = search(getIndexNameForTest(), boolQueryBuilder, 1);
        Map<String, Object> firstInnerHit = getFirstInnerHit(response);

        assertEquals("0", firstInnerHit.get("_id"));
        float minExpectedScore = computeExpectedScore(modelId, testRankFeaturesDoc1, TEXT_1);
        assertTrue(minExpectedScore < objectToFloat(firstInnerHit.get("_score")));
    }

    private String uploadSparseEncodingModel() throws Exception {
        String requestBody = Files.readString(
            Path.of(classLoader.getResource("processor/UploadSparseEncodingModelRequestBody.json").toURI())
        );
        return registerModelGroupAndGetModelId(requestBody);
    }

    private String registerModelGroupAndGetModelId(String requestBody) throws Exception {
        String modelGroupRegisterRequestBody = Files.readString(
            Path.of(classLoader.getResource("processor/CreateModelGroupRequestBody.json").toURI())
        );
        String modelGroupId = registerModelGroup(String.format(LOCALE, modelGroupRegisterRequestBody, generateModelId()));
        return uploadModel(String.format(LOCALE, requestBody, modelGroupId));
    }

    private void createPipelineProcessor(String modelId, String pipelineName) throws Exception {
        String requestBody = Files.readString(
            Path.of(classLoader.getResource("processor/PipelineForSparseEncodingProcessorConfiguration.json").toURI())
        );
        createPipelineProcessor(requestBody, pipelineName, modelId);
    }
}
