/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.neuralsearch.query;

import static org.opensearch.neuralsearch.TestUtils.createRandomVector;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import lombok.SneakyThrows;

import org.junit.After;
import org.junit.Before;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.index.query.TermQueryBuilder;
import org.opensearch.knn.index.SpaceType;
import org.opensearch.neuralsearch.common.BaseNeuralSearchIT;

import com.google.common.primitives.Floats;

public class HybridQueryIT extends BaseNeuralSearchIT {
    private static final String TEST_BASIC_INDEX_NAME = "test-neural-basic-index";
    private static final String TEST_BASIC_VECTOR_DOC_FIELD_INDEX_NAME = "test-neural-vector-doc-field-index";
    private static final String TEST_MULTI_DOC_INDEX_NAME = "test-neural-multi-doc-index";
    private static final int MAX_NUMBER_OF_DOCS_IN_MULTI_DOC_INDEX = 3;
    private static final String TEST_QUERY_TEXT = "greetings";
    private static final String TEST_QUERY_TEXT2 = "salute";
    private static final String TEST_QUERY_TEXT3 = "hello";
    private static final String TEST_QUERY_TEXT4 = "place";
    private static final String TEST_QUERY_TEXT5 = "welcome";
    private static final String TEST_DOC_TEXT1 = "Hello world";
    private static final String TEST_DOC_TEXT2 = "Hi to this place";
    private static final String TEST_DOC_TEXT3 = "We would like to welcome everyone";
    private static final String TEST_KNN_VECTOR_FIELD_NAME_1 = "test-knn-vector-1";
    private static final String TEST_KNN_VECTOR_FIELD_NAME_2 = "test-knn-vector-2";
    private static final String TEST_TEXT_FIELD_NAME_1 = "test-text-field-1";

    private static final int TEST_DIMENSION = 768;
    private static final SpaceType TEST_SPACE_TYPE = SpaceType.L2;
    private final float[] testVector1 = createRandomVector(TEST_DIMENSION);
    private final float[] testVector2 = createRandomVector(TEST_DIMENSION);
    private final float[] testVector3 = createRandomVector(TEST_DIMENSION);
    private final static String RELATION_EQUAL_TO = "eq";

    @Before
    public void setUp() throws Exception {
        super.setUp();
        updateClusterSettings();
        prepareModel();
    }

    @After
    @SneakyThrows
    public void tearDown() {
        super.tearDown();
        /* this is required to minimize chance of model not being deployed due to open memory CB,
         * this happens in case we leave model from previous test case. We use new model for every test, and old model
         * can be undeployed and deleted to free resources after each test case execution.
         */
        findDeployedModels().forEach(this::deleteModel);
    }

    @Override
    public boolean isUpdateClusterSettings() {
        return false;
    }

    @Override
    protected boolean preserveClusterUponCompletion() {
        return true;
    }

