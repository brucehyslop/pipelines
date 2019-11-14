package org.gbif.pipelines.transforms;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

import org.gbif.api.model.registry.MachineTag;
import org.gbif.api.vocabulary.TagNamespace;
import org.gbif.dwc.terms.Term;
import org.gbif.dwc.terms.TermFactory;
import org.gbif.pipelines.io.avro.ExtendedRecord;
import org.gbif.pipelines.parsers.config.WsConfig;
import org.gbif.pipelines.parsers.config.WsConfigFactory;
import org.gbif.pipelines.parsers.ws.client.metadata.MetadataServiceClient;
import org.gbif.pipelines.parsers.ws.client.metadata.response.Dataset;

import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.DoFn.Setup;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.PCollection;

import com.google.common.base.Strings;

/**
 * Beam level transformations to use verbatim default term values defined as MachineTags in an MetadataRecord.
 * transforms form {@link ExtendedRecord} to {@link ExtendedRecord}.
 */
public class DefaultValuesTransform extends PTransform<PCollection<ExtendedRecord>, PCollection<ExtendedRecord>> {

  private static final String DEFAULT_TERM_NAMESPACE = TagNamespace.GBIF_DEFAULT_TERM.getNamespace();
  private static final TermFactory TERM_FACTORY = TermFactory.instance();

  private final String datasetId;
  private final WsConfig wsConfig;

  private MetadataServiceClient client;

  private DefaultValuesTransform(WsConfig wsConfig, String datasetId) {
    this.wsConfig = wsConfig;
    this.datasetId = datasetId;
  }

  public static DefaultValuesTransform create(String propertiesPath, String datasetId) {
    WsConfig wsConfig = WsConfigFactory.create(Paths.get(propertiesPath), WsConfigFactory.METADATA_PREFIX);
    return new DefaultValuesTransform(wsConfig, datasetId);
  }

  public static DefaultValuesTransform create(Properties properties, String datasetId) {
    WsConfig wsConfig = WsConfigFactory.create(properties, WsConfigFactory.METADATA_PREFIX);
    return new DefaultValuesTransform(wsConfig, datasetId);
  }

  @Setup
  public void setup() {
    if (wsConfig != null) {
      client = MetadataServiceClient.create(wsConfig);
    }
  }

  /**
   * If the condition is FALSE returns empty collections, if you will you "write" data, it will create an empty file,
   * which is  useful when you "read" files, cause Beam can throw an exception if a file is absent
   */
  @Override
  public PCollection<ExtendedRecord> expand(PCollection<ExtendedRecord> input) {
    Dataset dataset = client.getDataset(datasetId);
    List<MachineTag> tags = Collections.emptyList();
    if (dataset != null && dataset.getMachineTags() != null && !dataset.getMachineTags().isEmpty()) {
      tags = dataset.getMachineTags()
          .stream()
          .filter(tag -> DEFAULT_TERM_NAMESPACE.equalsIgnoreCase(tag.getNamespace()))
          .collect(Collectors.toList());
    }
    return tags.isEmpty() ? input : ParDo.of(createDoFn(tags)).expand(input);
  }

  private DoFn<ExtendedRecord, ExtendedRecord> createDoFn(List<MachineTag> tags) {
    return new DoFn<ExtendedRecord, ExtendedRecord>() {
      @ProcessElement
      public void processElement(ProcessContext c) {

        ExtendedRecord er = ExtendedRecord.newBuilder(c.element()).build();

        tags.forEach(tag -> {
          Term term = TERM_FACTORY.findPropertyTerm(tag.getName());
          String defaultValue = tag.getValue();
          if (term != null && !Strings.isNullOrEmpty(defaultValue)) {
            er.getCoreTerms().replace(term.qualifiedName(), tag.getValue());
          }
        });

        c.output(er);
      }
    };
  }
}
