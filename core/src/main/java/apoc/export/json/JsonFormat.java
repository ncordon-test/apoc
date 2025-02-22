/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package apoc.export.json;

import apoc.export.cypher.ExportFileManager;
import apoc.export.util.ExportConfig;
import apoc.export.util.Reporter;
import apoc.meta.Types;
import apoc.result.ProgressInfo;
import apoc.util.JsonUtil;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.MinimalPrettyPrinter;
import org.neo4j.cypher.export.SubGraph;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.security.AuthorizationViolationException;

import java.io.IOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static apoc.util.Util.INVALID_QUERY_MODE_ERROR;


public class JsonFormat {
    enum Format {JSON_LINES, ARRAY_JSON, JSON, JSON_ID_AS_KEYS}
    private final GraphDatabaseService db;
    private final Format format;

    private boolean isExportSubGraph = false;

    public JsonFormat(GraphDatabaseService db, Format format) {
        this.db = db;
        this.format = format;
    }

    private ProgressInfo dump(Writer writer, Reporter reporter, Consumer<JsonGenerator> consumer) throws Exception {
        try (Transaction tx = db.beginTx();
             JsonGenerator jsonGenerator = getJsonGenerator(writer)) {
            consumer.accept(jsonGenerator);
            jsonGenerator.flush();
            tx.commit();
        }
        reporter.done();
        return reporter.getTotal();
    }

