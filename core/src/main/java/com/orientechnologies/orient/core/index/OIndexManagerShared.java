/*
 *
 *  *  Copyright 2010-2016 OrientDB LTD (http://orientdb.com)
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *  * For more information: http://orientdb.com
 *
 */
package com.orientechnologies.orient.core.index;

import com.orientechnologies.common.listener.OProgressListener;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.common.util.OMultiKey;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.db.ODatabase;
import com.orientechnologies.orient.core.db.ODatabaseDocumentInternal;
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentEmbedded;
import com.orientechnologies.orient.core.db.record.OIdentifiable;
import com.orientechnologies.orient.core.db.record.ORecordElement;
import com.orientechnologies.orient.core.db.record.OTrackedSet;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.metadata.schema.OClass;
import com.orientechnologies.orient.core.metadata.schema.OClassImpl;
import com.orientechnologies.orient.core.metadata.schema.OSchemaShared;
import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.storage.OStorage;
import com.orientechnologies.orient.core.storage.impl.local.OAbstractPaginatedStorage;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.nio.channels.UnsupportedAddressTypeException;
import java.util.*;

/**
 * Manages indexes at database level. A single instance is shared among multiple databases. Contentions are managed by r/w locks.
 *
 * @author Luca Garulli (l.garulli--(at)--orientdb.com)
 * @author Artem Orobets added composite index managemement
 */
@SuppressFBWarnings("EQ_DOESNT_OVERRIDE_EQUALS")
public class OIndexManagerShared extends OIndexManagerAbstract {
  private static final long serialVersionUID = 1L;

  protected volatile transient Thread  recreateIndexesThread = null;
  private volatile             boolean rebuildCompleted      = false;

  public OIndexManagerShared() {
    super();
  }

  public OIndex<?> getIndexInternal(final String name) {
    acquireSharedLock();
    try {
      final Locale locale = getServerLocale();
      return indexes.get(name);
    } finally {
      releaseSharedLock();
    }
  }

  /**
   * Create a new index with default algorithm.
   *
   * @param iName             - name of index
   * @param iType             - index type. Specified by plugged index factories.
   * @param indexDefinition   metadata that describes index structure
   * @param clusterIdsToIndex ids of clusters that index should track for changes.
   * @param progressListener  listener to track task progress.
   * @param metadata          document with additional properties that can be used by index engine.
   *
   * @return a newly created index instance
   */
  public OIndex<?> createIndex(final String iName, final String iType, final OIndexDefinition indexDefinition,
      final int[] clusterIdsToIndex, OProgressListener progressListener, ODocument metadata) {
    return createIndex(iName, iType, indexDefinition, clusterIdsToIndex, progressListener, metadata, null);
  }

