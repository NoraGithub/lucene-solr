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
package org.apache.lucene.document;

import org.apache.lucene.document.RangeFieldQuery.QueryType;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.NumericUtils;

/**
 * An indexed Long Range field.
 * <p>
 * This field indexes dimensional ranges defined as min/max pairs. It supports
 * up to a maximum of 4 dimensions (indexed as 8 numeric values). With 1 dimension representing a single long range,
 * 2 dimensions representing a bounding box, 3 dimensions a bounding cube, and 4 dimensions a tesseract.
 * <p>
 * Multiple values for the same field in one document is supported, and open ended ranges can be defined using
 * {@code Long.MIN_VALUE} and {@code Long.MAX_VALUE}.
 *
 * <p>
 * This field defines the following static factory methods for common search operations over long ranges:
 * <ul>
 *   <li>{@link #newIntersectsQuery newIntersectsQuery()} matches ranges that intersect the defined search range.
 *   <li>{@link #newWithinQuery newWithinQuery()} matches ranges that are within the defined search range.
 *   <li>{@link #newContainsQuery newContainsQuery()} matches ranges that contain the defined search range.
 * </ul>
 */
public class LongRangeField extends Field {
  /** stores long values so number of bytes is 8 */
  public static final int BYTES = Long.BYTES;

  /**
   * Create a new LongRangeField type, from min/max parallel arrays
   *
   * @param name field name. must not be null.
   * @param min range min values; each entry is the min value for the dimension
   * @param max range max values; each entry is the max value for the dimension
   */
  public LongRangeField(String name, final long[] min, final long[] max) {
    super(name, getType(min.length));
    setRangeValues(min, max);
  }

  /** set the field type */
  private static FieldType getType(int dimensions) {
    if (dimensions > 4) {
      throw new IllegalArgumentException("LongRangeField does not support greater than 4 dimensions");
    }

    FieldType ft = new FieldType();
    // dimensions is set as 2*dimension size (min/max per dimension)
    ft.setDimensions(dimensions*2, BYTES);
    ft.freeze();
    return ft;
  }

  /**
   * Changes the values of the field.
   * @param min array of min values. (accepts {@code Long.MIN_VALUE})
   * @param max array of max values. (accepts {@code Long.MAX_VALUE})
   * @throws IllegalArgumentException if {@code min} or {@code max} is invalid
   */
  public void setRangeValues(long[] min, long[] max) {
    checkArgs(min, max);
    if (min.length*2 != type.pointDimensionCount() || max.length*2 != type.pointDimensionCount()) {
      throw new IllegalArgumentException("field (name=" + name + ") uses " + type.pointDimensionCount()/2
          + " dimensions; cannot change to (incoming) " + min.length + " dimensions");
    }

    final byte[] bytes;
    if (fieldsData == null) {
      bytes = new byte[BYTES*2*min.length];
      fieldsData = new BytesRef(bytes);
    } else {
      bytes = ((BytesRef)fieldsData).bytes;
    }
    verifyAndEncode(min, max, bytes);
  }

  /** validate the arguments */
  private static void checkArgs(final long[] min, final long[] max) {
    if (min == null || max == null || min.length == 0 || max.length == 0) {
      throw new IllegalArgumentException("min/max range values cannot be null or empty");
    }
    if (min.length != max.length) {
      throw new IllegalArgumentException("min/max ranges must agree");
    }
    if (min.length > 4) {
      throw new IllegalArgumentException("LongRangeField does not support greater than 4 dimensions");
    }
  }

  /** Encodes the min, max ranges into a byte array */
  private static byte[] encode(long[] min, long[] max) {
    checkArgs(min, max);
    byte[] b = new byte[BYTES*2*min.length];
    verifyAndEncode(min, max, b);
    return b;
  }

  /**
   * encode the ranges into a sortable byte array ({@code Double.NaN} not allowed)
   * <p>
   * example for 4 dimensions (8 bytes per dimension value):
   * minD1 ... minD4 | maxD1 ... maxD4
   */
  static void verifyAndEncode(long[] min, long[] max, byte[] bytes) {
    for (int d=0,i=0,j=min.length*BYTES; d<min.length; ++d, i+=BYTES, j+=BYTES) {
      if (Double.isNaN(min[d])) {
        throw new IllegalArgumentException("invalid min value (" + Double.NaN + ")" + " in IntRangeField");
      }
      if (Double.isNaN(max[d])) {
        throw new IllegalArgumentException("invalid max value (" + Double.NaN + ")" + " in IntRangeField");
      }
      if (min[d] > max[d]) {
        throw new IllegalArgumentException("min value (" + min[d] + ") is greater than max value (" + max[d] + ")");
      }
      encode(min[d], bytes, i);
      encode(max[d], bytes, j);
    }
  }

