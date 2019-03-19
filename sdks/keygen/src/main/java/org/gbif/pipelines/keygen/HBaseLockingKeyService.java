package org.gbif.pipelines.keygen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.gbif.dwc.terms.GbifTerm;
import org.gbif.hbase.util.ResultReader;
import org.gbif.pipelines.keygen.api.KeyLookupResult;
import org.gbif.pipelines.keygen.config.OccHbaseConfiguration;
import org.gbif.pipelines.keygen.hbase.Columns;
import org.gbif.pipelines.keygen.hbase.HBaseStore;
import org.gbif.pipelines.keygen.identifier.OccurrenceKeyBuilder;

import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.filter.CompareFilter;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.filter.PrefixFilter;
import org.apache.hadoop.hbase.filter.SingleColumnValueFilter;
import org.apache.hadoop.hbase.util.Bytes;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import javax.annotation.Nullable;
import lombok.Cleanup;
import lombok.SneakyThrows;
import lombok.Synchronized;
import lombok.extern.slf4j.Slf4j;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * An extension of AbstractHBaseKeyPersistenceService with a generateKey implementation that uses an HBase
 * implementation of the algorithm described at
 * <a href="http://dev.gbif.org/code/snippet/CR-OCC-5">http://dev.gbif.org/code/snippet/CR-OCC-5</a>.
 */
@Slf4j
public class HBaseLockingKeyService {

  private static final long WAIT_BEFORE_RETRY_MS = 5000;
  private static final int WAIT_SKEW = 4000;
  private static final long STALE_LOCK_TIME = 5 * 60 * 1000;
  public static final int COUNTER_ROW = 1;

  // The number of IDs to reserve at a time in batch
  private static final Integer BATCHED_ID_SIZE = 100;
  // the next available key to allocate
  private int currentKey;
  // our reserved upper key limit for the current batch
  private int maxReservedKeyInclusive;

  private static final int HBASE_CLIENT_CACHING = 200;

  private final Connection connection;
  private final TableName lookupTableName;
  private final HBaseStore<Integer> occurrenceTableStore;
  private final HBaseStore<String> lookupTableStore;
  private final HBaseStore<Integer> counterTableStore;

  private final String datasetId;

  public HBaseLockingKeyService(OccHbaseConfiguration cfg, Connection connection, String datasetId) {
    this.lookupTableName = TableName.valueOf(checkNotNull(cfg.getLookupTable(), "lookupTable can't be null"));
    this.connection = checkNotNull(connection, "tablePool can't be null");
    this.lookupTableStore = new HBaseStore<>(cfg.getLookupTable(), Columns.OCCURRENCE_COLUMN_FAMILY, connection);
    this.counterTableStore = new HBaseStore<>(cfg.getCounterTable(), Columns.OCCURRENCE_COLUMN_FAMILY, connection);
    this.occurrenceTableStore = new HBaseStore<>(cfg.getOccTable(), Columns.OCCURRENCE_COLUMN_FAMILY, connection);
    this.datasetId = datasetId;
  }

  public HBaseLockingKeyService(OccHbaseConfiguration cfg, Connection connection) {
    this(cfg, connection, null);
  }

