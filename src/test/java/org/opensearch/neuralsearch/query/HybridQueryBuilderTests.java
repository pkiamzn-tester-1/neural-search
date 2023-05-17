/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.neuralsearch.query;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static java.util.stream.Collectors.toList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.opensearch.core.xcontent.ToXContent.EMPTY_PARAMS;
import static org.opensearch.index.query.AbstractQueryBuilder.BOOST_FIELD;
import static org.opensearch.knn.index.query.KNNQueryBuilder.FILTER_FIELD;
import static org.opensearch.neuralsearch.TestUtils.xContentBuilderToMap;
import static org.opensearch.neuralsearch.query.NeuralQueryBuilder.K_FIELD;
import static org.opensearch.neuralsearch.query.NeuralQueryBuilder.MODEL_ID_FIELD;
import static org.opensearch.neuralsearch.query.NeuralQueryBuilder.QUERY_TEXT_FIELD;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import lombok.SneakyThrows;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.opensearch.Version;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.common.CheckedConsumer;
import org.opensearch.common.ParsingException;
import org.opensearch.common.bytes.BytesReference;
import org.opensearch.common.compress.CompressedXContent;
import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.common.io.stream.FilterStreamInput;
import org.opensearch.common.io.stream.NamedWriteableAwareStreamInput;
import org.opensearch.common.io.stream.NamedWriteableRegistry;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.core.ParseField;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.index.Index;
import org.opensearch.index.IndexSettings;
import org.opensearch.index.analysis.AnalyzerScope;
import org.opensearch.index.analysis.IndexAnalyzers;
import org.opensearch.index.analysis.NamedAnalyzer;
import org.opensearch.index.mapper.MapperService;
import org.opensearch.index.mapper.TextFieldMapper;
import org.opensearch.index.query.MatchAllQueryBuilder;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.index.query.QueryShardContext;
import org.opensearch.index.query.TermQueryBuilder;
import org.opensearch.index.similarity.SimilarityService;
import org.opensearch.indices.IndicesModule;
import org.opensearch.indices.mapper.MapperRegistry;
import org.opensearch.knn.index.mapper.KNNVectorFieldMapper;
import org.opensearch.knn.index.query.KNNQuery;
import org.opensearch.knn.index.query.KNNQueryBuilder;
import org.opensearch.plugins.MapperPlugin;
import org.opensearch.plugins.ScriptPlugin;
import org.opensearch.script.ScriptModule;
import org.opensearch.script.ScriptService;
import org.opensearch.test.OpenSearchTestCase;

import com.carrotsearch.randomizedtesting.RandomizedTest;

public class HybridQueryBuilderTests extends OpenSearchTestCase {
    private static final String VECTOR_FIELD_NAME = "vectorField";
    private static final String TEXT_FIELD_NAME = "field";
    private static final String QUERY_TEXT = "Hello world!";
    private static final String TERM_QUERY_TEXT = "keyword";
    private static final String MODEL_ID = "mfgfgdsfgfdgsde";
    private static final int K = 10;
    private static final float BOOST = 1.8f;
    private static final Supplier<float[]> TEST_VECTOR_SUPPLIER = () -> new float[4];
    private static final QueryBuilder TEST_FILTER = new MatchAllQueryBuilder();

    public void testDoToQuery_whenNoSubqueries_thenBuildSuccessfully() throws Exception {
        HybridQueryBuilder queryBuilder = new HybridQueryBuilder();
        Index dummyIndex = new Index("dummy", "dummy");
        QueryShardContext mockQueryShardContext = mock(QueryShardContext.class);
        when(mockQueryShardContext.index()).thenReturn(dummyIndex);
        Query queryNoSubQueries = queryBuilder.doToQuery(mockQueryShardContext);
        assertTrue(queryNoSubQueries instanceof MatchNoDocsQuery);
    }