    public ProgressInfo dump(SubGraph graph, ExportFileManager writer, Reporter reporter, ExportConfig config) throws Exception {
        isExportSubGraph = true;
        Consumer<JsonGenerator> consumer = (jsonGenerator) -> {
            try {
                writeJsonContainerStart(jsonGenerator);
                writeJsonNodeContainerStart(jsonGenerator);
                writeNodes(graph.getNodes(), reporter, jsonGenerator, config);
                writeJsonNodeContainerEnd(jsonGenerator);
                writeJsonRelationshipContainerStart(jsonGenerator);
                writeRels(graph.getRelationships(), reporter, jsonGenerator, config);
                writeJsonRelationshipContainerEnd(jsonGenerator);
                writeJsonContainerEnd(jsonGenerator);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
        return dump(writer.getPrintWriter("json"), reporter, consumer);
    }

    private void writeJsonRelationshipContainerEnd(JsonGenerator jsonGenerator) throws IOException {
        switch (format) {
            case JSON:
                jsonGenerator.writeEndArray();
                break;
            case JSON_ID_AS_KEYS:
                jsonGenerator.writeEndObject();
                break;
        }
    }

    private void writeJsonRelationshipContainerStart(JsonGenerator jsonGenerator) throws IOException {
        switch (format) {
            case JSON:
                jsonGenerator.writeFieldName("rels");
                jsonGenerator.writeStartArray();
                break;
            case JSON_ID_AS_KEYS:
                jsonGenerator.writeFieldName("rels");
                jsonGenerator.writeStartObject();
                break;
        }
    }

    private void writeJsonNodeContainerEnd(JsonGenerator jsonGenerator) throws IOException {
        switch (format) {
            case JSON:
                jsonGenerator.writeEndArray();
                break;
            case JSON_ID_AS_KEYS:
                jsonGenerator.writeEndObject();
                break;
        }
    }

    private void writeJsonNodeContainerStart(JsonGenerator jsonGenerator) throws IOException {
        switch (format) {
            case JSON:
                jsonGenerator.writeStartObject();
                jsonGenerator.writeFieldName("nodes");
                jsonGenerator.writeStartArray();
                break;
            case JSON_ID_AS_KEYS:
                jsonGenerator.writeStartObject();
                jsonGenerator.writeFieldName("nodes");
                jsonGenerator.writeStartObject();
                break;
        }
    }

    private void writeJsonContainerEnd(JsonGenerator jsonGenerator) throws IOException {
        switch (format) {
            case ARRAY_JSON:
                jsonGenerator.writeEndArray();
                break;
        }
    }

    private void writeJsonContainerStart(JsonGenerator jsonGenerator) throws IOException {
        switch (format) {
            case ARRAY_JSON:
                jsonGenerator.writeStartArray();
                break;
        }
    }

    public ProgressInfo dump(Result result, ExportFileManager writer, Reporter reporter, ExportConfig config) throws Exception {
        Consumer<JsonGenerator> consumer = (jsonGenerator) -> {
            try {
                writeJsonContainerStart(jsonGenerator);
                String[] header = result.columns().toArray(new String[result.columns().size()]);
                result.accept((row) -> {
                    writeJsonResult(reporter, header, jsonGenerator, row, config);
                    reporter.nextRow();
                    return true;
                });
                writeJsonContainerEnd(jsonGenerator);
            } catch (IOException e) {
                throw new RuntimeException(e);
            } catch (AuthorizationViolationException e) {
                throw new RuntimeException(INVALID_QUERY_MODE_ERROR);
            }
        };
        return dump(writer.getPrintWriter("json"), reporter, consumer);
    }

    private JsonGenerator getJsonGenerator(Writer writer) throws IOException {
        JsonGenerator jsonGenerator = new JsonFactory()
                .createGenerator(writer)
                .setCodec(JsonUtil.OBJECT_MAPPER)
                .setPrettyPrinter(new MinimalPrettyPrinter("\n"));
        return jsonGenerator;
    }

    private void writeNodes(Iterable<Node> nodes, Reporter reporter, JsonGenerator jsonGenerator,ExportConfig config) throws IOException {
        for (Node node : nodes) {
            writeNode(reporter, jsonGenerator, node, config);
        }
    }

    private void writeNode(Reporter reporter, JsonGenerator jsonGenerator, Node node, ExportConfig config) throws IOException {
        Map<String, Object> allProperties = node.getAllProperties();
        writeJsonIdKeyStart(jsonGenerator, node.getId());
        JsonFormatSerializer.DEFAULT.writeNode(jsonGenerator, node, config);
        reporter.update(1, 0, allProperties.size());
    }

    private void writeJsonIdKeyStart(JsonGenerator jsonGenerator, long id) throws IOException {
        if (!isExportSubGraph) {
            return;
        }
        switch (format) {
            case JSON_ID_AS_KEYS:
                writeFieldName(jsonGenerator, String.valueOf(id), true);
                break;
        }
    }

    private void writeRels(Iterable<Relationship> rels, Reporter reporter, JsonGenerator jsonGenerator, ExportConfig config) throws IOException {
        for (Relationship rel : rels) {
            writeRel(reporter, jsonGenerator, rel, config);
        }
    }

    private void writeRel(Reporter reporter, JsonGenerator jsonGenerator, Relationship rel, ExportConfig config) throws IOException {
        Map<String, Object> allProperties = rel.getAllProperties();
        writeJsonIdKeyStart(jsonGenerator, rel.getId());
        JsonFormatSerializer.DEFAULT.writeRelationship(jsonGenerator, rel, config);
        reporter.update(0, 1, allProperties.size());
    }

    private void writeJsonResult(Reporter reporter, String[] header, JsonGenerator jsonGenerator, Result.ResultRow row, ExportConfig config) throws IOException {
        jsonGenerator.writeStartObject();
        for (int col = 0; col < header.length; col++) {
            String keyName = header[col];
            Object value = row.get(keyName);
            write(reporter, jsonGenerator, config, keyName, value, true);
        }
        jsonGenerator.writeEndObject();
    }

    private void write(Reporter reporter, JsonGenerator jsonGenerator, ExportConfig config, String keyName, Object value, boolean writeKey) throws IOException {
        Types type = Types.of(value);
        switch (type) {
            case NODE -> {
                writeFieldName(jsonGenerator, keyName, writeKey);
                writeNode(reporter, jsonGenerator, (Node) value, config);
            }
            case RELATIONSHIP -> {
                writeFieldName(jsonGenerator, keyName, writeKey);
                writeRel(reporter, jsonGenerator, (Relationship) value, config);
            }
            case PATH -> {
                writeFieldName(jsonGenerator, keyName, writeKey);
                writePath(reporter, jsonGenerator, config, (Path) value);
            }
            case MAP -> {
                if (writeKey) {
                    jsonGenerator.writeObjectFieldStart(keyName);
                } else {
                    jsonGenerator.writeStartObject();
                    writeKey = true;
                }
                Map<String, Object> map = (HashMap<String, Object>) value;
                for (Map.Entry<String, Object> entry : map.entrySet()) {
                    write(reporter, jsonGenerator, config, entry.getKey(), entry.getValue(), writeKey);
                }
                jsonGenerator.writeEndObject();
            }
            case LIST -> {
                if (writeKey) {
                    jsonGenerator.writeArrayFieldStart(keyName);
                } else {
                    jsonGenerator.writeStartArray();
                }
                Object[] list = Types.toObjectArray(value);
                for (Object elem : list) {
                    write(reporter, jsonGenerator, config, keyName, elem, false);
                }
                jsonGenerator.writeEndArray();
            }
            default -> {
                JsonFormatSerializer.DEFAULT.serializeProperty(jsonGenerator, keyName, value, writeKey);
                reporter.update(0, 0, 1);
            }
        }
    }

    private void writeFieldName(JsonGenerator jsonGenerator, String keyName, boolean writeKey) throws IOException {
        if (writeKey) {
            jsonGenerator.writeFieldName(keyName);
        }
    }

    private void writePath(Reporter reporter, JsonGenerator jsonGenerator, ExportConfig config, Path path) throws IOException {
        jsonGenerator.writeStartObject();
        jsonGenerator.writeObjectField("length", path.length());
        jsonGenerator.writeArrayFieldStart("rels");
        writeRels(path.relationships(), reporter, jsonGenerator, config);
        jsonGenerator.writeEndArray();
        jsonGenerator.writeArrayFieldStart("nodes");
        writeNodes(path.nodes(), reporter, jsonGenerator, config);
        jsonGenerator.writeEndArray();
        jsonGenerator.writeEndObject();
    }

}
