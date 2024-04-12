/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.processor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;
import org.opensearch.common.collect.Tuple;
import org.opensearch.core.common.util.CollectionUtils;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.env.Environment;
import org.opensearch.ingest.AbstractProcessor;
import org.opensearch.ingest.IngestDocument;
import org.opensearch.ingest.IngestDocumentWrapper;
import org.opensearch.neuralsearch.ml.MLCommonsClientAccessor;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;

import lombok.extern.log4j.Log4j2;
import org.opensearch.neuralsearch.util.ProcessorDocumentUtils;

/**
 * The abstract class for text processing use cases. Users provide a field name map and a model id.
 * During ingestion, the processor will use the corresponding model to inference the input texts,
 * and set the target fields according to the field name map.
 */
@Log4j2
public abstract class InferenceProcessor extends AbstractProcessor {

    public static final String MODEL_ID_FIELD = "model_id";
    public static final String FIELD_MAP_FIELD = "field_map";

    private final String type;

    // This field is used for nested knn_vector/rank_features field. The value of the field will be used as the
    // default key for the nested object.
    private final String listTypeNestedMapKey;

    protected final String modelId;

    private final Map<String, Object> fieldMap;

    protected final MLCommonsClientAccessor mlCommonsClientAccessor;

    private final Environment environment;
    private final ClusterService clusterService;

    public InferenceProcessor(
        String tag,
        String description,
        String type,
        String listTypeNestedMapKey,
        String modelId,
        Map<String, Object> fieldMap,
        MLCommonsClientAccessor clientAccessor,
        Environment environment,
        ClusterService clusterService
    ) {
        super(tag, description);
        this.type = type;
        if (StringUtils.isBlank(modelId)) throw new IllegalArgumentException("model_id is null or empty, cannot process it");
        validateEmbeddingConfiguration(fieldMap);
        this.listTypeNestedMapKey = listTypeNestedMapKey;
        this.modelId = modelId;
        this.fieldMap = fieldMap;
        this.mlCommonsClientAccessor = clientAccessor;
        this.environment = environment;
        this.clusterService = clusterService;
    }

    private void validateEmbeddingConfiguration(Map<String, Object> fieldMap) {
        if (fieldMap == null
            || fieldMap.size() == 0
            || fieldMap.entrySet()
                .stream()
                .anyMatch(
                    x -> StringUtils.isBlank(x.getKey()) || Objects.isNull(x.getValue()) || StringUtils.isBlank(x.getValue().toString())
                )) {
            throw new IllegalArgumentException("Unable to create the processor as field_map has invalid key or value");
        }
    }

    public abstract void doExecute(
        IngestDocument ingestDocument,
        Map<String, Object> ProcessMap,
        List<String> inferenceList,
        BiConsumer<IngestDocument, Exception> handler
    );

    @Override
    public IngestDocument execute(IngestDocument ingestDocument) throws Exception {
        return ingestDocument;
    }

    /**
     * This method will be invoked by PipelineService to make async inference and then delegate the handler to
     * process the inference response or failure.
     * @param ingestDocument {@link IngestDocument} which is the document passed to processor.
     * @param handler {@link BiConsumer} which is the handler which can be used after the inference task is done.
     */
    @Override
    public void execute(IngestDocument ingestDocument, BiConsumer<IngestDocument, Exception> handler) {
        try {
            validateEmbeddingFieldsValue(ingestDocument);
            Map<String, Object> processMap = buildMapWithTargetKeyAndOriginalValue(ingestDocument);
            List<String> inferenceList = createInferenceList(processMap);
            if (inferenceList.size() == 0) {
                handler.accept(ingestDocument, null);
            } else {
                doExecute(ingestDocument, processMap, inferenceList, handler);
            }
        } catch (Exception e) {
            handler.accept(null, e);
        }
    }

    /**
     * This is the function which does actual inference work for batchExecute interface.
     * @param inferenceList a list of String for inference.
     * @param handler a callback handler to handle inference results which is a list of objects.
     * @param onException an exception callback to handle exception.
     */
    abstract void doBatchExecute(List<String> inferenceList, Consumer<List<?>> handler, Consumer<Exception> onException);

