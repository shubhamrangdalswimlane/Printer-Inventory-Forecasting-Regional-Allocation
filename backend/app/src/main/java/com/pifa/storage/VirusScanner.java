package com.pifa.storage;

/** Antivirus check applied to every file before it is parsed. */
public interface VirusScanner {

    /**
     * @param data the complete file content
     * @return true if the file is clean, false if malware was found
     * @throws IllegalStateException if the scanner could not be reached or gave no verdict
     *         (callers must treat that as a failed scan, i.e. fail closed)
     */
    boolean isClean(byte[] data);
}
