/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.tsfile.v2.read;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.apache.iotdb.tsfile.common.conf.TSFileConfig;
import org.apache.iotdb.tsfile.file.MetaMarker;
import org.apache.iotdb.tsfile.file.header.ChunkGroupHeader;
import org.apache.iotdb.tsfile.file.header.ChunkHeader;
import org.apache.iotdb.tsfile.file.header.PageHeader;
import org.apache.iotdb.tsfile.file.metadata.ChunkGroupMetadata;
import org.apache.iotdb.tsfile.file.metadata.ChunkMetadata;
import org.apache.iotdb.tsfile.file.metadata.MetadataIndexEntry;
import org.apache.iotdb.tsfile.file.metadata.MetadataIndexNode;
import org.apache.iotdb.tsfile.file.metadata.TimeseriesMetadata;
import org.apache.iotdb.tsfile.file.metadata.TsFileMetadata;
import org.apache.iotdb.tsfile.file.metadata.enums.MetadataIndexNodeType;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.file.metadata.statistics.Statistics;
import org.apache.iotdb.tsfile.fileSystem.FSFactoryProducer;
import org.apache.iotdb.tsfile.read.TsFileCheckStatus;
import org.apache.iotdb.tsfile.read.TsFileSequenceReader;
import org.apache.iotdb.tsfile.read.common.Chunk;
import org.apache.iotdb.tsfile.read.common.Path;
import org.apache.iotdb.tsfile.read.reader.TsFileInput;
import org.apache.iotdb.tsfile.utils.Pair;
import org.apache.iotdb.tsfile.utils.VersionUtils;
import org.apache.iotdb.tsfile.v2.file.footer.ChunkGroupFooterV2;
import org.apache.iotdb.tsfile.v2.file.header.ChunkHeaderV2;
import org.apache.iotdb.tsfile.v2.file.header.PageHeaderV2;
import org.apache.iotdb.tsfile.v2.file.metadata.ChunkMetadataV2;
import org.apache.iotdb.tsfile.v2.file.metadata.MetadataIndexNodeV2;
import org.apache.iotdb.tsfile.v2.file.metadata.TimeseriesMetadataV2;
import org.apache.iotdb.tsfile.v2.file.metadata.TsFileMetadataV2;
import org.apache.iotdb.tsfile.write.schema.MeasurementSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TsFileSequenceReaderForV2 extends TsFileSequenceReader implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(TsFileSequenceReaderForV2.class);
  private int totalChunkNum;

  /**
   * Create a file reader of the given file. The reader will read the tail of the file to get the
   * file metadata size.Then the reader will skip the first TSFileConfig.MAGIC_STRING.getBytes().length
   * + TSFileConfig.NUMBER_VERSION.getBytes().length bytes of the file for preparing reading real
   * data.
   *
   * @param file the data file
   * @throws IOException If some I/O error occurs
   */
  public TsFileSequenceReaderForV2(String file) throws IOException {
    this(file, true);
  }

  /**
   * construct function for TsFileSequenceReader.
   *
   * @param file             -given file name
   * @param loadMetadataSize -whether load meta data size
   */
  public TsFileSequenceReaderForV2(String file, boolean loadMetadataSize) throws IOException {
    super(file, loadMetadataSize);
  }

  /**
   * Create a file reader of the given file. The reader will read the tail of the file to get the
   * file metadata size.Then the reader will skip the first TSFileConfig.MAGIC_STRING.getBytes().length
   * + TSFileConfig.NUMBER_VERSION.getBytes().length bytes of the file for preparing reading real
   * data.
   *
   * @param input given input
   */
  public TsFileSequenceReaderForV2(TsFileInput input) throws IOException {
    this(input, true);
  }

  /**
   * construct function for TsFileSequenceReader.
   *
   * @param input            -given input
   * @param loadMetadataSize -load meta data size
   */
  public TsFileSequenceReaderForV2(TsFileInput input, boolean loadMetadataSize) throws IOException {
    super(input, loadMetadataSize);
  }

  /**
   * construct function for TsFileSequenceReader.
   *
   * @param input            the input of a tsfile. The current position should be a markder and
   *                         then a chunk Header, rather than the magic number
   * @param fileMetadataPos  the position of the file metadata in the TsFileInput from the beginning
   *                         of the input to the current position
   * @param fileMetadataSize the byte size of the file metadata in the input
   */
  public TsFileSequenceReaderForV2(TsFileInput input, long fileMetadataPos, int fileMetadataSize) {
    super(input, fileMetadataPos, fileMetadataSize);
    this.fileMetadataPos = fileMetadataPos;
    this.fileMetadataSize = fileMetadataSize;
  }

  /**
   * whether the file is a complete TsFile: only if the head magic and tail magic string exists.
   */
  @Override
  public boolean isComplete() throws IOException {
    return tsFileInput.size() >= TSFileConfig.MAGIC_STRING.getBytes().length * 2
        + TSFileConfig.VERSION_NUMBER_V2.getBytes().length
        && (readTailMagic().equals(readHeadMagic()));
  }

  /**
   * this function reads version number and checks compatibility of TsFile.
   */
  public String readVersionNumberV2() throws IOException {
    ByteBuffer versionNumberBytes = ByteBuffer
        .allocate(TSFileConfig.VERSION_NUMBER_V2.getBytes().length);
    tsFileInput.read(versionNumberBytes, TSFileConfig.MAGIC_STRING.getBytes().length);
    versionNumberBytes.flip();
    return new String(versionNumberBytes.array());
  }

  /**
   * this function does not modify the position of the file reader.
   *
   * @throws IOException io error
   */
  @Override
  public TsFileMetadata readFileMetadata() throws IOException {
    if (tsFileMetaData == null) {
      tsFileMetaData = TsFileMetadataV2.deserializeFrom(
          readData(fileMetadataPos, fileMetadataSize));
    }
    return tsFileMetaData;
  }

  @Override
  public TimeseriesMetadata readTimeseriesMetadata(Path path) throws IOException {
    readFileMetadata();
    MetadataIndexNode deviceMetadataIndexNode = tsFileMetaData.getMetadataIndex();
    Pair<MetadataIndexEntry, Long> metadataIndexPair = getMetadataAndEndOffsetV2(
        deviceMetadataIndexNode, path.getDevice(), MetadataIndexNodeType.INTERNAL_DEVICE, true);
    if (metadataIndexPair == null) {
      return null;
    }
    ByteBuffer buffer = readData(metadataIndexPair.left.getOffset(), metadataIndexPair.right);
    MetadataIndexNode metadataIndexNode = deviceMetadataIndexNode;
    if (!metadataIndexNode.getNodeType().equals(MetadataIndexNodeType.LEAF_MEASUREMENT)) {
      metadataIndexNode = MetadataIndexNodeV2.deserializeFrom(buffer);
      metadataIndexPair = getMetadataAndEndOffsetV2(metadataIndexNode,
          path.getMeasurement(), MetadataIndexNodeType.INTERNAL_MEASUREMENT, false);
    }
    if (metadataIndexPair == null) {
      return null;
    }
    List<TimeseriesMetadata> timeseriesMetadataList = new ArrayList<>();
    buffer = readData(metadataIndexPair.left.getOffset(), metadataIndexPair.right);
    while (buffer.hasRemaining()) {
      timeseriesMetadataList.add(TimeseriesMetadataV2.deserializeFrom(buffer));
    }
    // return null if path does not exist in the TsFile
    int searchResult = binarySearchInTimeseriesMetadataList(timeseriesMetadataList,
        path.getMeasurement());
    return searchResult >= 0 ? timeseriesMetadataList.get(searchResult) : null;
  }

  @SuppressWarnings("squid:S3776")
  @Override
  public List<TimeseriesMetadata> readTimeseriesMetadata(String device, Set<String> measurements)
      throws IOException {
    readFileMetadata();
    MetadataIndexNode deviceMetadataIndexNode = tsFileMetaData.getMetadataIndex();
    Pair<MetadataIndexEntry, Long> metadataIndexPair = getMetadataAndEndOffsetV2(
        deviceMetadataIndexNode, device, MetadataIndexNodeType.INTERNAL_DEVICE, false);
    if (metadataIndexPair == null) {
      return Collections.emptyList();
    }
    List<TimeseriesMetadata> resultTimeseriesMetadataList = new ArrayList<>();
    List<String> measurementList = new ArrayList<>(measurements);
    Set<String> measurementsHadFound = new HashSet<>();
    for (int i = 0; i < measurementList.size(); i++) {
      if (measurementsHadFound.contains(measurementList.get(i))) {
        continue;
      }
      ByteBuffer buffer = readData(metadataIndexPair.left.getOffset(), metadataIndexPair.right);
      Pair<MetadataIndexEntry, Long> measurementMetadataIndexPair = metadataIndexPair;
      List<TimeseriesMetadata> timeseriesMetadataList = new ArrayList<>();
      MetadataIndexNode metadataIndexNode = deviceMetadataIndexNode;
      if (!metadataIndexNode.getNodeType().equals(MetadataIndexNodeType.LEAF_MEASUREMENT)) {
        metadataIndexNode = MetadataIndexNodeV2.deserializeFrom(buffer);
        measurementMetadataIndexPair = getMetadataAndEndOffsetV2(metadataIndexNode,
            measurementList.get(i), MetadataIndexNodeType.INTERNAL_MEASUREMENT, false);
      }
      if (measurementMetadataIndexPair == null) {
        return Collections.emptyList();
      }
      buffer = readData(measurementMetadataIndexPair.left.getOffset(),
          measurementMetadataIndexPair.right);
      while (buffer.hasRemaining()) {
        timeseriesMetadataList.add(TimeseriesMetadataV2.deserializeFrom(buffer));
      }
      for (int j = i; j < measurementList.size(); j++) {
        String current = measurementList.get(j);
        if (!measurementsHadFound.contains(current)) {
          int searchResult = binarySearchInTimeseriesMetadataList(timeseriesMetadataList, current);
          if (searchResult >= 0) {
            resultTimeseriesMetadataList.add(timeseriesMetadataList.get(searchResult));
            measurementsHadFound.add(current);
          }
        }
        if (measurementsHadFound.size() == measurements.size()) {
          return resultTimeseriesMetadataList;
        }
      }
    }
    return resultTimeseriesMetadataList;
  }

  @Override
  public List<String> getAllDevices() throws IOException {
    if (tsFileMetaData == null) {
      readFileMetadata();
    }
    return getAllDevicesV2(tsFileMetaData.getMetadataIndex());
  }

  private List<String> getAllDevicesV2(MetadataIndexNode metadataIndexNode) throws IOException {
    List<String> deviceList = new ArrayList<>();
    int metadataIndexListSize = metadataIndexNode.getChildren().size();
    if (metadataIndexNode.getNodeType().equals(MetadataIndexNodeType.INTERNAL_MEASUREMENT)) {
      for (MetadataIndexEntry index : metadataIndexNode.getChildren()) {
        deviceList.add(index.getName());
      }
    } else {
      for (int i = 0; i < metadataIndexListSize; i++) {
        long endOffset = metadataIndexNode.getEndOffset();
        if (i != metadataIndexListSize - 1) {
          endOffset = metadataIndexNode.getChildren().get(i + 1).getOffset();
        }
        ByteBuffer buffer = readData(metadataIndexNode.getChildren().get(i).getOffset(), endOffset);
        MetadataIndexNode node = MetadataIndexNodeV2.deserializeFrom(buffer);
        if (node.getNodeType().equals(MetadataIndexNodeType.LEAF_DEVICE)) {
          // if node in next level is LEAF_DEVICE, put all devices in node entry into the set
          deviceList.addAll(node.getChildren().stream().map(MetadataIndexEntry::getName).collect(
              Collectors.toList()));
        } else {
          // keep traversing
          deviceList.addAll(getAllDevicesV2(node));
        }
      }
    }
    return deviceList;
  }

  /**
   * read all ChunkMetaDatas of given device
   *
   * @param device name
   * @return measurement -> ChunkMetadata list
   * @throws IOException io error
   */
  @Override
  public Map<String, List<ChunkMetadata>> readChunkMetadataInDevice(String device)
      throws IOException {
    if (tsFileMetaData == null) {
      readFileMetadata();
    }

    long start = 0;
    int size = 0;
    List<TimeseriesMetadata> timeseriesMetadataMap = getDeviceTimeseriesMetadataV2(device);
    for (TimeseriesMetadata timeseriesMetadata : timeseriesMetadataMap) {
      if (start == 0) {
        start = timeseriesMetadata.getOffsetOfChunkMetaDataList();
      }
      size += timeseriesMetadata.getDataSizeOfChunkMetaDataList();
    }
    // read buffer of all ChunkMetadatas of this device
    ByteBuffer buffer = readData(start, size);
    Map<String, List<ChunkMetadata>> seriesMetadata = new HashMap<>();
    while (buffer.hasRemaining()) {
      ChunkMetadata chunkMetadata = ChunkMetadataV2.deserializeFrom(buffer);
      seriesMetadata.computeIfAbsent(chunkMetadata.getMeasurementUid(), key -> new ArrayList<>())
          .add(chunkMetadata);
    }

    // set version in ChunkMetadata
    List<Pair<Long, Long>> versionInfo = tsFileMetaData.getVersionInfo();
    for (Entry<String, List<ChunkMetadata>> entry : seriesMetadata.entrySet()) {
      VersionUtils.applyVersion(entry.getValue(), versionInfo);
    }
    return seriesMetadata;
  }

  /**
   * Traverse the metadata index from MetadataIndexEntry to get TimeseriesMetadatas
   *
   * @param metadataIndex         MetadataIndexEntry
   * @param buffer                byte buffer
   * @param deviceId              String
   * @param timeseriesMetadataMap map: deviceId -> timeseriesMetadata list
   */
  private void generateMetadataIndexV2(MetadataIndexEntry metadataIndex, ByteBuffer buffer,
      String deviceId, MetadataIndexNodeType type,
      Map<String, List<TimeseriesMetadata>> timeseriesMetadataMap) throws IOException {
    switch (type) {
      case INTERNAL_DEVICE:
      case LEAF_DEVICE:
      case INTERNAL_MEASUREMENT:
        deviceId = metadataIndex.getName();
        MetadataIndexNode metadataIndexNode = MetadataIndexNodeV2.deserializeFrom(buffer);
        int metadataIndexListSize = metadataIndexNode.getChildren().size();
        for (int i = 0; i < metadataIndexListSize; i++) {
          long endOffset = metadataIndexNode.getEndOffset();
          if (i != metadataIndexListSize - 1) {
            endOffset = metadataIndexNode.getChildren().get(i + 1).getOffset();
          }
          ByteBuffer nextBuffer = readData(metadataIndexNode.getChildren().get(i).getOffset(),
              endOffset);
          generateMetadataIndexV2(metadataIndexNode.getChildren().get(i), nextBuffer, deviceId,
              metadataIndexNode.getNodeType(), timeseriesMetadataMap);
        }
        break;
      case LEAF_MEASUREMENT:
        List<TimeseriesMetadata> timeseriesMetadataList = new ArrayList<>();
        while (buffer.hasRemaining()) {
          timeseriesMetadataList.add(TimeseriesMetadataV2.deserializeFrom(buffer));
        }
        timeseriesMetadataMap.computeIfAbsent(deviceId, k -> new ArrayList<>())
            .addAll(timeseriesMetadataList);
        break;
    }
  }

  @Override
  public Map<String, List<TimeseriesMetadata>> getAllTimeseriesMetadata() throws IOException {
    if (tsFileMetaData == null) {
      readFileMetadata();
    }
    Map<String, List<TimeseriesMetadata>> timeseriesMetadataMap = new HashMap<>();
    MetadataIndexNode metadataIndexNode = tsFileMetaData.getMetadataIndex();
    List<MetadataIndexEntry> metadataIndexEntryList = metadataIndexNode.getChildren();
    for (int i = 0; i < metadataIndexEntryList.size(); i++) {
      MetadataIndexEntry metadataIndexEntry = metadataIndexEntryList.get(i);
      long endOffset = tsFileMetaData.getMetadataIndex().getEndOffset();
      if (i != metadataIndexEntryList.size() - 1) {
        endOffset = metadataIndexEntryList.get(i + 1).getOffset();
      }
      ByteBuffer buffer = readData(metadataIndexEntry.getOffset(), endOffset);
      generateMetadataIndexV2(metadataIndexEntry, buffer, null,
          metadataIndexNode.getNodeType(), timeseriesMetadataMap);
    }
    return timeseriesMetadataMap;
  }

  private List<TimeseriesMetadata> getDeviceTimeseriesMetadataV2(String device) throws IOException {
    MetadataIndexNode metadataIndexNode = tsFileMetaData.getMetadataIndex();
    Pair<MetadataIndexEntry, Long> metadataIndexPair = getMetadataAndEndOffsetV2(
        metadataIndexNode, device, MetadataIndexNodeType.INTERNAL_DEVICE, true);
    if (metadataIndexPair == null) {
      return Collections.emptyList();
    }
    ByteBuffer buffer = readData(metadataIndexPair.left.getOffset(), metadataIndexPair.right);
    Map<String, List<TimeseriesMetadata>> timeseriesMetadataMap = new TreeMap<>();
    generateMetadataIndexV2(metadataIndexPair.left, buffer, device,
        MetadataIndexNodeType.INTERNAL_MEASUREMENT, timeseriesMetadataMap);
    List<TimeseriesMetadata> deviceTimeseriesMetadata = new ArrayList<>();
    for (List<TimeseriesMetadata> timeseriesMetadataList : timeseriesMetadataMap.values()) {
      deviceTimeseriesMetadata.addAll(timeseriesMetadataList);
    }
    return deviceTimeseriesMetadata;
  }

  /**
   * Get target MetadataIndexEntry and its end offset
   *
   * @param metadataIndex given MetadataIndexNode
   * @param name          target device / measurement name
   * @param type          target MetadataIndexNodeType, either INTERNAL_DEVICE or
   *                      INTERNAL_MEASUREMENT. When searching for a device node,  return when it is
   *                      not INTERNAL_DEVICE. Likewise, when searching for a measurement node,
   *                      return when it is not INTERNAL_MEASUREMENT. This works for the situation
   *                      when the index tree does NOT have the device level and ONLY has the
   *                      measurement level.
   * @param exactSearch   if is in exact search mode, return null when there is no entry with name;
   *                      or else return the nearest MetadataIndexEntry before it (for deeper
   *                      search)
   * @return target MetadataIndexEntry, endOffset pair
   */
  private Pair<MetadataIndexEntry, Long> getMetadataAndEndOffsetV2(MetadataIndexNode metadataIndex,
      String name, MetadataIndexNodeType type, boolean exactSearch) throws IOException {
    if (!metadataIndex.getNodeType().equals(type)) {
      return metadataIndex.getChildIndexEntry(name, exactSearch);
    } else {
      Pair<MetadataIndexEntry, Long> childIndexEntry = metadataIndex
          .getChildIndexEntry(name, false);
      ByteBuffer buffer = readData(childIndexEntry.left.getOffset(), childIndexEntry.right);
      return getMetadataAndEndOffsetV2(MetadataIndexNodeV2.deserializeFrom(buffer), name, type,
          false);
    }
  }

  /**
   * read data from current position of the input, and deserialize it to a CHUNK_GROUP_FOOTER. <br>
   * This method is not threadsafe.
   *
   * @return a CHUNK_GROUP_FOOTER
   * @throws IOException io error
   */
  public ChunkGroupHeader readChunkGroupFooter() throws IOException {
    return ChunkGroupFooterV2.deserializeFrom(tsFileInput.wrapAsInputStream(), true);
  }

  /**
   * read data from current position of the input, and deserialize it to a CHUNK_GROUP_FOOTER.
   *
   * @param position   the offset of the chunk group footer in the file
   * @param markerRead true if the offset does not contains the marker , otherwise false
   * @return a CHUNK_GROUP_FOOTER
   * @throws IOException io error
   */
  public ChunkGroupHeader readChunkGroupFooter(long position, boolean markerRead)
      throws IOException {
    return ChunkGroupFooterV2.deserializeFrom(tsFileInput, position, markerRead);
  }

  /**
   * read data from current position of the input, and deserialize it to a CHUNK_HEADER. <br> This
   * method is not threadsafe.
   *
   * @return a CHUNK_HEADER
   * @throws IOException io error
   */
  public ChunkHeader readChunkHeader() throws IOException {
    return ChunkHeaderV2.deserializeFrom(tsFileInput.wrapAsInputStream(), true);
  }

  /**
   * read the chunk's header.
   *
   * @param position        the file offset of this chunk's header
   * @param chunkHeaderSize the size of chunk's header
   * @param markerRead      true if the offset does not contains the marker , otherwise false
   */
  private ChunkHeader readChunkHeader(long position, int chunkHeaderSize, boolean markerRead)
      throws IOException {
    return ChunkHeaderV2.deserializeFrom(tsFileInput, position, chunkHeaderSize, markerRead);
  }

  /**
   * notice, this function will modify channel's position.
   *
   * @param dataSize the size of chunkdata
   * @param position the offset of the chunk data
   * @return the pages of this chunk
   */
  private ByteBuffer readChunkV2(long position, int dataSize) throws IOException {
    return readData(position, dataSize);
  }

  /**
   * read memory chunk.
   *
   * @param metaData -given chunk meta data
   * @return -chunk
   */
  @Override
  public Chunk readMemChunk(ChunkMetadata metaData) throws IOException {
    int chunkHeadSize = ChunkHeaderV2.getSerializedSize(metaData.getMeasurementUid());
    ChunkHeader header = readChunkHeader(metaData.getOffsetOfChunkHeader(), chunkHeadSize, false);
    ByteBuffer buffer = readChunkV2(metaData.getOffsetOfChunkHeader() + header.getSerializedSize(),
        header.getDataSize());
    Chunk chunk = new Chunk(header, buffer, metaData.getDeleteIntervalList(), metaData.getStatistics());
    chunk.setFromOldFile(true);
    return chunk;
  }

  /**
   * not thread safe.
   *
   * @param type given tsfile data type
   */
  public PageHeader readPageHeader(TSDataType type) throws IOException {
    return PageHeaderV2.deserializeFrom(tsFileInput.wrapAsInputStream(), type);
  }

  /**
   * Self Check the file and return the position before where the data is safe.
   *
   * @param newSchema              the schema on each time series in the file
   * @param chunkGroupMetadataList ChunkGroupMetadata List
   * @param versionInfo            version pair List
   * @param fastFinish             if true and the file is complete, then newSchema and
   *                               chunkGroupMetadataList parameter will be not modified.
   * @return the position of the file that is fine. All data after the position in the file should
   * be truncated.
   */
  @SuppressWarnings("squid:S3776") // Suppress high Cognitive Complexity warning
  public long selfCheck(Map<Path, MeasurementSchema> newSchema,
      List<ChunkGroupMetadata> chunkGroupMetadataList,
      List<Pair<Long, Long>> versionInfo,
      boolean fastFinish) throws IOException {
    File checkFile = FSFactoryProducer.getFSFactory().getFile(this.file);
    long fileSize;
    if (!checkFile.exists()) {
      return TsFileCheckStatus.FILE_NOT_FOUND;
    } else {
      fileSize = checkFile.length();
    }
    ChunkMetadata currentChunk;
    String measurementID;
    TSDataType dataType;
    long fileOffsetOfChunk;

    // ChunkMetadata of current ChunkGroup
    List<ChunkMetadata> chunkMetadataList = null;
    String deviceID;

    int headerLength = TSFileConfig.MAGIC_STRING.getBytes().length + TSFileConfig.VERSION_NUMBER_V2
        .getBytes().length;
    if (fileSize < headerLength) {
      return TsFileCheckStatus.INCOMPATIBLE_FILE;
    }
    if (!TSFileConfig.MAGIC_STRING.equals(readHeadMagic()) || !TSFileConfig.VERSION_NUMBER_V2
        .equals(readVersionNumberV2())) {
      return TsFileCheckStatus.INCOMPATIBLE_FILE;
    }

    tsFileInput.position(headerLength);
    if (fileSize == headerLength) {
      return headerLength;
    } else if (isComplete()) {
      loadMetadataSize();
      if (fastFinish) {
        return TsFileCheckStatus.COMPLETE_FILE;
      }
    }
    boolean newChunkGroup = true;
    // not a complete file, we will recover it...
    long truncatedSize = headerLength;
    byte marker;
    int chunkCnt = 0;
    List<MeasurementSchema> measurementSchemaList = new ArrayList<>();
    try {
      while ((marker = this.readMarker()) != MetaMarker.SEPARATOR) {
        switch (marker) {
          case MetaMarker.CHUNK_HEADER:
            // this is the first chunk of a new ChunkGroup.
            if (newChunkGroup) {
              newChunkGroup = false;
              chunkMetadataList = new ArrayList<>();
            }
            fileOffsetOfChunk = this.position() - 1;
            // if there is something wrong with a chunk, we will drop the whole ChunkGroup
            // as different chunks may be created by the same insertions(sqls), and partial
            // insertion is not tolerable
            ChunkHeader chunkHeader = this.readChunkHeader();
            measurementID = chunkHeader.getMeasurementID();
            MeasurementSchema measurementSchema = new MeasurementSchema(measurementID,
                chunkHeader.getDataType(),
                chunkHeader.getEncodingType(), chunkHeader.getCompressionType());
            measurementSchemaList.add(measurementSchema);
            dataType = chunkHeader.getDataType();
            Statistics<?> chunkStatistics = Statistics.getStatsByType(dataType);
            for (int j = 0; j < chunkHeader.getNumOfPages(); j++) {
              // a new Page
              PageHeader pageHeader = this.readPageHeader(chunkHeader.getDataType());
              chunkStatistics.mergeStatistics(pageHeader.getStatistics());
              this.skipPageData(pageHeader);
            }
            currentChunk = new ChunkMetadata(measurementID, dataType, fileOffsetOfChunk,
                chunkStatistics);
            chunkMetadataList.add(currentChunk);
            chunkCnt++;
            break;
          case MetaMarker.CHUNK_GROUP_HEADER:
            // this is a chunk group
            // if there is something wrong with the ChunkGroup Footer, we will drop this ChunkGroup
            // because we can not guarantee the correctness of the deviceId.
            ChunkGroupHeader chunkGroupFooter = this.readChunkGroupFooter();
            deviceID = chunkGroupFooter.getDeviceID();
            if (newSchema != null) {
              for (MeasurementSchema tsSchema : measurementSchemaList) {
                newSchema.putIfAbsent(new Path(deviceID, tsSchema.getMeasurementId()), tsSchema);
              }
            }
            chunkGroupMetadataList.add(new ChunkGroupMetadata(deviceID, chunkMetadataList));
            newChunkGroup = true;
            truncatedSize = this.position();

            totalChunkNum += chunkCnt;
            chunkCnt = 0;
            measurementSchemaList = new ArrayList<>();
            break;
          case MetaMarker.VERSION:
            long version = readVersion();
            versionInfo.add(new Pair<>(position(), version));
            truncatedSize = this.position();
            break;
          default:
            // the disk file is corrupted, using this file may be dangerous
            throw new IOException("Unexpected marker " + marker);
        }
      }
      // now we read the tail of the data section, so we are sure that the last
      // ChunkGroupFooter is complete.
      truncatedSize = this.position() - 1;
    } catch (Exception e) {
      logger.info("TsFile {} self-check cannot proceed at position {} " + "recovered, because : {}",
          file, this.position(), e.getMessage());
    }
    // Despite the completeness of the data section, we will discard current FileMetadata
    // so that we can continue to write data into this tsfile.
    return truncatedSize;
  }

  public int getTotalChunkNum() {
    return totalChunkNum;
  }

  /**
   * get ChunkMetaDatas in given TimeseriesMetaData
   *
   * @return List of ChunkMetaData
   */
  @Override
  public List<ChunkMetadata> readChunkMetaDataList(TimeseriesMetadata timeseriesMetaData)
      throws IOException {
    readFileMetadata();
    List<Pair<Long, Long>> versionInfo = tsFileMetaData.getVersionInfo();
    ArrayList<ChunkMetadata> chunkMetadataList = new ArrayList<>();
    long startOffsetOfChunkMetadataList = timeseriesMetaData.getOffsetOfChunkMetaDataList();
    int dataSizeOfChunkMetadataList = timeseriesMetaData.getDataSizeOfChunkMetaDataList();

    ByteBuffer buffer = readData(startOffsetOfChunkMetadataList, dataSizeOfChunkMetadataList);
    while (buffer.hasRemaining()) {
      chunkMetadataList.add(ChunkMetadataV2.deserializeFrom(buffer));
    }

    VersionUtils.applyVersion(chunkMetadataList, versionInfo);

    // minimize the storage of an ArrayList instance.
    chunkMetadataList.trimToSize();
    return chunkMetadataList;
  }

}