    @Override
    public void batchExecute(List<IngestDocumentWrapper> ingestDocumentWrappers, Consumer<List<IngestDocumentWrapper>> handler) {
        if (CollectionUtils.isEmpty(ingestDocumentWrappers)) {
            handler.accept(Collections.emptyList());
            return;
        }

        List<DataForInference> dataForInferences = getDataForInference(ingestDocumentWrappers);
        List<String> inferenceList = constructInferenceTexts(dataForInferences);
        if (inferenceList.isEmpty()) {
            handler.accept(ingestDocumentWrappers);
            return;
        }
        Tuple<List<String>, Map<Integer, Integer>> sortedResult = sortByLengthAndReturnOriginalOrder(inferenceList);
        inferenceList = sortedResult.v1();
        Map<Integer, Integer> originalOrder = sortedResult.v2();
        doBatchExecute(inferenceList, results -> {
            int startIndex = 0;
            results = restoreToOriginalOrder(results, originalOrder);
            for (DataForInference dataForInference : dataForInferences) {
                if (dataForInference.getIngestDocumentWrapper().getException() != null
                    || CollectionUtils.isEmpty(dataForInference.getInferenceList())) {
                    continue;
                }
                List<?> inferenceResults = results.subList(startIndex, startIndex + dataForInference.getInferenceList().size());
                startIndex += dataForInference.getInferenceList().size();
                setVectorFieldsToDocument(
                    dataForInference.getIngestDocumentWrapper().getIngestDocument(),
                    dataForInference.getProcessMap(),
                    inferenceResults
                );
            }
            handler.accept(ingestDocumentWrappers);
        }, exception -> {
            for (IngestDocumentWrapper ingestDocumentWrapper : ingestDocumentWrappers) {
                // The IngestDocumentWrapper might already run into exception and not sent for inference. So here we only
                // set exception to IngestDocumentWrapper which doesn't have exception before.
                if (ingestDocumentWrapper.getException() == null) {
                    ingestDocumentWrapper.update(ingestDocumentWrapper.getIngestDocument(), exception);
                }
            }
            handler.accept(ingestDocumentWrappers);
        });
    }

    private Tuple<List<String>, Map<Integer, Integer>> sortByLengthAndReturnOriginalOrder(List<String> inferenceList) {
        List<Tuple<Integer, String>> docsWithIndex = new ArrayList<>();
        for (int i = 0; i < inferenceList.size(); ++i) {
            docsWithIndex.add(Tuple.tuple(i, inferenceList.get(i)));
        }
        docsWithIndex.sort(Comparator.comparingInt(t -> t.v2().length()));
        List<String> sortedInferenceList = docsWithIndex.stream().map(Tuple::v2).collect(Collectors.toList());
        Map<Integer, Integer> originalOrderMap = new HashMap<>();
        for (int i = 0; i < docsWithIndex.size(); ++i) {
            originalOrderMap.put(i, docsWithIndex.get(i).v1());
        }
        return Tuple.tuple(sortedInferenceList, originalOrderMap);
    }

    private List<?> restoreToOriginalOrder(List<?> results, Map<Integer, Integer> originalOrder) {
        List<Object> sortedResults = Arrays.asList(results.toArray());
        for (int i = 0; i < results.size(); ++i) {
            if (!originalOrder.containsKey(i)) continue;
            int oldIndex = originalOrder.get(i);
            sortedResults.set(oldIndex, results.get(i));
        }
        return sortedResults;
    }

    private List<String> constructInferenceTexts(List<DataForInference> dataForInferences) {
        List<String> inferenceTexts = new ArrayList<>();
        for (DataForInference dataForInference : dataForInferences) {
            if (dataForInference.getIngestDocumentWrapper().getException() != null
                || CollectionUtils.isEmpty(dataForInference.getInferenceList())) {
                continue;
            }
            inferenceTexts.addAll(dataForInference.getInferenceList());
        }
        return inferenceTexts;
    }

    private List<DataForInference> getDataForInference(List<IngestDocumentWrapper> ingestDocumentWrappers) {
        List<DataForInference> dataForInferences = new ArrayList<>();
        for (IngestDocumentWrapper ingestDocumentWrapper : ingestDocumentWrappers) {
            Map<String, Object> processMap = null;
            List<String> inferenceList = null;
            try {
                validateEmbeddingFieldsValue(ingestDocumentWrapper.getIngestDocument());
                processMap = buildMapWithTargetKeyAndOriginalValue(ingestDocumentWrapper.getIngestDocument());
                inferenceList = createInferenceList(processMap);
            } catch (Exception e) {
                ingestDocumentWrapper.update(ingestDocumentWrapper.getIngestDocument(), e);
            } finally {
                dataForInferences.add(new DataForInference(ingestDocumentWrapper, processMap, inferenceList));
            }
        }
        return dataForInferences;
    }

