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
package apoc.export;

import apoc.ApocConfig;
import apoc.export.csv.ExportCSV;
import apoc.export.cypher.ExportCypher;
import apoc.export.graphml.ExportGraphML;
import apoc.export.json.ExportJson;
import apoc.util.FileUtils;
import apoc.util.TestUtil;
import junit.framework.TestCase;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.QueryExecutionException;
import org.neo4j.graphdb.Result;
import org.neo4j.test.rule.DbmsRule;
import org.neo4j.test.rule.ImpermanentDbmsRule;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static apoc.util.TestUtil.assertError;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(Enclosed.class)
public class ExportCoreSecurityTest {

    private static final File directory = new File("target/import");
    private static final File directoryWithSamePrefix = new File("target/imported");
    private static final File subDirectory = new File("target/import/tests");
    private static final List<String> APOC_EXPORT_PROCEDURE_NAME = Arrays.asList("csv", "json", "graphml", "cypher");

    static {
        //noinspection ResultOfMethodCallIgnored
        directory.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        subDirectory.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        directoryWithSamePrefix.mkdirs();
    }

    @ClassRule
    public static DbmsRule db = new ImpermanentDbmsRule()
            .withSetting(GraphDatabaseSettings.load_csv_file_url_root, directory.toPath().toAbsolutePath());

    @BeforeClass
    public static void setUp() {
        TestUtil.registerProcedure(db, ExportCSV.class, ExportJson.class, ExportGraphML.class, ExportCypher.class);
        setFileExport(false);
    }

    public static void setFileExport(boolean allowed) {
        ApocConfig.apocConfig().setProperty(ApocConfig.APOC_EXPORT_FILE_ENABLED, allowed);
    }

    private static Collection<String[]> data(Map<String, List<String>> apocProcedureArguments) {
        return data(apocProcedureArguments, APOC_EXPORT_PROCEDURE_NAME);
    }

    public static Collection<String[]> data(Map<String, List<String>> apocProcedureArguments, List<String> procedureNames) {
        return procedureNames
                .stream()
                .flatMap(method -> apocProcedureArguments
                        .entrySet()
                        .stream()
                        .flatMap(e -> e.getValue()
                                .stream()
                                .map(a -> new String[]{method, e.getKey(), a})))
                .collect(Collectors.toList());
    }

    @RunWith(Parameterized.class)
    public static class TestIllegalFSAccess {
        private final String apocProcedure;

        public TestIllegalFSAccess(String exportMethod, String exportMethodType, String exportMethodArguments) {
            this.apocProcedure = getApocProcedure(exportMethod, exportMethodType, exportMethodArguments);
        }

        private static final Map<String, List<String>> APOC_PROCEDURE_ARGUMENTS = Map.of(
                "query", List.of(
                        "\"RETURN 'hello' as key\", './hello', {}",
                        "\"RETURN 'hello' as key\", './hello', {stream:true}",
                        "\"RETURN 'hello' as key\", '  ', {}"
                ),
                "all", List.of(
                        "'./hello', {}",
                        "'./hello', {stream:true}",
                        "'  ', {}"
                ),
                "data", List.of(
                        "[], [], './hello', {}",
                        "[], [], './hello', {stream:true}",
                        "[], [], '  ', {}"
                ),
                "graph", List.of(
                        "{nodes: [], relationships: []}, './hello', {}",
                        "{nodes: [], relationships: []}, './hello', {stream:true}",
                        "{nodes: [], relationships: []}, '  ', {}"
                )
        );

        @Parameterized.Parameters
        public static Collection<String[]> data() {
            return ExportCoreSecurityTest.data(APOC_PROCEDURE_ARGUMENTS);
        }

        @Test
        public void testIllegalFSAccessExport() {
            QueryExecutionException e = Assert.assertThrows(QueryExecutionException.class,
                    () -> TestUtil.testCall(db, "CALL " + apocProcedure, (r) -> {})
            );

            assertError(e, ApocConfig.EXPORT_TO_FILE_ERROR, RuntimeException.class, apocProcedure);
        }
    }

    @RunWith(Parameterized.class)
    public static class TestIllegalExternalFSAccess {
        private final String apocProcedure;

        public TestIllegalExternalFSAccess(String exportMethod, String exportMethodType, String exportMethodArguments) {
            this.apocProcedure = getApocProcedure(exportMethod, exportMethodType, exportMethodArguments);
        }

