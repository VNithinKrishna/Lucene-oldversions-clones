/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.analysis.ja.dict;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Objects;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.store.DataInput;
import org.apache.lucene.store.InputStreamDataInput;
import org.apache.lucene.util.IOSupplier;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.IntsRef;

/** Base class for a binary-encoded in-memory dictionary. */
public abstract class BinaryDictionary implements Dictionary {

  /** Used to specify where (dictionary) resources get loaded from. */
  @Deprecated(forRemoval = true, since = "9.1")
  public enum ResourceScheme {
    CLASSPATH,
    FILE
  }

  public static final String DICT_FILENAME_SUFFIX = "$buffer.dat";
  public static final String TARGETMAP_FILENAME_SUFFIX = "$targetMap.dat";
  public static final String POSDICT_FILENAME_SUFFIX = "$posDict.dat";

  public static final String DICT_HEADER = "kuromoji_dict";
  public static final String TARGETMAP_HEADER = "kuromoji_dict_map";
  public static final String POSDICT_HEADER = "kuromoji_dict_pos";
  public static final int VERSION = 1;

  private final ByteBuffer buffer;
  private final int[] targetMapOffsets, targetMap;
  private final String[] posDict;
  private final String[] inflTypeDict;
  private final String[] inflFormDict;

  protected BinaryDictionary(
      IOSupplier<InputStream> targetMapResource,
      IOSupplier<InputStream> posResource,
      IOSupplier<InputStream> dictResource)
      throws IOException {
    try (InputStream mapIS = new BufferedInputStream(targetMapResource.get())) {
      final DataInput in = new InputStreamDataInput(mapIS);
      CodecUtil.checkHeader(in, TARGETMAP_HEADER, VERSION, VERSION);
      this.targetMap = new int[in.readVInt()];
      this.targetMapOffsets = new int[in.readVInt()];
      populateTargetMap(in, this.targetMap, this.targetMapOffsets);
    }

    try (InputStream posIS = new BufferedInputStream(posResource.get())) {
      final DataInput in = new InputStreamDataInput(posIS);
      CodecUtil.checkHeader(in, POSDICT_HEADER, VERSION, VERSION);
      final int posSize = in.readVInt();
      this.posDict = new String[posSize];
      this.inflTypeDict = new String[posSize];
      this.inflFormDict = new String[posSize];
      populatePosDict(in, posSize, this.posDict, this.inflTypeDict, this.inflFormDict);
    }

    // no buffering here, as we load in one large buffer
    try (InputStream dictIS = dictResource.get()) {
      final DataInput in = new InputStreamDataInput(dictIS);
      CodecUtil.checkHeader(in, DICT_HEADER, VERSION, VERSION);
      final int size = in.readVInt();
      final ByteBuffer tmpBuffer = ByteBuffer.allocateDirect(size);
      final ReadableByteChannel channel = Channels.newChannel(dictIS);
      final int read = channel.read(tmpBuffer);
      if (read != size) {
        throw new EOFException("Cannot read whole dictionary");
      }
      this.buffer = tmpBuffer.asReadOnlyBuffer();
    }
  }

  private static void populateTargetMap(DataInput in, int[] targetMap, int[] targetMapOffsets)
      throws IOException {
    int accum = 0, sourceId = 0;
    for (int ofs = 0; ofs < targetMap.length; ofs++) {
      final int val = in.readVInt();
      if ((val & 0x01) != 0) {
        targetMapOffsets[sourceId] = ofs;
        sourceId++;
      }
      accum += val >>> 1;
      targetMap[ofs] = accum;
    }
    if (sourceId + 1 != targetMapOffsets.length)
      throw new IOException(
          "targetMap file format broken; targetMap.length="
              + targetMap.length
              + ", targetMapOffsets.length="
              + targetMapOffsets.length
              + ", sourceId="
              + sourceId);
    targetMapOffsets[sourceId] = targetMap.length;
  }

  private static void populatePosDict(
      DataInput in, int posSize, String[] posDict, String[] inflTypeDict, String[] inflFormDict)
      throws IOException {
    for (int j = 0; j < posSize; j++) {
      posDict[j] = in.readString();
      inflTypeDict[j] = in.readString();
      inflFormDict[j] = in.readString();
      // this is how we encode null inflections
      if (inflTypeDict[j].length() == 0) {
        inflTypeDict[j] = null;
      }
      if (inflFormDict[j].length() == 0) {
        inflFormDict[j] = null;
      }
    }
  }

  @Deprecated(forRemoval = true, since = "9.1")
  public static final InputStream getResource(ResourceScheme scheme, String path)
      throws IOException {
    switch (scheme) {
      case CLASSPATH:
        Objects.requireNonNull(
            path,
            "Deprecated API no longer works with null paths, to load default resources use default ctors.");
        return IOUtils.requireResourceNonNull(
            BinaryDictionary.class.getClassLoader().getResourceAsStream(path), path);
      case FILE:
        return Files.newInputStream(Paths.get(path));
      default:
        throw new IllegalStateException("unknown resource scheme " + scheme);
    }
  }

