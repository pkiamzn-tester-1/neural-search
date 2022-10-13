/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.neuralsearch.processor.factory;

import static org.opensearch.ingest.ConfigurationUtils.readOptionalMap;
import static org.opensearch.ingest.ConfigurationUtils.readStringProperty;
import static org.opensearch.neuralsearch.processor.TextEmbeddingProcessor.*;

import java.util.Map;

import org.opensearch.ingest.Processor;
import org.opensearch.neuralsearch.ml.MLCommonsClientAccessor;
import org.opensearch.neuralsearch.processor.TextEmbeddingProcessor;

public class TextEmbeddingProcessorFactory implements Processor.Factory {

    private final MLCommonsClientAccessor clientAccessor;

    public TextEmbeddingProcessorFactory(MLCommonsClientAccessor clientAccessor) {
        this.clientAccessor = clientAccessor;
    }

    @Override
    public TextEmbeddingProcessor create(
        Map<String, Processor.Factory> registry,
        String processorTag,
        String description,
        Map<String, Object> config
    ) throws Exception {
        String modelId = readStringProperty(TYPE, processorTag, config, MODEL_ID_FIELD);
        Map<String, Object> filedMap = readOptionalMap(TYPE, processorTag, config, FIELD_MAP_FIELD);
        return new TextEmbeddingProcessor(processorTag, description, modelId, filedMap, clientAccessor);
    }
}