        private static final Map<String, List<String>> METHOD_ARGUMENTS = Map.of(
                "query", List.of(
                        "\"RETURN 'hello' as key\", '../hello', {}",
                        "\"RETURN 'hello' as key\", 'file:../hello', {}"
                ),
                "all", List.of(
                        "'../hello', {}",
                        "'file:../hello', {}"
                ),
                "data", List.of(
                        "[], [], '../hello', {}",
                        "[], [], 'file:../hello', {}"
                ),
                "graph", List.of(
                        "{nodes: [], relationships: []}, '../hello', {}",
                        "{nodes: [], relationships: []}, 'file:../hello', {}"
                )
        );

        @Parameterized.Parameters
        public static Collection<String[]> data() {
            return ExportCoreSecurityTest.data(METHOD_ARGUMENTS);
        }

        @Test
        public void testIllegalExternalFSAccessExport() {
            final String message = apocProcedure + " should throw an exception";
            try {
                setFileExport(true);
                db.executeTransactionally("CALL " + apocProcedure, Map.of(),
                        Result::resultAsString);
                fail(message);
            } catch (Exception e) {
                assertError(e, FileUtils.ACCESS_OUTSIDE_DIR_ERROR, IOException.class, apocProcedure);
            } finally {
                setFileExport(false);
            }
        }
    }

    @RunWith(Parameterized.class)
    public static class TestPathTraversalAccess {
        private final String apocProcedure;

        public TestPathTraversalAccess(String exportMethod, String exportMethodType, String exportMethodArguments) {
            this.apocProcedure = getApocProcedure(exportMethod, exportMethodType, exportMethodArguments);
        }

        private static final String case1 = "'file:///%2e%2e%2f%2f%2e%2e%2f%2f%2e%2e%2f%2f%2e%2e%2f%2fapoc/test.txt'";
        private static final String case2 = "'file:///%2e%2e%2f%2ftest.txt'";
        private static final String case3 = "'../test.txt'";
        private static final String case4 = "'tests/../../test.txt'";
        private static final String case5 = "'tests/..//..//test.txt'";

        public static final List<String> cases = Arrays.asList(case1, case2, case3, case4, case5);

        private static final Map<String, List<String>> METHOD_ARGUMENTS = Map.of(
                "query",  cases.stream().map(
                        filePath -> "\"RETURN 1\", " + filePath + ", {}"
                ).toList(),
                "all", cases.stream().map(
                        filePath -> filePath + ", {}"
                ).toList(),
                "data", cases.stream().map(
                        filePath -> "[], [], " + filePath + ", {}"
                ).toList(),
                "graph", cases.stream().map(
                        filePath -> "{nodes: [], relationships: []}, " + filePath + ", {}"
                ).toList()
        );

        @Parameterized.Parameters
        public static Collection<String[]> data() {
            return ExportCoreSecurityTest.data(METHOD_ARGUMENTS);
        }

        @Test
        public void testPathTraversal() {
            assertPathTraversalError(db, "CALL " + apocProcedure);
        }
    }

    /**
     * All of these will resolve to a local path after normalization which will point to
     * a non-existing directory in our import folder: /apoc. Causing them to error that is
     * not found. They all attempt to exit the import folder back to the apoc folder:
     * Directory Layout: .../apoc/core/target/import
     */
    @RunWith(Parameterized.class)
    public static class TestPathTraversalIsNormalised {
        private final String apocProcedure;

        public TestPathTraversalIsNormalised(String exportMethod, String exportMethodType, String exportMethodArguments) {
            this.apocProcedure = getApocProcedure(exportMethod, exportMethodType, exportMethodArguments);
        }

        private static final String case1 = "'file://%2e%2e%2f%2e%2e%2f%2e%2e%2f%2e%2e%2f/apoc/test.txt'";
        private static final String case2 = "'file://../../../../apoc/test.txt'";
        private static final String case3 = "'file:///..//..//..//..//apoc//core//..//test.txt'";
        private static final String case4 = "'file:///..//..//..//..//apoc/test.txt'";
        private static final String case5 = "'file://" + directory.getAbsolutePath() + "//..//..//..//..//apoc/test.txt'";
        private static final String case6 = "'file:///%252e%252e%252f%252e%252e%252f%252e%252e%252f%252e%252e%252f/apoc/test.txt'";

        public static final List<String> cases = Arrays.asList(case1, case2, case3, case4, case5, case6);