    /**
     * Tests basic query, example of query structure:
     * {
     *     "query": {
     *         "hybrid": {
     *              "queries": [
     *                  {
     *                      "neural": {
     *                          "text_knn": {
     *                              "query_text": "Hello world",
     *                              "model_id": "dcsdcasd",
     *                              "k": 1
     *                          }
     *                      }
     *                  }
     *              ]
     *          }
     *      }
     * }
     */
    @SneakyThrows
    public void testBasicQuery_whenOneSubQuery_thenSuccessful() {
        initializeIndexIfNotExist(TEST_MULTI_DOC_INDEX_NAME);
        String modelId = getDeployedModelId();

        NeuralQueryBuilder neuralQueryBuilder = new NeuralQueryBuilder(TEST_KNN_VECTOR_FIELD_NAME_1, "", modelId, 5, null, null);

        HybridQueryBuilder hybridQueryBuilder = new HybridQueryBuilder();
        hybridQueryBuilder.add(neuralQueryBuilder);

        Map<String, Object> searchResponseAsMap1 = search(TEST_MULTI_DOC_INDEX_NAME, hybridQueryBuilder, 10);

        assertEquals(MAX_NUMBER_OF_DOCS_IN_MULTI_DOC_INDEX, getHitCount(searchResponseAsMap1));

        List<Map<String, Object>> hits1NestedList = getNestedHits(searchResponseAsMap1);
        List<String> ids = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        for (Map<String, Object> oneHit : hits1NestedList) {
            ids.add((String) oneHit.get("_id"));
            scores.add((Double) oneHit.get("_score"));
        }

        // verify that scores are in desc order
        assertTrue(IntStream.range(0, scores.size() - 1).noneMatch(idx -> scores.get(idx) < scores.get(idx + 1)));
        // verify that all ids are unique
        assertEquals(Set.copyOf(ids).size(), ids.size());

        Map<String, Object> total = getTotalHits(searchResponseAsMap1);
        assertNotNull(total.get("value"));
        assertEquals(MAX_NUMBER_OF_DOCS_IN_MULTI_DOC_INDEX, total.get("value"));
        assertNotNull(total.get("relation"));
        assertEquals(RELATION_EQUAL_TO, total.get("relation"));
        assertTrue(getMaxScore(searchResponseAsMap1).isPresent());
    }

    /**
     * Tests complex query with multiple nested sub-queries:
     * {
     *     "query": {
     *         "hybrid": {
     *              "queries": [
     *                  {
     *                      "bool": {
     *                          "should": [
     *                              {
     *                                  "term": {
     *                                      "text": "word1"
     *                                  }
     *                             },
     *                             {
     *                                  "term": {
     *                                      "text": "word2"
     *                                   }
     *                              }
     *                         ]
     *                      }
     *                  },
     *                  {
     *                      "term": {
     *                          "text": "word3"
     *                      }
     *                  }
     *              ]
     *          }
     *      }
     * }
     */
    @SneakyThrows
    public void testComplexQuery_whenMultipleSubqueries_thenSuccessful() {
        initializeIndexIfNotExist(TEST_BASIC_VECTOR_DOC_FIELD_INDEX_NAME);

        TermQueryBuilder termQueryBuilder1 = QueryBuilders.termQuery(TEST_TEXT_FIELD_NAME_1, TEST_QUERY_TEXT3);
        TermQueryBuilder termQueryBuilder2 = QueryBuilders.termQuery(TEST_TEXT_FIELD_NAME_1, TEST_QUERY_TEXT4);
        TermQueryBuilder termQueryBuilder3 = QueryBuilders.termQuery(TEST_TEXT_FIELD_NAME_1, TEST_QUERY_TEXT5);
        BoolQueryBuilder boolQueryBuilder = new BoolQueryBuilder();
        boolQueryBuilder.should(termQueryBuilder2).should(termQueryBuilder3);

        HybridQueryBuilder hybridQueryBuilderNeuralThenTerm = new HybridQueryBuilder();
        hybridQueryBuilderNeuralThenTerm.add(termQueryBuilder1);
        hybridQueryBuilderNeuralThenTerm.add(boolQueryBuilder);

        Map<String, Object> searchResponseAsMap1 = search(TEST_BASIC_VECTOR_DOC_FIELD_INDEX_NAME, hybridQueryBuilderNeuralThenTerm, 10);

        assertEquals(3, getHitCount(searchResponseAsMap1));

        List<Map<String, Object>> hits1NestedList = getNestedHits(searchResponseAsMap1);
        List<String> ids = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        for (Map<String, Object> oneHit : hits1NestedList) {
            ids.add((String) oneHit.get("_id"));
            scores.add((Double) oneHit.get("_score"));
        }

        // verify that scores are in desc order
        assertTrue(IntStream.range(0, scores.size() - 1).noneMatch(idx -> scores.get(idx) < scores.get(idx + 1)));
        // verify that all ids are unique
        assertEquals(Set.copyOf(ids).size(), ids.size());

        Map<String, Object> total = getTotalHits(searchResponseAsMap1);
        assertNotNull(total.get("value"));
        assertEquals(3, total.get("value"));
        assertNotNull(total.get("relation"));
        assertEquals(RELATION_EQUAL_TO, total.get("relation"));
    }

