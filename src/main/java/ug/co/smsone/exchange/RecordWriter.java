package ug.co.smsone.exchange;

import java.util.Map;

/** Push-style export sink: the handler writes records; the platform owns format and destination. */
@FunctionalInterface
public interface RecordWriter {

    void write(Map<String, String> record);
}
