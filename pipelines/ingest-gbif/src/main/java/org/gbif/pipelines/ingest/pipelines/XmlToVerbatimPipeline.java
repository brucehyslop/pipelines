package org.gbif.pipelines.ingest.pipelines;

import org.gbif.pipelines.common.PipelinesVariables.Pipeline.Conversion;
import org.gbif.pipelines.common.beam.XmlIO;
import org.gbif.pipelines.ingest.options.BasePipelineOptions;
import org.gbif.pipelines.ingest.options.PipelinesOptionsFactory;
import org.gbif.pipelines.ingest.utils.FsUtils;
import org.gbif.pipelines.ingest.utils.MetricsHandler;
import org.gbif.pipelines.transforms.core.VerbatimTransform;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.slf4j.MDC;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Pipeline sequence:
 *
 * <pre>
 *    1) Reads XML files and converts to {@link org.gbif.pipelines.io.avro.ExtendedRecord}
 *    2) Writes data to verbatim.avro file
 * </pre>
 *
 * <p>How to run:
 *
 * <pre>{@code
 * java -cp target/ingest-gbif-BUILD_VERSION-shaded.jar org.gbif.pipelines.ingest.pipelines.XmlToVerbatimPipeline some.properties
 *
 * or pass all parameters:
 *
 * java -cp target/ingest-gbif-BUILD_VERSION-shaded.jar org.gbif.pipelines.ingest.pipelines.XmlToVerbatimPipeline
 * --datasetId=9f747cff-839f-4485-83a1-f10317a92a82
 * --attempt=1
 * --runner=SparkRunner
 * --targetPath=/path/GBIF/output/
 * --inputPath=/path/GBIF/input/xml/9f747cff-839f-4485-83a1-f10317a92a82/*.xml
 *
 * }</pre>
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class XmlToVerbatimPipeline {

  public static void main(String[] args) {
    BasePipelineOptions options = PipelinesOptionsFactory.create(BasePipelineOptions.class, args);
    run(options);
  }

  public static void run(BasePipelineOptions options) {

    MDC.put("datasetId", options.getDatasetId());
    MDC.put("attempt", options.getAttempt().toString());

    log.info("Adding step 1: Options");
    String targetPath = FsUtils.buildPath(options, Conversion.FILE_NAME);

    log.info("Adding step 2: Pipeline steps");
    Pipeline p = Pipeline.create(options);

    VerbatimTransform verbatimTransform = VerbatimTransform.create();

    p.apply("Read ExtendedRecords", XmlIO.read(options.getInputPath()))
        .apply("Write to avro", verbatimTransform.write(targetPath).withoutSharding());

    log.info("Running the pipeline");
    PipelineResult result = p.run();
    result.waitUntilFinish();

    MetricsHandler.saveCountersToFile(options, result);

    log.info("Pipeline has been finished");
  }
}