    /**
     * Using queries similar to below to test sub-queries order:
     * {
     *     "query": {
     *         "hybrid": {
     *              "queries": [
     *                  {
     *                      "neural": {
     *                          "text_knn": {
     *                              "query_text": "Hello world",
     *                              "model_id": "dcsdcasd",
     *                              "k": 1
     *                          }
     *                      }
     *                  },
     *                  {
     *                      "term": {
     *                          "text": "word"
     *                      }
     *                  }
     *              ]
     *          }
     *      }
     * }
     */
    @SneakyThrows
    public void testSubQuery_whenSubqueriesInDifferentOrder_thenResultIsSame() {
        initializeIndexIfNotExist(TEST_MULTI_DOC_INDEX_NAME);
        String modelId = getDeployedModelId();

        NeuralQueryBuilder neuralQueryBuilder = new NeuralQueryBuilder(TEST_KNN_VECTOR_FIELD_NAME_1, "", modelId, 5, null, null);
        TermQueryBuilder termQueryBuilder = QueryBuilders.termQuery(TEST_TEXT_FIELD_NAME_1, TEST_QUERY_TEXT);

        HybridQueryBuilder hybridQueryBuilderNeuralThenTerm = new HybridQueryBuilder();
        hybridQueryBuilderNeuralThenTerm.add(neuralQueryBuilder);
        hybridQueryBuilderNeuralThenTerm.add(termQueryBuilder);

        Map<String, Object> searchResponseAsMap1 = search(TEST_MULTI_DOC_INDEX_NAME, hybridQueryBuilderNeuralThenTerm, 10);

        assertEquals(MAX_NUMBER_OF_DOCS_IN_MULTI_DOC_INDEX, getHitCount(searchResponseAsMap1));

        List<Map<String, Object>> hits1NestedList = getNestedHits(searchResponseAsMap1);
        List<String> ids1 = new ArrayList<>();
        List<Double> scores1 = new ArrayList<>();
        for (Map<String, Object> oneHit : hits1NestedList) {
            ids1.add((String) oneHit.get("_id"));
            scores1.add((Double) oneHit.get("_score"));
        }

        Map<String, Object> total = getTotalHits(searchResponseAsMap1);
        assertNotNull(total.get("value"));
        assertEquals(MAX_NUMBER_OF_DOCS_IN_MULTI_DOC_INDEX, total.get("value"));
        assertNotNull(total.get("relation"));
        assertEquals(RELATION_EQUAL_TO, total.get("relation"));

        // verify that scores are in desc order
        assertTrue(IntStream.range(0, scores1.size() - 1).noneMatch(idx -> scores1.get(idx) < scores1.get(idx + 1)));
        // verify that all ids are unique
        assertEquals(Set.copyOf(ids1).size(), ids1.size());

        // check similar query when sub-queries are in reverse order, results must be same as in previous test case
        HybridQueryBuilder hybridQueryBuilderTermThenNeural = new HybridQueryBuilder();
        hybridQueryBuilderTermThenNeural.add(termQueryBuilder);
        hybridQueryBuilderTermThenNeural.add(neuralQueryBuilder);

        Map<String, Object> searchResponseAsMap2 = search(TEST_MULTI_DOC_INDEX_NAME, hybridQueryBuilderNeuralThenTerm, 10);

        assertEquals(MAX_NUMBER_OF_DOCS_IN_MULTI_DOC_INDEX, getHitCount(searchResponseAsMap2));

        List<String> ids2 = new ArrayList<>();
        List<Double> scores2 = new ArrayList<>();
        for (Map<String, Object> oneHit : hits1NestedList) {
            ids2.add((String) oneHit.get("_id"));
            scores2.add((Double) oneHit.get("_score"));
        }

        Map<String, Object> total2 = getTotalHits(searchResponseAsMap2);
        assertNotNull(total.get("value"));
        assertEquals(MAX_NUMBER_OF_DOCS_IN_MULTI_DOC_INDEX, total2.get("value"));
        assertNotNull(total2.get("relation"));
        assertEquals(RELATION_EQUAL_TO, total2.get("relation"));
        // doc ids must match same from the previous query, order of sub-queries doesn't change the result
        assertEquals(ids1, ids2);
        assertEquals(scores1, scores2);
    }