  /**
   *
   */
  public KeyLookupResult generateKey(Set<String> uniqueStrings, String scope) {
    Map<String, KeyStatus> statusMap = Maps.newTreeMap(); // required: predictable sorting for e.g. testing
    Map<String, Integer> existingKeyMap = Maps.newTreeMap(); // required: predictable sorting for e.g. testing
    byte[] lockId = Bytes.toBytes(UUID.randomUUID().toString());

    // lookupTable schema: lookupKey | status | lock | key

    // all of our locks will have the same timestamp
    long now = System.currentTimeMillis();

    Set<String> lookupKeys = OccurrenceKeyBuilder.buildKeys(uniqueStrings, scope);
    boolean failed = false;
    Integer key = null;
    Integer foundKey = null;
    for (String lookupKey : lookupKeys) {
      Result row = lookupTableStore.getRow(lookupKey);
      log.debug("Lookup for [{}] produced [{}]", lookupKey, row);
      KeyStatus status = null;
      byte[] existingLock = null;
      if (row != null) {
        String rawStatus = ResultReader.getString(row, Columns.OCCURRENCE_COLUMN_FAMILY,
            Columns.LOOKUP_STATUS_COLUMN, null);
        if (rawStatus != null) {
          status = KeyStatus.valueOf(rawStatus);
        }
        existingLock = ResultReader.getBytes(row, Columns.OCCURRENCE_COLUMN_FAMILY,
            Columns.LOOKUP_LOCK_COLUMN, null);
        key = ResultReader.getInteger(row, Columns.OCCURRENCE_COLUMN_FAMILY,
            Columns.LOOKUP_KEY_COLUMN, null);
        log.debug("Got existing status [{}] existingLock [{}] key [{}]", status, existingLock, key);
      }

      if (status == KeyStatus.ALLOCATED) {
        log.debug("Status ALLOCATED, using found key [{}]", key);
        // even if existingLock is != null, ALLOCATED means the key exists and is final
        statusMap.put(lookupKey, KeyStatus.ALLOCATED);
        existingKeyMap.put(lookupKey, key);
        if (foundKey == null) {
          foundKey = key;
        } else {
          // we've found conflicting keys for our lookupKeys - this is fatal
          if (foundKey.intValue() != key.intValue()) {
            failWithConflictingLookup(existingKeyMap);
          }
        }
        log.debug("Status ALLOCATED, using found key [{}]", foundKey);
      } else if (existingLock == null) {
        // lock is ours for the taking - checkAndPut lockId, expecting null for lockId
        boolean gotLock = lookupTableStore.checkAndPut(lookupKey, Columns.LOOKUP_LOCK_COLUMN, lockId,
            Columns.LOOKUP_LOCK_COLUMN, null, now);
        if (gotLock) {
          statusMap.put(lookupKey, KeyStatus.ALLOCATING);
          log.debug("Grabbed free lock, now ALLOCATING [{}]", lookupKey);
        } else {
          failed = true;
          log.debug("Failed to grab free lock for [{}], breaking", lookupKey);
          break;
        }
      } else {
        // somebody has written their lockId and so has the lock, but they haven't finished yet (status != ALLOCATED)
        Long existingLockTs = ResultReader.getTimestamp(row, Columns.OCCURRENCE_COLUMN_FAMILY,
            Columns.LOOKUP_LOCK_COLUMN);
        if (now - existingLockTs > STALE_LOCK_TIME) {
          log.debug("Found stale lock for [{}]", lookupKey);
          // Someone died before releasing lock.
          // Note that key could be not null here - this means that thread had the lock, wrote the key, but then
          // died before releasing lock.
          // check and put our lockId, expecting lock to match the existing lock
          boolean gotLock = lookupTableStore.checkAndPut(lookupKey, Columns.LOOKUP_LOCK_COLUMN,
              lockId, Columns.LOOKUP_LOCK_COLUMN, existingLock, now);
          if (gotLock) {
            statusMap.put(lookupKey, KeyStatus.ALLOCATING);
            log.debug("Reset stale lock, now ALLOCATING [{}]", lookupKey);
          } else {
            // someone beat us to this lock, in one of two ways
            // 1) they grabbed lock, wrote new id, and released lock, so now status is ALLOCATED and id is final
            // 2) they grabbed lock so status is a newer lock uuid with recent timestamp
            // in either case we're toast - abort and try again
            failed = true;
            log.debug("Failed to reset stale lock for [{}], breaking", lookupKey);
            break;
          }
        } else {
          // someone has a current lock, we need to give up and try again
          failed = true;
          log.debug("Hit valid, current lock for [{}], breaking", lookupKey);
          break;
        }
      }
    }

    if (failed) {
      log.debug("Failed to get lock. Releasing held locks and trying again.");
      releaseLocks(statusMap);
      try {
        Random random = new Random();
        TimeUnit.MILLISECONDS.sleep(WAIT_BEFORE_RETRY_MS + random.nextInt(WAIT_SKEW) - random.nextInt(WAIT_SKEW));
      } catch (InterruptedException e) {
        // do nothing
      }
      // recurse
      return generateKey(uniqueStrings, scope);
    }

    // now we have map of every lookupKey to either ALLOCATED or ALLOCATING, and locks on all ALLOCATING
    KeyLookupResult lookupResult;
    if (foundKey == null) {
      key = getNextKey();
      lookupResult = new KeyLookupResult(key, true);
      log.debug("Now assigning new key [{}]", key);
    } else {
      key = foundKey;
      lookupResult = new KeyLookupResult(key, false);
      log.debug("Using found key [{}]", key);
    }

    // write the key and update status to ALLOCATED
    for (Map.Entry<String, KeyStatus> entry : statusMap.entrySet()) {
      if (entry.getValue() == KeyStatus.ALLOCATING) {
        // TODO: combine into one put
        lookupTableStore.putInt(entry.getKey(), Columns.LOOKUP_KEY_COLUMN, key);
        lookupTableStore.putString(entry.getKey(), Columns.LOOKUP_STATUS_COLUMN, KeyStatus.ALLOCATED.toString());
      }
    }

    releaseLocks(statusMap);

    log.debug("<< generateKey (generated? [{}] key [{}])", !key.equals(foundKey), key);

    return lookupResult;

  }

