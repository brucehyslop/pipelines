package org.gbif.pipelines.transforms;

import java.util.Collections;
import java.util.Map;

import org.gbif.api.vocabulary.Extension;
import org.gbif.pipelines.io.avro.ExtendedRecord;
import org.gbif.pipelines.io.avro.MediaType;
import org.gbif.pipelines.io.avro.Multimedia;
import org.gbif.pipelines.io.avro.MultimediaRecord;
import org.gbif.pipelines.transforms.extension.MultimediaTransform.Interpreter;

import org.apache.beam.sdk.testing.NeedsRunner;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.PCollection;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class MultimediaRecordTransformTest {

  private static final String RECORD_ID = "123";
  private static final String URI = "http://specify-attachments-saiab.saiab.ac.za/originals/att.JPG";
  private static final String SOURCE = "http://farm8.staticflickr.com/7093/7039524065_8.jpg";
  private static final String TITLE = "Geranium Plume Moth 0032";
  private static final String DESCRIPTION = "Geranium Plume Moth 0032 description";
  private static final String LICENSE = "BY-NC-SA 2.0";
  private static final String CREATOR = "Moayed Bahajjaj";
  private static final String CREATED = "2012-03-29";

  @Rule
  public final transient TestPipeline p = TestPipeline.create();

  @Test
  @Category(NeedsRunner.class)
  public void transformationTest() {

    // State
    Map<String, String> extension =
        ExtendedRecordCustomBuilder.createMultimediaExtensionBuilder()
            .identifier(URI)
            .format("image/jpeg")
            .title(TITLE)
            .description(DESCRIPTION)
            .license(LICENSE)
            .creator(CREATOR)
            .created(CREATED)
            .source(SOURCE)
            .type("image")
            .build();

    ExtendedRecord extendedRecord =
        ExtendedRecordCustomBuilder.create()
            .id(RECORD_ID)
            .addExtensionRecord(Extension.MULTIMEDIA, extension)
            .build();

    // When
    PCollection<MultimediaRecord> dataStream =
        p.apply(Create.of(extendedRecord)).apply(ParDo.of(new Interpreter()));

    // Should
    PAssert.that(dataStream).containsInAnyOrder(createExpectedMultimedia());
    p.run();
  }

  private MultimediaRecord createExpectedMultimedia() {

    Multimedia multimedia =
        Multimedia.newBuilder()
            .setIdentifier(URI)
            .setFormat("image/jpeg")
            .setTitle(TITLE)
            .setDescription(DESCRIPTION)
            .setLicense(LICENSE)
            .setCreator(CREATOR)
            .setCreated(CREATED)
            .setSource(SOURCE)
            .setType(MediaType.StillImage.name())
            .build();

    return MultimediaRecord.newBuilder()
        .setId(RECORD_ID)
        .setMultimediaItems(Collections.singletonList(multimedia))
        .build();
  }
}