  /**
   * Create a new index.
   * <p>
   * May require quite a long time if big amount of data should be indexed.
   *
   * @param iName             name of index
   * @param type              index type. Specified by plugged index factories.
   * @param indexDefinition   metadata that describes index structure
   * @param clusterIdsToIndex ids of clusters that index should track for changes.
   * @param progressListener  listener to track task progress.
   * @param metadata          document with additional properties that can be used by index engine.
   * @param algorithm         tip to an index factory what algorithm to use
   *
   * @return a newly created index instance
   */
  public OIndex<?> createIndex(final String iName, String type, final OIndexDefinition indexDefinition,
      final int[] clusterIdsToIndex, OProgressListener progressListener, ODocument metadata, String algorithm) {
    if (getDatabase().getTransaction().isActive())
      throw new IllegalStateException("Cannot create a new index inside a transaction");

    final Character c = OSchemaShared.checkFieldNameIfValid(iName);
    if (c != null)
      throw new IllegalArgumentException("Invalid index name '" + iName + "'. Character '" + c + "' is invalid");

    if (indexDefinition == null) {
      throw new IllegalArgumentException("Index definition cannot be null");
    }

    ODatabaseDocumentInternal database = getDatabase();
    OStorage storage = database.getStorage();

    final Locale locale = getServerLocale();
    type = type.toUpperCase(locale);
    if (algorithm == null) {
      algorithm = OIndexes.chooseDefaultIndexAlgorithm(type);
    }

    final String valueContainerAlgorithm = chooseContainerAlgorithm(type);

    final OIndexInternal<?> index;
    acquireExclusiveLock();
    try {

      if (indexes.containsKey(iName))
        throw new OIndexException("Index with name " + iName+ " already exists.");

      // manual indexes are always durable
      if (clusterIdsToIndex == null || clusterIdsToIndex.length == 0) {
        if (metadata == null)
          metadata = new ODocument().setTrackingChanges(false);

        final Object durable = metadata.field("durableInNonTxMode");
        if (!(durable instanceof Boolean))
          metadata.field("durableInNonTxMode", true);
        if (metadata.field("trackMode") == null)
          metadata.field("trackMode", "FULL");
      }

      index = OIndexes.createIndex(getStorage(), iName, type, algorithm, valueContainerAlgorithm, metadata, -1);
      if (progressListener == null)
        // ASSIGN DEFAULT PROGRESS LISTENER
        progressListener = new OIndexRebuildOutputListener(index);

      final Set<String> clustersToIndex = findClustersByIds(clusterIdsToIndex, database);
      Object ignoreNullValues = metadata == null ? null : metadata.field("ignoreNullValues");
      if (Boolean.TRUE.equals(ignoreNullValues)) {
        indexDefinition.setNullValuesIgnored(true);
      } else if (Boolean.FALSE.equals(ignoreNullValues)) {
        indexDefinition.setNullValuesIgnored(false);
      } else {
        indexDefinition.setNullValuesIgnored(
            database.getConfiguration().getValueAsBoolean(OGlobalConfiguration.INDEX_IGNORE_NULL_VALUES_DEFAULT));
      }

      // decide which cluster to use ("index" - for automatic and "manindex" for manual)
      final String clusterName = indexDefinition.getClassName() != null ? defaultClusterName : manualClusterName;

      index.create(iName, indexDefinition, clusterName, clustersToIndex, true, progressListener);

      addIndexInternal(index);

      if (metadata != null) {
        final ODocument config = index.getConfiguration();
        config.field("metadata", metadata, OType.EMBEDDED);
      }

      setDirty();
      save();
    } finally {
      releaseExclusiveLock();
    }

    notifyInvolvedClasses(clusterIdsToIndex);

    if (database.getConfiguration().getValueAsBoolean(OGlobalConfiguration.INDEX_FLUSH_AFTER_CREATE))
      storage.synch();

    return preProcessBeforeReturn(database, index);
  }

  protected void notifyInvolvedClasses(int[] clusterIdsToIndex) {
    if (clusterIdsToIndex == null || clusterIdsToIndex.length == 0)
      return;

    final ODatabaseDocumentInternal database = getDatabase();

    // UPDATE INVOLVED CLASSES
    final Set<String> classes = new HashSet<String>();
    for (int clusterId : clusterIdsToIndex) {
      final OClass cls = database.getMetadata().getSchema().getClassByClusterId(clusterId);
      if (cls != null && cls instanceof OClassImpl && !classes.contains(cls.getName())) {
        ((OClassImpl) cls).onPostIndexManagement();
        classes.add(cls.getName());
      }
    }
  }

  private Set<String> findClustersByIds(int[] clusterIdsToIndex, ODatabase database) {
    Set<String> clustersToIndex = new HashSet<String>();
    if (clusterIdsToIndex != null) {
      for (int clusterId : clusterIdsToIndex) {
        final String clusterNameToIndex = database.getClusterNameById(clusterId);
        if (clusterNameToIndex == null)
          throw new OIndexException("Cluster with id " + clusterId + " does not exist.");

        clustersToIndex.add(clusterNameToIndex);
      }
    }
    return clustersToIndex;
  }