  /**
   *
   */
  public KeyLookupResult generateKey(Set<String> uniqueStrings) {
    return generateKey(uniqueStrings, datasetId);
  }

  /**
   * Provides the next available key. Because throughput of an incrementColumnValue is limited by HBase to a few
   * thousand calls per second, this implementation reserves a batch of IDs at a time, and then allocates them to
   * the calling threads, until they are exhausted, when it will go and reserve another batch. Failure scenarios
   * will therefore mean IDs go unused. This is expected to be a rare scenario and therefore acceptable.
   *
   * @return the next key
   */
  @Synchronized
  private int getNextKey() {
    // if we have exhausted our reserved keys, get a new batch of them
    if (currentKey == maxReservedKeyInclusive) {
      // get batch
      Long longKey = counterTableStore.incrementColumnValue(COUNTER_ROW, Columns.COUNTER_COLUMN, BATCHED_ID_SIZE.longValue());
      if (longKey > Integer.MAX_VALUE) {
        throw new IllegalStateException("HBase issuing keys larger than Integer can support");
      }
      maxReservedKeyInclusive = longKey.intValue();
      // safer to calculate our guaranteed safe range than rely on what nextKey was set to
      currentKey = maxReservedKeyInclusive - BATCHED_ID_SIZE;
    }
    currentKey++;
    return currentKey;
  }

  /**
   *
   */
  public KeyLookupResult findKey(Set<String> uniqueStrings, String scope) {
    checkNotNull(uniqueStrings, "uniqueStrings can't be null");
    checkNotNull(scope, "scope can't be null");
    if (uniqueStrings.isEmpty()) {
      return null;
    }

    Set<String> lookupKeys = OccurrenceKeyBuilder.buildKeys(uniqueStrings, scope);
    Map<String, Integer> foundOccurrenceKeys = Maps.newTreeMap(); // required: predictable sorting for e.g. testing

    // get the occurrenceKey for each lookupKey, and set a flag if we find any null
    boolean gotNulls = false;
    for (String uniqueString : lookupKeys) {
      Integer occurrenceKey = lookupTableStore.getInt(uniqueString, Columns.LOOKUP_KEY_COLUMN);
      if (occurrenceKey == null) {
        gotNulls = true;
      } else {
        foundOccurrenceKeys.put(uniqueString, occurrenceKey);
      }
    }

    // go through all the returned keys and make sure they're all the same - if not, fail loudly (this means
    // an inconsistency in the db that we can't resolve here)
    Integer resultKey = null;
    for (String uniqueString : lookupKeys) {
      Integer occurrenceKey = foundOccurrenceKeys.get(uniqueString);
      if (occurrenceKey != null) {
        if (resultKey == null) {
          resultKey = occurrenceKey;
        } else if (resultKey.intValue() != occurrenceKey.intValue()) {
          failWithConflictingLookup(foundOccurrenceKeys);
        }
      }
    }

    // if we got an occurrenceKey as well as nulls, then we need to fill in the lookup table with the missing entries
    if (resultKey != null && gotNulls) {
      fillMissingKeys(lookupKeys, foundOccurrenceKeys, resultKey);
    }

    KeyLookupResult result = null;
    if (resultKey != null) {
      result = new KeyLookupResult(resultKey, false);
    }

    return result;
  }

  /**
   *
   */
  public KeyLookupResult findKey(Set<String> uniqueStrings) {
    return findKey(uniqueStrings, datasetId);
  }

  @SneakyThrows
  public Set<Integer> findKeysByScope(String scope) {
    Set<Integer> keys = Sets.newHashSet();
    // note HTableStore isn't capable of ad hoc scans
    @Cleanup Table table = connection.getTable(lookupTableName);
    Scan scan = new Scan();
    scan.setCacheBlocks(false);
    scan.setCaching(HBASE_CLIENT_CACHING);
    scan.setFilter(new PrefixFilter(Bytes.toBytes(scope)));
    ResultScanner results = table.getScanner(scan);
    for (Result result : results) {
      byte[] rawKey = result.getValue(Columns.CF, Bytes.toBytes(Columns.LOOKUP_KEY_COLUMN));
      if (rawKey != null) {
        keys.add(Bytes.toInt(rawKey));
      }
    }
    return keys;
  }

  /**
   *
   */
  public Set<Integer> findKeysByScope() {
    return findKeysByScope(datasetId);
  }