    public void testDoToQuery_whenOneSubquery_thenBuildSuccessfully() throws Exception {
        HybridQueryBuilder queryBuilder = new HybridQueryBuilder();
        Index dummyIndex = new Index("dummy", "dummy");
        QueryShardContext mockQueryShardContext = mock(QueryShardContext.class);
        KNNVectorFieldMapper.KNNVectorFieldType mockKNNVectorField = mock(KNNVectorFieldMapper.KNNVectorFieldType.class);
        when(mockQueryShardContext.index()).thenReturn(dummyIndex);
        when(mockKNNVectorField.getDimension()).thenReturn(4);
        when(mockQueryShardContext.fieldMapper(eq(VECTOR_FIELD_NAME))).thenReturn(mockKNNVectorField);

        NeuralQueryBuilder neuralQueryBuilder = new NeuralQueryBuilder().fieldName(VECTOR_FIELD_NAME)
            .queryText(QUERY_TEXT)
            .modelId(MODEL_ID)
            .k(K)
            .vectorSupplier(TEST_VECTOR_SUPPLIER);

        queryBuilder.add(neuralQueryBuilder);
        Query queryOnlyNeural = queryBuilder.doToQuery(mockQueryShardContext);
        assertNotNull(queryOnlyNeural);
        assertTrue(queryOnlyNeural instanceof HybridQuery);
        assertEquals(1, ((HybridQuery) queryOnlyNeural).getSubQueries().size());
        assertTrue(((HybridQuery) queryOnlyNeural).getSubQueries().iterator().next() instanceof KNNQuery);
        KNNQuery knnQuery = (KNNQuery) ((HybridQuery) queryOnlyNeural).getSubQueries().iterator().next();
        assertEquals(VECTOR_FIELD_NAME, knnQuery.getField());
        assertEquals(K, knnQuery.getK());
        assertNotNull(knnQuery.getQueryVector());
    }

    public void testDoToQuery_whenMultipleSubqueries_thenBuildSuccessfully() throws Exception {
        HybridQueryBuilder queryBuilder = new HybridQueryBuilder();
        Index dummyIndex = new Index("dummy", "dummy");
        QueryShardContext mockQueryShardContext = mock(QueryShardContext.class);
        KNNVectorFieldMapper.KNNVectorFieldType mockKNNVectorField = mock(KNNVectorFieldMapper.KNNVectorFieldType.class);
        when(mockQueryShardContext.index()).thenReturn(dummyIndex);
        when(mockKNNVectorField.getDimension()).thenReturn(4);
        when(mockQueryShardContext.fieldMapper(eq(VECTOR_FIELD_NAME))).thenReturn(mockKNNVectorField);

        NeuralQueryBuilder neuralQueryBuilder = new NeuralQueryBuilder().fieldName(VECTOR_FIELD_NAME)
            .queryText(QUERY_TEXT)
            .modelId(MODEL_ID)
            .k(K)
            .vectorSupplier(TEST_VECTOR_SUPPLIER);

        queryBuilder.add(neuralQueryBuilder);

        TermQueryBuilder termSubQuery = QueryBuilders.termQuery(TEXT_FIELD_NAME, TERM_QUERY_TEXT);
        queryBuilder.add(termSubQuery);
        MapperService mapperService = createMapperService(
            fieldMapping(
                b -> b.field("type", "text")
                    .field("fielddata", true)
                    .startObject("fielddata_frequency_filter")
                    .field("min", 2d)
                    .field("min_segment_size", 1000)
                    .endObject()
            )
        );
        TextFieldMapper.TextFieldType fieldType = (TextFieldMapper.TextFieldType) mapperService.fieldType(TEXT_FIELD_NAME);
        when(mockQueryShardContext.fieldMapper(eq(TEXT_FIELD_NAME))).thenReturn(fieldType);
        Query queryTwoSubQueries = queryBuilder.doToQuery(mockQueryShardContext);
        assertNotNull(queryTwoSubQueries);
        assertTrue(queryTwoSubQueries instanceof HybridQuery);
        assertEquals(2, ((HybridQuery) queryTwoSubQueries).getSubQueries().size());
        // verify knn vector query
        Iterator<Query> queryIterator = ((HybridQuery) queryTwoSubQueries).getSubQueries().iterator();
        Query firstQuery = queryIterator.next();
        assertTrue(firstQuery instanceof KNNQuery);
        KNNQuery knnQuery = (KNNQuery) firstQuery;
        assertEquals(VECTOR_FIELD_NAME, knnQuery.getField());
        assertEquals(K, knnQuery.getK());
        assertNotNull(knnQuery.getQueryVector());
        // verify term query
        Query secondQuery = queryIterator.next();
        assertTrue(secondQuery instanceof TermQuery);
        TermQuery termQuery = (TermQuery) secondQuery;
        assertEquals(TEXT_FIELD_NAME, termQuery.getTerm().field());
        assertEquals(TERM_QUERY_TEXT, termQuery.getTerm().text());
    }

