package au.org.ala.pipelines.transforms;

import static org.apache.avro.Schema.Type.UNION;
import static org.gbif.pipelines.common.PipelinesVariables.Metrics.AVRO_TO_JSON_COUNT;

import au.org.ala.pipelines.interpreters.SensitiveDataInterpreter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import java.io.Serializable;
import java.text.DecimalFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.avro.Schema;
import org.apache.avro.Schema.Field;
import org.apache.avro.specific.SpecificRecordBase;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.join.CoGbkResult;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.util.Strings;
import org.apache.solr.common.SolrInputDocument;
import org.gbif.api.vocabulary.Extension;
import org.gbif.dwc.terms.DcTerm;
import org.gbif.dwc.terms.DwcTerm;
import org.gbif.dwc.terms.Term;
import org.gbif.dwc.terms.TermFactory;
import org.gbif.pipelines.io.avro.*;
import org.jetbrains.annotations.NotNull;

/**
 * A transform that creates IndexRecords which are used downstream to push data to a search index
 * (SOLR or ElasticSearch)
 */
@Slf4j
public class IndexRecordTransform implements Serializable, IndexFields {

  private static final long serialVersionUID = 1279313931024806169L;
  private static final TermFactory TERM_FACTORY = TermFactory.instance();

  // Core
  @NonNull private TupleTag<ExtendedRecord> erTag;
  @NonNull private TupleTag<BasicRecord> brTag;
  @NonNull private TupleTag<TemporalRecord> trTag;
  @NonNull private TupleTag<LocationRecord> lrTag;

  private TupleTag<TaxonRecord> txrTag;
  @NonNull private TupleTag<ALATaxonRecord> atxrTag;
  @NonNull private TupleTag<MultimediaRecord> mrTag;

  private TupleTag<ALAAttributionRecord> aarTag;
  @NonNull private TupleTag<ALAUUIDRecord> urTag;

  @NonNull private TupleTag<ImageRecord> isTag;

  @NonNull private TupleTag<TaxonProfile> tpTag;

  @NonNull private TupleTag<ALASensitivityRecord> srTag;

  @NonNull private PCollectionView<MetadataRecord> metadataView;

  String datasetID;

  Long lastLoadDate;

  Long lastLoadProcessed;

  static Set<String> interpretedFields;

  static {
    interpretedFields = getAddedValues();
  }

  public static void main(String[] args) {

    interpretedFields.stream().sorted().forEach(System.out::println);
  }

  public static IndexRecordTransform create(
      TupleTag<ExtendedRecord> erTag,
      TupleTag<BasicRecord> brTag,
      TupleTag<TemporalRecord> trTag,
      TupleTag<LocationRecord> lrTag,
      TupleTag<TaxonRecord> txrTag,
      TupleTag<ALATaxonRecord> atxrTag,
      TupleTag<MultimediaRecord> mrTag,
      TupleTag<ALAAttributionRecord> aarTag,
      TupleTag<ALAUUIDRecord> urTag,
      TupleTag<ImageRecord> isTag,
      TupleTag<TaxonProfile> tpTag,
      TupleTag<ALASensitivityRecord> srTag,
      PCollectionView<MetadataRecord> metadataView,
      String datasetID,
      Long lastLoadDate,
      Long lastLoadProcessed) {
    IndexRecordTransform t = new IndexRecordTransform();
    t.erTag = erTag;
    t.brTag = brTag;
    t.trTag = trTag;
    t.lrTag = lrTag;
    t.txrTag = txrTag;
    t.atxrTag = atxrTag;
    t.mrTag = mrTag;
    t.aarTag = aarTag;
    t.urTag = urTag;
    t.isTag = isTag;
    t.tpTag = tpTag;
    t.srTag = srTag;
    t.metadataView = metadataView;
    t.datasetID = datasetID;
    t.lastLoadDate = lastLoadDate;
    t.lastLoadProcessed = lastLoadProcessed;
    return t;
  }