  /**
   * Scans the lookup table for instances of the occurrenceKey and deletes those rows. It attempts to scope the scan
   * for this occurrenceKey within the dataset of the original occurrence, but note that there is no guarantee that the
   * original occurrence corresponding to this occurrenceKey still exists, so in the worst case this method will do a
   * full table scan of the lookup table.
   *
   * @param occurrenceKey the key to delete
   * @param datasetKey the optional "scope" for the lookup (without it this method is very slow)
   */
  @SneakyThrows
  public void deleteKey(Integer occurrenceKey, @Nullable String datasetKey) {
    checkNotNull(occurrenceKey, "occurrenceKey can't be null");

    // get the dataset for this occurrence if not handed in as scope
    String rawDatasetKey = datasetKey;
    if (rawDatasetKey == null) {
      rawDatasetKey = occurrenceTableStore.getString(occurrenceKey, Columns.column(GbifTerm.datasetKey));
    }

    // scan the lookup table for all rows where the key matches our dataset prefix and the cell value is our
    // target occurrenceKey, then delete those rows
    Scan scan = new Scan();
    scan.addColumn(Columns.CF, Bytes.toBytes(Columns.LOOKUP_KEY_COLUMN));
    // TODO: this is still too slow even with prefix - lease timeouts in logs
    List<Filter> filters = Lists.newArrayList();
    if (rawDatasetKey == null) {
      log.warn("About to scan lookup table with no datasetKey prefix - target key for deletion is [{}]", occurrenceKey);
    } else {
      filters.add(new PrefixFilter(Bytes.toBytes(OccurrenceKeyBuilder.buildKeyPrefix(rawDatasetKey))));
    }
    Filter valueFilter = new SingleColumnValueFilter(Columns.CF, Bytes.toBytes(Columns.LOOKUP_KEY_COLUMN),
        CompareFilter.CompareOp.EQUAL, Bytes.toBytes(occurrenceKey));
    filters.add(valueFilter);
    Filter filterList = new FilterList(FilterList.Operator.MUST_PASS_ALL, filters);
    scan.setFilter(filterList);
    @Cleanup Table lookupTable = connection.getTable(lookupTableName);
    @Cleanup ResultScanner resultScanner = lookupTable.getScanner(scan);
    List<Delete> keysToDelete = new ArrayList<>();
    for (Result result : resultScanner) {
      keysToDelete.add(new Delete(result.getRow()));
    }
    if (!keysToDelete.isEmpty()) {
      lookupTable.delete(keysToDelete);
    }

  }

  /**
   *
   */
  public void deleteKey(Integer occurrenceKey) {
    deleteKey(occurrenceKey, datasetId);
  }

  @SneakyThrows
  public void deleteKeyByUniques(Set<String> uniqueStrings, String scope) {
    checkNotNull(uniqueStrings, "uniqueStrings can't be null");
    checkNotNull(scope, "scope can't be null");

    // craft a delete for every uniqueString
    Set<String> lookupKeys = OccurrenceKeyBuilder.buildKeys(uniqueStrings, scope);

    List<Delete> keysToDelete = lookupKeys.stream()
        .map(lookupKey -> new Delete(Bytes.toBytes(lookupKey)))
        .collect(Collectors.toCollection(() -> Lists.newArrayListWithCapacity(lookupKeys.size())));

    @Cleanup Table lookupTable = connection.getTable(lookupTableName);
    if (!keysToDelete.isEmpty()) {
      lookupTable.delete(keysToDelete);
    }
  }

  /**
   *
   */
  public void deleteKeyByUniques(Set<String> uniqueStrings) {
    deleteKeyByUniques(uniqueStrings, datasetId);
  }

  private static void failWithConflictingLookup(Map<String, Integer> conflictingKeys) {
    StringBuilder sb = new StringBuilder("Found inconsistent occurrence keys in looking up unique identifiers:");
    for (Map.Entry<String, Integer> entry : conflictingKeys.entrySet()) {
      sb.append('[').append(entry.getKey()).append("]=[").append(entry.getValue()).append(']');
    }
    throw new IllegalStateException(sb.toString());
  }


  private void fillMissingKeys(Set<String> lookupKeys, Map<String, Integer> foundOccurrenceKeys,
      Integer occurrenceKey) {
    lookupKeys.stream()
        .filter(lookupKey -> !foundOccurrenceKeys.containsKey(lookupKey))
        .forEach(lookupKey -> lookupTableStore.putInt(lookupKey, Columns.LOOKUP_KEY_COLUMN, occurrenceKey));
  }

  private void releaseLocks(Map<String, KeyStatus> statusMap) {
    statusMap.entrySet()
        .stream()
        .filter(entry -> entry.getValue() == KeyStatus.ALLOCATING)
        .forEach(entry -> lookupTableStore.delete(entry.getKey(), Columns.LOOKUP_LOCK_COLUMN));
  }

  private enum KeyStatus {
    ALLOCATING,
    ALLOCATED
  }
}