  private String chooseContainerAlgorithm(String type) {
    final String valueContainerAlgorithm;
    if (OClass.INDEX_TYPE.NOTUNIQUE.toString().equals(type) || OClass.INDEX_TYPE.NOTUNIQUE_HASH_INDEX.toString().equals(type)
        || OClass.INDEX_TYPE.FULLTEXT_HASH_INDEX.toString().equals(type) || OClass.INDEX_TYPE.FULLTEXT.toString().equals(type)) {
      valueContainerAlgorithm = ODefaultIndexFactory.SBTREEBONSAI_VALUE_CONTAINER;
    } else {
      valueContainerAlgorithm = ODefaultIndexFactory.NONE_VALUE_CONTAINER;
    }
    return valueContainerAlgorithm;
  }

  public OIndexManager dropIndex(final String iIndexName) {
    if (getDatabase().getTransaction().isActive())
      throw new IllegalStateException("Cannot drop an index inside a transaction");

    int[] clusterIdsToIndex = null;

    acquireExclusiveLock();
    try {
      final Locale locale = getServerLocale();
      final OIndex<?> idx = indexes.remove(iIndexName);
      if (idx != null) {
        final Set<String> clusters = idx.getClusters();
        if (clusters != null && !clusters.isEmpty()) {
          final ODatabaseDocumentInternal db = getDatabase();
          clusterIdsToIndex = new int[clusters.size()];
          int i = 0;
          for (String cl : clusters) {
            clusterIdsToIndex[i++] = db.getClusterIdByName(cl);
          }
        }

        removeClassPropertyIndex(idx);

        idx.delete();
        setDirty();
        save();

        notifyInvolvedClasses(clusterIdsToIndex);
      }

    } finally {
      releaseExclusiveLock();
    }

    return this;
  }

  /**
   * Binds POJO to ODocument.
   */
  @Override
  public ODocument toStream() {
    acquireExclusiveLock();
    try {
      document.setInternalStatus(ORecordElement.STATUS.UNMARSHALLING);

      try {
        final OTrackedSet<ODocument> idxs = new OTrackedSet<ODocument>(document);

        for (final OIndex<?> i : indexes.values()) {
          idxs.add(((OIndexInternal<?>) i).updateConfiguration());
        }
        document.field(CONFIG_INDEXES, idxs, OType.EMBEDDEDSET);

      } finally {
        document.setInternalStatus(ORecordElement.STATUS.LOADED);
      }
      document.setDirty();

      return document;
    } finally {
      releaseExclusiveLock();
    }
  }

  @Override
  public void recreateIndexes(ODatabaseDocumentInternal database) {
    acquireExclusiveLock();
    try {
      if (recreateIndexesThread != null && recreateIndexesThread.isAlive())
        // BUILDING ALREADY IN PROGRESS
        return;

      document = database.load(new ORecordId(database.getStorage().getConfiguration().indexMgrRecordId));

      Runnable recreateIndexesTask = new RecreateIndexesTask(database.getStorage());
      recreateIndexesThread = new Thread(recreateIndexesTask, "OrientDB rebuild indexes");
      recreateIndexesThread.start();
    } finally {
      releaseExclusiveLock();
    }

    if (database.getConfiguration().getValueAsBoolean(OGlobalConfiguration.INDEX_SYNCHRONOUS_AUTO_REBUILD)) {
      waitTillIndexRestore();

      database.getMetadata().reload();
    }

  }

  @Override
  public void recreateIndexes() {
    throw new UnsupportedAddressTypeException();
  }

  @Override
  public void waitTillIndexRestore() {
    if (recreateIndexesThread != null && recreateIndexesThread.isAlive()) {
      if (Thread.currentThread().equals(recreateIndexesThread))
        return;

      OLogManager.instance().info(this, "Wait till indexes restore after crash was finished.");
      while (recreateIndexesThread.isAlive())
        try {
          recreateIndexesThread.join();
          OLogManager.instance().info(this, "Indexes restore after crash was finished.");
        } catch (InterruptedException e) {
          OLogManager.instance().info(this, "Index rebuild task was interrupted.");
        }
    }
  }