  /**
   * Create a IndexRecord using the supplied records.
   *
   * @return IndexRecord
   */
  @NotNull
  public static IndexRecord createIndexRecord(
      MetadataRecord mdr,
      BasicRecord br,
      TemporalRecord tr,
      LocationRecord lr,
      TaxonRecord txr,
      ALATaxonRecord atxr,
      ExtendedRecord er,
      ALAAttributionRecord aar,
      ALAUUIDRecord ur,
      ImageRecord isr,
      TaxonProfile tpr,
      ALASensitivityRecord sr,
      Long lastLoadDate,
      Long lastProcessedDate) {

    Set<String> skipKeys = new HashSet<>();
    skipKeys.add("id");
    skipKeys.add("created");
    skipKeys.add("text");
    skipKeys.add("name");
    skipKeys.add("coreRowType");
    skipKeys.add("coreTerms");
    skipKeys.add("extensions");
    skipKeys.add("usage");
    skipKeys.add("classification");
    skipKeys.add("eventDate");
    skipKeys.add("hasCoordinate");
    skipKeys.add("hasGeospatialIssue");
    skipKeys.add("gbifId");
    skipKeys.add("crawlId");
    skipKeys.add("networkKeys");
    skipKeys.add("protocol");
    skipKeys.add("issues");
    skipKeys.add("identifiedByIds"); // multi value field
    skipKeys.add("recordedByIds"); // multi value field
    skipKeys.add("machineTags"); // TODO review content

    IndexRecord.Builder indexRecord = IndexRecord.newBuilder().setId(ur.getUuid());
    indexRecord.setBooleans(new HashMap<>());
    indexRecord.setStrings(new HashMap<>());
    indexRecord.setLongs(new HashMap<>());
    indexRecord.setInts(new HashMap<>());
    indexRecord.setDates(new HashMap<>());
    indexRecord.setDoubles(new HashMap<>());
    indexRecord.setMultiValues(new HashMap<>());
    List<String> assertions = new ArrayList<>();

    // add timestamps
    indexRecord.getDates().put(LAST_LOAD_DATE, lastLoadDate);
    indexRecord.getDates().put(LAST_PROCESSED_DATE, lastProcessedDate);

    // If a sensitive record, construct new versions of the data with adjustments
    boolean isSensitive = sr != null && sr.getIsSensitive() != null && sr.getIsSensitive();
    if (isSensitive) {
      Set<Term> sensitiveTerms =
          sr.getAltered().keySet().stream().map(TERM_FACTORY::findTerm).collect(Collectors.toSet());
      if (mdr != null) {
        mdr = MetadataRecord.newBuilder(mdr).build();
        SensitiveDataInterpreter.applySensitivity(sensitiveTerms, sr, mdr);
      }
      if (br != null) {
        br = BasicRecord.newBuilder(br).build();
        SensitiveDataInterpreter.applySensitivity(sensitiveTerms, sr, br);
      }
      if (tr != null) {
        tr = TemporalRecord.newBuilder(tr).build();
        SensitiveDataInterpreter.applySensitivity(sensitiveTerms, sr, tr);
      }
      if (lr != null) {
        lr = LocationRecord.newBuilder(lr).build();
        SensitiveDataInterpreter.applySensitivity(sensitiveTerms, sr, lr);
      }
      if (txr != null) {
        txr = TaxonRecord.newBuilder(txr).build();
        SensitiveDataInterpreter.applySensitivity(sensitiveTerms, sr, txr);
      }
      if (atxr != null) {
        atxr = ALATaxonRecord.newBuilder(atxr).build();
        SensitiveDataInterpreter.applySensitivity(sensitiveTerms, sr, atxr);
      }
      if (er != null) {
        er = ExtendedRecord.newBuilder(er).build();
        SensitiveDataInterpreter.applySensitivity(sensitiveTerms, sr, er);
      }
      if (aar != null) {
        aar = ALAAttributionRecord.newBuilder(aar).build();
        SensitiveDataInterpreter.applySensitivity(sensitiveTerms, sr, aar);
      }
    }

    addToIndexRecord(lr, indexRecord, skipKeys);
    addToIndexRecord(tr, indexRecord, skipKeys);
    addToIndexRecord(br, indexRecord, skipKeys);
    addToIndexRecord(mdr, indexRecord, skipKeys);

    if (br != null) {
      if (br.getRecordedByIds() != null && br.getRecordedByIds().isEmpty()) {
        indexRecord
            .getMultiValues()
            .put(
                RECORDED_BY_ID,
                br.getRecordedByIds().stream().map(a -> a.getValue()).collect(Collectors.toList()));
      }
      if (br.getIdentifiedByIds() != null && br.getIdentifiedByIds().isEmpty()) {
        indexRecord
            .getMultiValues()
            .put(
                IDENTIFIED_BY_ID,
                br.getRecordedByIds().stream().map(a -> a.getValue()).collect(Collectors.toList()));
      }
    }

    // add event date
    try {
      if (tr.getEventDate() != null
          && tr.getEventDate().getGte() != null
          && tr.getEventDate().getGte().length() == 10) {

        indexRecord
            .getDates()
            .put(
                DwcTerm.eventDate.simpleName(),
                new SimpleDateFormat("yyyy-MM-dd").parse(tr.getEventDate().getGte()).getTime());

        // eventDateEnd
        if (tr.getEventDate().getLte() != null) {
          indexRecord
              .getDates()
              .put(
                  EVENT_DATE_END,
                  new SimpleDateFormat("yyyy-MM-dd").parse(tr.getEventDate().getLte()).getTime());
        }
      }
    } catch (ParseException e) {
      log.error(
          "Un-parsable date produced by downstream interpretation " + tr.getEventDate().getGte());
    }

    if (tr.getYear() != null && tr.getYear() > 0) {
      indexRecord.getInts().put(DECADE, ((tr.getYear() / 10) * 10));

      // Added for backwards compatibility
      // see
      // https://github.com/AtlasOfLivingAustralia/biocache-store/blob/develop/src/main/scala/au/org/ala/biocache/index/IndexDAO.scala#L1077
      String occurrenceYear = tr.getYear() + "-01-01";
      try {
        long occurrenceYearTime =
            new SimpleDateFormat("yyyy-MM-dd").parse(occurrenceYear).getTime();
        indexRecord.getDates().put(OCCURRENCE_YEAR, occurrenceYearTime);
      } catch (ParseException ex) {
        // NOP
      }
    }

    // GBIF taxonomy - add if available
    if (txr != null) {
      addGBIFTaxonomy(txr, indexRecord, assertions);
    }

    if (isSensitive) {
      indexRecord.getStrings().put(SENSITIVE, sr.getSensitive());
    }

    // Sensitive (Original) data
    if (isSensitive) {
      if (sr.getDataGeneralizations() != null)
        indexRecord
            .getStrings()
            .put(DwcTerm.dataGeneralizations.simpleName(), sr.getDataGeneralizations());
      if (sr.getInformationWithheld() != null)
        indexRecord
            .getStrings()
            .put(DwcTerm.informationWithheld.simpleName(), sr.getInformationWithheld());
      if (sr.getGeneralisationInMetres() != null)
        indexRecord.getStrings().put(GENERALISATION_IN_METRES, sr.getGeneralisationInMetres());
      if (sr.getGeneralisationInMetres() != null)
        indexRecord
            .getStrings()
            .put(GENERALISATION_TO_APPLY_IN_METRES, sr.getGeneralisationInMetres());
      for (Map.Entry<String, String> entry : sr.getOriginal().entrySet()) {
        Term field = TERM_FACTORY.findTerm(entry.getKey());
        if (entry.getValue() != null) {
          indexRecord.getStrings().put(SENSITIVE_PREFIX + field.simpleName(), entry.getValue());
        }
      }
    }

    if (lr.getHasCoordinate() != null
        && lr.getHasCoordinate()
        && lr.getDecimalLatitude() != null
        && lr.getDecimalLongitude() != null) {
      addGeo(indexRecord, lr.getDecimalLatitude(), lr.getDecimalLongitude());
    }

    // ALA taxonomy & species groups - backwards compatible for EYA
    if (atxr.getTaxonConceptID() != null) {
      List<Schema.Field> fields = atxr.getSchema().getFields();
      for (Schema.Field field : fields) {
        Object value = atxr.get(field.name());
        if (value != null
            && !field.name().equals(SPECIES_GROUP)
            && !field.name().equals(SPECIES_SUBGROUP)
            && !field.name().equals(RANK)
            && !skipKeys.contains(field.name())) {
          if (field.name().equalsIgnoreCase("issues")) {
            assertions.add((String) value);
          } else {
            if (value instanceof Integer) {
              indexRecord.getInts().put(field.name(), (Integer) value);
            } else {
              indexRecord.getStrings().put(field.name(), value.toString());
            }
          }
        }

        if (atxr.getRank() != null) {
          indexRecord.getStrings().put(DwcTerm.taxonRank.simpleName(), atxr.getRank());
          if (atxr.getRankID() != null && atxr.getRankID() == SUBSPECIES_RANK_ID) {
            indexRecord.getStrings().put(SUBSPECIES, atxr.getScientificName());
            indexRecord.getStrings().put(SUBSPECIES_ID, atxr.getTaxonConceptID());
          }
        }
      }
      // legacy fields referenced in biocache-service code
      indexRecord.setTaxonID(atxr.getTaxonConceptID());
      indexRecord
          .getMultiValues()
          .put(
              SPECIES_GROUP,
              atxr.getSpeciesGroup().stream().distinct().collect(Collectors.toList()));
      indexRecord
          .getMultiValues()
          .put(
              SPECIES_SUBGROUP,
              atxr.getSpeciesSubgroup().stream().distinct().collect(Collectors.toList()));

      // required for EYA
      indexRecord
          .getStrings()
          .put(
              NAMES_AND_LSID,
              String.join(
                  "|",
                  atxr.getScientificName(),
                  atxr.getTaxonConceptID(),
                  StringUtils.trimToEmpty(atxr.getVernacularName()),
                  atxr.getKingdom(),
                  atxr.getFamily())); // is set to IGNORE in headerAttributes

      indexRecord
          .getStrings()
          .put(
              COMMON_NAME_AND_LSID,
              String.join(
                  "|",
                  StringUtils.trimToEmpty(atxr.getVernacularName()),
                  atxr.getScientificName(),
                  atxr.getTaxonConceptID(),
                  StringUtils.trimToEmpty(atxr.getVernacularName()),
                  atxr.getKingdom(),
                  atxr.getFamily())); // is set to IGNORE in headerAttribute
    }

    // see https://github.com/AtlasOfLivingAustralia/la-pipelines/issues/99
    boolean spatiallyValid = lr.getHasGeospatialIssue() == null || !lr.getHasGeospatialIssue();
    indexRecord.getBooleans().put(SPATIALLY_VALID, spatiallyValid);

    // see  https://github.com/AtlasOfLivingAustralia/la-pipelines/issues/162
    if (ur.getFirstLoaded() != null) {
      indexRecord
          .getDates()
          .put(
              FIRST_LOADED_DATE,
              LocalDateTime.parse(ur.getFirstLoaded(), DateTimeFormatter.ISO_DATE_TIME)
                  .toEpochSecond(ZoneOffset.UTC));
    }

    // Add legacy collectory fields
    if (aar != null) {
      addIfNotEmpty(indexRecord, DcTerm.license.simpleName(), aar.getLicenseType());
      addIfNotEmpty(indexRecord, DATA_RESOURCE_UID, aar.getDataResourceUid());
      addIfNotEmpty(indexRecord, DATA_RESOURCE_NAME, aar.getDataResourceName());
      addIfNotEmpty(indexRecord, DATA_PROVIDER_UID, aar.getDataProviderUid());
      addIfNotEmpty(indexRecord, DATA_PROVIDER_NAME, aar.getDataProviderName());
      addIfNotEmpty(indexRecord, INSTITUTION_UID, aar.getInstitutionUid());
      addIfNotEmpty(indexRecord, COLLECTION_UID, aar.getCollectionUid());
      addIfNotEmpty(indexRecord, INSTITUTION_NAME, aar.getInstitutionName());
      addIfNotEmpty(indexRecord, COLLECTION_NAME, aar.getCollectionName());
      addIfNotEmpty(indexRecord, PROVENANCE, aar.getProvenance());
      indexRecord
          .getBooleans()
          .put(
              DEFAULT_VALUES_USED,
              aar.getHasDefaultValues() != null ? aar.getHasDefaultValues() : false);

      // add hub IDs
      if (aar.getHubMembership() != null && !aar.getHubMembership().isEmpty()) {
        indexRecord
            .getMultiValues()
            .put(
                DATA_HUB_UID,
                aar.getHubMembership().stream()
                    .map(EntityReference::getUid)
                    .collect(Collectors.toList()));
        indexRecord
            .getMultiValues()
            .put(
                DATA_HUB_NAME,
                aar.getHubMembership().stream()
                    .map(EntityReference::getName)
                    .collect(Collectors.toList()));
      }
    }

    // add image identifiers
    if (isr != null && isr.getImageItems() != null && !isr.getImageItems().isEmpty()) {

      Set<String> multimedia = new HashSet<>();
      Set<String> licenses = new HashSet<>();
      List<String> images = new ArrayList<>();
      List<String> videos = new ArrayList<>();
      List<String> sounds = new ArrayList<>();
      isr.getImageItems()
          .forEach(
              image -> {
                if (StringUtils.isNotEmpty(image.getLicense())) {
                  licenses.add(image.getLicense());
                }

                if (image.getFormat() != null) {
                  if (image.getFormat().startsWith("image")) {
                    multimedia.add(IMAGE);
                    images.add(image.getIdentifier());
                  }
                  if (image.getFormat().startsWith("audio")) {
                    multimedia.add(SOUND);
                    sounds.add(image.getIdentifier());
                  }
                  if (image.getFormat().startsWith("video")) {
                    multimedia.add(VIDEO);
                    videos.add(image.getIdentifier());
                  }
                }
              });

      if (!images.isEmpty()) {
        indexRecord.getStrings().put(IMAGE_ID, isr.getImageItems().get(0).getIdentifier());
        indexRecord
            .getMultiValues()
            .put(
                IMAGE_IDS,
                isr.getImageItems().stream()
                    .map(Image::getIdentifier)
                    .collect(Collectors.toList()));
      }
      if (!sounds.isEmpty()) {
        indexRecord
            .getMultiValues()
            .put(
                SOUND_IDS,
                isr.getImageItems().stream()
                    .map(Image::getIdentifier)
                    .collect(Collectors.toList()));
      }
      if (!videos.isEmpty()) {
        indexRecord
            .getMultiValues()
            .put(
                VIDEO_IDS,
                isr.getImageItems().stream()
                    .map(Image::getIdentifier)
                    .collect(Collectors.toList()));
      }

      if (!multimedia.isEmpty()) {
        List<String> distinctList = new ArrayList<>(multimedia);
        indexRecord.getMultiValues().put(MULTIMEDIA, distinctList);
      }

      if (!licenses.isEmpty()) {
        indexRecord.getMultiValues().put(MULTIMEDIA_LICENSE, new ArrayList<>(licenses));
      }
    }

    if (tpr != null && tpr.getSpeciesListID() != null && !tpr.getSpeciesListID().isEmpty()) {
      addSpeciesListInfo(lr, tpr, indexRecord);
    }

    List<String> taxonomicIssues = tr.getIssues().getIssueList();
    List<String> geospatialIssues = lr.getIssues().getIssueList();
    if (taxonomicIssues != null && !taxonomicIssues.isEmpty()) {
      indexRecord.getMultiValues().put(TAXONOMIC_ISSUES, taxonomicIssues);
    }
    if (geospatialIssues != null && !geospatialIssues.isEmpty()) {
      indexRecord.getMultiValues().put(GEOSPATIAL_ISSUES, geospatialIssues);
    }

    // add all to assertions
    assertions.addAll(taxonomicIssues);
    assertions.addAll(geospatialIssues);
    assertions.addAll(br.getIssues().getIssueList());
    assertions.addAll(mdr.getIssues().getIssueList());

    if (sr != null) {
      assertions.addAll(sr.getIssues().getIssueList());
    }

    indexRecord.getMultiValues().put(ASSERTIONS, assertions);

    // Verbatim (Raw) data
    Map<String, String> raw = er.getCoreTerms();
    for (Map.Entry<String, String> entry : raw.entrySet()) {

      String key = entry.getKey();
      if (key.startsWith("http")) {
        key = key.substring(key.lastIndexOf("/") + 1);
      }

      // if we already have an interpreted value, prefix with raw_
      if (interpretedFields.contains(key)) {
        indexRecord.getStrings().put("raw_" + key, entry.getValue());
      } else {
        if (key.endsWith(DwcTerm.dynamicProperties.simpleName())) {
          try {
            // index separate properties and the dynamicProperties
            // field as a string as it may not be parseable JSON
            indexRecord.getStrings().put(DwcTerm.dynamicProperties.simpleName(), entry.getValue());

            // attempt JSON parse - best effort service only, if this fails
            // we carry on indexing
            ObjectMapper om = new ObjectMapper();
            Map dynamicProperties = om.readValue(entry.getValue(), Map.class);
            indexRecord.setDynamicProperties(dynamicProperties);
          } catch (Exception e) {
            // NOP
          }
        } else {
          indexRecord.getStrings().put(key, entry.getValue());
        }
      }
    }

    Map<String, List<Map<String, String>>> extensions = er.getExtensions();

    List<Map<String, String>> identifications =
        extensions.get(Extension.IDENTIFICATION.getRowType());
    if (identifications != null && !identifications.isEmpty()) {
      // the flat SOLR schema will only allow for 1 identification per record
      Map<String, String> identification = identifications.get(0);
      addTermSafely(indexRecord, identification, DwcTerm.identificationID);
      addTermSafely(indexRecord, identification, DwcTerm.identifiedBy);
      addTermSafely(indexRecord, identification, DwcTerm.identificationRemarks);
      addTermSafely(indexRecord, identification, DwcTerm.dateIdentified);
      addTermSafely(indexRecord, identification, DwcTerm.identificationQualifier);
    }

    List<Map<String, String>> loans = extensions.get(GGBN_TERMS_LOAN);
    if (loans != null && !loans.isEmpty()) {
      // the flat SOLR schema will only allow for 1 loan per record
      Map<String, String> loan = loans.get(0);
      addTermSafely(indexRecord, loan, LOAN_DESTINATION_TERM);
      addTermSafely(indexRecord, loan, LOAN_IDENTIFIER_TERM);
    }
    return indexRecord.build();
  }