  /** encode the given value into the byte array at the defined offset */
  private static void encode(long val, byte[] bytes, int offset) {
    NumericUtils.longToSortableBytes(val, bytes, offset);
  }

  /**
   * Get the min value for the given dimension
   * @param dimension the dimension, always positive
   * @return the decoded min value
   */
  public long getMin(int dimension) {
    if (dimension < 0 || dimension >= type.pointDimensionCount()/2) {
      throw new IllegalArgumentException("dimension request (" + dimension +
          ") out of bounds for field (name=" + name + " dimensions=" + type.pointDimensionCount()/2 + "). ");
    }
    return decodeMin(((BytesRef)fieldsData).bytes, dimension);
  }

  /**
   * Get the max value for the given dimension
   * @param dimension the dimension, always positive
   * @return the decoded max value
   */
  public long getMax(int dimension) {
    if (dimension < 0 || dimension >= type.pointDimensionCount()/2) {
      throw new IllegalArgumentException("dimension request (" + dimension +
          ") out of bounds for field (name=" + name + " dimensions=" + type.pointDimensionCount()/2 + "). ");
    }
    return decodeMax(((BytesRef)fieldsData).bytes, dimension);
  }

  /** decodes the min value (for the defined dimension) from the encoded input byte array */
  static long decodeMin(byte[] b, int dimension) {
    int offset = dimension*BYTES;
    return NumericUtils.sortableBytesToLong(b, offset);
  }

  /** decodes the max value (for the defined dimension) from the encoded input byte array */
  static long decodeMax(byte[] b, int dimension) {
    int offset = b.length/2 + dimension*BYTES;
    return NumericUtils.sortableBytesToLong(b, offset);
  }

  /**
   * Create a query for matching indexed ranges that intersect the defined range.
   * @param field field name. must not be null.
   * @param min array of min values. (accepts {@code Long.MIN_VALUE})
   * @param max array of max values. (accepts {@code Long.MAX_VALUE})
   * @return query for matching intersecting ranges (overlap, within, or contains)
   * @throws IllegalArgumentException if {@code field} is null, {@code min} or {@code max} is invalid
   */
  public static Query newIntersectsQuery(String field, final long[] min, final long[] max) {
    return new RangeFieldQuery(field, encode(min, max), min.length, QueryType.INTERSECTS) {
      @Override
      protected String toString(byte[] ranges, int dimension) {
        return LongRangeField.toString(ranges, dimension);
      }
    };
  }

  /**
   * Create a query for matching indexed ranges that contain the defined range.
   * @param field field name. must not be null.
   * @param min array of min values. (accepts {@code Long.MIN_VALUE})
   * @param max array of max values. (accepts {@code Long.MAX_VALUE})
   * @return query for matching ranges that contain the defined range
   * @throws IllegalArgumentException if {@code field} is null, {@code min} or {@code max} is invalid
   */
  public static Query newContainsQuery(String field, final long[] min, final long[] max) {
    return new RangeFieldQuery(field, encode(min, max), min.length, QueryType.CONTAINS) {
      @Override
      protected String toString(byte[] ranges, int dimension) {
        return LongRangeField.toString(ranges, dimension);
      }
    };
  }

  /**
   * Create a query for matching indexed ranges that are within the defined range.
   * @param field field name. must not be null.
   * @param min array of min values. (accepts {@code Long.MIN_VALUE})
   * @param max array of max values. (accepts {@code Long.MAX_VALUE})
   * @return query for matching ranges within the defined range
   * @throws IllegalArgumentException if {@code field} is null, {@code min} or {@code max} is invalid
   */
  public static Query newWithinQuery(String field, final long[] min, final long[] max) {
    checkArgs(min, max);
    return new RangeFieldQuery(field, encode(min, max), min.length, QueryType.WITHIN) {
      @Override
      protected String toString(byte[] ranges, int dimension) {
        return LongRangeField.toString(ranges, dimension);
      }
    };
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append(getClass().getSimpleName());
    sb.append(" <");
    sb.append(name);
    sb.append(':');
    byte[] b = ((BytesRef)fieldsData).bytes;
    toString(b, 0);
    for (int d=1; d<type.pointDimensionCount(); ++d) {
      sb.append(' ');
      toString(b, d);
    }
    sb.append('>');

    return sb.toString();
  }

  /**
   * Returns the String representation for the range at the given dimension
   * @param ranges the encoded ranges, never null
   * @param dimension the dimension of interest
   * @return The string representation for the range at the provided dimension
   */
  private static String toString(byte[] ranges, int dimension) {
    return "[" + Long.toString(decodeMin(ranges, dimension)) + " : "
        + Long.toString(decodeMax(ranges, dimension)) + "]";
  }
}