        private static final Map<String, List<String>> METHOD_ARGUMENTS = Map.of(
                "query", cases.stream().map(
                        filePath -> "\"RETURN 1\", " + filePath + ", {}"
                ).toList(),
                "all", cases.stream().map(
                        filePath -> filePath + ", {}"
                ).toList(),
                "data", cases.stream().map(
                        filePath -> "[], [], " + filePath + ", {}"
                ).toList(),
                "graph", cases.stream().map(
                        filePath -> "{nodes: [], relationships: []}, " + filePath + ", {}"
                ).toList()
        );

        @Parameterized.Parameters
        public static Collection<String[]> data() {
            return ExportCoreSecurityTest.data(METHOD_ARGUMENTS);
        }

        @Test
        public void testPathTraversal() {
            assertPathTraversalError(db, "CALL " + apocProcedure,
                    e -> TestCase.assertTrue(e.getMessage().contains("apoc/test.txt (No such file or directory)"))
            );
        }
    }

    /**
     * These tests normalize the path to be within the import directory and make the file there.
     * Some attempt to exit the directory.
     * They result in a file name test.txt being created (and deleted after).
     */
    @RunWith(Parameterized.class)
    public static class TestPathTraversalIsNormalisedWithinDirectory {
        private final String apocProcedure;

        public TestPathTraversalIsNormalisedWithinDirectory(String exportMethod, String exportMethodType, String exportMethodArguments) {
            this.apocProcedure = getApocProcedure(exportMethod, exportMethodType, exportMethodArguments);
        }

        private static final String case1 = "'file:///..//..//..//..//apoc//..//..//..//..//test.txt'";
        private static final String case2 = "'file:///..//..//..//..//apoc//..//test.txt'";
        private static final String case3 = "'file:///../import/../import//..//test.txt'";
        private static final String case4 = "'file://test.txt'";
        private static final String case5 = "'file://tests/../test.txt'";
        private static final String case6 = "'file:///tests//..//test.txt'";
        private static final String case7 = "'test.txt'";
        private static final String case8 = "'file:///..//..//..//..//test.txt'";

        public static final List<String> cases = Arrays.asList(case1, case2, case3, case4, case5, case6, case7, case8);

        private static final Map<String, List<String>> METHOD_ARGUMENTS = Map.of(
                "query", cases.stream().map(
                        filePath -> "\"RETURN 1\", " + filePath + ", {}"
                ).toList(),
                "all", cases.stream().map(
                        filePath -> filePath + ", {}"
                ).toList(),
                "data", cases.stream().map(
                        filePath -> "[], [], " + filePath + ", {}"
                ).toList(),
                "graph", cases.stream().map(
                        filePath -> "{nodes: [], relationships: []}, " + filePath + ", {}"
                ).toList()
        );

        @Parameterized.Parameters
        public static Collection<String[]> data() {
            return ExportCoreSecurityTest.data(METHOD_ARGUMENTS);
        }

        @Test
        public void testPathTraversal() {
            assertPathTraversalWithoutErrors(db, apocProcedure, directory,
                    (r) -> assertTrue(((String) r.get("file")).contains("test.txt")));

        }
    }

    /*
     * These test cases attempt to access a directory with the same prefix as the import directory. This is design to
     * test "directoryName.startsWith" logic which is a common path traversal bug.
     *
     * All these tests should fail because they access a directory which isn't the configured directory
     */
    @RunWith(Parameterized.class)
    public static class TestPathTraversalIsWithSimilarDirectoryName {
        private final String apocProcedure;

        public TestPathTraversalIsWithSimilarDirectoryName(String exportMethod, String exportMethodType, String exportMethodArguments) {
            this.apocProcedure = getApocProcedure(exportMethod, exportMethodType, exportMethodArguments);
        }

        private static final String case1 = "'../imported/test.txt'";
        private static final String case2 = "'tests/../../imported/test.txt'";

        public static final List<String> cases = Arrays.asList(case1, case2);

        private static final Map<String, List<String>> METHOD_ARGUMENTS = Map.of(
                "query", cases.stream().map(
                        filePath -> "\"RETURN 1\", " + filePath + ", {}"
                ).toList(),
                "all", cases.stream().map(
                        filePath -> filePath + ", {}"
                ).toList(),
                "data", cases.stream().map(
                        filePath -> "[], [], " + filePath + ", {}"
                ).toList(),
                "graph", cases.stream().map(
                        filePath -> "{nodes: [], relationships: []}, " + filePath + ", {}"
                ).toList()
        );