  private static void addTermSafely(
      IndexRecord.Builder indexRecord, Map<String, String> extension, DwcTerm dwcTerm) {
    String termValue = extension.get(dwcTerm.name());
    if (Strings.isNotBlank(termValue)) {
      indexRecord.getStrings().put(dwcTerm.simpleName(), termValue);
    }
  }

  private static void addTermSafely(
      IndexRecord.Builder indexRecord, Map<String, String> extension, String dwcTerm) {
    String termValue = extension.get(dwcTerm);
    if (Strings.isNotBlank(termValue)) {
      String termToUse = dwcTerm;
      if (dwcTerm.startsWith("http")) {
        termToUse = dwcTerm.substring(dwcTerm.lastIndexOf("/") + 1);
      }
      indexRecord.getStrings().put(termToUse, termValue);
    }
  }

  public static Set<String> getAddedValues() {
    return ImmutableSet.<String>builder()
        .addAll(
            LocationRecord.getClassSchema().getFields().stream()
                .map(Field::name)
                .collect(Collectors.toList()))
        .addAll(
            ALAAttributionRecord.getClassSchema().getFields().stream()
                .map(Field::name)
                .collect(Collectors.toList()))
        .addAll(
            ALATaxonRecord.getClassSchema().getFields().stream()
                .map(Field::name)
                .collect(Collectors.toList()))
        .addAll(
            MetadataRecord.getClassSchema().getFields().stream()
                .map(Field::name)
                .collect(Collectors.toList()))
        .addAll(
            BasicRecord.getClassSchema().getFields().stream()
                .map(Field::name)
                .collect(Collectors.toList()))
        .addAll(
            TemporalRecord.getClassSchema().getFields().stream()
                .map(Field::name)
                .collect(Collectors.toList()))
        .build();
  }