    public void testDoToQuery_whenTooManySubqueries_thenFail() throws Exception {
        // create query with 6 sub-queries, which is more than current max allowed
        XContentBuilder xContentBuilder = XContentFactory.jsonBuilder()
            .startObject()
            .startArray("queries")
            .startObject()
            .startObject("term")
            .field(TEXT_FIELD_NAME, RandomizedTest.randomAsciiAlphanumOfLength(10))
            .endObject()
            .endObject()
            .startObject()
            .startObject("term")
            .field(TEXT_FIELD_NAME, RandomizedTest.randomAsciiAlphanumOfLength(10))
            .endObject()
            .endObject()
            .startObject()
            .startObject("term")
            .field(TEXT_FIELD_NAME, RandomizedTest.randomAsciiAlphanumOfLength(10))
            .endObject()
            .endObject()
            .startObject()
            .startObject("term")
            .field(TEXT_FIELD_NAME, RandomizedTest.randomAsciiAlphanumOfLength(10))
            .endObject()
            .endObject()
            .startObject()
            .startObject("term")
            .field(TEXT_FIELD_NAME, RandomizedTest.randomAsciiAlphanumOfLength(10))
            .endObject()
            .endObject()
            .startObject()
            .startObject("term")
            .field(TEXT_FIELD_NAME, TERM_QUERY_TEXT)
            .endObject()
            .endObject()
            .endArray()
            .endObject();

        NamedXContentRegistry namedXContentRegistry = new NamedXContentRegistry(
            List.of(
                new NamedXContentRegistry.Entry(QueryBuilder.class, new ParseField(TermQueryBuilder.NAME), TermQueryBuilder::fromXContent),
                new NamedXContentRegistry.Entry(
                    QueryBuilder.class,
                    new ParseField(HybridQueryBuilder.NAME),
                    HybridQueryBuilder::fromXContent
                )
            )
        );
        XContentParser contentParser = createParser(
            namedXContentRegistry,
            xContentBuilder.contentType().xContent(),
            BytesReference.bytes(xContentBuilder)
        );
        contentParser.nextToken();

        expectThrows(ParsingException.class, () -> HybridQueryBuilder.fromXContent(contentParser));
    }