        @Parameterized.Parameters
        public static Collection<String[]> data() {
            return ExportCoreSecurityTest.data(METHOD_ARGUMENTS);
        }

        @Test
        public void testPathTraversal() {
            assertPathTraversalError(db, "CALL " + apocProcedure);
        }
    }


    /**
     * These tests normalize the path to be within the import directory and step into a subdirectory
     * to make the file there.
     * Some attempt to exit the directory.
     * They result in a file name test.txt in the directory /tests being created (and deleted after).
     */
    @RunWith(Parameterized.class)
    public static class TestPathTraversAllowedWithinDirectory {
        private final String apocProcedure;

        public TestPathTraversAllowedWithinDirectory(String exportMethod, String exportMethodType, String exportMethodArguments) {
            this.apocProcedure = getApocProcedure(exportMethod, exportMethodType, exportMethodArguments);
        }

        private static final String case1 = "'file:///../import/../import//..//tests/test.txt'";
        private static final String case2 = "'file:///..//..//..//..//apoc//..//tests/test.txt'";
        private static final String case3 = "'file:///../import/../import//..//tests/../tests/test.txt'";
        private static final String case4 = "'file:///tests/test.txt'";
        private static final String case5 = "'tests/test.txt'";

        public static final List<String> cases = Arrays.asList(case1, case2, case3, case4, case5);

        private static final Map<String, List<String>> METHOD_ARGUMENTS = Map.of(
                "query", cases.stream().map(
                        filePath -> "\"RETURN 1\", " + filePath + ", {}"
                ).toList(),
                "all", cases.stream().map(
                        filePath -> filePath + ", {}"
                ).toList(),
                "data", cases.stream().map(
                        filePath -> "[], [], " + filePath + ", {}"
                ).toList(),
                "graph", cases.stream().map(
                        filePath -> "{nodes: [], relationships: []}, " + filePath + ", {}"
                ).toList()
        );

        @Parameterized.Parameters
        public static Collection<String[]> data() {
            return ExportCoreSecurityTest.data(METHOD_ARGUMENTS);
        }

        @Test
        public void testPathTraversal() {
            assertPathTraversalWithoutErrors(db, apocProcedure, subDirectory,
                    (r) -> assertTrue(((String) r.get("file")).contains("tests/test.txt")));
        }
    }

    public static class TestCypherSchema {
        private final String apocProcedure = "apoc.export.cypher.schema(%s)";

        @Test
        public void testIllegalFSAccessExportCypherSchema() {
            QueryExecutionException e = Assert.assertThrows(QueryExecutionException.class,
                    () -> TestUtil.testCall(db, String.format("CALL " + apocProcedure, "'./hello', {}"), (r) -> {})
            );
            assertError(e, ApocConfig.EXPORT_TO_FILE_ERROR, RuntimeException.class, apocProcedure);
        }

        @Test
        public void testIllegalExternalFSAccessExportCypherSchema() {
            assertPathTraversalError(db, String.format("CALL " + apocProcedure, "'../hello', {}"),
                    e -> assertError(e, FileUtils.ACCESS_OUTSIDE_DIR_ERROR, IOException.class, apocProcedure));
        }
    }

    public static String getApocProcedure(String exportMethod, String exportMethodType, String exportMethodArguments) {
        return "apoc.export." + exportMethod + "." + exportMethodType + "(" + exportMethodArguments + ")";
    }

    public static void assertPathTraversalError(GraphDatabaseService db, String query, Consumer<Exception> exceptionConsumer) {
        setFileExport(true);

        QueryExecutionException e = Assert.assertThrows(QueryExecutionException.class,
                () -> TestUtil.testCall(db, query, (r) -> {})
        );

        exceptionConsumer.accept(e);

        setFileExport(false);
    }

    public static void assertPathTraversalWithoutErrors(GraphDatabaseService db, String apocProcedure, File directory, Consumer<Map<String, Object>> consumer) {
        setFileExport(true);

        TestUtil.testCall(db, "CALL " + apocProcedure, consumer);

        File f = new File(directory.getAbsolutePath() + "/test.txt");
        TestCase.assertTrue(f.exists());
        TestCase.assertTrue(f.delete());
    }

    public static void assertPathTraversalError(GraphDatabaseService db, String apocProcedure) {
        assertPathTraversalError(db, apocProcedure,
                e -> assertError(e, FileUtils.ACCESS_OUTSIDE_DIR_ERROR, IOException.class, apocProcedure)
        );
    }

}