  private static void addSpeciesListInfo(
      LocationRecord lr, TaxonProfile tpr, IndexRecord.Builder indexRecord) {
    indexRecord.getMultiValues().put(SPECIES_LIST_UID, tpr.getSpeciesListID());

    // CONSERVATION STATUS
    String stateProvince = lr.getStateProvince();
    String country = lr.getCountry();

    // index conservation status
    List<ConservationStatus> conservationStatuses = tpr.getConservationStatuses();
    for (ConservationStatus conservationStatus : conservationStatuses) {
      if (conservationStatus.getRegion() != null) {
        if (conservationStatus.getRegion().equalsIgnoreCase(stateProvince)) {

          if (Strings.isNotBlank(conservationStatus.getSourceStatus())) {
            indexRecord
                .getStrings()
                .put(RAW_STATE_CONSERVATION, conservationStatus.getSourceStatus());
          }
          if (Strings.isNotBlank(conservationStatus.getStatus())) {
            indexRecord.getStrings().put(STATE_CONSERVATION, conservationStatus.getStatus());
          }
        }
        if (conservationStatus.getRegion().equalsIgnoreCase(country)) {
          if (Strings.isNotBlank(conservationStatus.getStatus())) {
            indexRecord.getStrings().put(COUNTRY_CONSERVATION, conservationStatus.getStatus());
          }
        }
      }
    }

    // index invasive status
    List<InvasiveStatus> invasiveStatuses = tpr.getInvasiveStatuses();
    for (InvasiveStatus invasiveStatus : invasiveStatuses) {
      if (invasiveStatus.getRegion() != null) {
        if (invasiveStatus.getRegion().equalsIgnoreCase(stateProvince)) {
          indexRecord.getStrings().put(STATE_INVASIVE, INVASIVE);
        }
        if (invasiveStatus.getRegion().equalsIgnoreCase(country)) {
          indexRecord.getStrings().put(COUNTRY_INVASIVE, INVASIVE);
        }
      }
    }
  }