  public boolean autoRecreateIndexesAfterCrash(ODatabaseDocumentInternal database) {
    if (rebuildCompleted)
      return false;

    final OStorage storage = database.getStorage();
    if (storage instanceof OAbstractPaginatedStorage) {
      OAbstractPaginatedStorage paginatedStorage = (OAbstractPaginatedStorage) storage;
      return paginatedStorage.wereDataRestoredAfterOpen() && paginatedStorage.wereNonTxOperationsPerformedInPreviousOpen();
    }

    return false;
  }

  public boolean autoRecreateIndexesAfterCrash() {
    throw new UnsupportedOperationException();
  }

  @Override
  protected void fromStream() {
    acquireExclusiveLock();
    try {
      final Map<String, OIndex<?>> oldIndexes = new HashMap<String, OIndex<?>>(indexes);

      clearMetadata();
      final Collection<ODocument> idxs = document.field(CONFIG_INDEXES);
      final Locale locale = getServerLocale();

      if (idxs != null) {
        OIndexInternal<?> index;
        boolean configUpdated = false;
        Iterator<ODocument> indexConfigurationIterator = idxs.iterator();
        while (indexConfigurationIterator.hasNext()) {
          final ODocument d = indexConfigurationIterator.next();
          try {
            final int indexVersion =
                d.field(OIndexInternal.INDEX_VERSION) == null ? 1 : (Integer) d.field(OIndexInternal.INDEX_VERSION);

            final OIndexMetadata newIndexMetadata = OIndexAbstract
                .loadMetadataInternal(d, (String) d.field(OIndexInternal.CONFIG_TYPE), (String) d.field(OIndexInternal.ALGORITHM),
                    d.<String>field(OIndexInternal.VALUE_CONTAINER_ALGORITHM));

            index = OIndexes
                .createIndex(getStorage(), newIndexMetadata.getName(), newIndexMetadata.getType(), newIndexMetadata.getAlgorithm(),
                    newIndexMetadata.getValueContainerAlgorithm(), (ODocument) d.field(OIndexInternal.METADATA), indexVersion);

            final String normalizedName = newIndexMetadata.getName();

            OIndex<?> oldIndex = oldIndexes.remove(normalizedName);
            if (oldIndex != null) {
              OIndexMetadata oldIndexMetadata = oldIndex.getInternal().loadMetadata(oldIndex.getConfiguration());

              if (!(oldIndexMetadata.equals(newIndexMetadata) || newIndexMetadata.getIndexDefinition() == null)) {
                oldIndex.delete();
              }

              if (index.loadFromConfiguration(d)) {
                addIndexInternal(index);
              } else {
                indexConfigurationIterator.remove();
                configUpdated = true;
              }
            } else {
              if (index.loadFromConfiguration(d)) {
                addIndexInternal(index);
              } else {
                indexConfigurationIterator.remove();
                configUpdated = true;
              }
            }
          } catch (RuntimeException e) {
            indexConfigurationIterator.remove();
            configUpdated = true;
            OLogManager.instance().error(this, "Error on loading index by configuration: %s", e, d);
          }
        }

        for (OIndex<?> oldIndex : oldIndexes.values())
          try {
            OLogManager.instance().warn(this, "Index '%s' was not found after reload and will be removed", oldIndex.getName());

            oldIndex.delete();
          } catch (Exception e) {
            OLogManager.instance().error(this, "Error on deletion of index '%s'", e, oldIndex.getName());
          }

        if (configUpdated) {
          document.field(CONFIG_INDEXES, idxs);
          save();
        }

      }
    } finally {
      releaseExclusiveLock();
    }
  }