    @Getter
    @AllArgsConstructor
    private static class DataForInference {
        private final IngestDocumentWrapper ingestDocumentWrapper;
        private final Map<String, Object> processMap;
        private final List<String> inferenceList;
    }

    @SuppressWarnings({ "unchecked" })
    private List<String> createInferenceList(Map<String, Object> knnKeyMap) {
        List<String> texts = new ArrayList<>();
        knnKeyMap.entrySet().stream().filter(knnMapEntry -> knnMapEntry.getValue() != null).forEach(knnMapEntry -> {
            Object sourceValue = knnMapEntry.getValue();
            if (sourceValue instanceof List) {
                texts.addAll(((List<String>) sourceValue));
            } else if (sourceValue instanceof Map) {
                createInferenceListForMapTypeInput(sourceValue, texts);
            } else {
                texts.add(sourceValue.toString());
            }
        });
        return texts;
    }

    @SuppressWarnings("unchecked")
    private void createInferenceListForMapTypeInput(Object sourceValue, List<String> texts) {
        if (sourceValue instanceof Map) {
            ((Map<String, Object>) sourceValue).forEach((k, v) -> createInferenceListForMapTypeInput(v, texts));
        } else if (sourceValue instanceof List) {
            texts.addAll(((List<String>) sourceValue));
        } else {
            if (sourceValue == null) return;
            texts.add(sourceValue.toString());
        }
    }

    @VisibleForTesting
    Map<String, Object> buildMapWithTargetKeyAndOriginalValue(IngestDocument ingestDocument) {
        Map<String, Object> sourceAndMetadataMap = ingestDocument.getSourceAndMetadata();
        Map<String, Object> mapWithProcessorKeys = new LinkedHashMap<>();
        for (Map.Entry<String, Object> fieldMapEntry : fieldMap.entrySet()) {
            String originalKey = fieldMapEntry.getKey();
            Object targetKey = fieldMapEntry.getValue();
            if (targetKey instanceof Map) {
                Map<String, Object> treeRes = new LinkedHashMap<>();
                buildMapWithProcessorKeyAndOriginalValueForMapType(originalKey, targetKey, sourceAndMetadataMap, treeRes);
                mapWithProcessorKeys.put(originalKey, treeRes.get(originalKey));
            } else {
                mapWithProcessorKeys.put(String.valueOf(targetKey), sourceAndMetadataMap.get(originalKey));
            }
        }
        return mapWithProcessorKeys;
    }

    private void buildMapWithProcessorKeyAndOriginalValueForMapType(
        String parentKey,
        Object processorKey,
        Map<String, Object> sourceAndMetadataMap,
        Map<String, Object> treeRes
    ) {
        if (processorKey == null || sourceAndMetadataMap == null) return;
        if (processorKey instanceof Map) {
            Map<String, Object> next = new LinkedHashMap<>();
            if (sourceAndMetadataMap.get(parentKey) instanceof Map) {
                for (Map.Entry<String, Object> nestedFieldMapEntry : ((Map<String, Object>) processorKey).entrySet()) {
                    buildMapWithProcessorKeyAndOriginalValueForMapType(
                        nestedFieldMapEntry.getKey(),
                        nestedFieldMapEntry.getValue(),
                        (Map<String, Object>) sourceAndMetadataMap.get(parentKey),
                        next
                    );
                }
            } else if (sourceAndMetadataMap.get(parentKey) instanceof List) {
                for (Map.Entry<String, Object> nestedFieldMapEntry : ((Map<String, Object>) processorKey).entrySet()) {
                    List<Map<String, Object>> list = (List<Map<String, Object>>) sourceAndMetadataMap.get(parentKey);
                    List<Object> listOfStrings = list.stream().map(x -> x.get(nestedFieldMapEntry.getKey())).collect(Collectors.toList());
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put(nestedFieldMapEntry.getKey(), listOfStrings);
                    buildMapWithProcessorKeyAndOriginalValueForMapType(
                        nestedFieldMapEntry.getKey(),
                        nestedFieldMapEntry.getValue(),
                        map,
                        next
                    );
                }
            }
            treeRes.put(parentKey, next);
        } else {
            String key = String.valueOf(processorKey);
            treeRes.put(key, sourceAndMetadataMap.get(parentKey));
        }
    }