  public void lookupWordIds(int sourceId, IntsRef ref) {
    ref.ints = targetMap;
    ref.offset = targetMapOffsets[sourceId];
    // targetMapOffsets always has one more entry pointing behind last:
    ref.length = targetMapOffsets[sourceId + 1] - ref.offset;
  }

  @Override
  public int getLeftId(int wordId) {
    return (buffer.getShort(wordId) & 0xffff) >>> 3;
  }

  @Override
  public int getRightId(int wordId) {
    return (buffer.getShort(wordId) & 0xffff) >>> 3;
  }

  @Override
  public int getWordCost(int wordId) {
    return buffer.getShort(wordId + 2); // Skip id
  }

  @Override
  public String getBaseForm(int wordId, char[] surfaceForm, int off, int len) {
    if (hasBaseFormData(wordId)) {
      int offset = baseFormOffset(wordId);
      int data = buffer.get(offset++) & 0xff;
      int prefix = data >>> 4;
      int suffix = data & 0xF;
      char[] text = new char[prefix + suffix];
      System.arraycopy(surfaceForm, off, text, 0, prefix);
      for (int i = 0; i < suffix; i++) {
        text[prefix + i] = buffer.getChar(offset + (i << 1));
      }
      return new String(text);
    } else {
      return null;
    }
  }

  @Override
  public String getReading(int wordId, char[] surface, int off, int len) {
    if (hasReadingData(wordId)) {
      int offset = readingOffset(wordId);
      int readingData = buffer.get(offset++) & 0xff;
      return readString(offset, readingData >>> 1, (readingData & 1) == 1);
    } else {
      // the reading is the surface form, with hiragana shifted to katakana
      char[] text = new char[len];
      for (int i = 0; i < len; i++) {
        char ch = surface[off + i];
        if (ch > 0x3040 && ch < 0x3097) {
          text[i] = (char) (ch + 0x60);
        } else {
          text[i] = ch;
        }
      }
      return new String(text);
    }
  }

  @Override
  public String getPartOfSpeech(int wordId) {
    return posDict[getLeftId(wordId)];
  }

  @Override
  public String getPronunciation(int wordId, char[] surface, int off, int len) {
    if (hasPronunciationData(wordId)) {
      int offset = pronunciationOffset(wordId);
      int pronunciationData = buffer.get(offset++) & 0xff;
      return readString(offset, pronunciationData >>> 1, (pronunciationData & 1) == 1);
    } else {
      return getReading(wordId, surface, off, len); // same as the reading
    }
  }

  @Override
  public String getInflectionType(int wordId) {
    return inflTypeDict[getLeftId(wordId)];
  }

  @Override
  public String getInflectionForm(int wordId) {
    return inflFormDict[getLeftId(wordId)];
  }

  private static int baseFormOffset(int wordId) {
    return wordId + 4;
  }

  private int readingOffset(int wordId) {
    int offset = baseFormOffset(wordId);
    if (hasBaseFormData(wordId)) {
      int baseFormLength = buffer.get(offset++) & 0xf;
      return offset + (baseFormLength << 1);
    } else {
      return offset;
    }
  }

  private int pronunciationOffset(int wordId) {
    if (hasReadingData(wordId)) {
      int offset = readingOffset(wordId);
      int readingData = buffer.get(offset++) & 0xff;
      final int readingLength;
      if ((readingData & 1) == 0) {
        readingLength = readingData & 0xfe; // UTF-16: mask off kana bit
      } else {
        readingLength = readingData >>> 1;
      }
      return offset + readingLength;
    } else {
      return readingOffset(wordId);
    }
  }

  private boolean hasBaseFormData(int wordId) {
    return (buffer.getShort(wordId) & HAS_BASEFORM) != 0;
  }

  private boolean hasReadingData(int wordId) {
    return (buffer.getShort(wordId) & HAS_READING) != 0;
  }

  private boolean hasPronunciationData(int wordId) {
    return (buffer.getShort(wordId) & HAS_PRONUNCIATION) != 0;
  }

  private String readString(int offset, int length, boolean kana) {
    char[] text = new char[length];
    if (kana) {
      for (int i = 0; i < length; i++) {
        text[i] = (char) (0x30A0 + (buffer.get(offset + i) & 0xff));
      }
    } else {
      for (int i = 0; i < length; i++) {
        text[i] = buffer.getChar(offset + (i << 1));
      }
    }
    return new String(text);
  }

  /** flag that the entry has baseform data. otherwise it's not inflected (same as surface form) */
  public static final int HAS_BASEFORM = 1;
  /**
   * flag that the entry has reading data. otherwise reading is surface form converted to katakana
   */
  public static final int HAS_READING = 2;
  /** flag that the entry has pronunciation data. otherwise pronunciation is the reading */
  public static final int HAS_PRONUNCIATION = 4;
}