  public void removeClassPropertyIndex(final OIndex<?> idx) {
    acquireExclusiveLock();
    try {
      final OIndexDefinition indexDefinition = idx.getDefinition();
      if (indexDefinition == null || indexDefinition.getClassName() == null)
        return;

      final Locale locale = getServerLocale();
      Map<OMultiKey, Set<OIndex<?>>> map = classPropertyIndex.get(indexDefinition.getClassName().toLowerCase(locale));

      if (map == null) {
        return;
      }

      map = new HashMap<OMultiKey, Set<OIndex<?>>>(map);

      final int paramCount = indexDefinition.getParamCount();

      for (int i = 1; i <= paramCount; i++) {
        final List<String> fields = normalizeFieldNames(indexDefinition.getFields().subList(0, i));
        final OMultiKey multiKey = new OMultiKey(fields);

        Set<OIndex<?>> indexSet = map.get(multiKey);
        if (indexSet == null)
          continue;

        indexSet = new HashSet<OIndex<?>>(indexSet);
        indexSet.remove(idx);

        if (indexSet.isEmpty()) {
          map.remove(multiKey);
        } else {
          map.put(multiKey, indexSet);
        }
      }

      if (map.isEmpty())
        classPropertyIndex.remove(indexDefinition.getClassName().toLowerCase(locale));
      else
        classPropertyIndex.put(indexDefinition.getClassName().toLowerCase(locale), copyPropertyMap(map));

    } finally {
      releaseExclusiveLock();
    }
  }

  private class RecreateIndexesTask implements Runnable {
    private final OStorage storage;
    private       int      ok;
    private       int      errors;

    public RecreateIndexesTask(OStorage storage) {
      this.storage = storage;
    }

    @Override
    public void run() {
      try {
        final ODatabaseDocumentEmbedded newDb = new ODatabaseDocumentEmbedded(storage);
        newDb.activateOnCurrentThread();
        newDb.resetInitialization();
        newDb.internalOpen("admin", "nopass", null, false);

        final Collection<ODocument> indexesToRebuild;
        acquireExclusiveLock();
        try {
          final Collection<ODocument> knownIndexes = document.field(CONFIG_INDEXES);
          if (knownIndexes == null) {
            OLogManager.instance().warn(this, "List of indexes is empty");
            indexesToRebuild = Collections.emptyList();
          } else {
            indexesToRebuild = new ArrayList<>();
            for (ODocument index : knownIndexes)
              indexesToRebuild.add(index.copy()); // make copies to safely iterate them later
          }
        } finally {
          releaseExclusiveLock();
        }

        if (storage instanceof OAbstractPaginatedStorage) {
          final OAbstractPaginatedStorage abstractPaginatedStorage = (OAbstractPaginatedStorage) storage;
          abstractPaginatedStorage.getAtomicOperationsManager().switchOnUnsafeMode();
        }

        try {
          recreateIndexes(storage, indexesToRebuild, newDb);
        } finally {
          if (storage instanceof OAbstractPaginatedStorage) {
            final OAbstractPaginatedStorage abstractPaginatedStorage = (OAbstractPaginatedStorage) storage;
            abstractPaginatedStorage.getAtomicOperationsManager().switchOffUnsafeMode();
            abstractPaginatedStorage.synch();
          }
          newDb.close();
        }

      } catch (Exception e) {
        OLogManager.instance().error(this, "Error when attempt to restore indexes after crash was performed", e);
      }
    }

    private void recreateIndexes(OStorage storage, Collection<ODocument> idxs, ODatabaseDocumentEmbedded db) {
      ok = 0;
      errors = 0;
      for (ODocument idx : idxs) {
        try {
          recreateIndex(storage, idx);

        } catch (RuntimeException e) {
          OLogManager.instance().error(this, "Error during addition of index '%s'", e, idx);
          errors++;
        }
      }

      db.getMetadata().getIndexManager().save();

      rebuildCompleted = true;

      OLogManager.instance().info(this, "%d indexes were restored successfully, %d errors", ok, errors);
    }

