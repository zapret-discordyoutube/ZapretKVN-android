package io.github.zapretkvn.android.vpn;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Arrays;

public final class ControlTrafficProvider extends ContentProvider {
    public static final String AUTHORITY = "io.github.zapretkvn.android.debug.test.traffic";
    public static final String METHOD_TCP = "tcp";
    public static final String METHOD_TCP_ECHO = "tcp-echo";
    public static final String METHOD_TCP_BULK = "tcp-bulk";
    public static final String METHOD_UDP_ECHO = "udp-echo";
    public static final String METHOD_REVOKE_START = "revoke-start";
    public static final String METHOD_REVOKE_STOP = "revoke-stop";
    public static final String RESULT_UID = "uid";
    public static final String RESULT_SUCCESS = "success";
    public static final String RESULT_ERROR = "error";
    public static final String EXTRA_ADDRESS = "address";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_SIZE = "size";
    public static final String EXTRA_VALUE = "value";
    public static final String EXTRA_REPEAT = "repeat";
    public static final String EXTRA_BYTES = "bytes";
    public static final String EXTRA_CHUNK_BYTES = "chunk-bytes";
    public static final String CLOUDFLARE_IPV4 = "1.1.1.1";
    public static final int HTTPS_PORT = 443;
    public static final int TIMEOUT_MILLIS = 8_000;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle result = new Bundle();
        result.putInt(RESULT_UID, Process.myUid());
        try {
            switch (method) {
                case METHOD_TCP:
                    tcpProbe();
                    break;
                case METHOD_TCP_ECHO:
                    tcpEcho(requireExtras(extras));
                    break;
                case METHOD_TCP_BULK:
                    tcpBulk(arg, extras);
                    break;
                case METHOD_UDP_ECHO:
                    udpEcho(requireExtras(extras));
                    break;
                case METHOD_REVOKE_START:
                    providerContext().startForegroundService(
                            new Intent(providerContext(), RevokingTestVpnService.class));
                    break;
                case METHOD_REVOKE_STOP:
                    providerContext().startService(
                            new Intent(providerContext(), RevokingTestVpnService.class)
                                    .setAction(RevokingTestVpnService.ACTION_STOP));
                    break;
                default:
                    throw new IllegalArgumentException("Unknown method: " + method);
            }
            result.putBoolean(RESULT_SUCCESS, true);
        } catch (Throwable error) {
            String message = error.getMessage();
            result.putString(RESULT_ERROR, message != null ? message : error.getClass().getSimpleName());
        }
        return result;
    }

    private void tcpEcho(Bundle extras) throws Exception {
        tcpEcho(extras, destination(extras));
    }

    private void tcpEcho(Bundle extras, InetSocketAddress destination) throws Exception {
        byte[] payload = payload(extras);
        try (Socket socket = new Socket()) {
            socket.connect(destination, TIMEOUT_MILLIS);
            socket.setSoTimeout(TIMEOUT_MILLIS);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.writeInt(payload.length);
            output.write(payload);
            output.flush();
            DataInputStream input = new DataInputStream(socket.getInputStream());
            int length = input.readInt();
            if (length != payload.length) throw new IllegalStateException("Unexpected TCP echo size: " + length);
            byte[] received = new byte[length];
            input.readFully(received);
            if (!Arrays.equals(payload, received)) throw new IllegalStateException("TCP echo mismatch");
        }
    }

    private void udpEcho(Bundle extras) throws Exception {
        byte[] payload = payload(extras);
        int repeat = extras.getInt(EXTRA_REPEAT, 1);
        if (repeat < 1 || repeat > 128) throw new IllegalArgumentException("Invalid UDP repeat count");
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(TIMEOUT_MILLIS);
            InetSocketAddress destination = destination(extras);
            for (int index = 0; index < repeat; index++) {
                socket.send(new DatagramPacket(payload, payload.length, destination));
                DatagramPacket response = new DatagramPacket(new byte[payload.length + 1], payload.length + 1);
                socket.receive(response);
                byte[] received = Arrays.copyOf(response.getData(), response.getLength());
                if (!Arrays.equals(payload, received)) throw new IllegalStateException("UDP echo mismatch");
            }
        }
    }

    private void tcpBulk(Bundle extras) throws Exception {
        int totalBytes = extras.getInt(EXTRA_BYTES);
        int chunkBytes = extras.getInt(EXTRA_CHUNK_BYTES);
        if (totalBytes <= 0 || totalBytes > 64 * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid total byte count");
        }
        if (chunkBytes < 1024 || chunkBytes > 64 * 1024) {
            throw new IllegalArgumentException("Invalid chunk size");
        }
        byte[] payload = new byte[chunkBytes];
        byte[] received = new byte[chunkBytes];
        Arrays.fill(payload, (byte) 0x5a);
        try (Socket socket = new Socket()) {
            socket.setTcpNoDelay(true);
            socket.connect(destination(extras), TIMEOUT_MILLIS);
            socket.setSoTimeout(30_000);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            DataInputStream input = new DataInputStream(socket.getInputStream());
            int remaining = totalBytes;
            while (remaining > 0) {
                int count = Math.min(remaining, chunkBytes);
                output.write(payload, 0, count);
                output.flush();
                input.readFully(received, 0, count);
                for (int index = 0; index < count; index++) {
                    if (received[index] != payload[index]) throw new IllegalStateException("Bulk echo mismatch");
                }
                remaining -= count;
            }
        }
    }

    private void tcpBulk(String arg, Bundle extras) throws Exception {
        if (arg == null || arg.isEmpty()) {
            tcpBulk(requireExtras(extras));
            return;
        }
        String[] fields = arg.split(",", -1);
        if (fields.length != 4) throw new IllegalArgumentException("Invalid bulk argument");
        Bundle normalized = new Bundle();
        normalized.putString(EXTRA_ADDRESS, fields[0]);
        normalized.putInt(EXTRA_PORT, Integer.parseInt(fields[1]));
        normalized.putInt(EXTRA_BYTES, Integer.parseInt(fields[2]));
        normalized.putInt(EXTRA_CHUNK_BYTES, Integer.parseInt(fields[3]));
        tcpBulk(normalized);
    }

    private Bundle requireExtras(Bundle extras) {
        if (extras == null) throw new IllegalArgumentException("Missing echo arguments");
        return extras;
    }

    private InetSocketAddress destination(Bundle extras) throws Exception {
        String address = extras.getString(EXTRA_ADDRESS);
        int port = extras.getInt(EXTRA_PORT);
        if (address == null || port <= 0 || port > 65535) throw new IllegalArgumentException("Invalid destination");
        return new InetSocketAddress(InetAddress.getByName(address), port);
    }

    private byte[] payload(Bundle extras) {
        int size = extras.getInt(EXTRA_SIZE);
        if (size <= 0 || size > 32 * 1024) throw new IllegalArgumentException("Invalid payload size");
        byte[] payload = new byte[size];
        Arrays.fill(payload, (byte) extras.getInt(EXTRA_VALUE));
        return payload;
    }

    private void tcpProbe() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(
                new InetSocketAddress(InetAddress.getByName(CLOUDFLARE_IPV4), HTTPS_PORT),
                TIMEOUT_MILLIS);
            try {
                socket.getOutputStream().write(new byte[4096]);
                socket.getOutputStream().flush();
            } catch (java.io.IOException ignored) {
                // A successful TCP connect is sufficient for the direct-path assertion.
            }
        }
    }

    private android.content.Context providerContext() {
        android.content.Context context = getContext();
        if (context == null) throw new IllegalStateException("Provider context is unavailable");
        return context;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