  private static void addGBIFTaxonomy(
      TaxonRecord txr, IndexRecord.Builder indexRecord, List<String> assertions) {
    // add the classification
    List<RankedName> taxonomy = txr.getClassification();
    for (RankedName entry : taxonomy) {
      indexRecord
          .getInts()
          .put("gbif_s_" + entry.getRank().toString().toLowerCase() + "_id", entry.getKey());
      indexRecord
          .getStrings()
          .put("gbif_s_" + entry.getRank().toString().toLowerCase(), entry.getName());
    }

    indexRecord.getStrings().put("gbif_s_rank", txr.getAcceptedUsage().getRank().toString());
    indexRecord.getStrings().put("gbif_s_scientificName", txr.getAcceptedUsage().getName());

    IssueRecord taxonomicIssues = txr.getIssues();
    assertions.addAll(taxonomicIssues.getIssueList());
  }

  public ParDo.SingleOutput<KV<String, CoGbkResult>, IndexRecord> converter() {

    DoFn<KV<String, CoGbkResult>, IndexRecord> fn =
        new DoFn<KV<String, CoGbkResult>, IndexRecord>() {

          private final Counter counter =
              Metrics.counter(IndexRecordTransform.class, AVRO_TO_JSON_COUNT);

          @ProcessElement
          public void processElement(ProcessContext c) {
            CoGbkResult v = c.element().getValue();
            String k = c.element().getKey();

            // Core
            MetadataRecord mdr = c.sideInput(metadataView);
            ExtendedRecord er = v.getOnly(erTag, ExtendedRecord.newBuilder().setId(k).build());
            BasicRecord br = v.getOnly(brTag, BasicRecord.newBuilder().setId(k).build());
            TemporalRecord tr = v.getOnly(trTag, TemporalRecord.newBuilder().setId(k).build());
            LocationRecord lr = v.getOnly(lrTag, LocationRecord.newBuilder().setId(k).build());
            TaxonRecord txr = null;
            if (txrTag != null) {
              txr = v.getOnly(txrTag, TaxonRecord.newBuilder().setId(k).build());
            }

            // ALA specific
            ALAUUIDRecord ur = v.getOnly(urTag, null);
            if (ur != null) {

              ALATaxonRecord atxr =
                  v.getOnly(atxrTag, ALATaxonRecord.newBuilder().setId(k).build());
              ALAAttributionRecord aar =
                  v.getOnly(aarTag, ALAAttributionRecord.newBuilder().setId(k).build());

              ImageRecord isr = null;
              if (isTag != null) {
                isr = v.getOnly(isTag, ImageRecord.newBuilder().setId(k).build());
              }

              TaxonProfile tpr = null;
              if (tpTag != null) {
                tpr = v.getOnly(tpTag, TaxonProfile.newBuilder().setId(k).build());
              }

              ALASensitivityRecord sr = null;
              if (srTag != null) {
                sr = v.getOnly(srTag, null);
              }

              if (aar != null && aar.getDataResourceUid() != null) {
                IndexRecord doc =
                    createIndexRecord(
                        mdr,
                        br,
                        tr,
                        lr,
                        txr,
                        atxr,
                        er,
                        aar,
                        ur,
                        isr,
                        tpr,
                        sr,
                        lastLoadDate,
                        lastLoadProcessed);

                c.output(doc);
                counter.inc();
              }
            } else {
              if (er != null) {
                log.error("UUID missing for record ID " + er.getId());
              } else {
                log.error("UUID missing and ER empty");
              }
            }
          }
        };

    return ParDo.of(fn).withSideInputs(metadataView);
  }