    private void recreateIndex(OStorage storage, ODocument idx) {
      final OIndexInternal<?> index = createIndex(idx);
      final OIndexMetadata indexMetadata = index.loadMetadata(idx);
      final OIndexDefinition indexDefinition = indexMetadata.getIndexDefinition();

      if (indexDefinition != null && indexDefinition.isAutomatic()) {
        try {
          index.loadFromConfiguration(idx);
          index.delete();
        } catch (Exception e) {
          OLogManager.instance()
              .error(this, "Error on removing index '%s' on rebuilding. Trying removing index files (Cause: %s)", index.getName(),
                  e);

          // TRY DELETING ALL THE FILES RELATIVE TO THE INDEX
          for (Iterator<OIndexFactory> it = OIndexes.getAllFactories(); it.hasNext(); ) {
            try {
              final OIndexFactory indexFactory = it.next();
              final OIndexEngine engine = indexFactory.createIndexEngine(null, index.getName(), false, storage, 0, null);

              engine.deleteWithoutLoad(index.getName());
            } catch (Exception e2) {
            }
          }
        }

        createAutomaticIndex(idx, index, indexMetadata, indexDefinition);
      } else {
        addIndexAsIs(idx, index, indexMetadata);
      }
    }

    private void createAutomaticIndex(ODocument idx, OIndexInternal<?> index, OIndexMetadata indexMetadata,
        OIndexDefinition indexDefinition) {
      final String indexName = indexMetadata.getName();
      final Set<String> clusters = indexMetadata.getClustersToIndex();
      final String type = indexMetadata.getType();

      if (indexName != null && clusters != null && !clusters.isEmpty() && type != null) {
        OLogManager.instance().info(this, "Start creation of index '%s'", indexName);
        index.create(indexName, indexDefinition, defaultClusterName, clusters, false, new OIndexRebuildOutputListener(index));

        index.setRebuildingFlag();
        addIndexInternal(index);

        OLogManager.instance().info(this, "Index '%s' was successfully created and rebuild is going to be started", indexName);

        index.rebuild(new OIndexRebuildOutputListener(index));
        index.flush();

        setDirty();

        ok++;

        OLogManager.instance().info(this, "Rebuild of '%s index was successfully finished", indexName);
      } else {
        errors++;
        OLogManager.instance().error(this, "Information about index was restored incorrectly, following data were loaded : "
            + "index name '%s', index definition '%s', clusters %s, type %s", indexName, indexDefinition, clusters, type);
      }
    }

    private void addIndexAsIs(ODocument idx, OIndexInternal<?> index, OIndexMetadata indexMetadata) {
      OLogManager.instance().info(this, "Index '%s' is not automatic index and will be added as is", indexMetadata.getName());

      if (index.loadFromConfiguration(idx)) {
        addIndexInternal(index);
        setDirty();

        ok++;
        OLogManager.instance().info(this, "Index '%s' was added in DB index list", index.getName());
      } else {
        index.delete();
        errors++;
      }
    }

    private OIndexInternal<?> createIndex(ODocument idx) {
      final String indexName = idx.field(OIndexInternal.CONFIG_NAME);
      final String indexType = idx.field(OIndexInternal.CONFIG_TYPE);
      String algorithm = idx.field(OIndexInternal.ALGORITHM);
      String valueContainerAlgorithm = idx.field(OIndexInternal.VALUE_CONTAINER_ALGORITHM);

      ODocument metadata = idx.field(OIndexInternal.METADATA);
      if (indexType == null) {
        OLogManager.instance().error(this, "Index type is null, will process other record");
        throw new OIndexException("Index type is null, will process other record. Index configuration: " + idx.toString());
      }

      return OIndexes.createIndex(storage, indexName, indexType, algorithm, valueContainerAlgorithm, metadata, -1);
    }
  }

  protected OIndex<?> preProcessBeforeReturn(ODatabaseDocumentInternal database, final OIndex<?> index) {
    if (index instanceof OIndexMultiValues)
      return new OIndexTxAwareMultiValue(database, (OIndex<Set<OIdentifiable>>) index);
    else if (index instanceof OIndexDictionary)
      return new OIndexTxAwareDictionary(database, (OIndex<OIdentifiable>) index);
    else if (index instanceof OIndexOneValue)
      return new OIndexTxAwareOneValue(database, (OIndex<OIdentifiable>) index);

    return index;
  }

}
