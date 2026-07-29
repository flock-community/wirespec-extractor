package org.apache.avro.specific;

import java.util.Map;

public abstract class SpecificRecordBase {

    public GenericData getSpecificData() {
        return null;
    }

    public static final class GenericData {
        private Map<String, Conversion<?>> conversions;
    }

    public abstract static class Conversion<T> {
    }
}