  static void addIfNotEmpty(IndexRecord.Builder doc, String fieldName, String value) {
    if (StringUtils.isNotEmpty(value)) {
      doc.getStrings().put(fieldName, value);
    }
  }

  static void addGeo(IndexRecord.Builder doc, Double lat, Double lon) {
    String latlon = "";
    // ensure that the lat longs are in the required range before
    if (lat <= 90 && lat >= -90d && lon <= 180 && lon >= -180d) {
      // https://lucene.apache.org/solr/guide/7_0/spatial-search.html#indexing-points
      latlon = lat + "," + lon; // required format for indexing geodetic points in SOLR
      doc.setLatLng(latlon);
    }

    doc.getStrings().put(LAT_LONG, latlon); // is set to IGNORE in headerAttributes
    doc.getStrings()
        .put(POINT_1, getLatLongString(lat, lon, "#")); // is set to IGNORE in headerAttributes
    doc.getStrings()
        .put(POINT_0_1, getLatLongString(lat, lon, "#.#")); // is set to IGNORE in headerAttributes
    doc.getStrings()
        .put(
            POINT_0_01, getLatLongString(lat, lon, "#.##")); // is set to IGNORE in headerAttributes
    doc.getStrings()
        .put(
            POINT_0_02,
            getLatLongStringStep(lat, lon, "#.##", 0.02)); // is set to IGNORE in headerAttributes
    doc.getStrings()
        .put(
            POINT_0_001,
            getLatLongString(lat, lon, "#.###")); // is set to IGNORE in headerAttributes
    doc.getStrings()
        .put(
            POINT_0_0001,
            getLatLongString(lat, lon, "#.####")); // is set to IGNORE in headerAttributes
  }

