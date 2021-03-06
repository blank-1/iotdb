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

package org.apache.iotdb.db.engine.storagegroup.timeindex;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Set;
import org.apache.iotdb.db.exception.PartitionViolationException;

public interface ITimeIndex {

  /**
   * serialize to outputStream
   *
   * @param outputStream outputStream
   */
  void serialize(OutputStream outputStream) throws IOException;

  /**
   * deserialize from inputStream
   *
   * @param inputStream inputStream
   * @return TimeIndex
   */
  ITimeIndex deserialize(InputStream inputStream) throws IOException;

  /**
   * deserialize from byte buffer
   *
   * @param buffer byte buffer
   * @return TimeIndex
   */
  ITimeIndex deserialize(ByteBuffer buffer);

  /**
   * do something when TsFileResource is closing (may be empty method)
   */
  void close();

  /**
   * get devices in TimeIndex
   *
   * @return device names
   */
  Set<String> getDevices();

  /**
   * @return whether end time is empty (Long.MIN_VALUE)
   */
  boolean endTimeEmpty();

  /**
   * @param timeLowerBound time lower bound
   * @return whether any of the device lives over the given time bound
   */
  boolean stillLives(long timeLowerBound);

  /**
   * @return Calculate file index ram size
   */
  long calculateRamSize();

  /**
   * Calculate file index ram increment when insert data in TsFileProcessor
   *
   * @param deviceToBeChecked device to be checked
   * @return ramIncrement
   */
  long estimateRamIncrement(String deviceToBeChecked);

  /**
   * get time partition
   *
   * @param tsfilePath tsfile absolute path
   * @return partition
   */
  long getTimePartition(String tsfilePath);

  /**
   * get time partition with check. If data of tsfile cross partitions, an exception will be thrown
   *
   * @param tsfilePath tsfile path
   * @return partition
   * @throws PartitionViolationException data of tsfile cross partitions
   */
  long getTimePartitionWithCheck(String tsfilePath) throws PartitionViolationException;

  /**
   * update start time
   *
   * @param deviceId device name
   * @param time     start time
   */
  void updateStartTime(String deviceId, long time);

  /**
   * update end time
   *
   * @param deviceId device name
   * @param time     end time
   */
  void updateEndTime(String deviceId, long time);

  /**
   * get start time of device
   *
   * @param deviceId device name
   * @return start time
   */
  long getStartTime(String deviceId);

  /**
   * get end time of device
   *
   * @param deviceId device name
   * @return end time
   */
  long getEndTime(String deviceId);
}
