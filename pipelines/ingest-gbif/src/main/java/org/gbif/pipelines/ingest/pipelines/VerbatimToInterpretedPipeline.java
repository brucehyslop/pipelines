package org.gbif.pipelines.ingest.pipelines;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.function.UnaryOperator;

import org.gbif.pipelines.ingest.options.InterpretationPipelineOptions;
import org.gbif.pipelines.ingest.options.PipelinesOptionsFactory;
import org.gbif.pipelines.ingest.utils.FsUtils;
import org.gbif.pipelines.ingest.utils.MetricsHandler;
import org.gbif.pipelines.io.avro.ExtendedRecord;
import org.gbif.pipelines.io.avro.MetadataRecord;
import org.gbif.pipelines.transforms.UniqueIdTransform;
import org.gbif.pipelines.transforms.converters.OccurrenceExtensionTransform;
import org.gbif.pipelines.transforms.core.BasicTransform;
import org.gbif.pipelines.transforms.core.LocationTransform;
import org.gbif.pipelines.transforms.core.MetadataTransform;
import org.gbif.pipelines.transforms.core.TaxonomyTransform;
import org.gbif.pipelines.transforms.core.TemporalTransform;
import org.gbif.pipelines.transforms.core.VerbatimTransform;
import org.gbif.pipelines.transforms.extension.AudubonTransform;
import org.gbif.pipelines.transforms.extension.ImageTransform;
import org.gbif.pipelines.transforms.extension.MeasurementOrFactTransform;
import org.gbif.pipelines.transforms.extension.MultimediaTransform;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.View;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionView;
import org.slf4j.MDC;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Pipeline sequence:
 *
 * <pre>
 *    1) Reads verbatim.avro file
 *    2) Interprets and converts avro {@link org.gbif.pipelines.io.avro.ExtendedRecord} file to:
 *      {@link org.gbif.pipelines.io.avro.MetadataRecord},
 *      {@link org.gbif.pipelines.io.avro.BasicRecord},
 *      {@link org.gbif.pipelines.io.avro.TemporalRecord},
 *      {@link org.gbif.pipelines.io.avro.MultimediaRecord},
 *      {@link org.gbif.pipelines.io.avro.ImageRecord},
 *      {@link org.gbif.pipelines.io.avro.AudubonRecord},
 *      {@link org.gbif.pipelines.io.avro.MeasurementOrFactRecord},
 *      {@link org.gbif.pipelines.io.avro.TaxonRecord},
 *      {@link org.gbif.pipelines.io.avro.LocationRecord}
 *    3) Writes data to independent files
 * </pre>
 *
 * <p>How to run:
 *
 * <pre>{@code
 * java -cp target/ingest-gbif-BUILD_VERSION-shaded.jar org.gbif.pipelines.ingest.pipelines.VerbatimToInterpretedPipeline some.properties
 *
 * or pass all parameters:
 *
 * java -cp target/ingest-gbif-BUILD_VERSION-shaded.jar org.gbif.pipelines.ingest.pipelines.VerbatimToInterpretedPipeline
 * --properties=/some/path/to/output/ws.properties
 * --datasetId=0057a720-17c9-4658-971e-9578f3577cf5
 * --attempt=1
 * --interpretationTypes=ALL
 * --runner=SparkRunner
 * --targetPath=/some/path/to/output/
 * --inputPath=/some/path/to/output/0057a720-17c9-4658-971e-9578f3577cf5/1/verbatim.avro
 *
 * }</pre>
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class VerbatimToInterpretedPipeline {

  public static void main(String[] args) {
    InterpretationPipelineOptions options = PipelinesOptionsFactory.createInterpretation(args);
    run(options);
  }

  public static void run(InterpretationPipelineOptions options) {

    String datasetId = options.getDatasetId();
    Integer attempt = options.getAttempt();
    boolean tripletValid = options.isTripletValid();
    boolean occurrenceIdValid = options.isOccurrenceIdValid();
    boolean useExtendedRecordId = options.isUseExtendedRecordId();
    String endPointType = options.getEndPointType();
    Set<String> types = options.getInterpretationTypes();
    String propertiesPath = options.getProperties();
    String targetPath = options.getTargetPath();
    String hdfsSiteConfig = options.getHdfsSiteConfig();

    FsUtils.deleteInterpretIfExist(hdfsSiteConfig, targetPath, datasetId, attempt, types);

    MDC.put("datasetId", datasetId);
    MDC.put("attempt", attempt.toString());

    String id = Long.toString(LocalDateTime.now().toEpochSecond(ZoneOffset.UTC));

    UnaryOperator<String> pathFn = t -> FsUtils.buildPathInterpret(options, t, id);

    log.info("Creating a pipeline from options");
    Pipeline p = Pipeline.create(options);

    log.info("Creating transformations");
    VerbatimTransform verbatimTransform = VerbatimTransform.create();
    AudubonTransform audubonTransform = AudubonTransform.create();
    TemporalTransform temporalTransform = TemporalTransform.create();
    MultimediaTransform multimediaTransform = MultimediaTransform.create();
    ImageTransform imageTransform = ImageTransform.create();
    MeasurementOrFactTransform measurementOrFactTransform = MeasurementOrFactTransform.create();
    BasicTransform basicTransform = BasicTransform.create(propertiesPath, datasetId, tripletValid, occurrenceIdValid, useExtendedRecordId);
    MetadataTransform metadataTransform = MetadataTransform.create(propertiesPath, endPointType, attempt);
    TaxonomyTransform taxonomyTransform = TaxonomyTransform.create(propertiesPath);
    LocationTransform locationTransform = LocationTransform.create(propertiesPath);

    log.info("Creating beam pipeline");
    //Create and write metadata
    PCollection<MetadataRecord> metadataRecord =
        p.apply("Create metadata collection", Create.of(options.getDatasetId()))
            .apply("Interpret metadata", metadataTransform.interpret());

    metadataRecord.apply("Write metadata to avro", metadataTransform.write(pathFn));

    //Create View for further use
    PCollectionView<MetadataRecord> metadataView =
        metadataRecord
            .apply("Check verbatim transform condition", metadataTransform.checkMetadata(types))
            .apply("Convert into view", View.asSingleton());

    PCollection<ExtendedRecord> uniqueRecords = metadataTransform.metadataOnly(types) ?
        VerbatimTransform.emptyCollection(p) :
        p.apply("Read ExtendedRecords", verbatimTransform.read(options.getInputPath()))
            .apply("Read occurrences from extension", OccurrenceExtensionTransform.create())
            .apply("Filter duplicates", UniqueIdTransform.create());

    uniqueRecords
        .apply("Check verbatim transform condition", verbatimTransform.check(types))
        .apply("Write verbatim to avro", verbatimTransform.write(pathFn));

    uniqueRecords
        .apply("Check basic transform condition", basicTransform.check(types))
        .apply("Interpret basic", basicTransform.interpret())
        .apply("Write basic to avro", basicTransform.write(pathFn));

    uniqueRecords
        .apply("Check temporal transform condition", temporalTransform.check(types))
        .apply("Interpret temporal", temporalTransform.interpret())
        .apply("Write temporal to avro", temporalTransform.write(pathFn));

    uniqueRecords
        .apply("Check multimedia transform condition", multimediaTransform.check(types))
        .apply("Interpret multimedia", multimediaTransform.interpret())
        .apply("Write multimedia to avro", multimediaTransform.write(pathFn));

    uniqueRecords
        .apply("Check image transform condition", imageTransform.check(types))
        .apply("Interpret image", imageTransform.interpret())
        .apply("Write image to avro", imageTransform.write(pathFn));

    uniqueRecords
        .apply("Check audubon transform condition", audubonTransform.check(types))
        .apply("Interpret audubon", audubonTransform.interpret())
        .apply("Write audubon to avro", audubonTransform.write(pathFn));

    uniqueRecords
        .apply("Check measurement transform condition", measurementOrFactTransform.check(types))
        .apply("Interpret measurement", measurementOrFactTransform.interpret())
        .apply("Write measurement to avro", measurementOrFactTransform.write(pathFn));

    uniqueRecords
        .apply("Check taxonomy transform condition", taxonomyTransform.check(types))
        .apply("Interpret taxonomy", taxonomyTransform.interpret())
        .apply("Write taxon to avro", taxonomyTransform.write(pathFn));

    uniqueRecords
        .apply("Check location transform condition", locationTransform.check(types))
        .apply("Interpret location", locationTransform.interpret(metadataView))
        .apply("Write location to avro", locationTransform.write(pathFn));

    log.info("Running the pipeline");
    PipelineResult result = p.run();
    result.waitUntilFinish();

    MetricsHandler.saveCountersToFile(options, result);

    log.info("Deleting beam temporal folders");
    String tempPath = String.join("/", targetPath, datasetId, attempt.toString());
    FsUtils.deleteDirectoryByPrefix(hdfsSiteConfig, tempPath, ".temp-beam");

    log.info("Pipeline has been finished");
  }
}