    /**
     * Tests basic query:
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
     *                          "text": "keyword"
     *                      }
     *                  }
     *              ]
     *          }
     *      }
     * }
     */
    public void testFromXContent_whenMultipleSubQueries_thenBuildSuccessfully() throws Exception {
        XContentBuilder xContentBuilder = XContentFactory.jsonBuilder()
            .startObject()
            .startArray("queries")
            .startObject()
            .startObject("neural")
            .startObject(VECTOR_FIELD_NAME)
            .field(QUERY_TEXT_FIELD.getPreferredName(), QUERY_TEXT)
            .field(MODEL_ID_FIELD.getPreferredName(), MODEL_ID)
            .field(K_FIELD.getPreferredName(), K)
            .field(BOOST_FIELD.getPreferredName(), BOOST)
            .endObject()
            .endObject()
            .endObject()
            .startObject()
            .startObject("term")
            .field(TEXT_FIELD_NAME, TERM_QUERY_TEXT)
            .endObject()
            .endObject()
            .endArray()
            .endObject();

        NamedXContentRegistry namedXContentRegistry = new NamedXContentRegistry(
            List.of(
                new NamedXContentRegistry.Entry(QueryBuilder.class, new ParseField(TermQueryBuilder.NAME), TermQueryBuilder::fromXContent),
                new NamedXContentRegistry.Entry(
                    QueryBuilder.class,
                    new ParseField(NeuralQueryBuilder.NAME),
                    NeuralQueryBuilder::fromXContent
                ),
                new NamedXContentRegistry.Entry(
                    QueryBuilder.class,
                    new ParseField(HybridQueryBuilder.NAME),
                    HybridQueryBuilder::fromXContent
                )
            )
        );
        XContentParser contentParser = createParser(
            namedXContentRegistry,
            xContentBuilder.contentType().xContent(),
            BytesReference.bytes(xContentBuilder)
        );
        contentParser.nextToken();

        HybridQueryBuilder queryTwoSubQueries = HybridQueryBuilder.fromXContent(contentParser);
        assertEquals(2, queryTwoSubQueries.queries().size());
        assertTrue(queryTwoSubQueries.queries().get(0) instanceof NeuralQueryBuilder);
        assertTrue(queryTwoSubQueries.queries().get(1) instanceof TermQueryBuilder);
        // verify knn vector query
        NeuralQueryBuilder neuralQueryBuilder = (NeuralQueryBuilder) queryTwoSubQueries.queries().get(0);
        assertEquals(VECTOR_FIELD_NAME, neuralQueryBuilder.fieldName());
        assertEquals(QUERY_TEXT, neuralQueryBuilder.queryText());
        assertEquals(K, neuralQueryBuilder.k());
        assertEquals(MODEL_ID, neuralQueryBuilder.modelId());
        assertEquals(BOOST, neuralQueryBuilder.boost(), 0f);
        // verify term query
        TermQueryBuilder termQueryBuilder = (TermQueryBuilder) queryTwoSubQueries.queries().get(1);
        assertEquals(TEXT_FIELD_NAME, termQueryBuilder.fieldName());
        assertEquals(TERM_QUERY_TEXT, termQueryBuilder.value());
    }

    @SneakyThrows
    public void testToXContent() {
        HybridQueryBuilder queryBuilder = new HybridQueryBuilder();
        Index dummyIndex = new Index("dummy", "dummy");
        QueryShardContext mockQueryShardContext = mock(QueryShardContext.class);
        KNNVectorFieldMapper.KNNVectorFieldType mockKNNVectorField = mock(KNNVectorFieldMapper.KNNVectorFieldType.class);
        when(mockQueryShardContext.index()).thenReturn(dummyIndex);
        when(mockKNNVectorField.getDimension()).thenReturn(4);
        when(mockQueryShardContext.fieldMapper(eq(VECTOR_FIELD_NAME))).thenReturn(mockKNNVectorField);

        NeuralQueryBuilder neuralQueryBuilder = new NeuralQueryBuilder().fieldName(VECTOR_FIELD_NAME)
            .queryText(QUERY_TEXT)
            .modelId(MODEL_ID)
            .k(K)
            .vectorSupplier(TEST_VECTOR_SUPPLIER)
            .filter(TEST_FILTER);

        queryBuilder.add(neuralQueryBuilder);

        TermQueryBuilder termSubQuery = QueryBuilders.termQuery(TEXT_FIELD_NAME, TERM_QUERY_TEXT);
        queryBuilder.add(termSubQuery);
        MapperService mapperService = createMapperService(
            fieldMapping(
                b -> b.field("type", "text")
                    .field("fielddata", true)
                    .startObject("fielddata_frequency_filter")
                    .field("min", 2d)
                    .field("min_segment_size", 1000)
                    .endObject()
            )
        );
        TextFieldMapper.TextFieldType fieldType = (TextFieldMapper.TextFieldType) mapperService.fieldType(TEXT_FIELD_NAME);
        when(mockQueryShardContext.fieldMapper(eq(TEXT_FIELD_NAME))).thenReturn(fieldType);

        XContentBuilder builder = XContentFactory.jsonBuilder();
        builder = queryBuilder.toXContent(builder, ToXContent.EMPTY_PARAMS);
        Map<String, Object> out = xContentBuilderToMap(builder);

        Object outer = out.get(HybridQueryBuilder.NAME);
        if (!(outer instanceof Map)) {
            fail("hybrid does not map to nested object");
        }

        Map<String, Object> outerMap = (Map<String, Object>) outer;

        assertNotNull(outerMap);
        assertTrue(outerMap.containsKey("queries"));
        assertTrue(outerMap.get("queries") instanceof List);
        List listWithQueries = (List) outerMap.get("queries");
        assertEquals(2, listWithQueries.size());

        // verify neural search query
        Map<String, Object> vectorFieldInnerMap = getInnerMap(listWithQueries.get(0), NeuralQueryBuilder.NAME, VECTOR_FIELD_NAME);
        assertEquals(MODEL_ID, vectorFieldInnerMap.get(MODEL_ID_FIELD.getPreferredName()));
        assertEquals(QUERY_TEXT, vectorFieldInnerMap.get(QUERY_TEXT_FIELD.getPreferredName()));
        assertEquals(K, vectorFieldInnerMap.get(K_FIELD.getPreferredName()));
        XContentBuilder xContentBuilder = XContentFactory.jsonBuilder();
        assertEquals(
            xContentBuilderToMap(TEST_FILTER.toXContent(xContentBuilder, EMPTY_PARAMS)),
            vectorFieldInnerMap.get(FILTER_FIELD.getPreferredName())
        );
        // verify term query
        Map<String, Object> termFieldInnerMap = getInnerMap(listWithQueries.get(1), TermQueryBuilder.NAME, TEXT_FIELD_NAME);
        assertEquals(TERM_QUERY_TEXT, termFieldInnerMap.get("value"));
    }

