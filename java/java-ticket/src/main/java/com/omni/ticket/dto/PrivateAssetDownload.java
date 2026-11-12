package com.omni.ticket.dto;

import java.io.InputStream;

public class PrivateAssetDownload {
    private final InputStream inputStream;
    private final String originalFilename;
    private final String contentType;
    private final long fileSize;

    public PrivateAssetDownload(InputStream inputStream, String originalFilename, String contentType, long fileSize) {
        this.inputStream = inputStream;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.fileSize = fileSize;
    }

    public InputStream getInputStream() { return inputStream; }
    public String getOriginalFilename() { return originalFilename; }
    public String getContentType() { return contentType; }
    public long getFileSize() { return fileSize; }
}