    @SneakyThrows
    public void testNoMatchResults_whenOnlyTermSubQueryWithoutMatch_thenEmptyResult() {
        initializeIndexIfNotExist(TEST_MULTI_DOC_INDEX_NAME);

        TermQueryBuilder termQueryBuilder = QueryBuilders.termQuery(TEST_TEXT_FIELD_NAME_1, TEST_QUERY_TEXT);
        TermQueryBuilder termQuery2Builder = QueryBuilders.termQuery(TEST_TEXT_FIELD_NAME_1, TEST_QUERY_TEXT2);
        HybridQueryBuilder hybridQueryBuilderOnlyTerm = new HybridQueryBuilder();
        hybridQueryBuilderOnlyTerm.add(termQueryBuilder);
        hybridQueryBuilderOnlyTerm.add(termQuery2Builder);

        Map<String, Object> searchResponseAsMap = search(TEST_MULTI_DOC_INDEX_NAME, hybridQueryBuilderOnlyTerm, 10);

        assertEquals(0, getHitCount(searchResponseAsMap));
        assertTrue(getMaxScore(searchResponseAsMap).isEmpty());

        Map<String, Object> total = getTotalHits(searchResponseAsMap);
        assertNotNull(total.get("value"));
        assertEquals(0, total.get("value"));
        assertNotNull(total.get("relation"));
        assertEquals(RELATION_EQUAL_TO, total.get("relation"));
    }