    @SneakyThrows
    public void testStreams() {
        HybridQueryBuilder original = new HybridQueryBuilder();
        NeuralQueryBuilder neuralQueryBuilder = new NeuralQueryBuilder().fieldName(VECTOR_FIELD_NAME)
            .queryText(QUERY_TEXT)
            .modelId(MODEL_ID)
            .k(K)
            .vectorSupplier(TEST_VECTOR_SUPPLIER);

        original.add(neuralQueryBuilder);

        TermQueryBuilder termSubQuery = QueryBuilders.termQuery(TEXT_FIELD_NAME, TERM_QUERY_TEXT);
        original.add(termSubQuery);

        BytesStreamOutput streamOutput = new BytesStreamOutput();
        original.writeTo(streamOutput);

        FilterStreamInput filterStreamInput = new NamedWriteableAwareStreamInput(
            streamOutput.bytes().streamInput(),
            new NamedWriteableRegistry(
                List.of(
                    new NamedWriteableRegistry.Entry(QueryBuilder.class, TermQueryBuilder.NAME, TermQueryBuilder::new),
                    new NamedWriteableRegistry.Entry(QueryBuilder.class, NeuralQueryBuilder.NAME, NeuralQueryBuilder::new),
                    new NamedWriteableRegistry.Entry(QueryBuilder.class, HybridQueryBuilder.NAME, HybridQueryBuilder::new)
                )
            )
        );

        HybridQueryBuilder copy = new HybridQueryBuilder(filterStreamInput);
        assertEquals(original, copy);
    }

    public void testHashAndEquals_whenSameOrIdenticalObject_thenReturnEqual() {
        HybridQueryBuilder hybridQueryBuilderBaseline = new HybridQueryBuilder();
        hybridQueryBuilderBaseline.add(
            new NeuralQueryBuilder().fieldName(VECTOR_FIELD_NAME)
                .queryText(QUERY_TEXT)
                .modelId(MODEL_ID)
                .k(K)
                .vectorSupplier(TEST_VECTOR_SUPPLIER)
                .filter(TEST_FILTER)
        );
        hybridQueryBuilderBaseline.add(QueryBuilders.termQuery(TEXT_FIELD_NAME, TERM_QUERY_TEXT));

        HybridQueryBuilder hybridQueryBuilderBaselineCopy = new HybridQueryBuilder();
        hybridQueryBuilderBaselineCopy.add(
            new NeuralQueryBuilder().fieldName(VECTOR_FIELD_NAME)
                .queryText(QUERY_TEXT)
                .modelId(MODEL_ID)
                .k(K)
                .vectorSupplier(TEST_VECTOR_SUPPLIER)
                .filter(TEST_FILTER)
        );
        hybridQueryBuilderBaselineCopy.add(QueryBuilders.termQuery(TEXT_FIELD_NAME, TERM_QUERY_TEXT));

        assertEquals(hybridQueryBuilderBaseline, hybridQueryBuilderBaseline);
        assertEquals(hybridQueryBuilderBaseline.hashCode(), hybridQueryBuilderBaseline.hashCode());

        assertEquals(hybridQueryBuilderBaselineCopy, hybridQueryBuilderBaselineCopy);
        assertEquals(hybridQueryBuilderBaselineCopy.hashCode(), hybridQueryBuilderBaselineCopy.hashCode());
    }

