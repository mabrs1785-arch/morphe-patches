/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3852
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.morphe.extension.shared.Logger;

/**
 * Reads protobuf fields on demand. Absent fields return empty values or zero for varints.
 * Field reads throw {@link IllegalArgumentException} for detected malformed wire data, unsupported wire types,
 * unexpected wire types for requested fields, or repeated singular fields.
 * Nested message contents are inspected only when their fields are read.
 */
final class ProtoMessage {
    private static final int WIRE_VARINT = WireFormat.WIRETYPE_VARINT;
    private static final int WIRE_FIXED64 = WireFormat.WIRETYPE_FIXED64;
    static final int WIRE_LENGTH_DELIMITED = WireFormat.WIRETYPE_LENGTH_DELIMITED;
    private static final int WIRE_FIXED32 = WireFormat.WIRETYPE_FIXED32;
    /** Nesting beyond this is not a command. */
    private static final int MAX_TEXT_FIELD_DEPTH = 8;
    static final ProtoMessage EMPTY = new ProtoMessage(new byte[0]);
    private final byte[] data;
    private final int start;
    private final int end;

    ProtoMessage(byte[] data) { this(data, 0, data.length); }

    private ProtoMessage(byte[] data, int start, int end) {
        this.data = data;
        this.start = start;
        this.end = end;
    }

    boolean isEmpty() { return start == end; }
    String utf8Text() { return new String(data, start, end - start, StandardCharsets.UTF_8); }
    String stringField(int fieldNumber) { return messageField(fieldNumber).utf8Text(); }
    byte[] serializedBytes() { return Arrays.copyOfRange(data, start, end); }

    ProtoMessage messagePath(int... fieldNumbers) {
        ProtoMessage nestedMessage = this;
        for (int fieldNumber : fieldNumbers) nestedMessage = nestedMessage.messageField(fieldNumber);
        return nestedMessage;
    }

    ProtoMessage messageField(int fieldNumber) {
        return messageField(fieldNumber, WIRE_LENGTH_DELIMITED);
    }

    private ProtoMessage messageField(int fieldNumber, int wireType) {
        List<ProtoMessage> matches = repeatedMessageField(fieldNumber, wireType);
        if (matches.size() > 1) throw new IllegalArgumentException("Repeated singular field: " + fieldNumber);
        return matches.isEmpty() ? EMPTY : matches.get(0);
    }

    long varintField(int fieldNumber) {
        ProtoMessage field = messageField(fieldNumber, WIRE_VARINT);
        if (field.isEmpty()) return 0;
        try {
            return CodedInputStream.newInstance(data, field.start, field.end - field.start).readRawVarint64();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Invalid protobuf response", ex);
        }
    }

    List<ProtoMessage> repeatedMessageField(int fieldNumber) {
        return repeatedMessageField(fieldNumber, WIRE_LENGTH_DELIMITED);
    }

    /**
     * @return Every length delimited field of this message and of its nested messages that is
     *         printable text, in wire order. A field that is not a valid message is read as text only.
     */
    List<String> allTextFields() {
        List<String> texts = new ArrayList<>();
        collectTextFields(texts, 0);
        return texts;
    }

    private void collectTextFields(List<String> texts, int depth) {
        if (depth > MAX_TEXT_FIELD_DEPTH) return;
        CodedInputStream input = CodedInputStream.newInstance(data, start, end - start);
        try {
            for (int tag; (tag = input.readTag()) != 0;) {
                int wireType = WireFormat.getTagWireType(tag);
                if (wireType != WIRE_LENGTH_DELIMITED) {
                    if (wireType != WIRE_VARINT && wireType != WIRE_FIXED64
                            && wireType != WIRE_FIXED32) {
                        return;
                    }
                    input.skipField(tag);
                    continue;
                }
                int length = input.readRawVarint32();
                int valueStart = start + input.getTotalBytesRead();
                input.skipRawBytes(length);
                ProtoMessage field = new ProtoMessage(data, valueStart, valueStart + length);
                if (field.isPrintableText()) texts.add(field.utf8Text());
                field.collectTextFields(texts, depth + 1);
            }
        } catch (IOException | RuntimeException ex) {
            // Text is also read as a message in case it is one. The fields read so far are kept.
            Logger.printDebug(() -> "Not a message, keeping the fields read so far", ex);
        }
    }

    private boolean isPrintableText() {
        if (isEmpty()) return false;
        for (int i = start; i < end; i++) {
            if (data[i] < ' ' || data[i] > '~') return false;
        }
        return true;
    }

    private List<ProtoMessage> repeatedMessageField(int fieldNumber, int expectedWireType) {
        List<ProtoMessage> matches = new ArrayList<>();
        CodedInputStream input = CodedInputStream.newInstance(data, start, end - start);
        try {
            for (int tag; (tag = input.readTag()) != 0;) {
                int currentFieldNumber = WireFormat.getTagFieldNumber(tag);
                int wireType = WireFormat.getTagWireType(tag);
                if (currentFieldNumber == fieldNumber && wireType != expectedWireType)
                    throw new IllegalArgumentException("Unexpected protobuf field type");
                int valueStart = start + input.getTotalBytesRead();
                if (wireType == WIRE_LENGTH_DELIMITED) {
                    int length = input.readRawVarint32();
                    valueStart = start + input.getTotalBytesRead();
                    input.skipRawBytes(length);
                } else if (wireType == WIRE_VARINT || wireType == WIRE_FIXED64 || wireType == WIRE_FIXED32) {
                    input.skipField(tag);
                } else {
                    throw new IllegalArgumentException("Unsupported protobuf wire type");
                }
                if (currentFieldNumber == fieldNumber)
                    matches.add(new ProtoMessage(data, valueStart, start + input.getTotalBytesRead()));
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("Invalid protobuf response", ex);
        }
        return matches;
    }
}