    private void initializeIndexIfNotExist(String indexName) throws IOException {
        if (TEST_BASIC_INDEX_NAME.equals(indexName) && !indexExists(TEST_BASIC_INDEX_NAME)) {
            prepareKnnIndex(
                TEST_BASIC_INDEX_NAME,
                Collections.singletonList(new KNNFieldConfig(TEST_KNN_VECTOR_FIELD_NAME_1, TEST_DIMENSION, TEST_SPACE_TYPE))
            );
            addKnnDoc(
                TEST_BASIC_INDEX_NAME,
                "1",
                Collections.singletonList(TEST_KNN_VECTOR_FIELD_NAME_1),
                Collections.singletonList(Floats.asList(testVector1).toArray())
            );
            assertEquals(1, getDocCount(TEST_BASIC_INDEX_NAME));
        }
        if (TEST_BASIC_VECTOR_DOC_FIELD_INDEX_NAME.equals(indexName) && !indexExists(TEST_BASIC_VECTOR_DOC_FIELD_INDEX_NAME)) {
            prepareKnnIndex(
                TEST_BASIC_VECTOR_DOC_FIELD_INDEX_NAME,
                List.of(
                    new KNNFieldConfig(TEST_KNN_VECTOR_FIELD_NAME_1, TEST_DIMENSION, TEST_SPACE_TYPE),
                    new KNNFieldConfig(TEST_KNN_VECTOR_FIELD_NAME_2, TEST_DIMENSION, TEST_SPACE_TYPE)
                )
            );
            addKnnDoc(
                TEST_BASIC_VECTOR_DOC_FIELD_INDEX_NAME,
                "1",
                List.of(TEST_KNN_VECTOR_FIELD_NAME_1, TEST_KNN_VECTOR_FIELD_NAME_2),
                List.of(Floats.asList(testVector1).toArray(), Floats.asList(testVector1).toArray()),
                Collections.singletonList(TEST_TEXT_FIELD_NAME_1),
                Collections.singletonList(TEST_DOC_TEXT1)
            );
            addKnnDoc(
                TEST_BASIC_VECTOR_DOC_FIELD_INDEX_NAME,
                "2",
                List.of(TEST_KNN_VECTOR_FIELD_NAME_1, TEST_KNN_VECTOR_FIELD_NAME_2),
                List.of(Floats.asList(testVector2).toArray(), Floats.asList(testVector2).toArray()),
                Collections.singletonList(TEST_TEXT_FIELD_NAME_1),
                Collections.singletonList(TEST_DOC_TEXT2)
            );
            addKnnDoc(
                TEST_BASIC_VECTOR_DOC_FIELD_INDEX_NAME,
                "3",
                List.of(TEST_KNN_VECTOR_FIELD_NAME_1, TEST_KNN_VECTOR_FIELD_NAME_2),
                List.of(Floats.asList(testVector3).toArray(), Floats.asList(testVector3).toArray()),
                Collections.singletonList(TEST_TEXT_FIELD_NAME_1),
                Collections.singletonList(TEST_DOC_TEXT3)
            );
            assertEquals(3, getDocCount(TEST_BASIC_VECTOR_DOC_FIELD_INDEX_NAME));
        }

        if (TEST_MULTI_DOC_INDEX_NAME.equals(indexName) && !indexExists(TEST_MULTI_DOC_INDEX_NAME)) {
            prepareKnnIndex(
                TEST_MULTI_DOC_INDEX_NAME,
                Collections.singletonList(new KNNFieldConfig(TEST_KNN_VECTOR_FIELD_NAME_1, TEST_DIMENSION, TEST_SPACE_TYPE))
            );
            addKnnDoc(
                TEST_MULTI_DOC_INDEX_NAME,
                "1",
                Collections.singletonList(TEST_KNN_VECTOR_FIELD_NAME_1),
                Collections.singletonList(Floats.asList(testVector1).toArray()),
                Collections.singletonList(TEST_TEXT_FIELD_NAME_1),
                Collections.singletonList(TEST_DOC_TEXT1)
            );
            addKnnDoc(
                TEST_MULTI_DOC_INDEX_NAME,
                "2",
                Collections.singletonList(TEST_KNN_VECTOR_FIELD_NAME_1),
                Collections.singletonList(Floats.asList(testVector2).toArray())
            );
            addKnnDoc(
                TEST_MULTI_DOC_INDEX_NAME,
                "3",
                Collections.singletonList(TEST_KNN_VECTOR_FIELD_NAME_1),
                Collections.singletonList(Floats.asList(testVector3).toArray()),
                Collections.singletonList(TEST_TEXT_FIELD_NAME_1),
                Collections.singletonList(TEST_DOC_TEXT2)
            );
            assertEquals(3, getDocCount(TEST_MULTI_DOC_INDEX_NAME));
        }
    }

    private List<Map<String, Object>> getNestedHits(Map<String, Object> searchResponseAsMap) {
        Map<String, Object> hitsMap = (Map<String, Object>) searchResponseAsMap.get("hits");
        return (List<Map<String, Object>>) hitsMap.get("hits");
    }

    private Map<String, Object> getTotalHits(Map<String, Object> searchResponseAsMap) {
        Map<String, Object> hitsMap = (Map<String, Object>) searchResponseAsMap.get("hits");
        return (Map<String, Object>) hitsMap.get("total");
    }

    private Optional<Float> getMaxScore(Map<String, Object> searchResponseAsMap) {
        Map<String, Object> hitsMap = (Map<String, Object>) searchResponseAsMap.get("hits");
        return hitsMap.get("max_score") == null ? Optional.empty() : Optional.of(((Double) hitsMap.get("max_score")).floatValue());
    }
}