    public void testHashAndEquals_whenSubQueriesDifferent_thenReturnNotEqual() {
        String modelId = "testModelId";
        String fieldName = "fieldTwo";
        String queryText = "query text";
        String termText = "another keyword";

        HybridQueryBuilder hybridQueryBuilderBaseline = new HybridQueryBuilder();
        hybridQueryBuilderBaseline.add(
            new NeuralQueryBuilder().fieldName(VECTOR_FIELD_NAME)
                .queryText(QUERY_TEXT)
                .modelId(MODEL_ID)
                .k(K)
                .vectorSupplier(TEST_VECTOR_SUPPLIER)
                .filter(TEST_FILTER)
        );
        hybridQueryBuilderBaseline.add(QueryBuilders.termQuery(TEXT_FIELD_NAME, TERM_QUERY_TEXT));

        HybridQueryBuilder hybridQueryBuilderOnlyOneSubQuery = new HybridQueryBuilder();
        hybridQueryBuilderOnlyOneSubQuery.add(
            new NeuralQueryBuilder().fieldName(VECTOR_FIELD_NAME)
                .queryText(QUERY_TEXT)
                .modelId(MODEL_ID)
                .k(K)
                .vectorSupplier(TEST_VECTOR_SUPPLIER)
                .filter(TEST_FILTER)
        );

        HybridQueryBuilder hybridQueryBuilderOnlyDifferentModelId = new HybridQueryBuilder();
        hybridQueryBuilderOnlyDifferentModelId.add(
            new NeuralQueryBuilder().fieldName(VECTOR_FIELD_NAME)
                .queryText(QUERY_TEXT)
                .modelId(modelId)
                .k(K)
                .vectorSupplier(TEST_VECTOR_SUPPLIER)
                .filter(TEST_FILTER)
        );
        hybridQueryBuilderBaseline.add(QueryBuilders.termQuery(TEXT_FIELD_NAME, TERM_QUERY_TEXT));

        HybridQueryBuilder hybridQueryBuilderOnlyDifferentFieldName = new HybridQueryBuilder();
        hybridQueryBuilderOnlyDifferentFieldName.add(
            new NeuralQueryBuilder().fieldName(fieldName)
                .queryText(QUERY_TEXT)
                .modelId(MODEL_ID)
                .k(K)
                .vectorSupplier(TEST_VECTOR_SUPPLIER)
                .filter(TEST_FILTER)
        );
        hybridQueryBuilderOnlyDifferentFieldName.add(QueryBuilders.termQuery(TEXT_FIELD_NAME, TERM_QUERY_TEXT));

        HybridQueryBuilder hybridQueryBuilderOnlyDifferentQuery = new HybridQueryBuilder();
        hybridQueryBuilderOnlyDifferentQuery.add(
            new NeuralQueryBuilder().fieldName(VECTOR_FIELD_NAME)
                .queryText(queryText)
                .modelId(MODEL_ID)
                .k(K)
                .vectorSupplier(TEST_VECTOR_SUPPLIER)
                .filter(TEST_FILTER)
        );
        hybridQueryBuilderOnlyDifferentQuery.add(QueryBuilders.termQuery(TEXT_FIELD_NAME, TERM_QUERY_TEXT));

        HybridQueryBuilder hybridQueryBuilderOnlyDifferentTermValue = new HybridQueryBuilder();
        hybridQueryBuilderOnlyDifferentTermValue.add(
            new NeuralQueryBuilder().fieldName(VECTOR_FIELD_NAME)
                .queryText(QUERY_TEXT)
                .modelId(MODEL_ID)
                .k(K)
                .vectorSupplier(TEST_VECTOR_SUPPLIER)
                .filter(TEST_FILTER)
        );
        hybridQueryBuilderOnlyDifferentTermValue.add(QueryBuilders.termQuery(TEXT_FIELD_NAME, termText));

        assertNotEquals(hybridQueryBuilderBaseline, hybridQueryBuilderOnlyOneSubQuery);
        assertNotEquals(hybridQueryBuilderBaseline.hashCode(), hybridQueryBuilderOnlyOneSubQuery.hashCode());

        assertNotEquals(hybridQueryBuilderBaseline, hybridQueryBuilderOnlyDifferentModelId);
        assertNotEquals(hybridQueryBuilderBaseline.hashCode(), hybridQueryBuilderOnlyDifferentModelId.hashCode());

        assertNotEquals(hybridQueryBuilderBaseline, hybridQueryBuilderOnlyDifferentFieldName);
        assertNotEquals(hybridQueryBuilderBaseline.hashCode(), hybridQueryBuilderOnlyDifferentFieldName.hashCode());

        assertNotEquals(hybridQueryBuilderBaseline, hybridQueryBuilderOnlyDifferentQuery);
        assertNotEquals(hybridQueryBuilderBaseline.hashCode(), hybridQueryBuilderOnlyDifferentQuery.hashCode());

        assertNotEquals(hybridQueryBuilderBaseline, hybridQueryBuilderOnlyDifferentTermValue);
        assertNotEquals(hybridQueryBuilderBaseline.hashCode(), hybridQueryBuilderOnlyDifferentTermValue.hashCode());
    }

