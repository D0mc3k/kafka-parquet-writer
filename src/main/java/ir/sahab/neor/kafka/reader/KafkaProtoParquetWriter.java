package ir.sahab.neor.kafka.reader;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import ir.sahab.neor.kafka.reader.ParquetFile.ParquetProperties;
import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.apache.commons.lang3.Validate;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Instance of this class reads proto objects from a Kafka topic and writes them on parquet files.
 * Operation can be done using multiple threads. Each thread creates separate temporary file and
 * writes data in it. Files are generated in configured path with {@link #TEMP_FILE_EXTENSION}
 * extension. Temporary files are finalized (closed and renamed) whenever any of configured criteria
 * (file open timeout, max records or max file size) has been met. Names of finalized files will be
 * in dateTime_instanceName_shardIndex.parquet format, which dateTime is in
 * {@link #DATETIME_PATTERN} format.<br>
 * Files can optionally be put in separate directories based on the time it has been finalized.
 * Format of these directories can be configured using
 * {@link Builder#directoryDateTimePattern(String)}.
 *
 * @param <T> class of proto message to write
 */
public class KafkaProtoParquetWriter<T extends Message> implements Closeable {
    private static final Logger logger = LoggerFactory.getLogger(KafkaProtoParquetWriter.class);

    private static final int DEFAULT_KAFKA_POLL_TIMEOUT = 1000;
    private static final long RETRY_SLEEP_MILLIS = 100L;

    private static final String DATETIME_PATTERN = "yyyyMMdd-HHmmssSSS";
    private static final DateTimeFormatter FILENAME_DATETIME_FORMATTER =
            DateTimeFormatter.ofPattern(DATETIME_PATTERN, Locale.getDefault())
                    .withZone(ZoneId.systemDefault());

    public static final String PARQUET_FILE_EXTENSION = ".parquet";
    private static final String TEMP_FILE_EXTENSION = ".tmp";



    /** Kafka consumer poll timeout */
    private final long kafkaPollTimeoutMillis;

    /**
     * Instance name of writer, this value is used for distinguish files generated by different
     * instance of class
     */
    private final String instanceName;
    // Kafka topic to read message from
    private final String topic;
    // Kafka consumer config
    private final Map<String, Object> consumerConfig;
    // Directory to generate parquet files in
    private final Path targetDir;
    // Proto message parser used to parse messages
    private final Parser<T> parser;

    // Class of written proto message
    private final Class<T> protoClass;

    // Properties of created parquet files
    private final ParquetProperties parquetProperties;
    // Number of threads used for read and writing
    private final int threadCount;
    // Maximum number of records to write in files. Zero value means no limitation.
    private final long maxRecordsInFile;
    // Maximum size of created parquet files. Zero value means no limitation.
    private final long maxFileSize;
    /**
     * Maximum time each file is kept open in milliseconds, after this time data of current shard
     * will be flushed and next file will be created. Zero value means no limitation.
     */
    private final long maxFileOpenDurationMillis;

    // Hadoop Configuration
    private final Configuration hadoopConf;
    // Pattern used for directory creation inside targetDir
    private final DateTimeFormatter directoryDateTimeFormatter;

    // Total written records by this writer
    private final Meter totalWrittenRecords = new Meter();
    // Total records written to disk by this writer
    private final Meter totalFlushedRecords = new Meter();
    // Total size of written proto messages in bytes
    private final Meter totalWrittenBytes = new Meter();
    // Total size of flushed data (bytes) in parquet files
    private final Meter totalFlushedBytes = new Meter();

    private List<WorkerThread> workerThreads;

    private KafkaProtoParquetWriter(Builder<T> builder) {
        instanceName = builder.instanceName;
        topic = builder.topic;
        protoClass = builder.protoClass;
        parser = builder.parser;

        consumerConfig = new HashMap<>(builder.consumerConfig);
        consumerConfig.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        consumerConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                ByteArrayDeserializer.class);
        consumerConfig.computeIfAbsent(ConsumerConfig.GROUP_ID_CONFIG,
            key -> "KafkaProtoParquetWriter-" + instanceName);

        parquetProperties = new ParquetProperties(builder.hadoopConf, builder.blockSize,
                builder.compressionCodecName, builder.pageSize, builder.enableDictionary);
        threadCount = builder.threadCount;
        maxRecordsInFile = builder.maxRecordsInFile;
        maxFileSize = builder.maxFileSize;
        maxFileOpenDurationMillis = builder.maxFileOpenDurationMillis;
        directoryDateTimeFormatter = builder.directoryDateTimeFormatter;

        hadoopConf = new Configuration(builder.hadoopConf);
        String defaultFs = hadoopConf.get(DFSConfigKeys.FS_DEFAULT_NAME_KEY);
        Validate.notNull(defaultFs);
        Validate.isTrue(!defaultFs.isEmpty());
        targetDir = new Path(defaultFs, builder.targetDir);

        // Consumer poll timeout must be less than max file duration
        this.kafkaPollTimeoutMillis = Math.min(DEFAULT_KAFKA_POLL_TIMEOUT,
                maxFileOpenDurationMillis > 0 ? maxFileOpenDurationMillis : Long.MAX_VALUE);

        MetricRegistry metricRegistry = builder.metricRegistry;
        if (metricRegistry != null) {
            metricRegistry.register("parquet.writer." + instanceName + ".written.records",
                    totalWrittenRecords);
            metricRegistry.register("parquet.writer." + instanceName + ".flushed.records",
                    totalFlushedRecords);
            metricRegistry.register("parquet.writer." + instanceName + ".written.bytes",
                    totalWrittenBytes);
            metricRegistry.register("parquet.writer." + instanceName + ".flushed.bytes",
                    totalFlushedBytes);
        }
    }

    /**
     * Shard writers starts to listen to configured Kafka topic and writing received messages to
     * parquet files.
     *
     * @throws IOException if connecting  to Kafka fails
     */
    public void start() throws IOException, InterruptedException {
        logger.info("Starting Kafka parquet writer '{}' for {} topic...", instanceName, topic);
        workerThreads = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            WorkerThread writer = new WorkerThread(i);
            workerThreads.add(writer);
            writer.start();
        }
        logger.info("Kafka parquet writer '{}' started for {} topic.", instanceName, topic);
    }

    /**
     * Closes all parquet files and Kafka consumers. <b>Note that this method does not throw
     * underlying I/O exceptions if closing any of files fails. It is simply logs the exception and
     * returns.</b>
     */
    @Override
    public void close() throws IOException {
        logger.debug("Closing Kafka parquet writer '{}' of {} topic...", instanceName, topic);
        for (WorkerThread worker : workerThreads) {
            worker.close();
        }
        logger.info("Kafka parquet writer '{}' of {} topic closed.", instanceName, topic);
    }

    /**
     * @return number of total written records till writer has been started
     */
    public long getTotalWrittenRecords() {
        return totalWrittenRecords.getCount();
    }

    /**
     * @return size of total proto messages which has been written till writer has been started
     */
    public long getTotalWrittenBytes() {
        return totalWrittenBytes.getCount();
    }

    /**
     * Reading from Kafka and writing to parquet files are done using instances of this class.
     * Each instance creates thread, reads from Kafka topic and writes them to a parquet file.
     */
    private class WorkerThread implements Runnable, Closeable {

        private final int index;
        private KafkaConsumer<?, byte[]> consumer;
        private Thread thread;
        private volatile boolean running = false;

        private ParquetFile<T> currentFile;

        /**
         * Each worker thread has a single temporary file path which writes data in it before
         * finalizing the files. On finalizing the files, we rename them to separate names.
         */
        private final Path temporaryFilePath;

        // Lock used to synchronize interrupting thread and closing underlying parquet writer.
        private final Object closeLock = new Object();

        private Map<Integer, Long> writtenOffsets = new HashMap<>();

        WorkerThread(int index) {
            this.index = index;
            temporaryFilePath =
                    new Path(targetDir, instanceName + "_" + index + TEMP_FILE_EXTENSION);
        }

        /**
         * Starts to listen to configured Kafka topic and writing received messages to parquet file
         * through. This method is not blocking and reading and writing messages are done in another
         * threads.
         * @throws IOException if connecting to Kafka fails
         */
        void start() throws IOException, InterruptedException {
            initConsumer();
            running = true;
            thread = new Thread(this, "KafkaProtoParquetWriter-" + instanceName + "-" + index);
            thread.start();
        }

        /**
         * Creates consumer and subscribes to target topic.
         */
        private void initConsumer() throws InterruptedException, IOException {
            /*
             * TODO: Making number of threads independent of number of files
             * We should not bind the number of threads we want for our CPU workloads
             * (i.e., for proto deserialization and making parquet blocks) to the number of
             * concurrent parquet files. Because we can have limited number of concurrent parquet
             * files (due to We should not bind the number of threads we want for our CPU workloads
             * (i.e., for proto deserialization and making parquet blocks) to the number of
             * concurrent parquet files. Because we can have limited number of concurrent parquet
             * files (due to their memory consumption). If we want to fill up CPU, the number of
             * worker threads should be at least equal to the number of CPU cores and they should
             * not contain any I/O operations. This way, we can target to either fill up CPU or net.
             * their memory consumption). If we want to fill up CPU, the number of worker threads
             * should be at least equal to the number of CPU cores and they should not contain any
             * I/O operations. This way, we can target to either fill up CPU or net.
             */

            consumer = new KafkaConsumer<>(consumerConfig);
            consumer.subscribe(Collections.singleton(topic), new ConsumerRebalanceListener() {
                @Override
                public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                    logger.info("Partitions revoked for worker '{}', old assignment: {}.", index,
                            partitions);
                }

                @Override
                public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                    logger.info("{} partitions assigned to worker '{}'.", partitions, index);
                    /*
                     * Offset of partitions which has been unassigned from current shard must not be
                     * committed, so we remove partitions
                     */
                    if (writtenOffsets != null) {
                        writtenOffsets.keySet().removeIf(partition -> !partitions
                                .contains(new TopicPartition(topic, partition)));
                    }
                }
            });
            ExecutorService executor = Executors.newFixedThreadPool(1);
            try {
                executor.submit(() -> consumer.poll(0)).get(60, TimeUnit.SECONDS);
                executor.shutdown();
            } catch (ExecutionException | TimeoutException e) {
                throw new IOException("Connecting to kafka failed.", e);
            }
            logger.debug("Kafka consumer for worker '{}' initialized.", index);
        }

        @Override
        public void run() {
            while (running) {
                try {
                    if (currentFile != null && isCurrentFileTimedOut()) {
                        finalizeCurrentFile();
                    }
                    ConsumerRecords<?, byte[]> records = consumer.poll(kafkaPollTimeoutMillis);
                    logger.trace("Read {} records from consumer.", records.count());
                    if (records.isEmpty()) {
                        continue;
                    }
                    if (currentFile == null) {
                        currentFile = tryUntilSucceeds(() -> new ParquetFile<>(temporaryFilePath,
                                protoClass, parquetProperties));
                    }
                    Iterator<? extends ConsumerRecord<?, byte[]>> it = records.iterator();
                    while (it.hasNext()) {
                        ConsumerRecord<?, byte[]> record = it.next();
                        T log;
                        try {
                            log = parser.parseFrom(record.value());
                        } catch (InvalidProtocolBufferException e) {
                            // TODO: Support more general purpose way to handle invalid logs
                            // General implementation can call a user provided callback and
                            // expose invalid log metrics
                            throw new IllegalStateException("Invalid proto message received.",
                                    e);
                        }
                        tryUntilSucceeds(() -> currentFile.write(log));
                        writtenOffsets.put(record.partition(), record.offset());
                        totalWrittenRecords.mark();
                        totalWrittenBytes.mark(record.serializedValueSize());
                        if (isCurrentFileFull()) {
                            logger.debug("File {} is full, starting to close file.",
                                    temporaryFilePath);
                            finalizeCurrentFile();
                            if (it.hasNext()) {
                                currentFile =
                                        tryUntilSucceeds(() -> new ParquetFile<>(temporaryFilePath,
                                                protoClass, parquetProperties));
                            }
                        }
                    }

                } catch (WakeupException | InterruptedException e) {
                    if (running) {
                        throw new IllegalStateException("Unexpected exception occurred.", e);
                    }
                }
            }
        }

        /**
         * @return if open file duration has been reached and current file must be closed.
         */
        private boolean isCurrentFileTimedOut() {
            return maxFileOpenDurationMillis > 0 && (System.currentTimeMillis()
                    - currentFile.getCreationDate().getTime()) > maxFileOpenDurationMillis;
        }

        /**
         * @return true if size or record count has been reached and current file must be flushed
         *         and closed.
         */
        private boolean isCurrentFileFull() {
            /*
             * zero value for maxFileOpenDurationMillis, maxRecordsInFile and maxFileSize means no
             * limitation is opposed.
             */
            return (maxRecordsInFile > 0 && currentFile.getNumWrittenRecords() >= maxRecordsInFile)
                    || (maxFileSize > 0 && currentFile.getDataSize() >= maxFileSize);
        }

        /**
         * @return name for a new parquet file
         */
        private String newFileName() {
            return FILENAME_DATETIME_FORMATTER.format(Instant.now()) + "_" + instanceName + "_"
                   + index + PARQUET_FILE_EXTENSION;
        }

        /**
         * Closes current temporary file and renames it to its final name. It also moves it to
         * appropriate directory based on {@link #directoryDateTimeFormatter} if it has been
         * configured.
         */
        private void finalizeCurrentFile() throws InterruptedException {
            final long dataSize = currentFile.getDataSize();
            // Unfortunately ParquetWriter.close() eats InterruptedException and throws
            // IOException instead. We retry operation when IOException is thrown, this leads to
            // NullPointerException in ParquetWriter.close() in second invocation. To avoid this
            // situation, interrupting ParquetWriter.close() is prevented by synchronizing on a lock
            // object.
            tryUntilSucceeds(() -> {
                synchronized (closeLock) {
                    currentFile.close();
                }
            });
            totalFlushedRecords.mark(currentFile.getNumWrittenRecords());
            totalFlushedBytes.mark(dataSize);
            currentFile = null;

            Path finalFilePath = renameAndMoveTempFile();

            // Committing written offsets
            if (!writtenOffsets.isEmpty()) {
                Map<TopicPartition, OffsetAndMetadata> offsetsToCommit =
                        writtenOffsets.entrySet().stream().collect(
                                Collectors.toMap(entry -> new TopicPartition(topic, entry.getKey()),
                                    entry -> new OffsetAndMetadata(entry.getValue() + 1)));
                logger.debug("Committing {} offsets for '{}' file.", offsetsToCommit,
                        finalFilePath);
                consumer.commitAsync(offsetsToCommit, (offsets, exception) -> {
                    if (exception == null) {
                        logger.debug("Committing offsets '{}' succeeded.", offsets);
                    } else {
                        logger.error("Committing offsets {} failed.", offsets, exception);
                    }
                });

                writtenOffsets.clear();
            }
        }

        /**
         * Renames temporary file to its final name and optionally moves it to directory configured
         * with {@link #directoryDateTimeFormatter}
         *
         * @return path of final parquet file
         */
        private Path renameAndMoveTempFile() throws InterruptedException {
            Path destDir;
            FileSystem fileSystem = tryUntilSucceeds(() -> FileSystem.get(hadoopConf));
            // Creating directory based on provided directory datetime pattern if it does not exist
            if (directoryDateTimeFormatter != null) {
                destDir = new Path(targetDir, directoryDateTimeFormatter.format(Instant.now()));
                tryUntilSucceeds(() -> fileSystem.mkdirs(destDir));
            } else {
                destDir = targetDir;
            }
            // Renaming temporary file based on current time (and optionally moving to appropriate
            // directory if directoryDateTimeFormatter is provided)
            Path finalFilePath = tryUntilSucceeds(() -> {
                Path dst = new Path(destDir, newFileName());
                fileSystem.rename(temporaryFilePath, dst);
                return dst;
            });
            logger.debug("Parquet file '{}' finalized to '{}'", temporaryFilePath, finalFilePath);
            return finalFilePath;
        }

        @Override
        public void close() throws IOException {
            logger.info("Closing parquet writer worker thread: {}.", index);
            running = false;
            if (consumer != null) {
                consumer.wakeup();
                if (thread != null) {
                    // Preventing interrupting ParquetWriter.close() method. Refer to
                    // finalizeCurrentFile method to further explanation.
                    synchronized (closeLock) {
                        thread.interrupt();
                    }
                    try {
                        thread.join();
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(
                                "Unexpected interrupt while joining thread.", e);
                    }
                }
                consumer.close();
            }
            logger.info("Parquet writer worker thread {} closed.", index);
        }
    }

    /**
     * Tries given I/O operation until succeeds. Operation is retried if {@link IOException} is
     * thrown. It sleeps RETRY_SLEEP_MILLIS milliseconds between retries.
     *
     * @return result of given callable
     *
     * @throws IllegalStateException if operation throws any checked exception other than
     *         {@link IOException}
     */
    private static <V> V tryUntilSucceeds(Callable<V> operation) throws InterruptedException {
        int tried = 0;
        do {
            try {
                return operation.call();
            } catch (InterruptedIOException e) {
                logger.info("I/O operation interrupted.", e);
                throw new InterruptedException("I/O operation interrupted.");
            } catch (IOException e) {
                tried++;
                logger.error("I/O operation failed (tried {}).", tried, e);
                Thread.sleep(RETRY_SLEEP_MILLIS);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("Unexpected exception occurred.", e);
            }
        } while (true);
    }

    /**
     * Tries given I/O operation until succeeds. Operation is retried if {@link IOException} is
     * thrown. It sleeps RETRY_SLEEP_MILLIS milliseconds between retries.
     *
     * @throws IllegalStateException if operation throws any checked exception other than
     *         {@link IOException}
     */
    private static void tryUntilSucceeds(RunnableWithException<?> runnable)
            throws InterruptedException {
        tryUntilSucceeds(() -> {
            runnable.run();
            return null;
        });
    }

    /**
     * Builder class for {@link KafkaProtoParquetWriter}.
     *
     * @param <T> type of proto message
     */
    public static class Builder<T extends Message> {
        // Minimum value for maxFileSize config
        private static final long MIN_MAX_FILE_SIZE = 100 * 1024L;

        private final String instanceName;
        private final String topic;
        private final Map<String, Object> consumerConfig;
        private final String targetDir;
        private final Class<T> protoClass;
        private final Parser<T> parser;

        private Configuration hadoopConf = new Configuration();
        private CompressionCodecName compressionCodecName = CompressionCodecName.UNCOMPRESSED;
        private int blockSize = ParquetWriter.DEFAULT_BLOCK_SIZE;
        private int pageSize = ParquetWriter.DEFAULT_PAGE_SIZE;
        private boolean enableDictionary = true;
        private int threadCount = 1;
        private MetricRegistry metricRegistry = null;
        private long maxRecordsInFile = 0L;
        private long maxFileSize = 1024 * 1024 * 1024L; // 1GB
        private long maxFileOpenDurationMillis = 0L; // Infinite
        private DateTimeFormatter directoryDateTimeFormatter;

        /**
         * @param instanceName instance name for
         * @param topic Kafka topic to read data from
         * @param consumerConfig config for Kafka consumer
         * @param targetDir path of directory to store parquet files
         * @param protoClass class of proto message to store
         * @param parser parser instance for parsing proto messages
         */
        public Builder(String instanceName, String topic, Map<String, Object> consumerConfig,
                String targetDir, Class<T> protoClass, Parser<T> parser) {
            Validate.notEmpty(instanceName, "Instance name cannot be null/empty.");
            Validate.notEmpty(topic, "Kafka topic cannot be null/empty.");
            Validate.notNull(protoClass, "Proto message class cannot be null.");
            Validate.notNull(parser, "Proto message parser cannot be null.");
            Validate.notEmpty(consumerConfig, "Kafka consumer config cannot be null/empty.");
            this.instanceName = instanceName;
            this.topic = topic;
            this.consumerConfig = consumerConfig;
            this.targetDir = targetDir;
            this.protoClass = protoClass;
            this.parser = parser;
        }

        /**
         * @param compressionCodecName name of compression codec used in parquet files
         */
        public Builder<T> compressionCodecName(CompressionCodecName compressionCodecName) {
            Validate.notNull(compressionCodecName, "Compression codec cannot be null.");
            this.compressionCodecName = compressionCodecName;
            return this;
        }

        /**
         * @param blockSize HDFS block size of parquet files
         */
        public Builder<T> blockSize(int blockSize) {
            Validate.isTrue(blockSize > 0, "Block size must be a positive number.");
            this.blockSize = blockSize;
            return this;
        }

        /**
         * @param pageSize page size of generated parquet files
         */
        public Builder<T> pageSize(int pageSize) {
            Validate.isTrue(pageSize > 0, "Page size must be a positive number.");
            this.pageSize = pageSize;
            return this;
        }

        /**
         * @param enableDictionary whether to enable dictionary in parquet files or not
         */
        public Builder<T> enableDictionary(boolean enableDictionary) {
            this.enableDictionary = enableDictionary;
            return this;
        }

        /**
         * @param metricRegistry metric registry to register metrics on.
         */
        public Builder<T> metricRegistry(MetricRegistry metricRegistry) {
            this.metricRegistry = metricRegistry;
            return this;
        }

        /**
         * @param threadCount number of concurrent threads used for writing parquet file. Default
         *        value is 1.
         */
        public Builder<T> threadCount(int threadCount) {
            Validate.isTrue(threadCount > 0, "Thread count must be a positive number.");
            this.threadCount = threadCount;
            return this;
        }

        /**
         * @param maxRecordsInFile maximum records in each parquet file. Zero means no limitation.
         *        Default value is 0.
         */
        public Builder<T> maxRecordsInFile(long maxRecordsInFile) {
            this.maxRecordsInFile = maxRecordsInFile;
            return this;
        }

        /**
         * @param maxFileSize maximum size of parquet files. Zero means no limitation. Default value
         *        is 0. <br/>
         *        Due to file format overheads this value must be >= {{@link #MIN_MAX_FILE_SIZE}}
         */
        public Builder<T> maxFileSize(long maxFileSize) {
            Validate.isTrue(maxFileSize >= MIN_MAX_FILE_SIZE);
            this.maxFileSize = maxFileSize;
            return this;
        }

        /**
         * @param maxFileDuration maximum time each file is kept open in milliseconds, after this
         *        time data of current file will be flushed and next file will be created. Zero
         *        value means no limitation. Default value is 0.
         * @param unit unit of maxFileOpenDurationMillis
         */
        public Builder<T> maxFileOpenDuration(long maxFileDuration, TimeUnit unit) {
            Validate.isTrue(maxFileDuration >= 0, "Maximum duration of file cannot be negative.");
            this.maxFileOpenDurationMillis = unit.toMillis(maxFileDuration);
            return this;
        }

        public Builder<T> hadoopConf(Configuration hadoopConf) {
            Validate.notNull(hadoopConf);
            this.hadoopConf = hadoopConf;
            return this;
        }

        /**
         * Sets date pattern used to create directory inside target directory.
         *
         * @param directoryDateTimePattern pattern used to create directory from, see
         *        {@link DateTimeFormatter} for format. null value disables directory creation.
         * @throws IllegalArgumentException if pattern is invalid
         */
        public Builder<T> directoryDateTimePattern(String directoryDateTimePattern) {
            this.directoryDateTimeFormatter = (directoryDateTimePattern == null)
                    ? null
                    : DateTimeFormatter.ofPattern(directoryDateTimePattern, Locale.getDefault())
                            .withZone(ZoneId.systemDefault());
            return this;
        }

        public KafkaProtoParquetWriter<T> build() {
            return new KafkaProtoParquetWriter<>(this);
        }
    }

    /**
     * Similar to {@link Runnable} which can throw exception.
     * @param <X> type of exception that can be thrown
     */
    @FunctionalInterface
    private interface RunnableWithException<X extends Exception> {
        void run() throws X;
    }
}