    private void validateEmbeddingFieldsValue(IngestDocument ingestDocument) {
        Map<String, Object> sourceAndMetadataMap = ingestDocument.getSourceAndMetadata();
        ProcessorDocumentUtils.validateMapTypeValue(
            "field_map",
            sourceAndMetadataMap,
            fieldMap,
            1,
            ProcessorDocumentUtils.getMaxDepth(sourceAndMetadataMap, clusterService, environment),
            true
        );
    }

    protected void setVectorFieldsToDocument(IngestDocument ingestDocument, Map<String, Object> processorMap, List<?> results) {
        Objects.requireNonNull(results, "embedding failed, inference returns null result!");
        log.debug("Model inference result fetched, starting build vector output!");
        Map<String, Object> nlpResult = buildNLPResult(processorMap, results, ingestDocument.getSourceAndMetadata());
        nlpResult.forEach(ingestDocument::setFieldValue);
    }

    @SuppressWarnings({ "unchecked" })
    @VisibleForTesting
    Map<String, Object> buildNLPResult(Map<String, Object> processorMap, List<?> results, Map<String, Object> sourceAndMetadataMap) {
        IndexWrapper indexWrapper = new IndexWrapper(0);
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> knnMapEntry : processorMap.entrySet()) {
            String knnKey = knnMapEntry.getKey();
            Object sourceValue = knnMapEntry.getValue();
            if (sourceValue instanceof String) {
                result.put(knnKey, results.get(indexWrapper.index++));
            } else if (sourceValue instanceof List) {
                result.put(knnKey, buildNLPResultForListType((List<String>) sourceValue, results, indexWrapper));
            } else if (sourceValue instanceof Map) {
                putNLPResultToSourceMapForMapType(knnKey, sourceValue, results, indexWrapper, sourceAndMetadataMap);
            }
        }
        return result;
    }

    @SuppressWarnings({ "unchecked" })
    private void putNLPResultToSourceMapForMapType(
        String processorKey,
        Object sourceValue,
        List<?> results,
        IndexWrapper indexWrapper,
        Map<String, Object> sourceAndMetadataMap
    ) {
        if (processorKey == null || sourceAndMetadataMap == null || sourceValue == null) return;
        if (sourceValue instanceof Map) {
            for (Map.Entry<String, Object> inputNestedMapEntry : ((Map<String, Object>) sourceValue).entrySet()) {
                if (sourceAndMetadataMap.get(processorKey) instanceof List) {
                    // build nlp output for list of nested objects
                    for (Map<String, Object> nestedElement : (List<Map<String, Object>>) sourceAndMetadataMap.get(processorKey)) {
                        nestedElement.put(inputNestedMapEntry.getKey(), results.get(indexWrapper.index++));
                    }
                } else {
                    putNLPResultToSourceMapForMapType(
                        inputNestedMapEntry.getKey(),
                        inputNestedMapEntry.getValue(),
                        results,
                        indexWrapper,
                        (Map<String, Object>) sourceAndMetadataMap.get(processorKey)
                    );
                }
            }
        } else if (sourceValue instanceof String) {
            sourceAndMetadataMap.put(processorKey, results.get(indexWrapper.index++));
        } else if (sourceValue instanceof List) {
            sourceAndMetadataMap.put(processorKey, buildNLPResultForListType((List<String>) sourceValue, results, indexWrapper));
        }
    }

    private List<Map<String, Object>> buildNLPResultForListType(List<String> sourceValue, List<?> results, IndexWrapper indexWrapper) {
        List<Map<String, Object>> keyToResult = new ArrayList<>();
        IntStream.range(0, sourceValue.size())
            .forEachOrdered(x -> keyToResult.add(ImmutableMap.of(listTypeNestedMapKey, results.get(indexWrapper.index++))));
        return keyToResult;
    }

    @Override
    public String getType() {
        return type;
    }

    /**
     * Since we need to build a {@link List<String>} as the input for text embedding, and the result type is {@link List<Float>} of {@link List},
     * we need to map the result back to the input one by one with exactly order. For nested map type input, we're performing a pre-order
     * traversal to extract the input strings, so when mapping back to the nested map, we still need a pre-order traversal to ensure the
     * order. And we also need to ensure the index pointer goes forward in the recursive, so here the IndexWrapper is to store and increase
     * the index pointer during the recursive.
     * index: the index pointer of the text embedding result.
     */
    static class IndexWrapper {
        private int index;

        protected IndexWrapper(int index) {
            this.index = index;
        }
    }
}