    @SneakyThrows
    public void testRewrite_whenMultipleSubQueries_thenReturnBuilderForEachSubQuery() {
        HybridQueryBuilder queryBuilder = new HybridQueryBuilder();
        NeuralQueryBuilder neuralQueryBuilder = new NeuralQueryBuilder().fieldName(VECTOR_FIELD_NAME)
            .queryText(QUERY_TEXT)
            .modelId(MODEL_ID)
            .k(K)
            .vectorSupplier(TEST_VECTOR_SUPPLIER);

        queryBuilder.add(neuralQueryBuilder);

        TermQueryBuilder termSubQuery = QueryBuilders.termQuery(TEXT_FIELD_NAME, TERM_QUERY_TEXT);
        queryBuilder.add(termSubQuery);

        QueryShardContext mockQueryShardContext = mock(QueryShardContext.class);
        KNNVectorFieldMapper.KNNVectorFieldType mockKNNVectorField = mock(KNNVectorFieldMapper.KNNVectorFieldType.class);
        Index dummyIndex = new Index("dummy", "dummy");
        when(mockQueryShardContext.index()).thenReturn(dummyIndex);
        when(mockKNNVectorField.getDimension()).thenReturn(4);
        when(mockQueryShardContext.fieldMapper(eq(VECTOR_FIELD_NAME))).thenReturn(mockKNNVectorField);

        MapperService mapperService = createMapperService(
            fieldMapping(
                b -> b.field("type", "text")
                    .field("fielddata", true)
                    .startObject("fielddata_frequency_filter")
                    .field("min", 2d)
                    .field("min_segment_size", 1000)
                    .endObject()
            )
        );
        TextFieldMapper.TextFieldType fieldType = (TextFieldMapper.TextFieldType) mapperService.fieldType(TEXT_FIELD_NAME);
        when(mockQueryShardContext.fieldMapper(eq(TEXT_FIELD_NAME))).thenReturn(fieldType);

        QueryBuilder queryBuilderAfterRewrite = queryBuilder.doRewrite(mockQueryShardContext);
        assertTrue(queryBuilderAfterRewrite instanceof HybridQueryBuilder);
        HybridQueryBuilder hybridQueryBuilder = (HybridQueryBuilder) queryBuilderAfterRewrite;
        assertNotNull(hybridQueryBuilder.queries());
        assertEquals(2, hybridQueryBuilder.queries().size());
        List<QueryBuilder> queryBuilders = hybridQueryBuilder.queries();
        // verify each sub-query builder
        assertTrue(queryBuilders.get(0) instanceof KNNQueryBuilder);
        KNNQueryBuilder knnQueryBuilder = (KNNQueryBuilder) queryBuilders.get(0);
        assertEquals(neuralQueryBuilder.fieldName(), knnQueryBuilder.fieldName());
        assertEquals(neuralQueryBuilder.k(), knnQueryBuilder.getK());
        assertTrue(queryBuilders.get(1) instanceof TermQueryBuilder);
        TermQueryBuilder termQueryBuilder = (TermQueryBuilder) queryBuilders.get(1);
        assertEquals(termSubQuery.fieldName(), termQueryBuilder.fieldName());
        assertEquals(termSubQuery.value(), termQueryBuilder.value());
    }

