package com.omni.common.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * ClamAV clamd 病毒扫描实现（INSTREAM 协议）。
 * 连接失败/超时/协议异常时抛出 IllegalStateException，由上层按 fail-open 策略处理。
 */
public class ClamdVirusScanner implements VirusScanner {

    private static final int CONNECT_TIMEOUT_MILLIS = 3000;
    private static final int READ_TIMEOUT_MILLIS = 30000;
    private static final int CHUNK_SIZE = 2048;
    /** clamd INSTREAM 命令头：`zINSTREAM` + NUL 终止符。 */
    private static final byte[] INSTREAM_COMMAND =
            new byte[] {'z', 'I', 'N', 'S', 'T', 'R', 'E', 'A', 'M', '\0'};

    private final String host;
    private final int port;

    public ClamdVirusScanner(String host, int port) {
        this.host = host;
        this.port = port;
    }

    @Override
    public VirusScanResult scan(byte[] content, String filename) {
        String response = instreamScan(content);
        if (response == null) {
            throw new IllegalStateException("病毒扫描服务无响应");
        }
        String normalized = response.trim();
        if (normalized.endsWith("OK")) {
            return VirusScanResult.clean();
        }
        int foundIndex = normalized.indexOf("FOUND");
        if (foundIndex > 0) {
            String signature = normalized.substring(0, foundIndex).trim();
            if (signature.startsWith("stream:")) {
                signature = signature.substring("stream:".length()).trim();
            }
            return VirusScanResult.infected(signature);
        }
        throw new IllegalStateException("病毒扫描服务返回异常: " + normalized);
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    private String instreamScan(byte[] content) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            // 注意：不能对 socket.getOutputStream() 使用 try-with-resources，
            // 关闭该流会同时关闭底层 socket，导致后续读取响应失败。
            OutputStream outputStream = socket.getOutputStream();
            // clamd INSTREAM 协议要求先发送命令头（z 前缀 + NUL 终止）
            outputStream.write(INSTREAM_COMMAND);
            int offset = 0;
            while (offset < content.length) {
                int length = Math.min(CHUNK_SIZE, content.length - offset);
                outputStream.write(intToBytes(length));
                outputStream.write(content, offset, length);
                offset += length;
            }
            outputStream.write(intToBytes(0));
            outputStream.flush();
            return readResponse(socket.getInputStream());
        } catch (IOException e) {
            throw new IllegalStateException("病毒扫描服务连接失败: " + e.getMessage(), e);
        }
    }

    private String readResponse(InputStream inputStream) throws IOException {
        byte[] buffer = new byte[256];
        int total = 0;
        while (total < buffer.length) {
            int read = inputStream.read(buffer, total, buffer.length - total);
            if (read == -1) {
                break;
            }
            total += read;
            if (buffer[total - 1] == '\n' || buffer[total - 1] == '\0') {
                break;
            }
        }
        return total == 0 ? null : new String(buffer, 0, total).trim();
    }

    private byte[] intToBytes(int value) {
        return new byte[] {
                (byte) ((value >>> 24) & 0xff),
                (byte) ((value >>> 16) & 0xff),
                (byte) ((value >>> 8) & 0xff),
                (byte) (value & 0xff)
        };
    }
}
