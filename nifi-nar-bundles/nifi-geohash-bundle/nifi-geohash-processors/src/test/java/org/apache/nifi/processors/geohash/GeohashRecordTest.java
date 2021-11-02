/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.geohash;

import org.apache.avro.Schema;
import org.apache.commons.io.IOUtils;
import org.apache.nifi.avro.AvroTypeUtil;
import org.apache.nifi.controller.ControllerService;
import org.apache.nifi.json.JsonRecordSetWriter;
import org.apache.nifi.json.JsonTreeReader;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.schema.access.SchemaAccessUtils;
import org.apache.nifi.serialization.record.MockSchemaRegistry;
import org.apache.nifi.serialization.record.RecordSchema;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.codehaus.jackson.map.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GeohashRecordTest {

    private final TestRunner runner = TestRunners.newTestRunner(GeohashRecord.class);

    @BeforeEach
    public void setUp() throws InitializationException {
        ControllerService reader = new JsonTreeReader();
        ControllerService writer = new JsonRecordSetWriter();
        ControllerService registry = new MockSchemaRegistry();
        runner.addControllerService("reader", reader);
        runner.addControllerService("writer", writer);
        runner.addControllerService("registry", registry);

        try (InputStream is = getClass().getResourceAsStream("/record_schema.avsc")) {
            String raw = IOUtils.toString(is, "UTF-8");
            RecordSchema parsed = AvroTypeUtil.createSchema(new Schema.Parser().parse(raw));
            ((MockSchemaRegistry) registry).addSchema("record", parsed);

        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }

        runner.setProperty(reader, SchemaAccessUtils.SCHEMA_REGISTRY, "registry");
        runner.setProperty(writer, SchemaAccessUtils.SCHEMA_ACCESS_STRATEGY, SchemaAccessUtils.SCHEMA_NAME_PROPERTY);
        runner.setProperty(writer, SchemaAccessUtils.SCHEMA_REGISTRY, "registry");

        runner.setProperty(GeohashRecord.RECORD_READER, "reader");
        runner.setProperty(GeohashRecord.RECORD_WRITER, "writer");
        runner.enableControllerService(registry);
        runner.enableControllerService(reader);
        runner.enableControllerService(writer);

        runner.setProperty(GeohashRecord.LATITUDE_RECORD_PATH, "/latitude");
        runner.setProperty(GeohashRecord.LONGITUDE_RECORD_PATH, "/longitude");
        runner.setProperty(GeohashRecord.GEOHASH_RECORD_PATH, "/geohash");
        runner.setProperty(GeohashRecord.GEOHASH_FORMAT, GeohashRecord.GeohashFormat.BASE_32.toString());
        runner.setProperty(GeohashRecord.GEOHASH_LEVEL, "12");
    }

    private void commonTest(String path, int failure, int success, int original) {
        Map<String, String> attrs = new HashMap<>();
        attrs.put("schema.name", "record");
        runner.enqueue(getClass().getResourceAsStream(path), attrs);
        runner.run();

        runner.assertTransferCount(GeohashRecord.REL_FAILURE, failure);
        runner.assertTransferCount(GeohashRecord.REL_SUCCESS, success);
        runner.assertTransferCount(GeohashRecord.REL_ORIGINAL, original);
    }

    @Test
    public void testEncodeSendToSuccessDefault() throws Exception {
        runner.setProperty(GeohashRecord.MODE, GeohashRecord.ProcessingMode.ENCODE.toString());
        runner.setProperty(GeohashRecord.ROUTING_STRATEGY, GeohashRecord.RoutingStrategy.SKIP_UNENRICHED.toString());
        runner.assertValid();

        commonTest("/record_encode.json", 0, 1, 0);

        MockFlowFile ff = runner.getFlowFilesForRelationship(GeohashRecord.REL_SUCCESS).get(0);
        byte[] raw = runner.getContentAsByteArray(ff);
        String content = new String(raw);
        ObjectMapper mapper = new ObjectMapper();
        List<Map<String, Object>> result = (List<Map<String, Object>>) mapper.readValue(content, List.class);

        assertNotNull(result);
        assertEquals(2, result.size());

        Map<String, Object> element = result.get(0);
        String geohash = (String)element.get("geohash");
        assertNotNull(geohash);
    }

    @Test
    public void testEncodeSendToFailureDefault() {
        runner.setProperty(GeohashRecord.MODE, GeohashRecord.ProcessingMode.ENCODE.toString());
        runner.setProperty(GeohashRecord.ROUTING_STRATEGY, GeohashRecord.RoutingStrategy.SKIP_UNENRICHED.toString());
        runner.assertValid();

        commonTest("/record_decode.json", 1, 0, 0);
    }

    @Test
    public void testSplit() {
        runner.setProperty(GeohashRecord.MODE, GeohashRecord.ProcessingMode.ENCODE.toString());
        runner.setProperty(GeohashRecord.ROUTING_STRATEGY, GeohashRecord.RoutingStrategy.SPLIT.toString());
        runner.assertValid();

        commonTest("/record_encode.json", 1, 1, 1);
    }

    @Test
    public void testRequireAllEnrichedSendToFailure() {
        runner.setProperty(GeohashRecord.MODE, GeohashRecord.ProcessingMode.ENCODE.toString());
        runner.setProperty(GeohashRecord.ROUTING_STRATEGY, GeohashRecord.RoutingStrategy.REQUIRE_ALL_ENRICHED.toString());
        runner.assertValid();

        commonTest("/record_encode.json", 1, 0, 0);
    }

    @Test
    public void testRequireAllEnrichedSendToSuccess() {
        runner.setProperty(GeohashRecord.MODE, GeohashRecord.ProcessingMode.DECODE.toString());
        runner.setProperty(GeohashRecord.ROUTING_STRATEGY, GeohashRecord.RoutingStrategy.REQUIRE_ALL_ENRICHED.toString());
        runner.assertValid();

        commonTest("/record_decode.json", 0, 1, 0);
    }
}