    protected final MapperService createMapperService(Version version, XContentBuilder mapping) throws IOException {
        IndexMetadata meta = IndexMetadata.builder("index")
            .settings(Settings.builder().put("index.version.created", version))
            .numberOfReplicas(0)
            .numberOfShards(1)
            .build();
        IndexSettings indexSettings = new IndexSettings(meta, getIndexSettings());
        MapperRegistry mapperRegistry = new IndicesModule(
            Stream.of().filter(p -> p instanceof MapperPlugin).map(p -> (MapperPlugin) p).collect(toList())
        ).getMapperRegistry();
        ScriptModule scriptModule = new ScriptModule(
            Settings.EMPTY,
            Stream.of().filter(p -> p instanceof ScriptPlugin).map(p -> (ScriptPlugin) p).collect(toList())
        );
        ScriptService scriptService = new ScriptService(getIndexSettings(), scriptModule.engines, scriptModule.contexts);
        SimilarityService similarityService = new SimilarityService(indexSettings, scriptService, emptyMap());
        MapperService mapperService = new MapperService(
            indexSettings,
            createIndexAnalyzers(indexSettings),
            xContentRegistry(),
            similarityService,
            mapperRegistry,
            () -> { throw new UnsupportedOperationException(); },
            () -> true,
            scriptService
        );
        merge(mapperService, mapping);
        return mapperService;
    }

    protected Settings getIndexSettings() {
        return Settings.builder().put("index.version.created", Version.CURRENT).build();
    }

    protected IndexAnalyzers createIndexAnalyzers(IndexSettings indexSettings) {
        return new IndexAnalyzers(
            singletonMap("default", new NamedAnalyzer("default", AnalyzerScope.INDEX, new StandardAnalyzer())),
            emptyMap(),
            emptyMap()
        );
    }

    protected final void merge(MapperService mapperService, XContentBuilder mapping) throws IOException {
        mapperService.merge("_doc", new CompressedXContent(BytesReference.bytes(mapping)), MapperService.MergeReason.MAPPING_UPDATE);
    }

    protected final XContentBuilder fieldMapping(CheckedConsumer<XContentBuilder, IOException> buildField) throws IOException {
        return mapping(b -> {
            b.startObject("field");
            buildField.accept(b);
            b.endObject();
        });
    }

    protected final XContentBuilder mapping(CheckedConsumer<XContentBuilder, IOException> buildFields) throws IOException {
        XContentBuilder builder = XContentFactory.jsonBuilder().startObject().startObject("_doc").startObject("properties");
        buildFields.accept(builder);
        return builder.endObject().endObject().endObject();
    }

    protected MapperService createMapperService(XContentBuilder mappings) throws IOException {
        return createMapperService(Version.CURRENT, mappings);
    }

    private static Map<String, Object> getInnerMap(Object innerObject, String queryName, String fieldName) {
        if (!(innerObject instanceof Map)) {
            fail("field name does not map to nested object");
        }
        Map<String, Object> secondInnerMap = (Map<String, Object>) innerObject;
        assertTrue(secondInnerMap.containsKey(queryName));
        assertTrue(secondInnerMap.get(queryName) instanceof Map);
        Map<String, Object> neuralInnerMap = (Map<String, Object>) secondInnerMap.get(queryName);
        assertTrue(neuralInnerMap.containsKey(fieldName));
        assertTrue(neuralInnerMap.get(fieldName) instanceof Map);
        Map<String, Object> vectorFieldInnerMap = (Map<String, Object>) neuralInnerMap.get(fieldName);
        return vectorFieldInnerMap;
    }
}