  static String getLatLongStringStep(Double lat, Double lon, String format, Double step) {
    DecimalFormat df = new DecimalFormat(format);
    // By some "strange" decision the default rounding model is HALF_EVEN
    df.setRoundingMode(java.math.RoundingMode.HALF_UP);
    return df.format(Math.round(lat / step) * step)
        + ","
        + df.format(Math.round(lon / step) * step);
  }

  /** Returns a lat,long string expression formatted to the supplied Double format */
  static String getLatLongString(Double lat, Double lon, String format) {
    DecimalFormat df = new DecimalFormat(format);
    // By some "strange" decision the default rounding model is HALF_EVEN
    df.setRoundingMode(java.math.RoundingMode.HALF_UP);
    return df.format(lat) + "," + df.format(lon);
  }

  static void addToIndexRecord(
      SpecificRecordBase record, IndexRecord.Builder builder, Set<String> skipKeys) {

    record.getSchema().getFields().stream()
        .filter(n -> !skipKeys.contains(n.name()))
        .forEach(
            f ->
                Optional.ofNullable(record.get(f.pos()))
                    .ifPresent(
                        r -> {
                          Schema schema = f.schema();
                          Optional<Schema.Type> type =
                              schema.getType() == UNION
                                  ? schema.getTypes().stream()
                                      .filter(t -> t.getType() != Schema.Type.NULL)
                                      .findFirst()
                                      .map(Schema::getType)
                                  : Optional.of(schema.getType());
                          type.ifPresent(
                              t -> {
                                switch (t) {
                                  case BOOLEAN:
                                    //
                                    builder.getBooleans().put(f.name(), (Boolean) r);
                                    break;
                                  case FLOAT:
                                    builder.getDoubles().put(f.name(), (Double) r);
                                    break;
                                  case DOUBLE:
                                    builder.getDoubles().put(f.name(), (Double) r);
                                    break;
                                  case INT:
                                    builder.getInts().put(f.name(), (Integer) r);
                                    break;
                                  case LONG:
                                    builder.getLongs().put(f.name(), (Long) r);
                                    break;
                                  case ARRAY:
                                    builder.getMultiValues().put(f.name(), (List) r);
                                    break;
                                  default:
                                    builder.getStrings().put(f.name(), r.toString());
                                    break;
                                }
                              });
                        }));
  }

