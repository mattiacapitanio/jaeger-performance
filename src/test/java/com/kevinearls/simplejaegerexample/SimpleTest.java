package com.kevinearls.simplejaegerexample;

import static org.junit.Assert.assertEquals;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uber.jaeger.metrics.Metrics;
import com.uber.jaeger.metrics.NullStatsReporter;
import com.uber.jaeger.metrics.StatsFactoryImpl;
import com.uber.jaeger.reporters.CompositeReporter;
import com.uber.jaeger.reporters.LoggingReporter;
import com.uber.jaeger.reporters.RemoteReporter;
import com.uber.jaeger.reporters.Reporter;
import com.uber.jaeger.samplers.ProbabilisticSampler;
import com.uber.jaeger.samplers.Sampler;
import com.uber.jaeger.senders.HttpSender;
import com.uber.jaeger.senders.Sender;
import com.uber.jaeger.senders.UdpSender;
import io.opentracing.Span;
import io.opentracing.Tracer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.apache.http.HttpHost;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SimpleTest {
    private static final Map<String, String> envs = System.getenv();

    private static final String CASSANDRA_CLUSTER_IP = envs.getOrDefault("CASSANDRA_CLUSTER_IP", "cassandra");
    private static final String CASSANDRA_KEYSPACE_NAME = envs.getOrDefault("CASSANDRA_KEYSPACE_NAME", "jaeger_v1_test");
    private static final Integer DELAY = new Integer(envs.getOrDefault("DELAY", "1"));
    private static final Integer DURATION_IN_MINUTES = new Integer(envs.getOrDefault("DURATION_IN_MINUTES", "5"));
    private static final String ELASTICSEARCH_HOST = envs.getOrDefault("ELASTICSEARCH_HOST", "elasticsearch");
    private static final Integer ELASTICSEARCH_PORT = new Integer(envs.getOrDefault("ELASTICSEARCH_PORT", "9200"));
    private static final String JAEGER_AGENT_HOST = envs.getOrDefault("JAEGER_AGENT_HOST", "localhost");
    private static final String JAEGER_COLLECTOR_HOST = envs.getOrDefault("JAEGER_COLLECTOR_HOST", "localhost");
    private static final String JAEGER_COLLECTOR_PORT = envs.getOrDefault("MY_JAEGER_COLLECTOR_PORT", "14268");
    private static final Integer JAEGER_FLUSH_INTERVAL = new Integer(envs.getOrDefault("JAEGER_FLUSH_INTERVAL", "100"));
    private static final Integer JAEGER_MAX_PACKET_SIZE = new Integer(envs.getOrDefault("JAEGER_MAX_PACKET_SIZE", "0"));
    private static final Integer JAEGER_MAX_QUEUE_SIZE = new Integer(envs.getOrDefault("JAEGER_MAX_QUEUE_SIZE", "100000"));
    private static final Double JAEGER_SAMPLING_RATE = new Double(envs.getOrDefault("JAEGER_SAMPLING_RATE", "1.0"));
    private static final String SPAN_STORAGE_TYPE = envs.getOrDefault("SPAN_STORAGE_TYPE", "cassandra");
    private static final Integer JAEGER_UDP_PORT = new Integer(envs.getOrDefault("JAEGER_UDP_PORT", "6831"));
    private static final String TEST_SERVICE_NAME = envs.getOrDefault("TEST_SERVICE_NAME", "standalone");
    private static final Integer THREAD_COUNT = new Integer(envs.getOrDefault("THREAD_COUNT", "100"));
    private static final String USE_AGENT_OR_COLLECTOR = envs.getOrDefault("USE_AGENT_OR_COLLECTOR", "COLLECTOR");
    private static final String USE_LOGGING_REPORTER = envs.getOrDefault("USE_LOGGING_REPORTER", "false");

    private static final Logger logger = LoggerFactory.getLogger(SimpleTest.class.getName());
    private static Tracer tracer;

    @BeforeClass
    public static void setUp() {
        tracer = jaegerTracer();
    }

    @After
    public void tearDown() throws Exception {
        closeTracer();
    }

    private static Tracer jaegerTracer() {
        Tracer tracer;
        Sender sender;
        CompositeReporter compositeReporter;

        if (USE_AGENT_OR_COLLECTOR.equalsIgnoreCase("agent")) {
            sender = new UdpSender(JAEGER_AGENT_HOST, JAEGER_UDP_PORT, JAEGER_MAX_PACKET_SIZE);
            logger.info("Using JAEGER tracer using agent on host [" + JAEGER_AGENT_HOST + "] port [" + JAEGER_UDP_PORT +
                    "] Service Name [" + TEST_SERVICE_NAME + "] Sampling rate [" + JAEGER_SAMPLING_RATE
                    + "] Max queue size: [" + JAEGER_MAX_QUEUE_SIZE + "]");
        } else {
            // use the collector
            String httpEndpoint = "http://" + JAEGER_COLLECTOR_HOST + ":" + JAEGER_COLLECTOR_PORT + "/api/traces";
            sender = new HttpSender(httpEndpoint);
            logger.info("Using JAEGER tracer using collector on host [" + JAEGER_COLLECTOR_HOST + "] port [" + JAEGER_COLLECTOR_PORT +
                    "] Service Name [" + TEST_SERVICE_NAME + "] Sampling rate [" + JAEGER_SAMPLING_RATE
                    + "] Max queue size: [" + JAEGER_MAX_QUEUE_SIZE + "]");
        }

        Metrics metrics = new Metrics(new StatsFactoryImpl(new NullStatsReporter()));
        Reporter remoteReporter = new RemoteReporter(sender, JAEGER_FLUSH_INTERVAL, JAEGER_MAX_QUEUE_SIZE, metrics);
        if (USE_LOGGING_REPORTER.equalsIgnoreCase("true")) {
            Reporter loggingRepoter = new LoggingReporter(logger);
            compositeReporter = new CompositeReporter(remoteReporter, loggingRepoter);
        } else {
            compositeReporter = new CompositeReporter(remoteReporter);
        }

        Sampler sampler = new ProbabilisticSampler(JAEGER_SAMPLING_RATE);
        tracer = new com.uber.jaeger.Tracer.Builder(TEST_SERVICE_NAME, compositeReporter, sampler)
                .build();

        return tracer;
    }

    @Test
    public void countTraces() throws Exception {
        int traceCount = 0;

        if (SPAN_STORAGE_TYPE.equalsIgnoreCase("cassandra")) {
            Session cassandraSession = getCassandraSession();
            traceCount = countTracesInCassandra(cassandraSession);
        } else {
            RestClient restClient = getESRestClient();
            LocalDateTime now = LocalDateTime.now();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            String formattedDate = now.format(formatter);
            String targetUrlString = "/jaeger-span-" + formattedDate + "/_count";
            traceCount = getElasticSearchTraceCount(restClient, targetUrlString);
        }
        logger.info("Traces contains " + traceCount + " entries");
    }


    private void closeTracer() {
        try {
            Thread.sleep(JAEGER_FLUSH_INTERVAL);
        } catch (InterruptedException e) {
            logger.warn("Interrupted Exception", e);
        }
        com.uber.jaeger.Tracer jaegerTracer = (com.uber.jaeger.Tracer) tracer;
        jaegerTracer.close();
    }

    /**
     * This is the primary test.  Create the traces, and then verify that they exist in
     * whichever storage back end we have selected.
     *
     * @throws Exception of some sort
     */
    @Test
    public void createTracesTest() throws Exception {
        logger.info("Starting with " + THREAD_COUNT + " threads for " + DURATION_IN_MINUTES + " minutes with a delay of " + DELAY);
        final Instant createStartTime = Instant.now();
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        List<Future<Integer>> workers = new ArrayList<>();

        for (int i = 0; i < THREAD_COUNT; i++) {
            Callable<Integer> worker = new WriteTraces(tracer, DURATION_IN_MINUTES, i);
            Future<Integer> created = executor.submit(worker);
            workers.add(created);
        }
        executor.shutdown();
        executor.awaitTermination(DURATION_IN_MINUTES + 1, TimeUnit.MINUTES);

        int tracesCreated = 0;
        for (Future<Integer> worker : workers) {
            int traceeCount = worker.get();
            logger.info("Got " + traceeCount + " traces");
            tracesCreated += traceeCount;
        }
        logger.info("Got a total of " + tracesCreated + " traces");

        final Instant createEndTime = Instant.now();
        long duration = Duration.between(createStartTime, createEndTime).toMillis();
        logger.info("Finished all " + THREAD_COUNT + " threads; Created " + tracesCreated + " traces" + " in " + duration + " milliseconds");

        closeTracer();

        // Validate trace count here
        int actualTraceCount;

        if (SPAN_STORAGE_TYPE.equalsIgnoreCase("cassandra")) {
            logger.info("Validating Cassandra Traces");
            actualTraceCount = validateCassandraTraces(tracesCreated);
        } else {
            logger.info("Validating ES Traces");
            actualTraceCount = validateElasticSearchTraces(tracesCreated);
        }
        Files.write(Paths.get("traceCount.txt"), Long.toString(actualTraceCount).getBytes(), StandardOpenOption.CREATE);

        Instant countEndTime = Instant.now();
        long countDuration = Duration.between(createEndTime, countEndTime).toMillis();
        logger.info("Counting " + actualTraceCount + " traces took " + countDuration / 1000 + "." + countDuration % 1000 + " seconds.");
        assertEquals("Did not find expected number of traces", tracesCreated, actualTraceCount);
    }

    /**
     * It can take a while for traces to actually get written to storage, so both this and the Cassandra validation
     * method loop until they either find the expected number of traces, or the count returned ceases to increase
     *
     * @param expectedTraceCount number of traces we expect to find
     * @return actual number of traces found in ElasticSearch
     */
    private int validateElasticSearchTraces(int expectedTraceCount) throws IOException {
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        String formattedDate = now.format(formatter);
        String targetUrlString = "/jaeger-span-" + formattedDate + "/_count";
        logger.info("Using ElasticSearch URL : [" + targetUrlString + "]");

        RestClient restClient = getESRestClient();

        int previousTraceCount = -1;
        int actualTraceCount = getElasticSearchTraceCount(restClient, targetUrlString);
        final int startTraceCount = actualTraceCount;
        int iterations = 0;
        final long sleepDelay = 10L;   // TODO externalize?
        logger.info("Setting SLEEP DELAY " + sleepDelay + " seconds");
        logger.info("Actual Trace count " + actualTraceCount);
        while (actualTraceCount < expectedTraceCount && previousTraceCount < actualTraceCount) {
            logger.info("FOUND " + actualTraceCount + " traces in ElasticSearch");
            try {
                TimeUnit.SECONDS.sleep(sleepDelay);
            } catch (InterruptedException e) {
                logger.warn("Got interrupted exception", e);
            }
            previousTraceCount = actualTraceCount;
            actualTraceCount = getElasticSearchTraceCount(restClient, targetUrlString);
            iterations++;
        }

        logger.info("It took " + iterations  + " iterations to go from " + startTraceCount + " to " + actualTraceCount + " traces");
        logger.info("FOUND " + actualTraceCount + " traces in ElasticSearch");
        return actualTraceCount;
    }

    private RestClient getESRestClient() {
        logger.debug("Connecting to elasticsearch using host " + ELASTICSEARCH_HOST + " and port " + ELASTICSEARCH_PORT);
        return RestClient.builder(
                    new HttpHost(ELASTICSEARCH_HOST, ELASTICSEARCH_PORT, "http"),
                    new HttpHost(ELASTICSEARCH_HOST, ELASTICSEARCH_PORT + 1, "http"))
                    .build();
    }

    private int getElasticSearchTraceCount(RestClient restClient, String targetUrlString) throws IOException {
        Response response = restClient.performRequest("GET", targetUrlString);
        String responseBody = EntityUtils.toString(response.getEntity());
        ObjectMapper jsonObjectMapper = new ObjectMapper();
        JsonNode jsonPayload = jsonObjectMapper.readTree(responseBody);
        JsonNode count = jsonPayload.get("count");
        int traceCount = count.asInt();

        return traceCount;
    }

    /**
     * It can take a while for traces to actually get written to storage, so both this and the ElasticSearch validation
     * method loop until they either find the expected number of traces, or the count returned ceases to increase
     *
     * @param expectedTraceCount number of traces we expect to find
     * @return final trace count found in Cassandra
     */
    private int validateCassandraTraces(int expectedTraceCount) {
        Session cassandraSession = getCassandraSession();
        int previousTraceCount = -1;
        int actualTraceCount = countTracesInCassandra(cassandraSession);
        int startTraceCount = actualTraceCount;
        int iterations = 0;
        while (actualTraceCount < expectedTraceCount && previousTraceCount < actualTraceCount) {
            logger.info("FOUND " + actualTraceCount + " traces in Cassandra");
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                logger.warn("Got interrupted exception", 3);
            }
            previousTraceCount = actualTraceCount;
            actualTraceCount = countTracesInCassandra(cassandraSession);
            iterations++;
        }

        logger.info("It took " + iterations  + " iterations to go from " + startTraceCount + " to " + actualTraceCount + " traces");
        logger.info("FOUND " + actualTraceCount + " traces in Cassandra");
        return actualTraceCount;
    }

    private Session getCassandraSession() {
        Cluster.Builder builder = Cluster.builder();
        builder.addContactPoint(CASSANDRA_CLUSTER_IP);
        Cluster cluster = builder.build();
        Session session = cluster.connect(CASSANDRA_KEYSPACE_NAME);

        return session;
    }

    /**
     * For performance reasons Cassandra won't let us do a "select count(*) from traces" so instead we just have to do
     * "select * from traces" and count the number of rows it returns.
     *
     * @param session An open Cassandra session to use for queries
     * @return current number of traces found in Cassandra
     */
    private int countTracesInCassandra(Session session) {
        ResultSet result = session.execute("select * from traces");
        RowCountingConsumer consumer = new RowCountingConsumer();
        result.iterator()
                .forEachRemaining(consumer);
        int totalTraceCount = consumer.getRowCount();

        return totalTraceCount;
    }

    class WriteTraces implements Callable<Integer> {
        Tracer tracer;
        int id;
        int durationInMinutes;

        public WriteTraces(Tracer tracer, int durationInMinutes, int id) {
            this.tracer = tracer;
            this.durationInMinutes = durationInMinutes;
            this.id = id;
        }

        @Override
        public Integer call() throws Exception {
            int  spanCount = 0;
            String s = "Thread " + id;
            logger.debug("Starting " + s);

            Instant finish = Instant.now().plus(durationInMinutes, ChronoUnit.MINUTES);
            while (Instant.now().isBefore(finish)) {
                Span span = tracer.buildSpan(s).start();
                try {
                    span.setTag("iteration", spanCount);
                    spanCount++;
                    Thread.sleep(DELAY);
                } catch (InterruptedException e) {
                    logger.warn("Got interrupted exception", 3);
                }
                span.finish();
            }

            return spanCount;
        }
    }


    class RowCountingConsumer implements Consumer<Row> {
        AtomicInteger rowCount = new AtomicInteger(0);

        @Override
        public void accept(Row r) {
            rowCount.getAndIncrement();
        }

        public int getRowCount() {
            return rowCount.get();
        }
    }

}
