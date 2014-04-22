package com.litesuits.http.impl.apache;

import com.litesuits.android.log.Log;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.message.BasicHeader;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Simplified multipart entity mainly used for sending one or more files.
 * <p/>
 * 2014-1-15上午12:40:04
 */
class SimpleMultipartEntity implements HttpEntity {

    private static final String TAG = SimpleMultipartEntity.class.getSimpleName();

    private static final String APPLICATION_OCTET_STREAM = "application/octet-stream";
    private static final byte[] CR_LF                    = ("\r\n").getBytes();
    private static final byte[] TRANSFER_ENCODING_BINARY = "Content-Transfer-Encoding: binary\r\n".getBytes();

    private final static char[] MULTIPART_CHARS = "-_1234567890abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    private String boundary;
    private byte[] boundaryLine;
    private byte[] boundaryEnd;
    private boolean isRepeatable = false;

    private List<FilePart> fileParts = new ArrayList<FilePart>();

    // The buffer we use for building the message excluding files and the last
    // boundary
    private ByteArrayOutputStream out = new ByteArrayOutputStream();

//	private ResponseHandlerInterface progressHandler;

    private int bytesWritten;

    private int totalSize;

    public SimpleMultipartEntity() {
        final StringBuilder buf = new StringBuilder();
        final Random rand = new Random();
        for (int i = 0; i < 30; i++) {
            buf.append(MULTIPART_CHARS[rand.nextInt(MULTIPART_CHARS.length)]);
        }

        boundary = buf.toString();
        boundaryLine = ("--" + boundary + "\r\n").getBytes();
        boundaryEnd = ("--" + boundary + "--\r\n").getBytes();
//		this.progressHandler = progressHandler;
    }

    public void addPart(final byte[] bytes) {
        try {
            out.write(bytes);
            out.write(CR_LF);
        } catch (final IOException e) {
            Log.e(TAG, "addPart ByteArrayOutputStream exception", e);
        }
    }

    public void addPart(final String string, final String charset) {
        try {
            addPart(string.getBytes(charset));
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
    }

    public void addPart(final String key, final byte[] bytes, final String contentType) {
        try {
            out.write(boundaryLine);
            if (key != null) out.write(createContentDisposition(key));
            out.write(createContentType(contentType));
            out.write(CR_LF);
            out.write(bytes);
            out.write(CR_LF);
        } catch (final IOException e) {
            // Shall not happen on ByteArrayOutputStream
            Log.e(TAG, "addPart ByteArrayOutputStream exception", e);
        }
    }

    public void addPart(String key, File file) {
        addPart(key, file, null);
    }

    public void addPart(final String key, File file, String type) {
        if (type == null) {
            type = APPLICATION_OCTET_STREAM;
        }
        fileParts.add(new FilePart(key, file, type));
    }

    public void addPart(String key, String streamName, InputStream inputStream, String type) throws IOException {
        if (type == null) {
            type = APPLICATION_OCTET_STREAM;
        }
        out.write(boundaryLine);

        // Headers
        out.write(createContentDisposition(key, streamName));
        out.write(createContentType(type));
        out.write(TRANSFER_ENCODING_BINARY);
        out.write(CR_LF);

        // Stream (file)
        final byte[] tmp = new byte[4096];
        int l;
        while ((l = inputStream.read(tmp)) != -1) {
            out.write(tmp, 0, l);
        }

        out.write(CR_LF);
        out.flush();
        try {
            inputStream.close();
        } catch (final IOException e) {
            // Not important, just log it
            Log.w(TAG, "Cannot close input stream", e);
        }
    }

    private byte[] createContentType(String type) {
        String result = "Content-Type: " + type + "\r\n";
        return result.getBytes();
    }

    private byte[] createContentDisposition(final String key) {
        return ("Content-Disposition: form-data; name=\"" + key + "\"\r\n").getBytes();
    }

    private byte[] createContentDisposition(final String key, final String fileName) {
        return ("Content-Disposition: form-data; name=\"" + key + "\"; filename=\"" + fileName + "\"\r\n").getBytes();
    }

    private void updateProgress(int count) {
        bytesWritten += count;
//		progressHandler.sendProgressMessage(bytesWritten, totalSize);
    }

    @Override
    public long getContentLength() {
        long contentLen = out.size();
        for (FilePart filePart : fileParts) {
            long len = filePart.getTotalLength();
            if (len < 0) {
                return -1; // Should normally not happen
            }
            contentLen += len;
        }
        contentLen += boundaryEnd.length;
        return contentLen;
    }

    // The following methods are from the HttpEntity interface

    @Override
    public Header getContentType() {
        return new BasicHeader("Content-Type", "multipart/form-data; boundary=" + boundary);
    }

    @Override
    public boolean isChunked() {
        return false;
    }

    public void setIsRepeatable(boolean isRepeatable) {
        this.isRepeatable = isRepeatable;
    }

    @Override
    public boolean isRepeatable() {
        return isRepeatable;
    }

    @Override
    public boolean isStreaming() {
        return false;
    }

    @Override
    public void writeTo(final OutputStream outstream) throws IOException {
        bytesWritten = 0;
        totalSize = (int) getContentLength();
        out.writeTo(outstream);
        updateProgress(out.size());

        for (FilePart filePart : fileParts) {
            filePart.writeTo(outstream);
        }
        outstream.write(boundaryEnd);
        updateProgress(boundaryEnd.length);
    }

    @Override
    public Header getContentEncoding() {
        return null;
    }

    @Override
    public void consumeContent() throws IOException, UnsupportedOperationException {
        if (isStreaming()) {
            throw new UnsupportedOperationException("Streaming entity does not implement #consumeContent()");
        }
    }

    @Override
    public InputStream getContent() throws IOException, UnsupportedOperationException {
        throw new UnsupportedOperationException("getContent() is not supported. Use writeTo() instead.");
    }

    private class FilePart {
        public File   file;
        public byte[] header;

        public FilePart(String key, File file, String type) {
            header = createHeader(key, file.getName(), type);
            this.file = file;
        }

        private byte[] createHeader(String key, String filename, String type) {
            ByteArrayOutputStream headerStream = new ByteArrayOutputStream();
            try {
                headerStream.write(boundaryLine);

                // Headers
                headerStream.write(createContentDisposition(key, filename));
                headerStream.write(createContentType(type));
                headerStream.write(TRANSFER_ENCODING_BINARY);
                headerStream.write(CR_LF);
            } catch (IOException e) {
                // Can't happen on ByteArrayOutputStream
                Log.e(TAG, "createHeader ByteArrayOutputStream exception", e);
            }
            return headerStream.toByteArray();
        }

        public long getTotalLength() {
            long streamLength = file.length();
            return header.length + streamLength;
        }

        public void writeTo(OutputStream out) throws IOException {
            out.write(header);
            updateProgress(header.length);

            FileInputStream inputStream = new FileInputStream(file);
            final byte[] tmp = new byte[4096];
            int l;
            while ((l = inputStream.read(tmp)) != -1) {
                out.write(tmp, 0, l);
                updateProgress(l);
            }
            out.write(CR_LF);
            updateProgress(CR_LF.length);
            out.flush();
            try {
                inputStream.close();
            } catch (final IOException e) {
                // Not important, just log it
                Log.w(TAG, "Cannot close input stream", e);
            }
        }
    }
}