  public static class KVIndexRecordToSolrInputDocumentFcn
      extends DoFn<KV<String, IndexRecord>, SolrInputDocument> {
    @ProcessElement
    public void processElement(
        @Element KV<String, IndexRecord> kvIndexRecord, OutputReceiver<SolrInputDocument> out) {
      SolrInputDocument solrInputDocument = convertIndexRecordToSolrDoc(kvIndexRecord.getValue());
      out.output(solrInputDocument);
    }
  }

  public static SolrInputDocument convertIndexRecordToSolrDoc(IndexRecord indexRecord) {
    SolrInputDocument doc = new SolrInputDocument();
    doc.setField(ID, indexRecord.getId());

    // strings
    for (Map.Entry<String, String> s : indexRecord.getStrings().entrySet()) {
      doc.addField(s.getKey(), s.getValue());
    }

    // doubles
    for (Map.Entry<String, Double> s : indexRecord.getDoubles().entrySet()) {
      doc.addField(s.getKey(), s.getValue());
    }

    // integers
    for (Map.Entry<String, Integer> s : indexRecord.getInts().entrySet()) {
      doc.addField(s.getKey(), s.getValue());
    }

    // longs
    for (Map.Entry<String, Long> s : indexRecord.getLongs().entrySet()) {
      doc.addField(s.getKey(), s.getValue());
    }

    // dates
    for (Map.Entry<String, Long> s : indexRecord.getDates().entrySet()) {
      doc.addField(s.getKey(), new Date(s.getValue()));
    }

    // booleans
    for (Map.Entry<String, Boolean> s : indexRecord.getBooleans().entrySet()) {
      doc.addField(s.getKey(), s.getValue());
    }

    // multi-value fields
    for (Map.Entry<String, List<String>> s : indexRecord.getMultiValues().entrySet()) {
      for (String value : s.getValue()) {
        doc.addField(s.getKey(), value);
      }
    }

    if (indexRecord.getDynamicProperties() != null
        && !indexRecord.getDynamicProperties().isEmpty()) {
      for (Map.Entry<String, String> entry : indexRecord.getDynamicProperties().entrySet()) {
        if (StringUtils.isNotEmpty(entry.getValue())) {
          String key = entry.getKey().replaceAll("[^A-Za-z0-9]", "_");
          doc.addField(DYNAMIC_PROPERTIES_PREFIX + key, entry.getValue());
        }
      }
    }

    return doc;
  }
}
