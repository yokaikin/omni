package com.omni.common.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ClamdVirusScanner INSTREAM 协议测试：用本地 ServerSocket 模拟 clamd 行为。
 */
class ClamdVirusScannerTest {

    private ServerSocket serverSocket;

    @AfterEach
    void stopServer() throws IOException {
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
        }
    }

    @Test
    void scanReturnsCleanWhenClamdRepliesOk() throws Exception {
        AtomicInteger receivedBytes = new AtomicInteger();
        startClamdStub(response -> {
            response.accept("stream: OK\n");
        }, receivedBytes);

        ClamdVirusScanner scanner = new ClamdVirusScanner("127.0.0.1", serverSocket.getLocalPort());

        VirusScanResult result = scanner.scan("clean-content".getBytes(StandardCharsets.UTF_8), "a.txt");

        assertTrue(result.isClean());
        assertEquals("clean-content".length(), receivedBytes.get());
    }

    @Test
    void scanReturnsInfectedWhenClamdReportsFound() throws Exception {
        startClamdStub(response -> response.accept("stream: Eicar-Signature FOUND\n"), new AtomicInteger());

        ClamdVirusScanner scanner = new ClamdVirusScanner("127.0.0.1", serverSocket.getLocalPort());

        VirusScanResult result = scanner.scan("x5o!p%@ap[4\\pzx54(p^)7cc)7}$eicar".getBytes(StandardCharsets.UTF_8), "eicar.txt");

        assertTrue(!result.isClean());
        assertEquals("Eicar-Signature", result.getSignature());
    }

    @Test
    void scanThrowsWhenClamdUnreachable() {
        ClamdVirusScanner scanner = new ClamdVirusScanner("127.0.0.1", 1);

        assertThrows(IllegalStateException.class,
                () -> scanner.scan("content".getBytes(StandardCharsets.UTF_8), "a.txt"));
    }

    @Test
    void scanHandlesChunkedContentLargerThanChunkSize() throws Exception {
        AtomicInteger receivedBytes = new AtomicInteger();
        startClamdStub(response -> response.accept("stream: OK\n"), receivedBytes);

        ClamdVirusScanner scanner = new ClamdVirusScanner("127.0.0.1", serverSocket.getLocalPort());
        byte[] large = new byte[5000];
        for (int i = 0; i < large.length; i++) {
            large[i] = (byte) (i % 251);
        }

        VirusScanResult result = scanner.scan(large, "large.bin");

        assertTrue(result.isClean());
        assertEquals(large.length, receivedBytes.get());
    }

    /**
     * 启动一个遵守 INSTREAM 协议的假 clamd：先校验 zINSTREAM 命令头，
     * 再读取 chunk 直到 0 长度终止符，最后回复给定响应。
     */
    private void startClamdStub(Consumer<Consumer<String>> responder, AtomicInteger receivedBytes) throws IOException {
        serverSocket = new ServerSocket(0);
        Thread serverThread = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                byte[] command = new byte[10];
                in.readFully(command);
                if (!"zINSTREAM".equals(new String(command, 0, 9, StandardCharsets.US_ASCII)) || command[9] != 0) {
                    throw new IllegalStateException("非法的 clamd 命令头: " + new String(command, StandardCharsets.US_ASCII));
                }
                int total = 0;
                while (true) {
                    int chunkLength = in.readInt();
                    if (chunkLength == 0) {
                        break;
                    }
                    byte[] chunk = new byte[chunkLength];
                    in.readFully(chunk);
                    total += chunkLength;
                }
                receivedBytes.set(total);
                OutputStream out = socket.getOutputStream();
                responder.accept(reply -> {
                    try {
                        out.write(reply.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } catch (IOException ignored) {
                    }
                });
                socket.getInputStream().read();
            } catch (IOException ignored) {
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }
}
